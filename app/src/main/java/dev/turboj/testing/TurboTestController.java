package dev.turboj.testing;

import com.google.gson.Gson;
import dev.turboj.compiler.*;
import dev.turboj.execution.*;
import dev.turboj.refactorj.api.Model.WorkspaceSnapshot;
import dev.turboj.turbotest.*;
import dev.turboj.mavenj.api.MavenModels.Workspace;
import dev.turboj.project.MavenCompilationWorkspace;
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
        /** Starts a fresh output section only once an execution request has been accepted. */
        default void begin(boolean compilationOnly) {}
        /** Hosts with a structured reporter can replace the plain status summary. */
        default void completed(String message, TestEngine.Report result) {
            status(message);
            if (result != null) report(result);
        }
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
    private MavenCompilationWorkspace mavenCompiler;
    private boolean ownsMavenCompiler;
    private final Map<Path,WorkerClient> projectWorkers = new ConcurrentHashMap<>();
    private Future<?> projectTask;
    private volatile Map<String,TestEngine.Report> moduleReports=Map.of();
    private volatile Map<String,String> testedFingerprints=Map.of();

    public TurboTestController(Host host) { this.host = Objects.requireNonNull(host); }
    /** The editor supplies its single coordinator; module caches are shared with Fast Compile. */
    public void setMavenCompiler(MavenCompilationWorkspace compiler) { edtRequired();if(mavenCompiler==compiler)return;if(busy)throw new IllegalStateException("Cannot replace compiler while tests run");if(ownsMavenCompiler&&mavenCompiler!=null)mavenCompiler.close();mavenCompiler=Objects.requireNonNull(compiler);ownsMavenCompiler=false; }
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
        host.begin(selection.mode == Mode.COMPILE);
        host.status(selection.mode == Mode.COMPILE ? "Compiling project…" : "Compiling tests…");
        executor.execute(() -> compileAndRun(snapshot, selection, failures, request));
    }

    /** Maven dependencies and JDKs are authoritative. Trust is required before executing tests. */
    public void runMaven(WorkspaceSnapshot snapshot, Workspace model, Map<Path,String> overlays,
            Selection selection, String moduleId, java.util.function.BooleanSupplier current,
            boolean trusted) {
        runMaven(snapshot,model,overlays,selection,moduleId,current,trusted,false);
    }
    /** Automatic execution selects only modules whose source/dependency fingerprint changed. */
    public void runMaven(WorkspaceSnapshot snapshot, Workspace model, Map<Path,String> overlays,
            Selection selection, String moduleId, java.util.function.BooleanSupplier current,
            boolean trusted,boolean automaticRun) {
        edtRequired();if(closed)return;
        if(!trusted&&selection.mode()!=Mode.COMPILE){host.status("Trust the project before executing its tests.");return;}
        if(busy){host.status("Tests are already running.");return;}
        List<String> failures=lastReport==null?List.of():lastReport.failedIds();
        if(selection.mode()==Mode.FAILED&&failures.isEmpty()){host.status("No failed tests to rerun.");return;}
        busy=true;long request=++sequence;Map<Path,String> buffers=Map.copyOf(overlays);
        Map<String,TestEngine.Report> previousReports=moduleReports;Map<String,String> previousFingerprints=testedFingerprints;
        host.begin(selection.mode()==Mode.COMPILE);
        host.status("Compiling Maven test sources with TurboJ Native…");
        projectTask=executor.submit(()->{
            try {
                if(mavenCompiler==null){mavenCompiler=new MavenCompilationWorkspace();ownsMavenCompiler=true;}
                var result=mavenCompiler.compile(model,buffers,snapshot.version(),true);
                if(!active(request))return;
                onEdt(()->{if(active(request)&&current.getAsBoolean())host.compilation(result.combined());});
                if(!result.success()){finishMaven(request,"Tests were not run because native compilation failed.",null,current);return;}
                if(selection.mode()==Mode.COMPILE){finishMaven(request,"Maven project compiled with TurboJ Native.",null,current);return;}
                var stillCurrent=new CompletableFuture<Boolean>();onEdt(()->stillCurrent.complete(active(request)&&current.getAsBoolean()));
                if(!stillCurrent.get()){finishMaven(request,"Source generation changed before test execution.",null,current);return;}
                if(junit==null)junit=new JUnitRuntime();
                var allTests=new ArrayList<TestEngine.Test>();var engineFailures=new ArrayList<String>();long started=System.nanoTime();
                var newReports=new HashMap<>(previousReports);var newFingerprints=new HashMap<>(previousFingerprints);int executedModules=0;
                var ordered=result.modules().stream().sorted(Comparator.comparingInt((MavenCompilationWorkspace.ModuleResult m)->previousReports.containsKey(m.moduleId())&&!previousReports.get(m.moduleId()).failedIds().isEmpty()?0:1).thenComparingLong(m->previousReports.containsKey(m.moduleId())?previousReports.get(m.moduleId()).durationNanos():Long.MAX_VALUE)).toList();
                for(var compiled:ordered) {
                    if(!active(request))return;
                    if(!compiled.tests()||compiled.compilation().bytecode().isEmpty())continue;
                    if(moduleId!=null&&!moduleId.isBlank()&&!compiled.moduleId().equals(moduleId))continue;
                    String fingerprint=mavenCompiler.sourceFingerprint(compiled.moduleId(),true);
                    if(automaticRun&&fingerprint.equals(previousFingerprints.get(compiled.moduleId())))continue;
                    List<String> moduleFailures=previousReports.containsKey(compiled.moduleId())?previousReports.get(compiled.moduleId()).failedIds():List.of();
                    if(selection.mode()==Mode.FAILED&&moduleFailures.isEmpty())continue;
                    var module=model.module(compiled.moduleId()).orElseThrow();
                    List<String> classes=compiled.compilation().bytecode().keySet().stream().filter(n->!n.equals("module-info")&&!n.endsWith("package-info")).sorted().toList();
                    if(selection.mode()==Mode.CLASS||selection.mode()==Mode.METHOD){if(!classes.contains(selection.className()))continue;classes=List.of(selection.className());}
                    List<String> methods=selection.mode()==Mode.METHOD?List.of(selection.className()+"#"+selection.methodName()):List.of();
                    Path home=module.testJava().javaHome()==null?Path.of(System.getProperty("java.home")):module.testJava().javaHome();
                    WorkerClient projectWorker=projectWorkers.computeIfAbsent(home,p->new WorkerClient(Duration.ofMinutes(5),4*1024*1024,512,p));
                    var testRequest=new TestEngine.Request(snapshot.version(),classes,selection.mode()==Mode.FAILED?moduleFailures:List.of(),methods);
                    Path directory=Files.createTempDirectory("turboj-maven-tests-");
                    try(var pinned=mavenCompiler.pinExecution(module.id(),true,model.generation(),snapshot.version())) {
                        List<Path> runtime=junit.projectExecutionClasspath(pinned.classpath());
                        if(!pinned.modulePath().isEmpty())throw new IllegalStateException("Named-module JUnit patch-module execution requires an explicit Maven Test build");
                        Path report=directory.resolve("report.json");String encoded=Base64.getEncoder().encodeToString(new Gson().toJson(testRequest).getBytes(StandardCharsets.UTF_8));
                        var readyToExecute=new CompletableFuture<Boolean>();
                        onEdt(()->readyToExecute.complete(active(request)&&current.getAsBoolean()));
                        if(!readyToExecute.get()){finishMaven(request,"Source generation changed before test execution.",null,current);return;}
                        if(!active(request))return;
                        var execution=projectWorker.run(snapshot.version(),compiled.compilation().bytecode(),WorkerEntry.class.getName(),List.of(encoded,report.toString()),runtime,false,event->onEdt(()->{if(active(request)&&current.getAsBoolean())host.output(event);})).get();
                        if(execution.exitCode()!=0||!Files.isRegularFile(report))throw new IllegalStateException("Test worker failed: "+execution.detail());
                        if(Files.size(report)>16*1024*1024)throw new IllegalStateException("Test report exceeds size limit");
                        var value=new Gson().fromJson(Files.readString(report),TestEngine.Report.class);
                        if(value.sourceVersion()!=snapshot.version())throw new IllegalStateException("Stale test report generation");
                        allTests.addAll(value.tests());engineFailures.addAll(value.engineFailures());
                        newReports.put(module.id(),value);if(selection.mode()==Mode.ALL)newFingerprints.put(module.id(),fingerprint);executedModules++;
                    }finally{cleanup(directory);}
                }
                boolean removedReports=false;
                if(automaticRun&&selection.mode()==Mode.ALL) {
                    Set<String> currentTestModules=result.modules().stream().filter(module->module.tests()&&!module.compilation().bytecode().isEmpty()).map(MavenCompilationWorkspace.ModuleResult::moduleId).collect(java.util.stream.Collectors.toSet());
                    removedReports=newReports.keySet().removeIf(id->!currentTestModules.contains(id));
                    newFingerprints.keySet().retainAll(currentTestModules);
                }
                if(automaticRun&&executedModules==0&&!removedReports){finishMaven(request,"No affected Maven test modules.",null,current);return;}
                if(automaticRun&&selection.mode()==Mode.ALL) {
                    // Skipping an unchanged module does not erase its last valid test result.
                    // In particular, an unrelated edit must not hide an outstanding failure or
                    // disable Run Failed. Reuse only results for identical current inputs.
                    allTests.clear();engineFailures.clear();
                    for(var module:model.modules())if(Objects.equals(newFingerprints.get(module.id()),mavenCompiler.sourceFingerprint(module.id(),true))&&newReports.containsKey(module.id())){
                        var value=newReports.get(module.id());allTests.addAll(value.tests());engineFailures.addAll(value.engineFailures());
                    }
                }
                onEdt(()->{if(active(request)&&current.getAsBoolean()){moduleReports=Map.copyOf(newReports);testedFingerprints=Map.copyOf(newFingerprints);}});
                allTests.sort(Comparator.comparingInt((TestEngine.Test test)->test.status()==TestEngine.Status.FAILED?0:1).thenComparingLong(TestEngine.Test::durationNanos).thenComparing(TestEngine.Test::id));
                var report=new TestEngine.Report(snapshot.version(),System.nanoTime()-started,allTests,engineFailures);
                finishMaven(request,report.tests().size()+" tests: "+report.passed()+" passed, "+report.failed()+" failed"+(engineFailures.isEmpty()?".":"; test setup failed."),report,current);
            }catch(Throwable error){finishMaven(request,"TurboTest: "+error.getMessage(),null,current);}
        });
    }
    private void finishMaven(long request,String message,TestEngine.Report report,java.util.function.BooleanSupplier current){
        onEdt(()->{if(!active(request))return;busy=false;if(!current.getAsBoolean()){host.status("Test results belong to an older project/source generation.");return;}if(report!=null)lastReport=report;host.completed(message,report);});
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
            // Publishing diagnostics can yield to edits on the EDT. Validate after runtime
            // preparation, immediately before dispatching project-controlled test code.
            var stillCurrent = new CompletableFuture<Boolean>();
            onEdt(() -> {
                boolean current = active(request) && host.current(snapshot);
                if (current) host.status("Running JUnit tests…");
                stillCurrent.complete(current);
            });
            if (!stillCurrent.get()) {
                finish(request, "Source generation changed before test execution.", null, snapshot);
                return;
            }
            if (!active(request)) return;
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
                if (report != null) lastReport = report;
                host.completed(message, report);
            } else host.status("Test results belong to an older source version; run again for current results.");
            WorkspaceSnapshot pending = pendingAutomatic; pendingAutomatic = null;
            if (automatic && pending != null && host.current(pending)) run(pending, Selection.all());
        });
    }
    public void stop() { edtRequired(); sequence++; pendingAutomatic = null; worker.stop();projectWorkers.values().forEach(WorkerClient::stop);if(projectTask!=null)projectTask.cancel(true); busy = false; host.status("Tests stopped."); }
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
        projectWorkers.values().forEach(WorkerClient::close);if(projectTask!=null)projectTask.cancel(true);
        executor.execute(() -> { if (compiler != null) compiler.close(); if(ownsMavenCompiler&&mavenCompiler!=null)mavenCompiler.close(); if (junit != null) junit.close(); });
        executor.shutdown();
    }
}
