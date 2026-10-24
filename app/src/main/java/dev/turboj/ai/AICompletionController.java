package dev.turboj.ai;

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
    private KeyStroke accept = KeyStroke.getKeyStroke("TAB"),
            dismiss = KeyStroke.getKeyStroke("ESCAPE"),
            nextWord = KeyStroke.getKeyStroke("ctrl alt ENTER");

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
        status.accept(
                config.mode() == AIConfig.Mode.DISABLED
                        ? ""
                        : "AI · " + (config.mode() == AIConfig.Mode.LOCAL ? "Local" : "Cloud"));
    }

    /** Cheap disabled fast path; snapshots are captured only for an enabled service. */
    public void changed() {
        if (closed || config.mode() == AIConfig.Mode.DISABLED) return;
        int caret = editor.caretOffset();
        if (lastDocument == editor.document()
                && lastVersion == editor.version()
                && lastCaret == caret) return;
        boolean edited = lastDocument != editor.document() || lastVersion != editor.version();
        lastDocument = editor.document();
        lastVersion = editor.version();
        lastCaret = caret;
        dismiss();
        if (edited
                && config.automatic()
                && !editor.isReadOnly()
                && editor.getSelectedText().isEmpty()) {
            scheduledAt = System.nanoTime();
            timer.restart();
        }
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
        if (editor.isReadOnly() || !editor.getSelectedText().isEmpty()) return;
        if (!explicit && System.nanoTime() < retryAfter) return;
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
                                if (ticket.explicit)
                                    notice.accept(
                                            "AI returned no safe insertion at this position.");
                            }
                        });
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
                                                + " unavailable");
                            }
                        });
        } finally {
            // A request may finish while the EDT installs its successor. Only retire our own token.
            cancellation.compareAndSet(token, null);
        }
    }

    private static String safeMessage(Exception error) {
        if (error instanceof IllegalArgumentException
                && error.getMessage() != null
                && error.getMessage().startsWith("AI")) return error.getMessage();
        return "check provider configuration or connection";
    }

    private boolean valid(Ticket ticket) {
        return !closed
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
        if (key.equals(accept) || key.equals(nextWord)) {
            cancelRequest();
            timer.stop();
            boolean accepted =
                    key.equals(accept)
                            ? editor.acceptInlineCompletion()
                            : editor.acceptNextAIWord();
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
