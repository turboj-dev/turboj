package dev.turboj.testing;

import com.google.gson.Gson;
import dev.turboj.compiler.*;
import dev.turboj.execution.*;
import dev.turboj.refactorj.api.Model.WorkspaceSnapshot;
import dev.turboj.turbotest.*;
import javax.swing.SwingUtilities;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Compiles immutable workspace sources in-process, then runs JUnit in a reusable isolated worker. */
public final class TurboTestController implements AutoCloseable {
    public enum Mode { ALL, CLASS, METHOD, FAILED, COMPILE }
    public record Selection(Mode mode, String className, String methodName) {
        public static Selection all() { return new Selection(Mode.ALL, "", ""); }
    }
    public interface Host {
        boolean current(WorkspaceSnapshot snapshot);
        void status(String message);
        void compilation(WorkspaceCompilationResult result);
        void report(TestEngine.Report result);
        void output(OutputEvent event);
    }
    private final Host host;
    private final ExecutorService executor = Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().name("turboj-tests").factory());
    private final Executor completions = task -> {
        try { executor.execute(task); }
        catch (RejectedExecutionException closing) {
            // Worker termination can complete after close() shuts down the compiler lane.
            Thread.ofPlatform().daemon().name("turboj-test-cleanup").start(task);
        }
    };
    private final WorkerClient worker = new WorkerClient(Duration.ofMinutes(5), 4 * 1024 * 1024, 512);
    private JUnitRuntime junit;
    private JavacCompilerBackend compiler;
    private List<Path> compilerClasspath = List.of();
    private volatile long sequence;
    private volatile boolean closed;
    private boolean busy, automatic;
    private WorkspaceSnapshot pendingAutomatic;
    private TestEngine.Report lastReport;

    public TurboTestController(Host host) { this.host = Objects.requireNonNull(host); }
    public boolean busy() { return busy; }
    public boolean automatic() { return automatic; }
    public TestEngine.Report lastReport() { return lastReport; }
    public void setAutomatic(boolean enabled) { edtRequired(); automatic = enabled; if (!enabled) pendingAutomatic = null; }

    /** Called only after a whole refactoring has been published, never for preparation or preview. */
    public void afterRefactoring(WorkspaceSnapshot snapshot) {
        edtRequired();
        if (!automatic) return;
        if (busy) pendingAutomatic = snapshot;
        else run(snapshot, Selection.all());
    }

    public void run(WorkspaceSnapshot snapshot, Selection selection) {
        edtRequired();
        if (closed) return;
        if (busy) { host.status("Tests are already running. Stop them before starting another run."); return; }
        List<String> failures = lastReport == null ? List.of() : lastReport.failedIds();
        if (selection.mode == Mode.FAILED && failures.isEmpty()) { host.status("No failed tests to rerun."); return; }
        busy = true;
        long request = ++sequence;
        host.status(selection.mode == Mode.COMPILE ? "Compiling project…" : "Compiling tests…");
        executor.execute(() -> compileAndRun(snapshot, selection, failures, request));
    }

    private void compileAndRun(WorkspaceSnapshot snapshot, Selection selection, List<String> failures, long request) {
        Path reportDirectory = null;
        try {
            if (junit == null) junit = new JUnitRuntime();
            var classpath = new LinkedHashSet<Path>(snapshot.classpath());
            classpath.addAll(junit.compilationClasspath());
            List<Path> compilationPaths = List.copyOf(classpath);
            if (compiler == null || !compilerClasspath.equals(compilationPaths)) {
                if (compiler != null) compiler.close();
                compiler = new JavacCompilerBackend(25, compilationPaths);
                compilerClasspath = compilationPaths;
            }
            var sources = snapshot.sources().stream().map(source -> {
                String name = source.file().toString();
                if (source.file().getFileName().toString().startsWith(".turboj-untitled-")) {
                    String inferred = SourceSnapshot.forBuffer(source.text(), source.version()).fileName();
                    name = source.file().getParent().resolve(".turboj-unsaved")
                            .resolve(source.file().getFileName().toString()).resolve(inferred).toString();
                }
                return new SourceSnapshot(name, source.text(), source.version());
            }).toList();
            WorkspaceCompilationResult compiled = compiler.compileSources(sources, snapshot.version());
            if (!active(request)) return;
            onEdt(() -> { if (active(request) && host.current(snapshot)) host.compilation(compiled); });
            if (!compiled.success()) { finish(request, "Tests were not run because compilation failed.", null, snapshot); return; }
            if (selection.mode == Mode.COMPILE) { finish(request, "Project compiled.", null, snapshot); return; }
            List<String> classes = selection.mode == Mode.CLASS ? List.of(selection.className)
                    : compiled.bytecode().keySet().stream().filter(name -> !name.equals("module-info") && !name.endsWith("package-info")).sorted().toList();
            List<String> methods = selection.mode == Mode.METHOD ? List.of(selection.className + "#" + selection.methodName) : List.of();
            var testRequest = new TestEngine.Request(snapshot.version(), classes, selection.mode == Mode.FAILED ? failures : List.of(), methods);
            var runtimePaths = new LinkedHashSet<Path>(snapshot.classpath()); runtimePaths.addAll(junit.executionClasspath());
            reportDirectory = Files.createTempDirectory("turboj-test-report-");
            Path report = reportDirectory.resolve("result.json");
            String encoded = Base64.getEncoder().encodeToString(new Gson().toJson(testRequest).getBytes(StandardCharsets.UTF_8));
            Path cleanup = reportDirectory;
            onEdt(() -> { if (active(request)) host.status("Running JUnit tests…"); });
            worker.run(snapshot.version(), compiled.bytecode(), WorkerEntry.class.getName(), List.of(encoded, report.toString()),
                    List.copyOf(runtimePaths), false, event -> onEdt(() -> { if (active(request)) host.output(event); }))
                    .whenCompleteAsync((result, error) -> {
                        try {
                            if (!active(request)) return;
                            if (error != null) throw new IllegalStateException("Test worker failed", error);
                            if (result.exitCode() != 0 || !Files.isRegularFile(report, LinkOption.NOFOLLOW_LINKS))
                                throw new IllegalStateException("Tests did not complete: " + result.detail());
                            if (Files.size(report) > 16 * 1024 * 1024) throw new IllegalStateException("Test report exceeds the supported size");
                            TestEngine.Report reportValue = new Gson().fromJson(Files.readString(report), TestEngine.Report.class);
                            if (reportValue.sourceVersion() != snapshot.version()) throw new IllegalStateException("Test report source version does not match");
                            finish(request, reportValue.tests().size() + " tests: " + reportValue.passed() + " passed, " + reportValue.failed() + " failed"
                                    + (reportValue.engineFailures().isEmpty() ? "." : "; test setup failed."), reportValue, snapshot);
                        } catch (Throwable failure) { finish(request, failure.getMessage(), null, snapshot); }
                        finally { cleanup(cleanup); }
                    }, completions);
            reportDirectory = null; // Completion callback owns the private report directory now.
        } catch (Throwable error) { finish(request, "TurboTest: " + error.getMessage(), null, snapshot); }
        finally { if (reportDirectory != null) cleanup(reportDirectory); }
    }

    private void finish(long request, String message, TestEngine.Report report, WorkspaceSnapshot snapshot) {
        onEdt(() -> {
            if (!active(request)) return;
            busy = false;
            if (host.current(snapshot)) {
                host.status(message);
                if (report != null) { lastReport = report; host.report(report); }
            } else host.status("Test results belong to an older source version; run again for current results.");
            WorkspaceSnapshot pending = pendingAutomatic; pendingAutomatic = null;
            if (automatic && pending != null && host.current(pending)) run(pending, Selection.all());
        });
    }
    public void stop() { edtRequired(); sequence++; pendingAutomatic = null; worker.stop(); busy = false; host.status("Tests stopped."); }
    private boolean active(long request) { return !closed && sequence == request; }
    private static void onEdt(Runnable task) { SwingUtilities.invokeLater(task); }
    private static void edtRequired() { if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("TurboTest commands require the EDT"); }
    private static void cleanup(Path directory) {
        try (var paths = Files.walk(directory)) { for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path); }
        catch (Exception ignored) {}
    }
    @Override public void close() {
        if (closed) return;
        closed = true; sequence++; worker.close();
        executor.execute(() -> { if (compiler != null) compiler.close(); if (junit != null) junit.close(); });
        executor.shutdown();
    }
}
