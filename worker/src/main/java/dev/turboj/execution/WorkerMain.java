package dev.turboj.execution;

import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Child-process entry point. Never call this inside the desktop JVM. */
public final class WorkerMain {
    private final DataInputStream commands;
    private final DataOutputStream events;
    private volatile Execution active;

    private WorkerMain() {
        commands =
                new DataInputStream(
                        new BufferedInputStream(new FileInputStream(FileDescriptor.in)));
        events =
                new DataOutputStream(
                        new BufferedOutputStream(new FileOutputStream(FileDescriptor.out)));
    }

    public static void main(String[] args) {
        WorkerMain worker = new WorkerMain();
        try {
            worker.listen();
        } catch (EOFException ignored) {
        } catch (Throwable error) {
            error.printStackTrace(new PrintStream(new FileOutputStream(FileDescriptor.err)));
        } finally {
            Runtime.getRuntime().halt(0);
        }
    }

    private void listen() throws IOException {
        Protocol.write(events, Protocol.READY, out -> out.writeInt(Protocol.VERSION));
        while (true) {
            Protocol.Frame frame = Protocol.read(commands);
            DataInputStream data = frame.data();
            switch (frame.type()) {
                case Protocol.RUN -> start(data);
                case Protocol.INPUT -> {
                    long id = data.readLong();
                    int count = data.readInt();
                    if (count < 0 || count > Protocol.MAX_INPUT || count > data.available())
                        throw new IOException("Invalid input length");
                    byte[] input = data.readNBytes(count);
                    Execution run = active;
                    if (run != null && run.id == id) run.input.accept(input);
                }
                case Protocol.EOF -> {
                    long id = data.readLong();
                    Execution run = active;
                    if (run != null && run.id == id) run.input.finish();
                }
                case Protocol.PING -> Protocol.write(events, Protocol.PONG, out -> {});
                default -> throw new IOException("Unknown worker command " + frame.type());
            }
            Protocol.exhausted(data);
        }
    }

    private void start(DataInputStream data) throws IOException {
        if (active != null) throw new IOException("Concurrent RUN is forbidden");
        long id = data.readLong();
        long sourceVersion = data.readLong();
        String mainClass = Protocol.string(data);
        int outputLimit = data.readInt();
        if (outputLimit < 1024 || outputLimit > 16 * 1024 * 1024)
            throw new IOException("Invalid output limit");
        int argc = data.readInt();
        if (argc < 0 || argc > 4096) throw new IOException("Invalid argument count");
        String[] arguments = new String[argc];
        for (int i = 0; i < argc; i++) arguments[i] = Protocol.string(data);
        int pathCount = data.readInt();
        if (pathCount < 0 || pathCount > 1024) throw new IOException("Invalid classpath count");
        URL[] paths = new URL[pathCount];
        for (int i = 0; i < pathCount; i++)
            paths[i] = java.nio.file.Path.of(Protocol.string(data)).toUri().toURL();
        int count = data.readInt();
        if (count < 1 || count > Protocol.MAX_CLASSES) throw new IOException("Invalid class count");
        Map<String, byte[]> classes = new HashMap<>();
        for (int i = 0; i < count; i++) {
            String name = Protocol.string(data);
            int size = data.readInt();
            if (size < 1 || size > data.available()) throw new IOException("Invalid class length");
            if (classes.put(name, data.readNBytes(size)) != null)
                throw new IOException("Duplicate class name");
        }
        ProgramInput stdin =
                new ProgramInput(
                        waiting ->
                                Protocol.write(
                                        events,
                                        Protocol.INPUT_STATE,
                                        out -> {
                                            out.writeLong(id);
                                            out.writeLong(sourceVersion);
                                            out.writeBoolean(waiting);
                                        }));
        Execution run =
                new Execution(
                        id,
                        sourceVersion,
                        classes,
                        mainClass,
                        arguments,
                        paths,
                        outputLimit,
                        stdin);
        active = run;
        Thread thread=new Thread(() -> execute(run),"turboj-program-"+id);thread.setDaemon(true);thread.start();
    }

    private void execute(Execution run) {
        long started = System.nanoTime();
        try {
            executeGuarded(run);
        } catch (Throwable infrastructureFailure) {
            // Health inspection can itself fail (e.g. macOS sandbox denies process enumeration).
            // Always report completion: a dead execution thread must not leave the client waiting.
            active = null;
            try {
                Protocol.write(
                        events,
                        Protocol.RESULT,
                        out -> {
                            out.writeLong(run.id);
                            out.writeLong(run.sourceVersion);
                            out.writeInt(-1);
                            out.writeLong(System.nanoTime() - started);
                            out.writeBoolean(true);
                            Protocol.string(
                                    out, "Worker infrastructure failed: " + infrastructureFailure);
                        });
            } catch (Throwable ignored) {
                Runtime.getRuntime().halt(2);
            }
        }
    }

