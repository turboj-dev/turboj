package dev.turboj.ai;

import dev.turboj.ai.java.CompletionContext;
import dev.turboj.editor.document.PieceTable;
import dev.turboj.intelligence.CompletionCoordinator;
import dev.turboj.ui.EditorComponent;

import java.awt.event.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

import javax.swing.*;

/** One cancellable inference lane, one pending request and one coalesced EDT delivery. */
public final class AICompletionController implements AutoCloseable {
    public record Snapshot(Path file, PieceTable.Snapshot source) {}

    @FunctionalInterface
    public interface ProviderFactory {
        AICompletionProvider create(AIConfig config);
    }

    private record Ticket(
            Snapshot snapshot,
            PieceTable document,
            int offset,
            long id,
            long presentation,
            AIConfig config,
            boolean explicit,
            boolean larger,
            boolean fresh,
            long scheduled) {}

    private record Delivery(Ticket ticket, String text) {}

    /** The suggestion on screen, to keep its remainder while the user types it. */
    private record Ghost(PieceTable document, long version, int offset, int length, String text) {}

    private record CacheKey(
            String prefix, String suffix, String context, AIConfig config, boolean larger) {}

    private final EditorComponent editor;
    private final CompletionCoordinator coordinator;
    private final Supplier<Snapshot> snapshots;
    private final ProviderFactory factory;
    private final Consumer<String> notice, status;
    private final javax.swing.Timer timer;
    private final ThreadPoolExecutor worker =
            new ThreadPoolExecutor(
                    1,
                    1,
                    30,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(1),
                    r -> {
                        var t = new Thread(r, "turboj-ai-inference");
                        t.setDaemon(true);
                        return t;
                    },
                    new ThreadPoolExecutor.DiscardOldestPolicy());
    private final AtomicReference<Delivery> delivery = new AtomicReference<>();
    private final AtomicBoolean deliveryQueued = new AtomicBoolean();
    private final LinkedHashMap<CacheKey, String> cache = new LinkedHashMap<>(16, .75f, true);
    private final AIMetrics metrics = new AIMetrics();
    private volatile long generation;
    private volatile boolean closed;
    private final AtomicReference<Cancellation> cancellation = new AtomicReference<>();
    private volatile AICompletionProvider provider;
    private final Object providerLock = new Object();
    private AIConfig providerConfig;
    private volatile AIConfig config;
    private long lastVersion = -1, scheduledAt;
    private int lastCaret = -1;
    private long retryAfter;
    private int failures;
    private PieceTable lastDocument;
    private Ghost ghost;
    private long lastEditAt;
    private double typingInterval = 400, latencyMillis;
    private java.util.function.BooleanSupplier localReady = () -> true;
    private String runtimeSummary = "", runtimeDetail = "";
    private KeyStroke accept = KeyStroke.getKeyStroke("TAB"),
            dismiss = KeyStroke.getKeyStroke("ESCAPE"),
            nextWord = KeyStroke.getKeyStroke("ctrl alt ENTER"),
            nextLine = null;

    public AICompletionController(
            EditorComponent editor,
            CompletionCoordinator coordinator,
            Supplier<Snapshot> snapshots,
            AIConfig config,
            ProviderFactory factory,
            Consumer<String> notice,
            Consumer<String> status) {
        this.editor = editor;
        this.coordinator = coordinator;
        this.snapshots = snapshots;
        this.factory = factory;
        this.notice = notice;
        this.status = status;
        this.config = safeConfig(config);
        timer = new javax.swing.Timer(this.config.delayMillis(), e -> request(false, false));
        timer.setRepeats(false);
        worker.allowCoreThreadTimeOut(true);
        editor.addFocusListener(
                new FocusAdapter() {
                    @Override
                    public void focusLost(FocusEvent e) {
                        dismiss();
                    }
                });
        updateStatus();
    }

    private static AIConfig safeConfig(AIConfig value) {
        try {
            value.validate();
            return value;
        } catch (RuntimeException error) {
            return AIConfig.defaults();
        }
    }

    public AIConfig config() {
        return config;
    }

    public void setKeys(KeyStroke accept, KeyStroke dismiss, KeyStroke nextWord) {
        this.accept = accept;
        this.dismiss = dismiss;
        this.nextWord = nextWord;
    }

    public void setNextLineKey(KeyStroke nextLine) {
        this.nextLine = nextLine;
    }

