package com.turboj.compiler;

import com.turboj.language.JdtSyntaxAnalyzer;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/** Coalesces pending edits. At most one compiler task runs and one replacement is queued. */
public final class ContinuousCompiler implements AutoCloseable {
    private static final int COALESCE_MILLIS = 35;
    private final CompilerBackend backend;
    private final JdtSyntaxAnalyzer syntax = new JdtSyntaxAnalyzer();
    private final ScheduledThreadPoolExecutor executor;
    private final AtomicLong generation = new AtomicLong();
    private ScheduledFuture<?> pending;
    private CompletableFuture<CompilationResult> requested;
    private boolean closed;
    private int previousCompletedEnd;
    private volatile JdtSyntaxAnalyzer.SyntaxResult latestSyntax;
    private volatile long completedCompilations;
    private volatile long supersededResults;

    public ContinuousCompiler(CompilerBackend backend) {
        this.backend = Objects.requireNonNull(backend);
        executor =
                new ScheduledThreadPoolExecutor(
                        1,
                        runnable -> {
                            var thread = new Thread(runnable, "turboj-compiler");
                            thread.setDaemon(true);
                            return thread;
                        });
        executor.setRemoveOnCancelPolicy(true);
        executor.setExecuteExistingDelayedTasksAfterShutdownPolicy(false);
    }

    /** The callback runs on the compiler thread. Recheck source identity/version on the EDT. */
    public synchronized void submit(SourceSnapshot snapshot, Consumer<CompilationResult> callback) {
        ensureOpen();
        long ticket = supersede();
        pending =
                executor.schedule(
                        () -> analyze(ticket, snapshot, callback),
                        COALESCE_MILLIS,
                        TimeUnit.MILLISECONDS);
    }

    /** Explicit Make/Run bypasses the coalescing delay, using the same serial compiler. */
    public synchronized CompletableFuture<CompilationResult> compileNow(SourceSnapshot snapshot) {
        ensureOpen();
        long ticket = supersede();
        var future = new CompletableFuture<CompilationResult>();
        requested = future;
        pending =
                executor.schedule(
                        () -> compile(ticket, snapshot, future::complete),
                        0,
                        TimeUnit.MILLISECONDS);
        return future;
    }

    public JdtSyntaxAnalyzer.SyntaxResult latestSyntax() {
        return latestSyntax;
    }

    public long completedCompilations() {
        return completedCompilations;
    }

    public long supersededResults() {
        return supersededResults;
    }

    private long supersede() {
        long ticket = generation.incrementAndGet();
        if (pending != null) pending.cancel(false); // Never interrupt a javac operation.
        if (requested != null && !requested.isDone())
            requested.completeExceptionally(
                    new CancellationException("Source superseded by a newer request"));
        requested = null;
        return ticket;
    }

    private void analyze(
            long ticket, SourceSnapshot snapshot, Consumer<CompilationResult> callback) {
        if (ticket != generation.get()) return;
        try {
            var parsed = syntax.analyze(snapshot);
            synchronized (this) {
                if (closed || ticket != generation.get()) {
                    supersededResults++;
                    return;
                }
                latestSyntax = parsed;
                boolean complete =
                        parsed.syntacticallyValid()
                                || parsed.completedUnitEnd() > previousCompletedEnd;
                previousCompletedEnd = parsed.completedUnitEnd();
                if (!complete) {
                    pending =
                            executor.schedule(
                                    () -> compile(ticket, snapshot, callback),
                                    COALESCE_MILLIS,
                                    TimeUnit.MILLISECONDS);
                    return;
                }
            }
        } catch (RuntimeException | LinkageError failure) {
            // javac remains the authority; an optional parser failure must not disable compilation.
            latestSyntax = null;
        }
        compile(ticket, snapshot, callback);
    }

    private void compile(
            long ticket, SourceSnapshot snapshot, Consumer<CompilationResult> callback) {
        if (ticket != generation.get()) return;
        CompilationResult result;
        long started = System.nanoTime();
        try {
            result = backend.compile(snapshot);
        } catch (RuntimeException | LinkageError failure) {
            result =
                    new CompilationResult(
                            snapshot.version(),
                            false,
                            List.of(
                                    new CompilerDiagnostic(
                                            "ERROR",
                                            "Compiler failure: " + failure,
                                            -1,
                                            -1,
                                            -1,
                                            -1)),
                            Map.of(),
                            System.nanoTime() - started,
                            false,
                            null);
        }
        completedCompilations++;
        synchronized (this) {
            if (closed || ticket != generation.get()) {
                supersededResults++;
                return;
            }
            // Guard publication and supersession under the same monitor. UI still checks on EDT.
            try {
                callback.accept(result);
            } catch (RuntimeException ignored) {
                /* a UI consumer cannot terminate the compiler service */
            }
        }
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("Compiler service is closed");
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        supersede();
        // Close on the service thread so a slow compiler cannot block the GUI's close action.
        executor.execute(backend::close);
        executor.shutdown();
    }
}