    private void executeGuarded(Execution run) {
        long started = System.nanoTime();
        Set<Thread> baseline = Thread.getAllStackTraces().keySet();
        Locale locale = Locale.getDefault();
        TimeZone zone = TimeZone.getDefault();
        // TimeZone initialization can set user.timezone; capture properties only afterwards.
        Properties properties = (Properties) System.getProperties().clone();
        Thread.UncaughtExceptionHandler handler = Thread.getDefaultUncaughtExceptionHandler();
        PrintStream stdout =
                new PrintStream(new EventStream(run, false), true, StandardCharsets.UTF_8);
        PrintStream stderr =
                new PrintStream(new EventStream(run, true), true, StandardCharsets.UTF_8);
        int exit = 0;
        String detail = "Completed";
        boolean restart;
        System.setIn(run.input);
        System.setOut(stdout);
        System.setErr(stderr);
        try (BytecodeLoader loader = new BytecodeLoader(run.classes, run.paths)) {
            String moduleName=System.getProperty("turboj.run.module","");
            ClassLoader executionLoader=moduleName.isEmpty()?loader:ModuleLayer.boot().findLoader(moduleName);
            Thread.currentThread().setContextClassLoader(executionLoader);
            Class<?> entry = Class.forName(run.mainClass, false, executionLoader);
            Method main = findMain(entry);
            Object receiver = null;
            if (!Modifier.isStatic(main.getModifiers())) {
                Constructor<?> constructor = entry.getDeclaredConstructor();
                if (Modifier.isPrivate(constructor.getModifiers()))
                    throw new NoSuchMethodException(
                            "Instance main requires a non-private no-argument constructor");
                constructor.setAccessible(true);
                receiver = constructor.newInstance();
            }
            main.setAccessible(true);
            main.invoke(
                    receiver,
                    main.getParameterCount() == 0 ? new Object[0] : new Object[] {run.arguments});
        } catch (Throwable error) {
            Throwable cause =
                    error instanceof InvocationTargetException && error.getCause() != null
                            ? error.getCause()
                            : error;
            cause.printStackTrace(stderr);
            exit = 1;
            detail =
                    cause.getClass().getName()
                            + (cause.getMessage() == null ? "" : ": " + cause.getMessage());
        } finally {
            stdout.flush();
            stderr.flush();
            run.input.finish();
            Thread.currentThread().setContextClassLoader(ClassLoader.getSystemClassLoader());
        }
        boolean processInspectionUnavailable = false;
        boolean hasChildren;
        try {
            hasChildren = ProcessHandle.current().descendants().findAny().isPresent();
        } catch (RuntimeException denied) {
            hasChildren = true;
            processInspectionUnavailable = true;
        }
        restart =
                System.out != stdout
                        || System.err != stderr
                        || System.in != run.input
                        || !System.getProperties().equals(properties)
                        || !Locale.getDefault().equals(locale)
                        || !TimeZone.getDefault().equals(zone)
                        || Thread.getDefaultUncaughtExceptionHandler() != handler
                        || Thread.getAllStackTraces().keySet().stream()
                                .anyMatch(t -> t.isAlive() && !baseline.contains(t))
                        || hasChildren;
        if (restart)
            detail +=
                    "; worker restart required (residual threads, child process, or global state"
                        + " change)";
        if (processInspectionUnavailable) detail += "; process inspection unavailable";
        int code = exit;
        String resultDetail = detail;
        boolean retire = restart;
        long duration = System.nanoTime() - started;
        active = null;
        try {
            Protocol.write(
                    events,
                    Protocol.RESULT,
                    out -> {
                        out.writeLong(run.id);
                        out.writeLong(run.sourceVersion);
                        out.writeInt(code);
                        out.writeLong(duration);
                        out.writeBoolean(retire);
                        Protocol.string(out, resultDetail);
                    });
        } catch (IOException ignored) {
            Runtime.getRuntime().halt(2);
        }
    }

    private static Method findMain(Class<?> type) throws NoSuchMethodException {
        for (int arity : new int[] {1, 0}) {
            for (Class<?> current = type; current != null; current = current.getSuperclass()) {
                for (Method method : current.getDeclaredMethods()) {
                    int modifiers = method.getModifiers();
                    if (!method.getName().equals("main")
                            || method.getReturnType() != void.class
                            || Modifier.isPrivate(modifiers)
                            || method.getParameterCount() != arity) continue;
                    if (arity == 1 && method.getParameterTypes()[0] != String[].class) continue;
                    if (current != type
                            && !Modifier.isPublic(modifiers)
                            && !Modifier.isProtected(modifiers)
                            && !current.getPackageName().equals(type.getPackageName())) continue;
                    return method;
                }
            }
        }
        throw new NoSuchMethodException(
                "No supported main(String[]) or main() in " + type.getName());
    }