    /**
     * The local runtime's readiness, from the model manager. While it is not ready no automatic request
     * is prepared, so loading a model costs no context building and opens no circuit.
     */
    public void setLocalRuntime(java.util.function.BooleanSupplier ready) {
        localReady = ready == null ? () -> true : ready;
    }

    /** The manager's summary ("Ready", "Loading model…") and detail; a newly ready runtime is retried at once. */
    public void runtimeStatus(String summary, String detail, boolean ready) {
        runtimeSummary = summary == null ? "" : summary;
        runtimeDetail = detail == null ? "" : detail;
        if (ready) {
            retryAfter = 0;
            failures = 0;
        }
        updateStatus();
    }

    public void configure(AIConfig value) {
        value.validate();
        dismiss();
        config = value;
        AICompletionProvider previous;
        synchronized (providerLock) {
            previous = provider;
            provider = null;
        }
        if (previous != null) Thread.startVirtualThread(previous::close);
        synchronized (cache) {
            cache.clear();
        }
        if (!value.diagnostics()) metrics.clear();
        lastVersion = -1;
        lastDocument = null;
        retryAfter = 0;
        failures = 0;
        timer.setInitialDelay(value.delayMillis());
        updateStatus();
    }

    private void updateStatus() {
        if (config.mode() == AIConfig.Mode.DISABLED) {
            status.accept("");
            return;
        }
        boolean local = config.mode() == AIConfig.Mode.LOCAL;
        status.accept("AI · " + (local ? "Local" : "Cloud " + config.cloudProvider().label())
                + (local && !runtimeSummary.isEmpty() ? " · " + runtimeSummary : ""));
    }

    /** Cheap disabled fast path; snapshots are captured only for an enabled service. */
    public void changed() {
        if (closed || config.mode() == AIConfig.Mode.DISABLED) return;
        // An inline template owns Tab and the caret; suggestions would only get in its way.
        if (editor.caretCount() > 1 || editor.templateActive()) { dismiss(); return; }
        int caret = editor.caretOffset();
        if (lastDocument == editor.document()
                && lastVersion == editor.version()
                && lastCaret == caret) {
            restoreGhost(caret);
            return;
        }
        boolean edited = lastDocument != editor.document() || lastVersion != editor.version();
        lastDocument = editor.document();
        lastVersion = editor.version();
        lastCaret = caret;
        if (edited && typeThrough(caret)) return;
        dismiss();
        if (edited
                && config.automatic()
                && !editor.isReadOnly()
                && editor.getSelectedText().isEmpty()) {
            long now = System.nanoTime();
            if (lastEditAt != 0)
                typingInterval = typingInterval * 0.7 + Math.min(2_000, (now - lastEditAt) / 1e6) * 0.3;
            lastEditAt = now;
            scheduledAt = now;
            timer.setInitialDelay(adaptiveDelay());
            timer.restart();
        }
    }

    /**
     * Typing the next characters of the suggestion keeps the rest of it, as in IntelliJ and Copilot,
     * instead of discarding it and asking the model again.
     */
    private boolean typeThrough(int caret) {
        Ghost shown = ghost;
        if (shown == null || editor.document() != shown.document() || coordinator.hasSemanticChoice()) return false;
        int typed = caret - shown.offset();
        PieceTable document = editor.document();
        if (typed <= 0 || typed >= shown.text().length() || document.length() != shown.length() + typed
                || editor.version() <= shown.version()) return false;
        if (!document.subSequence(shown.offset(), caret).toString().equals(shown.text().substring(0, typed))) return false;
        String rest = shown.text().substring(typed);
        cancelRequest();
        timer.stop();
        if (!coordinator.ai(coordinator.generation(),
                new EditorComponent.InlineCompletion(editor.version(), caret, caret, caret, rest, rest.length()))) return false;
        ghost = new Ghost(document, editor.version(), caret, document.length(), rest);
        return true;
    }

    /**
     * A general reset of completion presentation (each edit and caret notification clears it) must not
     * take away a suggestion that still applies exactly where it was; Escape and Java choices still do.
     */
    private void restoreGhost(int caret) {
        Ghost shown = ghost;
        if (shown == null || editor.isAIInlineCompletion() || coordinator.hasSemanticChoice()
                || editor.document() != shown.document() || editor.version() != shown.version() || caret != shown.offset()) return;
        coordinator.ai(coordinator.generation(),
                new EditorComponent.InlineCompletion(shown.version(), caret, caret, caret, shown.text(), shown.text().length()));
    }

