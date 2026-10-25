package dev.turboj.testing;

import dev.turboj.testing.intelligence.*;
import static dev.turboj.testing.intelligence.LineIntelligence.*;
import javax.swing.SwingUtilities;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Isolated Cherry CLI transport owned by TurboTestController; Cherry owns test orchestration. */
public final class CherryTestProcess implements AutoCloseable {
    public record Request(Path root, String executable, Path eventFile, List<String> selectors) {
        public Request {
            root = root.toAbsolutePath().normalize(); eventFile = eventFile.toAbsolutePath().normalize();
            selectors = List.copyOf(selectors);
        }
    }
    private volatile Process process;
    private volatile Set<ProcessHandle> descendants = Set.of();
    private volatile boolean closed, cancelled, busy;
    private volatile long generation;
    private volatile Run last;
    private Future<?> task;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("cherry-cli").factory());

    public boolean busy() { return busy; }
    public Run last() { return last; }
    public void run(Request request, Consumer<Run> results, Consumer<String> output) {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Run on EDT");
        if (closed || busy) return;
        busy = true; cancelled = false;
        long ticket = ++generation;
        last = new Run("",request.root(),RunState.PENDING,List.of(),Map.of(),Map.of(),"Starting Cherry Test…",null,0);
        results.accept(last);
        task = worker.submit(() -> execute(request,ticket,results,output));
    }

    private void execute(Request request,long ticket,Consumer<Run> results,Consumer<String> output) {
        CherryEvents events = new CherryEvents(request.root());
        Process owned = null;
        Set<ProcessHandle> children = ConcurrentHashMap.newKeySet();
        try {
            if (closed || ticket != generation) return;
            if (cancelled) {
                Run result = events.interrupted(true,"Cherry Test cancelled before launch");
                complete(request,ticket,result,results,output);
                return;
            }
            Files.createDirectories(request.eventFile().getParent());
            Files.createFile(request.eventFile());
            List<String> command = command(request);
            ProcessBuilder builder = new ProcessBuilder(command).directory(request.root().toFile()).redirectErrorStream(true);
            builder.environment().put("CHERRY_TEST_IDE_EVENTS",request.eventFile().toString());
            if (closed || cancelled || ticket != generation) throw new InterruptedException("Execution cancelled before launch");
            owned = builder.start(); descendants = children; process = owned;
            Process streamOwner = owned;
            OutputPump pump = new OutputPump(ticket,output);
            Thread drain = Thread.ofVirtual().name("cherry-output").start(() -> {
                try (Reader reader = new InputStreamReader(streamOwner.getInputStream(),StandardCharsets.UTF_8);
                        Writer log = Files.newBufferedWriter(request.eventFile().resolveSibling(request.eventFile().getFileName() + ".log"))) {
                    char[] chunk = new char[2048]; int count;
                    while ((count = reader.read(chunk)) >= 0) {
                        log.write(chunk,0,count);
                        String value = new String(chunk,0,count);
                        pump.append(value);
                    }
                } catch (IOException ignored) { }
            });
            try (RandomAccessFile input = new RandomAccessFile(request.eventFile().toFile(),"r")) {
                ByteArrayOutputStream pending = new ByteArrayOutputStream();
                while (owned.isAlive() && !cancelled && !closed && ticket == generation) {
                    owned.descendants().forEach(children::add);
                    readEvents(input,pending,events,ticket,results);
                    owned.waitFor(30,TimeUnit.MILLISECONDS);
                }
                terminate(owned,children);
                readEvents(input,pending,events,ticket,results);
                drain.join(Duration.ofSeconds(2));
                Run finalRun = cancelled || closed || ticket != generation ? events.interrupted(true,"Cherry Test cancelled; partial results retained")
                        : !events.terminal() ? events.interrupted(false,"Cherry CLI exited " + owned.exitValue()
                        + " without a complete schema-v1 result. Check the CLI version and execution log.")
                        : owned.exitValue() != 0 && events.snapshot().state() == RunState.COMPLETED
                        ? events.interrupted(false,"Cherry CLI exited " + owned.exitValue()
                        + " after reporting success; the execution is not a verified complete success.")
                        : TestSourceResolver.resolve(events.snapshot());
                complete(request,ticket,finalRun,results,output);
            }
        } catch (Exception error) {
            if (owned != null) terminate(owned,children);
            Run failure = events.interrupted(cancelled || closed || ticket != generation,
                    "Cherry Test: " + error.getClass().getSimpleName() + ": " + error.getMessage());
            complete(request,ticket,failure,results,output);
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
        } finally {
            if (owned != null) {
                terminate(owned,children);
                try { owned.getInputStream().close(); } catch (IOException ignored) { }
            }
            if (process == owned) { process = null; descendants = Set.of(); }
        }
    }

    private void complete(Request request,long ticket,Run value,Consumer<Run> results,Consumer<String> output) {
        try {
            if (Files.isRegularFile(request.eventFile())) CherryExecutionReceipt.write(request.eventFile(),value);
        } catch (IOException failure) {
            onEdt(ticket,() -> output.accept("Cherry history cannot certify this process outcome: " + failure.getMessage() + "\n"));
        }
        onEdt(ticket,() -> { busy = false; last = value; results.accept(value); });
    }

    /** At most one queued EDT drain and 64 KiB of display text; the complete stream is on disk. */
    private final class OutputPump {
        private final long ticket;
        private final Consumer<String> output;
        private final StringBuilder pending = new StringBuilder();
        private boolean scheduled, truncated;
        OutputPump(long ticket,Consumer<String> output) { this.ticket = ticket; this.output = output; }
        synchronized void append(String text) {
            int remaining = 65536 - pending.length();
            if (text.length() > remaining) truncated = true;
            pending.append(text,0,Math.min(text.length(),remaining));
            if (!scheduled) { scheduled = true; SwingUtilities.invokeLater(this::flush); }
        }
        private void flush() {
            String value;
            synchronized (this) {
                value = pending + (truncated ? "\n[Display output coalesced; complete CLI log is retained beside the event file.]\n" : "");
                pending.setLength(0); truncated = false; scheduled = false;
            }
            if (!closed && ticket == generation) output.accept(value);
        }
    }

    private void readEvents(RandomAccessFile input,ByteArrayOutputStream pending,CherryEvents events,
            long ticket,Consumer<Run> results) throws IOException {
        int value;
        boolean changed = false;
        while ((value = input.read()) >= 0) {
            if (value == '\n') {
                String line = pending.toString(StandardCharsets.UTF_8); pending.reset();
                if (!line.isBlank()) changed |= events.accept(line);
            } else {
                if (pending.size() >= CherryEvents.MAX_EVENT_BYTES) throw new IOException("Cherry event exceeds size limit");
                pending.write(value);
            }
        }
        if (changed) {
            Run measured = TestSourceResolver.resolve(events.snapshot());
            Run snapshot = events.terminal() ? new Run(measured.id(),measured.root(),RunState.RUNNING,
                    measured.tests(),measured.coverage(),measured.sourceHashes(),
                    "Cherry report received; waiting for CLI exit verification.",measured.htmlReport(),
                    measured.sequence(),measured.coverageAvailability()) : measured;
            onEdt(ticket,() -> { last = snapshot; results.accept(snapshot); });
        }
    }

    public static List<String> command(Request request) {
        String executable = request.executable();
        if (executable == null || executable.isBlank()) executable = System.getenv("CHERRY_TEST_JAR");
        if (executable == null || executable.isBlank()) executable = "cherry-test";
        List<String> command = new ArrayList<>();
        if (executable.endsWith(".jar")) {
            command.add(Path.of(System.getProperty("java.home"),"bin","java").toString());
            command.add("--enable-native-access=ALL-UNNAMED"); command.add("-jar");
        }
        command.add(executable);
        command.addAll(List.of("--root",request.root().toString(),"--execution","local","--no-open","--no-banner"));
        command.addAll(request.selectors());
        return List.copyOf(command);
    }

    public void cancel() {
        cancelled = true;
        Process owned = process;
        Set<ProcessHandle> children = descendants;
        if (owned != null) Thread.ofVirtual().name("cherry-cancel").start(() -> terminate(owned,children));
    }
    /** A project switch invalidates every queued completion, even if the old process exits later. */
    public void detach() { cancel(); generation++; busy = false; last = null; }
    private void onEdt(long ticket,Runnable action) { SwingUtilities.invokeLater(() -> { if (!closed && ticket == generation) action.run(); }); }
    private static void terminate(Process owned,Set<ProcessHandle> remembered) {
        owned.descendants().forEach(remembered::add);
        List<ProcessHandle> children = List.copyOf(remembered);
        children.reversed().stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroy); owned.destroy();
        try { owned.waitFor(2,TimeUnit.SECONDS); }
        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        children.reversed().stream().filter(ProcessHandle::isAlive).forEach(ProcessHandle::destroyForcibly);
        if (owned.isAlive()) owned.destroyForcibly();
    }
    @Override public void close() { closed = true; cancel(); generation++; worker.shutdownNow(); }
}