    private static final class BytecodeLoader extends URLClassLoader {
        private final Map<String, byte[]> classes;

        BytecodeLoader(Map<String, byte[]> classes, URL[] paths) {
            super(paths, ClassLoader.getPlatformClassLoader());
            this.classes = classes;
        }

        @Override
        protected Class<?> findClass(String name) throws ClassNotFoundException {
            byte[] bytes = classes.get(name);
            return bytes == null
                    ? super.findClass(name)
                    : defineClass(name, bytes, 0, bytes.length);
        }
    }

    private final class EventStream extends OutputStream {
        private final Execution run;
        private final boolean stderr;

        EventStream(Execution run, boolean stderr) {
            this.run = run;
            this.stderr = stderr;
        }

        @Override
        public void write(int value) throws IOException {
            write(new byte[] {(byte) value}, 0, 1);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            for (int pos = offset; pos < offset + length; ) {
                int size = Math.min(8192, offset + length - pos);
                int previous =
                        run.outputBytes.getAndUpdate(
                                old -> Math.min(run.outputLimit + 1, old + size));
                int allowed = Math.min(size, Math.max(0, run.outputLimit - previous));
                if (allowed > 0) {
                    int start = pos;
                    Protocol.write(
                            events,
                            Protocol.OUTPUT,
                            out -> {
                                out.writeLong(run.id);
                                out.writeBoolean(stderr);
                                out.writeInt(allowed);
                                out.write(bytes, start, allowed);
                            });
                }
                if (previous <= run.outputLimit && previous + size > run.outputLimit) {
                    byte[] notice =
                            "\n[TurboJ: output limit reached; further output discarded]\n"
                                    .getBytes(StandardCharsets.UTF_8);
                    Protocol.write(
                            events,
                            Protocol.OUTPUT,
                            out -> {
                                out.writeLong(run.id);
                                out.writeBoolean(true);
                                out.writeInt(notice.length);
                                out.write(notice);
                            });
                }
                pos += size;
            }
        }
    }

    private static final class Execution {
        final long id, sourceVersion;
        final Map<String, byte[]> classes;
        final String mainClass;
        final String[] arguments;
        final URL[] paths;
        final int outputLimit;
        final AtomicInteger outputBytes = new AtomicInteger();
        final ProgramInput input;

        Execution(
                long id,
                long version,
                Map<String, byte[]> classes,
                String mainClass,
                String[] arguments,
                URL[] paths,
                int limit,
                ProgramInput input) {
            this.id = id;
            this.sourceVersion = version;
            this.classes = classes;
            this.mainClass = mainClass;
            this.arguments = arguments;
            this.paths = paths;
            this.outputLimit = limit;
            this.input = input;
        }
    }

    @FunctionalInterface
    private interface InputStateListener {
        void changed(boolean waiting) throws IOException;
    }

    /** The protocol reader never waits for the program to consume stdin. */
    private static final class ProgramInput extends InputStream {
        private static final byte[] END = new byte[0];
        private final BlockingQueue<byte[]> chunks = new ArrayBlockingQueue<>(64);
        private byte[] current;
        private int position;
        private volatile boolean finished;
        private final InputStateListener inputState;

        ProgramInput(InputStateListener inputState) {
            this.inputState = inputState;
        }

        void accept(byte[] bytes) throws IOException {
            if (!finished && bytes.length > 0 && !chunks.offer(bytes))
                throw new IOException("Program input buffer full");
        }

        void finish() {
            finished = true;
            chunks.offer(END);
        }

        @Override
        public synchronized int read() throws IOException {
            while (current == null || position == current.length) {
                if (finished && chunks.isEmpty()) return -1;
                current = chunks.poll();
                if (current == null) {
                    // Notify only when a read genuinely needs more input, never from output text.
                    inputState.changed(true);
                    try {
                        current = chunks.take();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new InterruptedIOException();
                    } finally {
                        inputState.changed(false);
                    }
                }
                position = 0;
                if (current == END) return -1;
            }
            return current[position++] & 255;
        }

        @Override
        public synchronized int read(byte[] target, int offset, int length) throws IOException {
            Objects.checkFromIndexSize(offset, length, target.length);
            if (length == 0) return 0;
            int first = read();
            if (first < 0) return -1;
            target[offset] = (byte) first;
            int rest = Math.min(length - 1, current.length - position);
            System.arraycopy(current, position, target, offset + 1, rest);
            position += rest;
            return rest + 1;
        }

        @Override
        public void close() {
            finish();
        }
    }
}