    /**
     * The configured pause, longer while the user types quickly (a pause between bursts is the moment to
     * suggest) and when the provider has been slow, so its queue never fills with requests already stale.
     */
    private int adaptiveDelay() {
        int delay = config.delayMillis();
        if (typingInterval < 160) delay += 120;
        if (latencyMillis > 600) delay += 150;
        return Math.min(1_000, delay);
    }

    public void request(boolean explicit, boolean larger) {
        request(explicit, larger, false);
    }

    public void requestAnother() {
        dismiss();
        request(true, false, true);
    }

    private void request(boolean explicit, boolean larger, boolean fresh) {
        timer.stop();
        if (closed || config.mode() == AIConfig.Mode.DISABLED) {
            if (explicit) notice.accept("AI is disabled. Configure Options → AI Completion first.");
            return;
        }
        if (editor.caretCount() > 1) {
            if (explicit) notice.accept("Remove secondary carets before requesting AI completion.");
            return;
        }
        if (editor.isReadOnly() || !editor.getSelectedText().isEmpty() || editor.templateActive()) return;
        if (!explicit && System.nanoTime() < retryAfter) return;
        if (config.mode() == AIConfig.Mode.LOCAL && !localReady.getAsBoolean()) {
            if (explicit)
                notice.accept("Local AI is not ready: " + (runtimeDetail.isBlank() ? runtimeSummary : runtimeDetail));
            return;
        }
        if (coordinator.hasSemanticChoice()) {
            if (explicit)
                notice.accept(
                        "A Java completion is active. Accept or dismiss it before requesting AI.");
            return;
        }
        cancelRequest();
        Snapshot snapshot = snapshots.get();
        Ticket ticket =
                new Ticket(
                        snapshot,
                        editor.document(),
                        editor.caretOffset(),
                        generation,
                        coordinator.generation(),
                        config,
                        explicit,
                        larger,
                        fresh,
                        explicit ? System.nanoTime() : scheduledAt);
        Cancellation token = new Cancellation();
        cancellation.set(token);
        if (config.diagnostics()) metrics.request();
        worker.execute(() -> generate(ticket, token));
    }

