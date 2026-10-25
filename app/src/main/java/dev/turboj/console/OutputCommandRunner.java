package dev.turboj.console;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;

/** One explicit noninteractive command at a time; no PTY, login shell or persistent shell state. */
public final class OutputCommandRunner implements AutoCloseable {
    public record Result(String command, Path directory, int exitCode, boolean cancelled,
                         boolean timedOut, long elapsedNanos, String error) {
        public boolean succeeded() { return exitCode == 0 && !cancelled && !timedOut && error == null; }
    }

    private static final int OUTPUT_LIMIT = 1_048_576;
    private final Duration timeout;
    private Job active;
    private boolean closed;

    public OutputCommandRunner() { this(Duration.ofMinutes(10)); }

    public OutputCommandRunner(Duration timeout) {
        if (timeout == null || timeout.isNegative() || timeout.isZero()
                || timeout.compareTo(Duration.ofHours(1)) > 0)
            throw new IllegalArgumentException("Command timeout must be positive and at most one hour");
        this.timeout = timeout;
    }

    /** The callback runs on stream-reader threads. It must be fast and safe for concurrent calls. */
    public synchronized CompletableFuture<Result> start(String command, Path directory,
                                                         BiConsumer<String, Boolean> output) {
        Objects.requireNonNull(command);
        Objects.requireNonNull(directory);
        Objects.requireNonNull(output);
        if (closed) throw new IllegalStateException("Output command runner is closed");
        if (active != null) throw new IllegalStateException("An output command is already running");
        if (command.isBlank() || command.length() > 16384 || command.indexOf('\0') >= 0
                || command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0)
            throw new IllegalArgumentException("Enter one nonempty command line (maximum 16384 characters)");
        var job = new Job(command, directory.toAbsolutePath().normalize(), output);
        active = job;
        Thread.ofVirtual().name("turboj-output-command").start(() -> execute(job));
        return job.completion;
    }

    public synchronized boolean isRunning() { return active != null; }

    /** Signals only this runner's job; never waits or sends a signal on the Swing EDT. */
    public synchronized boolean cancel() {
        if (active == null) return false;
        active.cancelled.set(true);
        return true;
    }

    @Override public synchronized void close() {
        closed = true;
        cancel();
    }

    private void execute(Job job) {
        Process process = null;
        Thread stdout = null, stderr = null;
        int exit = -1;
        boolean timedOut = false;
        String error = null;
        var owned = new LinkedHashMap<Long, ProcessHandle>();
        try {
            if (!job.cancelled.get()) {
                if (!Files.isDirectory(job.directory))
                    throw new IOException("Command working directory does not exist: " + job.directory);
                process = new ProcessBuilder("/bin/sh", "-c", job.command)
                        .directory(job.directory.toFile()).start();
                // These are one-shot commands. Scanner/Java stdin belongs to ConsolePane separately.
                process.getOutputStream().close();
                stdout = pump(process.getInputStream(), false, job);
                stderr = pump(process.getErrorStream(), true, job);
                while (process.isAlive()) {
                    rememberDescendants(process, owned);
                    if (job.cancelled.get()) break;
                    if (System.nanoTime() - job.started >= timeout.toNanos()) {
                        timedOut = true;
                        break;
                    }
                    process.waitFor(25, TimeUnit.MILLISECONDS);
                }
                // Also clean up tracked children on normal completion: this is not a daemon launcher.
                terminateOwned(process, owned);
                if (!process.isAlive()) exit = process.exitValue();
                else error = "The command did not terminate after cancellation";
                joinPump(stdout);
                joinPump(stderr);
            }
        } catch (IOException | RuntimeException failure) {
            error = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            job.cancelled.set(true);
            error = "Command controller interrupted";
        } finally {
            if (process != null) {
                terminateOwned(process, owned);
                closeQuietly(process.getInputStream());
                closeQuietly(process.getErrorStream());
            }
            if (stdout != null && stdout.isAlive()) stdout.interrupt();
            if (stderr != null && stderr.isAlive()) stderr.interrupt();
            synchronized (this) { if (active == job) active = null; }
            job.completion.complete(new Result(job.command, job.directory, exit, job.cancelled.get(),
                    timedOut, System.nanoTime() - job.started, error));
        }
    }

    private static Thread pump(InputStream stream, boolean stderr, Job job) {
        return Thread.ofVirtual().name(stderr ? "turboj-command-stderr" : "turboj-command-stdout").start(() -> {
            try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                char[] buffer = new char[4096];
                int count;
                while ((count = reader.read(buffer)) != -1) {
                    int previous = job.outputChars.getAndAccumulate(count,
                            (before, more) -> Math.min(OUTPUT_LIMIT + 1, before + more));
                    int allowed = Math.min(count, Math.max(0, OUTPUT_LIMIT - previous));
                    if (allowed > 0) job.output.accept(new String(buffer, 0, allowed), stderr);
                    if (allowed < count && job.truncated.compareAndSet(false, true))
                        job.output.accept("\n[command output truncated after 1 MiB]\n", true);
                }
            } catch (IOException ignored) {
                // Closing the owned process pipes is expected during cancellation/timeout.
            }
        });
    }

    private static void rememberDescendants(Process process, Map<Long, ProcessHandle> owned) {
        process.descendants().limit(1024).forEach(child -> owned.putIfAbsent(child.pid(), child));
    }

    private static void terminateOwned(Process process, Map<Long, ProcessHandle> owned) {
        rememberDescendants(process, owned);
        var children = owned.values().stream().filter(ProcessHandle::isAlive).toList();
        if (!process.isAlive() && children.isEmpty()) return;
        // Children first lets a waiting shell reap them before terminating the shell itself.
        children.forEach(ProcessHandle::destroy);
        try { process.waitFor(150, TimeUnit.MILLISECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        if (process.isAlive()) process.destroy();
        for (var child : children) if (child.isAlive()) child.destroyForcibly();
        try {
            if (!process.waitFor(250, TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                process.waitFor(500, TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException interrupted) {
            process.destroyForcibly();
            Thread.currentThread().interrupt();
        }
    }

    private static void joinPump(Thread pump) throws InterruptedException {
        // Detached children must not be able to hold the job open indefinitely through inherited pipes.
        pump.join(1000);
    }

    private static void closeQuietly(InputStream stream) {
        try { stream.close(); } catch (IOException ignored) { }
    }

    private static final class Job {
        final String command;
        final Path directory;
        final BiConsumer<String, Boolean> output;
        final long started = System.nanoTime();
        final AtomicBoolean cancelled = new AtomicBoolean();
        final AtomicBoolean truncated = new AtomicBoolean();
        final AtomicInteger outputChars = new AtomicInteger();
        final CompletableFuture<Result> completion = new CompletableFuture<>();

        Job(String command, Path directory, BiConsumer<String, Boolean> output) {
            this.command = command;
            this.directory = directory;
            this.output = output;
        }
    }
}
