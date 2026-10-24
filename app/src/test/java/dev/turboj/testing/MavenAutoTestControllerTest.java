package dev.turboj.testing;

import com.google.gson.Gson;
import dev.turboj.compiler.WorkspaceCompilationResult;
import dev.turboj.execution.OutputEvent;
import dev.turboj.mavenj.api.MavenModels.*;
import dev.turboj.mavenj.core.DefaultMavenJ;
import dev.turboj.project.MavenCompilationWorkspace;
import dev.turboj.refactorj.api.Model.WorkspaceSnapshot;
import dev.turboj.turbotest.TestEngine;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Uses actual Maven models, native compiler generations, EDT dispatch and isolated project JUnit. */
class MavenAutoTestControllerTest {
    @TempDir Path project;

    @Test void autoTestUsesCurrentGenerationSkipsUnchangedModulesAndRunsPreviousFailuresFirst() throws Exception {
        Path order = project.resolve("execution-order.txt"), release = project.resolve("release-stale-test");
        Files.writeString(project.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion><groupId>example.auto</groupId>
                <artifactId>reactor</artifactId><version>1</version><packaging>pom</packaging>
                <properties><maven.compiler.release>17</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>
                <dependencies><dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><version>5.13.4</version><scope>test</scope></dependency></dependencies>
                <modules><module>good</module><module>bad</module></modules></project>
                """);
        Map<String,Path> mains = new HashMap<>(), tests = new HashMap<>();
        for (String module : List.of("good", "bad")) {
            Path directory = Files.createDirectories(project.resolve(module));
            Files.writeString(directory.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion><parent><groupId>example.auto</groupId><artifactId>reactor</artifactId><version>1</version></parent><artifactId>"+module+"</artifactId></project>");
            Path main = Files.createDirectories(directory.resolve("src/main/java/"+module)).resolve("Value.java");
            Path test = Files.createDirectories(directory.resolve("src/test/java/"+module)).resolve("ValueTest.java");
            Files.writeString(main, value(module, module.equals("good") ? 1 : 0));
            Files.writeString(test, test(module, order, ""));
            mains.put(module, main); tests.put(module, test);
        }
        Path marker = project.resolve("maven-was-launched");
        Files.writeString(project.resolve("mvnw"), "#!/bin/sh\ntouch '"+marker+"'\nexit 90\n");
        project.resolve("mvnw").toFile().setExecutable(true);
        Path checkout = Path.of("").toAbsolutePath();
        while (!Files.isRegularFile(checkout.resolve("mvnw"))) checkout = Objects.requireNonNull(checkout.getParent());
        var request = new ImportRequest(project, null, null, checkout.resolve(".cache/m2"), Set.of(), Set.of(), Map.of(), true, Set.of(), false, Path.of(System.getProperty("java.home")), Map.of());
        TestHost host = new TestHost();
        try (var maven = new DefaultMavenJ(); var compiler = new MavenCompilationWorkspace(); var controller = new TurboTestController(host)) {
            Workspace model = maven.open(request);
            assertTrue(model.resolved(), () -> model.diagnostics()+" "+model.modules());
            edt(() -> { controller.setMavenCompiler(compiler); controller.setAutomatic(true); });
            Map<Path,String> buffers = new HashMap<>();

            run(controller, host, model, buffers, 1);
            TestEngine.Report initial = host.reports.remove();
            assertEquals(1, initial.sourceVersion()); assertEquals(1, initial.passed()); assertEquals(1, initial.failed());
            assertEquals(List.of("good", "bad"), Files.readAllLines(order));
            assertTrue(host.compiled.get().success());

            // An unaffected module is not executed, but its outstanding failure must remain
            // visible in the current report and keep Run Failed available.
            Files.writeString(order, "");
            buffers.put(mains.get("good"), value("good", 2));
            run(controller, host, model, buffers, 2);
            TestEngine.Report stillFailing = host.reports.remove();
            assertEquals(2, stillFailing.sourceVersion()); assertEquals(1, stillFailing.passed()); assertEquals(1, stillFailing.failed());
            assertEquals(initial.failedIds(), stillFailing.failedIds());
            assertEquals(List.of("good"), Files.readAllLines(order));

            // Both modules changed. The previously failing module executes first, even though it is
            // second in reactor order. These source buffers are never written to the project.
            Files.writeString(order, "");
            buffers.put(mains.get("good"), value("good", 3));
            buffers.put(mains.get("bad"), value("bad", 1));
            run(controller, host, model, buffers, 3);
            TestEngine.Report repaired = host.reports.remove();
            assertEquals(3, repaired.sourceVersion()); assertEquals(2, repaired.passed());
            assertEquals(List.of("bad", "good"), Files.readAllLines(order));

            Files.writeString(order, "");
            buffers.put(mains.get("good"), value("good", 4));
            run(controller, host, model, buffers, 4);
            TestEngine.Report affected = host.reports.remove();
            assertEquals(4, affected.sourceVersion()); assertEquals(2, affected.passed());
            assertEquals(List.of("good"), Files.readAllLines(order), "An independent unchanged module must not execute again");

            Files.writeString(order, "");
            run(controller, host, model, buffers, 5);
            assertTrue(host.reports.isEmpty()); assertEquals(List.of(), Files.readAllLines(order));
            edt(() -> assertSame(affected, controller.lastReport(), "An unchanged automatic request must preserve the previous valid results"));
            assertTrue(host.statuses.contains("No affected Maven test modules."));

            // A superseded compilation result must not start a worker.
            buffers.put(mains.get("good"), value("good", 5));
            host.generation.set(7);
            start(controller, host, model, buffers, 6);
            awaitIdle(controller, host);
            assertTrue(host.reports.isEmpty()); assertEquals(List.of(), Files.readAllLines(order));

            // Change generation while an actual JUnit test is executing. A file gate makes this
            // deterministic rather than relying on a test sleep racing EDT completion.
            String gate = "System.out.println(\"STALE_GENERATION_STARTED\"); long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(10); while(!java.nio.file.Files.exists(java.nio.file.Path.of("+literal(release)+"))){ if(System.nanoTime()>until)throw new AssertionError(\"release gate timed out\"); Thread.sleep(5); }";
            buffers.put(tests.get("good"), test("good", order, gate));
            buffers.put(tests.get("bad"), test("bad", order, gate));
            host.onOutput.set(() -> { host.generation.set(8); try { Files.writeString(release, "release"); } catch (Exception error) { throw new AssertionError(error); } });
            run(controller, host, model, buffers, 7);
            assertTrue(Files.exists(release), "The current worker executed before its generation became stale");
            assertEquals(1, Files.readAllLines(order).size(), "The second changed module must not execute after the first module's generation becomes stale");
            assertTrue(host.reports.isEmpty());
            assertTrue(host.statuses.contains("Test results belong to an older project/source generation."));
            edt(() -> assertSame(affected, controller.lastReport()));

            // Discarded reports must not mark a fingerprint as tested: the current generation reruns.
            Files.writeString(order, "");
            run(controller, host, model, buffers, 8);
            TestEngine.Report current = host.reports.remove();
            assertEquals(8, current.sourceVersion()); assertEquals(2, current.passed());
            assertEquals(Set.of("good", "bad"), Set.copyOf(Files.readAllLines(order)));
            assertEquals(2, Files.readAllLines(order).size(), "Both invalidated modules must execute once in the current generation");

            // Removing a failed module must remove its result even if no remaining module needs
            // execution. The same rule applies when the last test source disappears.
            buffers.put(mains.get("bad"), value("bad", 0));
            run(controller, host, model, buffers, 9);
            assertEquals(1, host.reports.remove().failed());
            Files.writeString(project.resolve("pom.xml"),Files.readString(project.resolve("pom.xml")).replace("<module>bad</module>",""));
            Workspace reduced=maven.open(request);
            assertTrue(reduced.resolved());assertEquals(2,reduced.modules().size());
            Files.writeString(order, "");
            run(controller, host, reduced, buffers, 10);
            TestEngine.Report removedModule=host.reports.remove();
            assertEquals(10,removedModule.sourceVersion());assertEquals(1,removedModule.passed());assertEquals(0,removedModule.failed());
            assertEquals(List.of("good.ValueTest"),removedModule.tests().stream().map(TestEngine.Test::className).toList());
            assertEquals(List.of(),Files.readAllLines(order),"Removing a module must not rerun unchanged tests");
            buffers.remove(tests.get("good"));Files.delete(tests.get("good"));
            run(controller, host, reduced, buffers, 11);
            TestEngine.Report removedTests=host.reports.remove();
            assertEquals(11,removedTests.sourceVersion());assertTrue(removedTests.tests().isEmpty());
            assertEquals(List.of(),Files.readAllLines(order));
            assertFalse(Files.exists(marker));
            assertFalse(Files.exists(project.resolve("good/target")));
            assertFalse(Files.exists(project.resolve("bad/target")));
            assertEquals(value("bad", 0), Files.readString(mains.get("bad")), "Automatic testing must preserve unsaved source buffers");
        }
    }

    private String value(String module, int value) { return "package "+module+"; public class Value { public static int current(){return "+value+";} }"; }
    private String test(String module, Path order, String gate) {
        return "package "+module+"; public class ValueTest { @org.junit.jupiter.api.Test void current() throws Exception { java.nio.file.Files.writeString(java.nio.file.Path.of("+literal(order)+"),\""+module+"\\n\",java.nio.file.StandardOpenOption.CREATE,java.nio.file.StandardOpenOption.APPEND); "+gate+" org.junit.jupiter.api.Assertions.assertTrue(Value.current()>0); } }";
    }
    private static String literal(Path path) { return new Gson().toJson(path.toString()); }
    private void run(TurboTestController controller, TestHost host, Workspace model, Map<Path,String> buffers, long generation) throws Exception {
        host.generation.set(generation); start(controller, host, model, buffers, generation); awaitIdle(controller, host);
    }
    private void start(TurboTestController controller, TestHost host, Workspace model, Map<Path,String> buffers, long generation) throws Exception {
        WorkspaceSnapshot snapshot = new WorkspaceSnapshot(project, generation, List.of(), List.of(), List.of());
        edt(() -> controller.runMaven(snapshot, model, buffers, TurboTestController.Selection.all(), "", () -> host.generation.get() == generation, true, true));
    }
    private static void awaitIdle(TurboTestController controller, TestHost host) throws Exception {
        long deadline = System.nanoTime()+Duration.ofSeconds(30).toNanos();
        AtomicBoolean busy = new AtomicBoolean(true);
        while (busy.get() && System.nanoTime() < deadline) { edt(() -> busy.set(controller.busy())); if (busy.get()) Thread.sleep(10); }
        assertFalse(busy.get(), () -> "Auto Test did not complete: "+host.statuses);
    }
    private static void edt(Runnable task) throws Exception { SwingUtilities.invokeAndWait(task); }
    private static final class TestHost implements TurboTestController.Host {
        final AtomicLong generation = new AtomicLong(1);
        final BlockingQueue<TestEngine.Report> reports = new LinkedBlockingQueue<>();
        final List<String> statuses = new CopyOnWriteArrayList<>();
        final AtomicReference<WorkspaceCompilationResult> compiled = new AtomicReference<>();
        final AtomicReference<Runnable> onOutput = new AtomicReference<>();
        public boolean current(WorkspaceSnapshot snapshot) { assertTrue(SwingUtilities.isEventDispatchThread()); return snapshot.version()==generation.get(); }
        public void status(String message) { assertTrue(SwingUtilities.isEventDispatchThread()); statuses.add(message); }
        public void compilation(WorkspaceCompilationResult result) { assertTrue(SwingUtilities.isEventDispatchThread()); compiled.set(result); }
        public void report(TestEngine.Report report) { assertTrue(SwingUtilities.isEventDispatchThread()); reports.add(report); }
        public void output(OutputEvent event) {
            assertTrue(SwingUtilities.isEventDispatchThread());
            if(event.text().contains("STALE_GENERATION_STARTED")) { Runnable callback=onOutput.getAndSet(null); if(callback!=null)callback.run(); }
        }
    }
}