    private void generate(Ticket ticket, Cancellation token) {
        long start = System.nanoTime();
        AtomicLong first = new AtomicLong();
        try {
            token.throwIfCancelled();
            AICompletionRequest request =
                    CompletionContext.build(
                            ticket.snapshot.file(),
                            ticket.snapshot.source(),
                            ticket.snapshot.source().version(),
                            ticket.offset,
                            ticket.config,
                            ticket.larger,
                            token);
            CacheKey key =
                    new CacheKey(
                            request.prefix(),
                            request.suffix(),
                            request.context(),
                            ticket.config,
                            ticket.larger);
            String cached;
            synchronized (cache) {
                cached = ticket.fresh ? null : cache.get(key);
            }
            if (cached != null) {
                token.throwIfCancelled();
                if (ticket.config.diagnostics()) metrics.hit();
                publish(ticket, cached);
                return;
            }
            if (provider == null || !ticket.config.equals(providerConfig)) {
                AICompletionProvider previous;
                synchronized (providerLock) {
                    previous = provider;
                    provider = null;
                }
                if (previous != null) previous.close();
                AICompletionProvider next = factory.create(ticket.config);
                boolean installed;
                synchronized (providerLock) {
                    installed = !token.isCancelled() && generation == ticket.id && !closed;
                    if (installed) {
                        provider = next;
                        providerConfig = ticket.config;
                    }
                }
                if (!installed) {
                    next.close();
                    return;
                }
            }
            AICompletionProvider active = provider;
            token.throwIfCancelled();
            if (active == null) return;
            AtomicReference<String> last = new AtomicReference<>("");
            AtomicLong lastPublication = new AtomicLong();
            active.stream(
                    request,
                    cumulative -> {
                        last.set(cumulative);
                        if (token.isCancelled()) return;
                        String usable = CompletionValidation.usable(request, cumulative, false);
                        long now = System.nanoTime();
                        if (!usable.isEmpty()
                                && now - lastPublication.get()
                                        >= TimeUnit.MILLISECONDS.toNanos(50)) {
                            first.compareAndSet(0, now);
                            lastPublication.set(now);
                            publish(ticket, usable);
                        }
                    },
                    token);
            token.throwIfCancelled();
            String result = CompletionValidation.usable(request, last.get(), true);
            // Syntax and invented names: a suggestion that would break the file or use a variable the
            // file never mentions is dropped, not shown as if it were valid code.
            if (!result.isEmpty()
                    && dev.turboj.ai.java.JavaSuggestionFilter.check(ticket.snapshot.source().toString(), ticket.offset,
                                    result, request.javaVersion())
                            != dev.turboj.ai.java.JavaSuggestionFilter.Verdict.ACCEPT) result = "";
            if (!result.isEmpty()) {
                first.compareAndSet(0, System.nanoTime());
                synchronized (cache) {
                    if (generation == ticket.id) {
                        cache.put(key, result);
                        while (cache.size() > 16) cache.remove(cache.keySet().iterator().next());
                    }
                }
                publish(ticket, result);
            } else
                SwingUtilities.invokeLater(
                        () -> {
                            if (valid(ticket)) {
                                coordinator.clearAI();
                                // With a Java choice on screen the AI result was not wanted anyway.
                                if (ticket.explicit && !coordinator.hasSemanticChoice())
                                    notice.accept(
                                            "AI returned no safe insertion at this position.");
                            }
                        });
            if (generation == ticket.id && first.get() != 0)
                latencyMillis = latencyMillis == 0 ? (first.get() - start) / 1e6 : latencyMillis * 0.7 + (first.get() - start) / 1e6 * 0.3;
            if (ticket.config.diagnostics() && config.diagnostics() && generation == ticket.id)
                metrics.sample(
                        start - ticket.scheduled,
                        active.metrics().connectionNanos(),
                        first.get() == 0 ? -1 : first.get() - start,
                        System.nanoTime() - start);
        } catch (Exception error) {
            if (!token.isCancelled())
                SwingUtilities.invokeLater(
                        () -> {
                            if (valid(ticket)) {
                                coordinator.clearAI();
                                failures = Math.min(failures + 1, 5);
                                retryAfter =
                                        System.nanoTime()
                                                + TimeUnit.SECONDS.toNanos(
                                                        Math.min(30, 1 << failures));
                                if (ticket.explicit)
                                    notice.accept(
                                            "AI completion unavailable: " + safeMessage(error));
                                status.accept(
                                        "AI · "
                                                + (ticket.config.mode() == AIConfig.Mode.LOCAL
                                                        ? "Local"
                                                        : "Cloud")
                                                + " · "
                                                + summary(error));
                            }
                        });
        } finally {
            // A request may finish while the EDT installs its successor. Only retire our own token.
            cancellation.compareAndSet(token, null);
        }
    }

    /** Typed provider messages are written for the user and carry no source; other failures stay generic. */
    private static String safeMessage(Exception error) {
        if (error instanceof AIException typed) return typed.getMessage();
        if (error instanceof IllegalArgumentException
                && error.getMessage() != null
                && error.getMessage().startsWith("AI")) return error.getMessage();
        return "check provider configuration or connection";
    }

    /** A status-line word for a failure: what is wrong, not that something is. */
    private static String summary(Exception error) {
        return switch (AIException.classify(error)) {
            case MODEL_NOT_FOUND, MODEL_DOWNLOAD_REQUIRED -> "Model not installed";
            case RUNTIME_NOT_RUNNING, RUNTIME_NOT_INSTALLED -> "Runtime not running";
            case MODEL_NOT_LOADED -> "Loading model…";
            case AUTHENTICATION_FAILED -> "Key rejected";
            case RATE_LIMITED -> "Rate-limited";
            case TIMEOUT -> "Timed out";
            case UNSUPPORTED_ENDPOINT, INVALID_CONFIGURATION -> "Check settings";
            case UNSUPPORTED_MODEL_CAPABILITY -> "Model has no FIM";
            default -> "Unavailable";
        };
    }

    private boolean valid(Ticket ticket) {
        return !closed
                && editor.caretCount() == 1
                && generation == ticket.id
                && config.equals(ticket.config)
                && editor.version() == ticket.snapshot.source().version()
                && editor.caretOffset() == ticket.offset
                && editor.document() == ticket.document
                && editor.hasFocus();
    }

    private void publish(Ticket ticket, String text) {
        if (closed || generation != ticket.id) return;
        delivery.set(new Delivery(ticket, text));
        if (deliveryQueued.compareAndSet(false, true))
            SwingUtilities.invokeLater(this::drainDelivery);
    }

