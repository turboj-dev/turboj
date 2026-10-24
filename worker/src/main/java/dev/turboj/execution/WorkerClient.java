package dev.turboj.execution;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * One active program, with a prewarmed, reusable child JVM. Calls never wait for a child process.
 * Output callbacks run on a reader thread and must return promptly. The caller owns GUI dispatch.
 * Bytecode arrays must remain immutable until the returned future completes.
 */
public final class WorkerClient implements AutoCloseable {
    private final Object lock = new Object();
    private final ThreadPoolExecutor writer;
    private final ScheduledExecutorService timers;
    private final Duration timeout;
    private final int outputLimitBytes, maxHeapMb;
    private volatile Session session;
    private volatile Run active;
    private volatile boolean closed;
    private long sequence;

    public WorkerClient() {
        this(Duration.ofSeconds(30), 1024 * 1024, 256);
    }

    public WorkerClient(Duration timeout, int outputLimitBytes, int maxHeapMb) {
        Objects.requireNonNull(timeout);
        if (timeout.isNegative() || timeout.isZero())
            throw new IllegalArgumentException("Timeout must be positive");
        if (outputLimitBytes < 1024 || outputLimitBytes > 16 * 1024 * 1024)
            throw new IllegalArgumentException("Output limit: 1 KiB–16 MiB");
        if (maxHeapMb < 32 || maxHeapMb > 8192)
            throw new IllegalArgumentException("Heap limit: 32–8192 MiB");
        this.timeout = timeout;
        this.outputLimitBytes = outputLimitBytes;
        this.maxHeapMb = maxHeapMb;
        writer =
                new ThreadPoolExecutor(
                        1,
                        1,
                        0,
                        TimeUnit.MILLISECONDS,
                        new ArrayBlockingQueue<>(64),
                        Thread.ofPlatform().daemon().name("turboj-worker-writer").factory(),
                        new ThreadPoolExecutor.AbortPolicy());
        timers =
                Executors.newSingleThreadScheduledExecutor(
                        Thread.ofPlatform().daemon().name("turboj-worker-timer").factory());
    }

    public CompletableFuture<Void> warmup() {
        CompletableFuture<Void> ready = new CompletableFuture<>();
        enqueue(
                () -> {
                    try {
                        ensureSession();
                        ready.complete(null);
                    } catch (Throwable error) {
                        ready.completeExceptionally(error);
                    }
                },
                ready);
        return ready;
    }

    public CompletableFuture<RunResult> run(
            Map<String, byte[]> bytecode,
            String mainClass,
            List<String> args,
            boolean isolated,
            Consumer<OutputEvent> output) {
        return run(bytecode, mainClass, args, isolated, output, waiting -> {});
    }

    /** Input-state callbacks describe a blocked stdin read; they run on the worker event thread. */
    public CompletableFuture<RunResult> run(
            Map<String, byte[]> bytecode,
            String mainClass,
            List<String> args,
            boolean isolated,
            Consumer<OutputEvent> output,
            Consumer<Boolean> inputState) {
        return run(0, bytecode, mainClass, args, List.of(), isolated, output, inputState);
    }

    /** Explicit source version and runtime dependencies for callers that have a project model. */
    public CompletableFuture<RunResult> run(
            long sourceVersion,
            Map<String, byte[]> bytecode,
            String mainClass,
            List<String> args,
            List<Path> classpath,
            boolean isolated,
            Consumer<OutputEvent> output) {
        return run(
                sourceVersion,
                bytecode,
                mainClass,
                args,
                classpath,
                isolated,
                output,
                waiting -> {});
    }

