package com.turboj.repl;

import jdk.jshell.*;
import jdk.jshell.execution.JdiDefaultExecutionControl.JdiStarter.TargetDescription;
import jdk.jshell.execution.JdiExecutionControlProvider;
import jdk.jshell.execution.JdiInitiator;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Persistent JShell controller; snippets always execute in a separate JVM. Call off the EDT. */
public final class JavaRepl implements AutoCloseable {
    public record Evaluation(
            boolean complete,
            boolean success,
            List<String> values,
            List<String> diagnostics,
            long durationNanos) {
        public Evaluation {
            values = List.copyOf(values);
            diagnostics = List.copyOf(diagnostics);
        }
    }

    private final PrintStream output, errors;
    private final Duration timeout;
    private final AtomicLong outputRemaining = new AtomicLong();
    private final ScheduledExecutorService timer =
            Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().daemon().name("turboj-repl-timeout").factory());
    private final List<String> history = new ArrayList<>();
    private volatile JShell shell;
    private volatile Process remote;
    private volatile boolean terminated, closed;

    public JavaRepl(PrintStream output, PrintStream errors) {
        this(output, errors, Duration.ofSeconds(30));
    }

    public JavaRepl(PrintStream output, PrintStream errors, Duration timeout) {
        this.output = Objects.requireNonNull(output);
        this.errors = Objects.requireNonNull(errors);
        this.timeout = Objects.requireNonNull(timeout);
        if (timeout.isNegative() || timeout.isZero())
            throw new IllegalArgumentException("Timeout must be positive");
        try {
            start();
        } catch (RuntimeException failure) {
            timer.shutdownNow();
            kill(remote);
            throw failure;
        }
    }

    private void start() {
        // The public JDK 22+ starter hook lets reset/timeout kill the actual child process.
        // It avoids JShell.stop(), which uses unsafe thread termination within the remote JVM.
        var provider =
                new JdiExecutionControlProvider(
                        (env, parameters, port) -> {
                            var target =
                                    new JdiInitiator(
                                            port,
                                            env.extraRemoteVMOptions(),
                                            parameters.get("remoteAgent"),
                                            false,
                                            "127.0.0.1",
                                            Integer.parseInt(parameters.get("timeout")),
                                            Map.of());
                            remote = target.process();
                            return new TargetDescription(target.vm(), target.process());
                        });
        shell =
                JShell.builder()
                        .executionEngine(
                                provider, Map.of("timeout", "5000", "hostname", "127.0.0.1"))
                        .out(
                                new PrintStream(
                                        new LimitedOutput(output), true, StandardCharsets.UTF_8))
                        .err(
                                new PrintStream(
                                        new LimitedOutput(errors), true, StandardCharsets.UTF_8))
                        .remoteVMOptions("-Xmx256m", "-Dfile.encoding=UTF-8")
                        .build();
        // Snippet input is deliberately empty: the command reader owns terminal stdin.
        terminated = false;
        JShell created = shell;
        shell.onShutdown(
                ignored -> {
                    if (shell == created) terminated = true;
                });
    }

    public synchronized Evaluation submit(String source) {
        if (closed) throw new IllegalStateException("Java REPL is closed");
        if (terminated)
            return new Evaluation(
                    true,
                    false,
                    List.of(),
                    List.of("Java REPL terminated; reset the session before evaluating again"),
                    0);
        List<String> snippets = split(source);
        if (snippets == null) return new Evaluation(false, false, List.of(), List.of(), 0);
        if (snippets.isEmpty()) return new Evaluation(true, true, List.of(), List.of(), 0);
        history.add(source);
        if (history.size() > 1000) history.removeFirst();
        long started = System.nanoTime();
        List<String> values = new ArrayList<>(), diagnostics = new ArrayList<>();
        boolean success = true;
        outputRemaining.set(1024 * 1024);
        Process process = remote;
        var deadline =
                timer.schedule(
                        () -> {
                            terminated = true;
                            kill(process);
                        },
                        Math.max(1, timeout.toMillis()),
                        TimeUnit.MILLISECONDS);
        try {
            for (String snippet : snippets) {
                for (SnippetEvent event : shell.eval(snippet)) {
                    if (event.status() == Snippet.Status.REJECTED) success = false;
                    shell.diagnostics(event.snippet())
                            .forEach(
                                    d ->
                                            diagnostics.add(
                                                    (d.isError() ? "error: " : "warning: ")
                                                            + d.getMessage(Locale.ROOT)));
                    if (event.exception() != null) {
                        success = false;
                        String kind =
                                event.exception() instanceof EvalException evaluated
                                        ? evaluated.getExceptionClassName()
                                        : event.exception().getClass().getSimpleName();
                        diagnostics.add(kind + ": " + event.exception().getMessage());
                    }
                    if (event.causeSnippet() == null
                            && event.value() != null
                            && !event.value().isBlank()) values.add(event.value());
                }
                if (terminated) {
                    success = false;
                    break;
                }
            }
        } catch (IllegalStateException failure) {
            success = false;
            diagnostics.add(failure.getMessage());
        } finally {
            deadline.cancel(false);
        }
        if (terminated) {
            success = false;
            diagnostics.add("Remote Java session terminated or timed out; reset to continue");
        }
        return new Evaluation(true, success, values, diagnostics, System.nanoTime() - started);
    }

    /** Returns null only for genuine incomplete input; malformed complete input reaches diagnostics. */
    private List<String> split(String source) {
        List<String> snippets = new ArrayList<>();
        String remaining = Objects.requireNonNull(source);
        while (!remaining.isBlank()) {
            var completion = shell.sourceCodeAnalysis().analyzeCompletion(remaining);
            switch (completion.completeness()) {
                case DEFINITELY_INCOMPLETE, CONSIDERED_INCOMPLETE -> {
                    return null;
                }
                case EMPTY -> {
                    return snippets;
                }
                case UNKNOWN -> {
                    snippets.add(remaining);
                    return snippets;
                }
                default -> {
                    snippets.add(completion.source());
                    if (completion.remaining().equals(remaining))
                        throw new IllegalStateException("JShell analysis made no progress");
                    remaining = completion.remaining();
                }
            }
        }
        return snippets;
    }

    public synchronized List<String> complete(String source) {
        if (closed || terminated) return List.of();
        int[] anchor = new int[1];
        return shell
                .sourceCodeAnalysis()
                .completionSuggestions(source, source.length(), anchor)
                .stream()
                .map(s -> source.substring(0, anchor[0]) + s.continuation())
                .distinct()
                .sorted()
                .limit(100)
                .toList();
    }

    public synchronized List<String> history() {
        return List.copyOf(history);
    }

    /** Nonblocking process kill; the in-flight submit returns with a terminated-session diagnostic. */
    public void cancel() {
        terminated = true;
        Process process = remote;
        if (process != null)
            Thread.ofPlatform().daemon().name("turboj-repl-stop").start(() -> kill(process));
    }

    public void reset() {
        cancel();
        synchronized (this) {
            if (closed) throw new IllegalStateException("Java REPL is closed");
            shell.close();
            start();
        }
    }

    @Override
    public void close() {
        cancel();
        synchronized (this) {
            if (closed) return;
            closed = true;
            shell.close();
            timer.shutdownNow();
        }
    }

    private static void kill(Process process) {
        if (process == null) return;
        try {
            process.descendants().forEach(ProcessHandle::destroyForcibly);
        } catch (RuntimeException ignored) {
        }
        process.destroyForcibly();
    }

    private final class LimitedOutput extends OutputStream {
        private final PrintStream target;

        LimitedOutput(PrintStream target) {
            this.target = target;
        }

        @Override
        public void write(int value) {
            write(new byte[] {(byte) value}, 0, 1);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            long before = outputRemaining.getAndUpdate(old -> Math.max(-1, old - length));
            int allowed = (int) Math.max(0, Math.min(length, before));
            if (allowed > 0) target.write(bytes, offset, allowed);
            if (before >= 0 && length > before)
                errors.println("\n[TurboJ: REPL output limit reached]");
        }

        @Override
        public void flush() {
            target.flush();
        }
    }
}
