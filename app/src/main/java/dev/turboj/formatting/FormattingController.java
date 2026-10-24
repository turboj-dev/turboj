package dev.turboj.formatting;

import dev.turboj.editor.document.PieceTable;
import dev.turboj.ui.EditorComponent;

import dev.javafmt.core.FormatRequest;
import dev.javafmt.core.FormatResult;
import dev.javafmt.core.JavaFormatter;
import dev.javafmt.core.LineEndingPolicy;
import dev.javafmt.core.TextEdit;
import dev.javafmt.core.TextRange;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import javax.swing.SwingUtilities;

/** Bounded asynchronous in-process formatting. Every callback and edit belongs to the EDT. */
public final class FormattingController implements AutoCloseable {
    public enum Outcome {
        APPLIED,
        UNCHANGED,
        FAILED,
        STALE
    }

    @FunctionalInterface
    public interface Engine {
        FormatResult format(FormatRequest request, TextRange range);
        default FormatResult format(FormatRequest request,TextRange range,String languageLevel){return format(request,range);}
    }

    public record Applied(
            PieceTable document,
            PieceTable.Snapshot before,
            List<TextEdit> edits,
            long afterVersion) {}

    private record Pending(
            long id,
            PieceTable document,
            PieceTable.Snapshot source,
            TextRange range,
            String languageLevel,
            Consumer<Outcome> done) {}

    private final EditorComponent editor;
    private final Engine engine;
    private final Consumer<String> notice;
    private final Consumer<Applied> applied;
    private final ThreadPoolExecutor worker =
            new ThreadPoolExecutor(
                    1,
                    1,
                    30,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(1),
                    task -> {
                        var thread = new Thread(task, "turboj-javafmt");
                        thread.setDaemon(true);
                        return thread;
                    },
                    new ThreadPoolExecutor.DiscardOldestPolicy());
    private Pending pending;
    private long generation;
    private String languageLevel="25";
    public void setLanguageLevel(String level){requireEdt();languageLevel=Objects.requireNonNull(level);}
    private volatile boolean closed;

    public FormattingController(
            EditorComponent editor, Consumer<String> notice, Consumer<Applied> applied) {
        this(
                editor,
                new Engine() {
                    private JavaFormatter formatter;
                    private String currentLevel="";
                    @Override public FormatResult format(FormatRequest request,TextRange range,String level){
                        if(formatter==null||!currentLevel.equals(level)){formatter=new JavaFormatter(new dev.javafmt.parser.JdtJavaParser(level));currentLevel=level;}
                        return format(request,range);
                    }

                    @Override
                    public FormatResult format(FormatRequest request, TextRange range) {
                        if (formatter == null) formatter = new JavaFormatter();
                        return range == null
                                ? formatter.format(request)
                                : formatter.formatRange(request, range);
                    }
                },
                notice,
                applied);
    }

    public FormattingController(
            EditorComponent editor,
            Engine engine,
            Consumer<String> notice,
            Consumer<Applied> applied) {
        this.editor = Objects.requireNonNull(editor);
        this.engine = Objects.requireNonNull(engine);
        this.notice = Objects.requireNonNull(notice);
        this.applied = Objects.requireNonNull(applied);
        worker.allowCoreThreadTimeOut(true);
    }

    public void format(boolean selection) {
        format(selection, ignored -> {});
    }

    public void format(boolean selection, Consumer<Outcome> done) {
        requireEdt();
        Objects.requireNonNull(done);
        if (closed || editor.isReadOnly()) {
            if (!closed) notice.accept("Attached source is read only; formatting was not applied.");
            done.accept(Outcome.FAILED);
            return;
        }
        var location = editor.captureLocation();
        if (selection && location.caret() == location.anchor()) {
            notice.accept("Select Java source before formatting a selection.");
            done.accept(Outcome.FAILED);
            return;
        }
        supersede();
        var source = editor.snapshot();
        var range =
                selection
                        ? new TextRange(
                                Math.min(location.caret(), location.anchor()),
                                Math.max(location.caret(), location.anchor()))
                        : null;
        var request = new Pending(++generation, editor.document(), source, range, languageLevel, done);
        pending = request;
        worker.execute(() -> run(request));
    }

    private void run(Pending pending) {
        if (closed) return;
        long started = System.nanoTime();
        try {
            var request =
                    new FormatRequest(
                            pending.source().toString(),
                            pending.source().version(),
                            LineEndingPolicy.PRESERVE);
            FormatResult result = engine.format(request, pending.range(),pending.languageLevel());
            long duration = System.nanoTime() - started;
            SwingUtilities.invokeLater(() -> deliver(pending, result, duration));
        } catch (RuntimeException | LinkageError error) {
            SwingUtilities.invokeLater(
                    () -> {
                        if (valid(pending)) {
                            notice.accept("Formatting failed; the source is unchanged.");
                            finish(pending, Outcome.FAILED);
                        } else finish(pending, Outcome.STALE);
                    });
        }
    }

    private void deliver(Pending request, FormatResult result, long duration) {
        if (!valid(request) || result.sourceVersion() != request.source().version()) {
            finish(request, Outcome.STALE);
            return;
        }
        if (!result.success()) {
            String detail =
                    result.diagnostics().isEmpty()
                            ? "Unsupported or invalid Java source."
                            : result.diagnostics().getFirst().message();
            notice.accept("Format skipped: " + detail);
            finish(request, Outcome.FAILED);
            return;
        }
        boolean changed = !result.edits().isEmpty();
        if (!editor.applyFormatting(result.sourceVersion(), result.edits())) {
            finish(request, Outcome.STALE);
            return;
        }
        if (changed)
            applied.accept(
                    new Applied(
                            request.document(),
                            request.source(),
                            result.edits(),
                            editor.version()));
        notice.accept(
                String.format(
                        Locale.ROOT,
                        "JavaFMT Style %d · %s · %.2f ms",
                        result.styleVersion(),
                        changed ? result.edits().size() + " edits" : "already formatted",
                        duration / 1e6));
        finish(request, changed ? Outcome.APPLIED : Outcome.UNCHANGED);
    }

    private boolean valid(Pending request) {
        return !closed
                && pending == request
                && generation == request.id()
                && editor.document() == request.document()
                && editor.version() == request.source().version();
    }

    private void finish(Pending request, Outcome outcome) {
        if (pending != request) return;
        pending = null;
        request.done().accept(outcome);
    }

    private void supersede() {
        Pending old = pending;
        pending = null;
        generation++;
        worker.getQueue().clear();
        if (old != null) old.done().accept(Outcome.STALE);
    }

    public boolean isPending() {
        return pending != null;
    }

    @Override
    public void close() {
        requireEdt();
        if (closed) return;
        closed = true;
        supersede();
        worker.shutdownNow();
    }

    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread())
            throw new IllegalStateException("Formatter UI call must use EDT");
    }
}