    public CompletableFuture<RunResult> run(
            long sourceVersion,
            Map<String, byte[]> bytecode,
            String mainClass,
            List<String> args,
            List<Path> classpath,
            boolean isolated,
            Consumer<OutputEvent> output,
            Consumer<Boolean> inputState) {
        Objects.requireNonNull(bytecode);
        Objects.requireNonNull(mainClass);
        Objects.requireNonNull(output);
        Objects.requireNonNull(inputState);
        if (bytecode.isEmpty() || bytecode.size() > Protocol.MAX_CLASSES)
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Missing or excessive bytecode"));
        if (args.size() > 4096 || classpath.size() > 1024)
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Excessive arguments or classpath"));
        long total = 0;
        for (var entry : bytecode.entrySet()) {
            Objects.requireNonNull(entry.getKey());
            Objects.requireNonNull(entry.getValue());
            total += entry.getValue().length + entry.getKey().length() * 3L + 8;
        }
        if (total > Protocol.MAX_FRAME - 2 * Protocol.MAX_STRING)
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("Bytecode exceeds worker protocol limit"));
        Map<String, byte[]> classes = Map.copyOf(bytecode);
        List<String> arguments = List.copyOf(args);
        List<Path> dependencies = List.copyOf(classpath);
        Run run;
        synchronized (lock) {
            if (closed)
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Worker client is closed"));
            if (active != null)
                return CompletableFuture.failedFuture(
                        new IllegalStateException("A program is already running"));
            run =
                    new Run(
                            ++sequence,
                            sourceVersion,
                            output,
                            inputState,
                            isolated,
                            timeout.toNanos());
            active = run;
            scheduleDeadline(run);
        }
        enqueue(
                () -> {
                    if (run.cancelled) return;
                    try {
                        if (isolated) retire(session);
                        Session child = ensureSession();
                        synchronized (lock) {
                            if (closed || run.cancelled || active != run) return;
                            run.child = child;
                            child.run = run;
                        }
                        Protocol.write(
                                child.out,
                                Protocol.RUN,
                                out -> {
                                    out.writeLong(run.id);
                                    out.writeLong(run.sourceVersion);
                                    Protocol.string(out, mainClass);
                                    out.writeInt(outputLimitBytes);
                                    out.writeInt(arguments.size());
                                    for (String arg : arguments) Protocol.string(out, arg);
                                    out.writeInt(dependencies.size());
                                    for (Path path : dependencies)
                                        Protocol.string(out, path.toAbsolutePath().toString());
                                    out.writeInt(classes.size());
                                    for (var entry : classes.entrySet()) {
                                        Protocol.string(out, entry.getKey());
                                        out.writeInt(entry.getValue().length);
                                        out.write(entry.getValue());
                                    }
                                });
                    } catch (Throwable error) {
                        retire(run.child);
                        finish(
                                run,
                                new RunResult(
                                        -1,
                                        System.nanoTime() - run.started,
                                        "Worker start failed: " + error));
                    }
                },
                run.result);
        run.result.whenComplete(
                (result, error) -> {
                    if (error != null) {
                        synchronized (lock) {
                            if (active == run) active = null;
                        }
                        if (run.deadline != null) run.deadline.cancel(false);
                    }
                });
        return run.result;
    }

    public void sendInput(String input) {
        Objects.requireNonNull(input);
        sendInput(input.getBytes(StandardCharsets.UTF_8));
    }

    /** Raw stdin chunks, including UTF-8 sequences split across pipe reads. */
    public void sendInput(byte[] input) {
        Objects.requireNonNull(input);
        if (input.length > Protocol.MAX_INPUT)
            throw new IllegalArgumentException("Input chunk exceeds 64 KiB");
        byte[] bytes = input.clone();
        Run run = active;
        if (run == null || run.cancelled) throw new IllegalStateException("No running program");
        enqueueInput(
                run,
                Protocol.INPUT,
                out -> {
                    out.writeLong(run.id);
                    out.writeInt(bytes.length);
                    out.write(bytes);
                });
    }

    public void closeInput() {
        Run run = active;
        if (run != null && !run.cancelled)
            enqueueInput(run, Protocol.EOF, out -> out.writeLong(run.id));
    }

    private void enqueueInput(Run run, byte type, Protocol.Body body) {
        try {
            writer.execute(
                    () -> {
                        if (run.cancelled || active != run || run.child == null) return;
                        try {
                            Protocol.write(run.child.out, type, body);
                        } catch (IOException error) {
                            terminate(run, -1, "Worker input failed: " + error.getMessage());
                        }
                    });
        } catch (RejectedExecutionException error) {
            throw new IllegalStateException("Program input queue is full or closed", error);
        }
    }

    public boolean isRunning() {
        return active != null;
    }

    /** Useful for reproducible warm/cold benchmarks; does not expose a protocol endpoint. */
    public OptionalLong workerPid() {
        Session current = session;
        return current != null && current.process.isAlive()
                ? OptionalLong.of(current.process.pid())
                : OptionalLong.empty();
    }

    public void stop() {
        Run run = active;
        if (run != null) terminate(run, 130, "Stopped by user");
    }

    private void terminate(Run run, int code, String detail) {
        synchronized (lock) {
            if (active != run || run.cancelled) return;
            run.cancelled = true;
        }
        Thread.ofPlatform()
                .daemon()
                .name("turboj-worker-stop")
                .start(
                        () -> {
                            retire(run.child);
                            finish(
                                    run,
                                    new RunResult(code, System.nanoTime() - run.started, detail));
                        });
    }

    private Session ensureSession() throws Exception {
        if (closed) throw new IllegalStateException("Worker client is closed");
        Session current = session;
        if (current != null && current.process.isAlive()) {
            CompletableFuture<Void> ping = new CompletableFuture<>();
            current.health = ping;
            Protocol.write(current.out, Protocol.PING, out -> {});
            try {
                ping.get(2, TimeUnit.SECONDS);
                return current;
            } catch (Exception error) {
                retire(current);
            }
        }
        ProcessBuilder builder =
                new ProcessBuilder(
                        javaExecutable(),
                        "-Xms16m",
                        "-Xmx" + maxHeapMb + "m",
                        "-Dfile.encoding=UTF-8",
                        "-cp",
                        System.getProperty("java.class.path"),
                        WorkerMain.class.getName());
        builder.environment().remove("JAVA_TOOL_OPTIONS");
        builder.environment().remove("JDK_JAVA_OPTIONS");
        builder.environment().remove("_JAVA_OPTIONS");
        Session child = new Session(builder.start());
        synchronized (lock) {
            if (closed) {
                killTree(child.process);
                throw new IllegalStateException("Worker client is closed");
            }
            session = child;
        }
        Thread.ofPlatform().daemon().name("turboj-worker-events").start(() -> readEvents(child));
        Thread.ofPlatform()
                .daemon()
                .name("turboj-worker-errors")
                .start(() -> readNativeErrors(child));
        try {
            child.ready.get(8, TimeUnit.SECONDS);
        } catch (Exception error) {
            retire(child);
            throw error;
        }
        return child;
    }

    private static String javaExecutable() {
        String name = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", name).toString();
    }

    private void readEvents(Session child) {
        try (DataInputStream input =
                new DataInputStream(new BufferedInputStream(child.process.getInputStream()))) {
            while (true) {
                Protocol.Frame frame = Protocol.read(input);
                DataInputStream data = frame.data();
                switch (frame.type()) {
                    case Protocol.READY -> {
                        if (data.readInt() != Protocol.VERSION)
                            throw new IOException("Unsupported worker version");
                        child.ready.complete(null);
                    }
                    case Protocol.PONG -> child.health.complete(null);
                    case Protocol.OUTPUT -> {
                        long id = data.readLong();
                        boolean stderr = data.readBoolean();
                        int length = data.readInt();
                        if (length < 0 || length > 8192 || length > data.available())
                            throw new IOException("Invalid output length");
                        byte[] bytes = data.readNBytes(length);
                        Run run = child.run;
                        if (run != null && run.id == id && !run.cancelled) {
                            String text = (stderr ? run.stderr : run.stdout).decode(bytes, false);
                            if (!text.isEmpty()) emit(run, new OutputEvent(text, stderr));
                        }
                    }
                    case Protocol.INPUT_STATE -> {
                        long id = data.readLong(), version = data.readLong();
                        boolean waiting = data.readBoolean();
                        Run run = child.run;
                        if (run != null && run.id == id) {
                            if (run.sourceVersion != version)
                                throw new IOException("Mismatched input state");
                            updateInputState(run, waiting);
                        }
                    }
                    case Protocol.RESULT -> {
                        long id = data.readLong(), version = data.readLong();
                        int exit = data.readInt();
                        long duration = data.readLong();
                        boolean restart = data.readBoolean();
                        String detail = Protocol.string(data);
                        Run run = child.run;
                        if (run == null || run.id != id || run.sourceVersion != version)
                            throw new IOException("Mismatched run result");
                        String stdout = run.stdout.decode(new byte[0], true),
                                stderr = run.stderr.decode(new byte[0], true);
                        if (!stdout.isEmpty()) emit(run, new OutputEvent(stdout, false));
                        if (!stderr.isEmpty()) emit(run, new OutputEvent(stderr, true));
                        child.run = null;
                        if (run.isolated || restart) retire(child);
                        if (!run.cancelled) finish(run, new RunResult(exit, duration, detail));
                    }
                    default -> throw new IOException("Unknown worker event " + frame.type());
                }
                Protocol.exhausted(data);
            }
        } catch (Throwable error) {
            child.ready.completeExceptionally(error);
            child.health.completeExceptionally(error);
            Run run = child.run;
            int code = -1;
            try {
                if (child.process.waitFor(500, TimeUnit.MILLISECONDS))
                    code = child.process.exitValue();
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            retire(child);
            if (run != null && !run.cancelled) {
                String detail =
                        error instanceof EOFException
                                ? "Worker process exited (System.exit or crash); next run starts a"
                                      + " fresh worker"
                                : "Worker protocol failed: " + error.getMessage();
                finish(run, new RunResult(code, System.nanoTime() - run.started, detail));
            }
        }
    }

    private void readNativeErrors(Session child) {
        // JVM/native stderr is separate from the framed Java stderr, and is capped independently.
        try (InputStream stream = child.process.getErrorStream()) {
            byte[] bytes = new byte[4096];
            int count, accepted = 0;
            Utf8 decoder = new Utf8();
            while ((count = stream.read(bytes)) >= 0) {
                if (accepted >= outputLimitBytes) continue;
                int length = Math.min(count, outputLimitBytes - accepted);
                accepted += length;
                Run run = child.run;
                if (run != null)
                    emit(
                            run,
                            new OutputEvent(
                                    decoder.decode(Arrays.copyOf(bytes, length), false), true));
            }
        } catch (IOException ignored) {
        }
    }

    private static void emit(Run run, OutputEvent event) {
        if (run.cancelled) return;
        try {
            run.output.accept(event);
        } catch (RuntimeException ignored) {
            /* A UI callback failure cannot corrupt the worker protocol. */
        }
    }

    /**
     * Called while holding lock. The budget counts startup/dispatch/execution, excluding stdin
     * waits.
     */
    private void scheduleDeadline(Run run) {
        run.activeSince = System.nanoTime();
        long generation = ++run.deadlineGeneration;
        run.deadline =
                timers.schedule(
                        () -> {
                            synchronized (lock) {
                                if (active != run
                                        || run.cancelled
                                        || run.waitingForInput
                                        || run.deadlineGeneration != generation) return;
                                terminate(
                                        run,
                                        124,
                                        "Execution timed out after "
                                                + timeout
                                                + " of active execution (stdin wait excluded)");
                            }
                        },
                        Math.max(0, run.remainingNanos),
                        TimeUnit.NANOSECONDS);
    }

    private void updateInputState(Run run, boolean waiting) {
        synchronized (lock) {
            if (active != run || run.cancelled || run.waitingForInput == waiting) return;
            if (waiting) {
                run.remainingNanos =
                        Math.max(0, run.remainingNanos - (System.nanoTime() - run.activeSince));
                run.waitingForInput = true;
                run.deadlineGeneration++;
                if (run.deadline != null) run.deadline.cancel(false);
            } else {
                run.waitingForInput = false;
                scheduleDeadline(run);
            }
            // Publish transitions before Stop/finish can publish the final false state.
            emitInputState(run, waiting);
        }
    }

    private static void emitInputState(Run run, boolean waiting) {
        try {
            run.inputState.accept(waiting);
        } catch (RuntimeException ignored) {
            /* UI callbacks must not corrupt protocol state. */
        }
    }

    private void finish(Run run, RunResult result) {
        boolean wasWaiting;
        synchronized (lock) {
            if (active != run) return;
            active = null;
            wasWaiting = run.waitingForInput;
            run.waitingForInput = false;
            run.deadlineGeneration++;
            if (run.deadline != null) run.deadline.cancel(false);
        }
        if (wasWaiting) emitInputState(run, false);
        run.result.complete(result);
    }

    private void retire(Session child) {
        if (child == null) return;
        synchronized (lock) {
            if (session == child) session = null;
        }
        killTree(child.process);
    }

    private static void killTree(Process process) {
        try {
            List<ProcessHandle> descendants = process.descendants().toList();
            for (int i = descendants.size() - 1; i >= 0; i--) descendants.get(i).destroyForcibly();
        } catch (RuntimeException ignored) {
        }
        process.destroyForcibly();
        try {
            process.getOutputStream().close();
        } catch (IOException ignored) {
        }
    }

    private void enqueue(Runnable action, CompletableFuture<?> result) {
        try {
            writer.execute(action);
        } catch (RejectedExecutionException error) {
            result.completeExceptionally(error);
        }
    }

    @Override
    public void close() {
        synchronized (lock) {
            if (closed) return;
            closed = true;
        }
        stop();
        Session child = session;
        Thread.ofPlatform().daemon().name("turboj-worker-close").start(() -> retire(child));
        writer.shutdown();
        timers.shutdownNow();
    }

    private static final class Session {
        final Process process;
        final DataOutputStream out;
        final CompletableFuture<Void> ready = new CompletableFuture<>();
        volatile CompletableFuture<Void> health = new CompletableFuture<>();
        volatile Run run;

        Session(Process process) {
            this.process = process;
            out = new DataOutputStream(new BufferedOutputStream(process.getOutputStream()));
        }
    }

    private static final class Run {
        final long id, sourceVersion, started = System.nanoTime();
        final Consumer<OutputEvent> output;
        final Consumer<Boolean> inputState;
        final boolean isolated;
        long remainingNanos, activeSince, deadlineGeneration;
        boolean waitingForInput;
        final CompletableFuture<RunResult> result = new CompletableFuture<>();
        final Utf8 stdout = new Utf8(), stderr = new Utf8();
        volatile boolean cancelled;
        volatile Session child;
        volatile ScheduledFuture<?> deadline;

        Run(
                long id,
                long version,
                Consumer<OutputEvent> output,
                Consumer<Boolean> inputState,
                boolean isolated,
                long budgetNanos) {
            this.id = id;
            sourceVersion = version;
            this.output = output;
            this.inputState = inputState;
            this.isolated = isolated;
            remainingNanos = budgetNanos;
        }
    }

    private static final class Utf8 {
        private final CharsetDecoder decoder =
                StandardCharsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPLACE)
                        .onUnmappableCharacter(CodingErrorAction.REPLACE);
        private byte[] remaining = new byte[0];

        synchronized String decode(byte[] bytes, boolean end) {
            ByteBuffer input =
                    ByteBuffer.allocate(remaining.length + bytes.length)
                            .put(remaining)
                            .put(bytes)
                            .flip();
            CharBuffer output = CharBuffer.allocate(input.remaining() + 2);
            decoder.decode(input, output, end);
            remaining = new byte[input.remaining()];
            input.get(remaining);
            if (end) decoder.flush(output);
            return output.flip().toString();
        }
    }
}