    private void drainDelivery() {
        deliveryQueued.set(false);
        Delivery result = delivery.getAndSet(null);
        if (result == null || !valid(result.ticket)) return;
        Ticket t = result.ticket;
        if (coordinator.ai(
                t.presentation,
                new EditorComponent.InlineCompletion(
                        t.snapshot.source().version(),
                        t.offset,
                        t.offset,
                        t.offset,
                        result.text,
                        result.text.length()))) {
            ghost = new Ghost(t.document, t.snapshot.source().version(), t.offset, t.document.length(), result.text);
            retryAfter = 0;
            failures = 0;
            updateStatus();
        }
    }

    private void cancelRequest() {
        generation++;
        delivery.set(null);
        Cancellation active = cancellation.getAndSet(null);
        if (active != null) {
            active.cancel();
            if (config.diagnostics()) metrics.cancelled();
        }
        worker.getQueue().clear();
    }

    public void dismiss() {
        timer.stop();
        cancelRequest();
        ghost = null;
        coordinator.clearAI();
    }

    public boolean handleKey(KeyEvent event) {
        if (event.getID() != KeyEvent.KEY_PRESSED) return false;
        KeyStroke key = KeyStroke.getKeyStrokeForEvent(event);
        if (key.equals(dismiss)) {
            dismiss();
            return false;
        }
        if (!editor.isAIInlineCompletion()) return false;
        if (key.equals(accept) || key.equals(nextWord) || key.equals(nextLine)) {
            cancelRequest();
            timer.stop();
            ghost = null;
            boolean accepted =
                    key.equals(accept)
                            ? editor.acceptInlineCompletion()
                            : key.equals(nextWord) ? editor.acceptNextAIWord() : editor.acceptNextAILine();
            if (accepted && editor.isAIInlineCompletion())
                ghost = new Ghost(editor.document(), editor.version(), editor.caretOffset(), editor.document().length(),
                        editor.inlineCompletion().insertion());
            timer.stop(); // acceptance must not immediately spend another request
            if (accepted && config.diagnostics()) metrics.accepted();
            return accepted;
        }
        return false;
    }

    public String metrics() {
        return config.diagnostics()
                ? metrics.report()
                : "AI diagnostics disabled (enable explicitly in AI settings).";
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        dismiss();
        worker.shutdownNow();
        AICompletionProvider active;
        synchronized (providerLock) {
            active = provider;
            provider = null;
        }
        if (active != null) Thread.startVirtualThread(active::close);
        synchronized (cache) {
            cache.clear();
        }
    }

    private static final class AIMetrics {
        private long requests, hits, cancelled, accepted;
        private final ArrayDeque<long[]> samples = new ArrayDeque<>();

        synchronized void request() {
            requests++;
        }

        synchronized void hit() {
            hits++;
        }

        synchronized void cancelled() {
            cancelled++;
        }

        synchronized void accepted() {
            accepted++;
        }

        synchronized void clear() {
            requests = hits = cancelled = accepted = 0;
            samples.clear();
        }

        synchronized void sample(long scheduling, long connection, long first, long total) {
            if (samples.size() == 256) samples.removeFirst();
            samples.addLast(new long[] {scheduling, connection, first, total});
        }

        synchronized String report() {
            StringBuilder out =
                    new StringBuilder("AI diagnostics (local, consent enabled)\nRequests ")
                            .append(requests)
                            .append(" · cache hits ")
                            .append(hits)
                            .append(" · cancellations ")
                            .append(cancelled)
                            .append(" · accepted ")
                            .append(accepted);
            String[] labels = {
                "Scheduling", "Provider connection", "First usable suggestion", "Total generation"
            };
            for (int column = 0; column < 4; column++) {
                final int c = column;
                long[] values =
                        samples.stream()
                                .mapToLong(row -> row[c])
                                .filter(v -> v >= 0)
                                .sorted()
                                .toArray();
                if (values.length > 0)
                    out.append(
                            String.format(
                                    Locale.ROOT,
                                    "\n%s n=%d p50 %.2f ms p95 %.2f ms",
                                    labels[c],
                                    values.length,
                                    values[(values.length - 1) / 2] / 1e6,
                                    values[(int) Math.ceil(values.length * .95) - 1] / 1e6));
            }
            Runtime runtime = Runtime.getRuntime();
            out.append("\nEditor JVM used heap: ")
                    .append((runtime.totalMemory() - runtime.freeMemory()) / 1048576)
                    .append(" MiB. Local model memory belongs to its external server.");
            return out.toString();
        }
    }
}
