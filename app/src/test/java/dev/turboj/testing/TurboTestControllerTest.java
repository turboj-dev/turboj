package dev.turboj.testing;

import dev.turboj.compiler.WorkspaceCompilationResult;
import dev.turboj.execution.OutputEvent;
import dev.turboj.refactorj.api.Model.*;
import dev.turboj.turbotest.TestEngine;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.swing.SwingUtilities;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class TurboTestControllerTest {
    @TempDir Path directory;
    @Test void runsUnsavedJUnitInResidentWorkerAndRerunsFailures() throws Exception {
        TestHost host = new TestHost();
        try (var controller = new TurboTestController(host)) {
            WorkspaceSnapshot snapshot = workspace(1, "return n*n;", "org.junit.jupiter.api.Assertions.assertEquals(4, Calc.square(2));");
            edt(() -> controller.run(snapshot, TurboTestController.Selection.all()));
            TestEngine.Report passed = host.reports.poll(20, TimeUnit.SECONDS);
            assertNotNull(passed, () -> host.statuses + " " + host.compiled.get()); assertEquals(1, passed.passed());
            assertTrue(host.compiled.get().success());
            host.version = 2;
            WorkspaceSnapshot failing = workspace(2, "return n+1;", "org.junit.jupiter.api.Assertions.assertEquals(4, Calc.square(2));");
            edt(() -> controller.run(failing, TurboTestController.Selection.all()));
            TestEngine.Report failed = host.reports.poll(20, TimeUnit.SECONDS);
            assertNotNull(failed, () -> host.statuses + " " + host.compiled.get()); assertEquals(1, failed.failed());
            edt(() -> controller.run(failing, new TurboTestController.Selection(TurboTestController.Mode.FAILED, "", "")));
            TestEngine.Report rerun = host.reports.poll(20, TimeUnit.SECONDS);
            assertNotNull(rerun, () -> host.statuses + " " + host.compiled.get()); assertEquals(failed.failedIds(), rerun.failedIds());
        }
    }

    @Test void compilationErrorsNeverExecuteTests() throws Exception {
        TestHost host = new TestHost();
        try (var controller = new TurboTestController(host)) {
            edt(() -> controller.run(workspace(1, "return missing;", "throw new AssertionError(\"must not execute\");"), TurboTestController.Selection.all()));
            assertTrue(host.compilation.await(20, TimeUnit.SECONDS));
            assertFalse(host.compiled.get().success());
            assertTrue(host.reports.isEmpty());
        }
    }

    @Test void autoTestIsOptInAndStaleResultsDoNotReplaceCurrentDiagnostics() throws Exception {
        TestHost host = new TestHost();
        try (var controller = new TurboTestController(host)) {
            WorkspaceSnapshot snapshot = workspace(1, "return n*n;", "Thread.sleep(300);");
            edt(() -> controller.afterRefactoring(snapshot));
            assertTrue(host.statuses.isEmpty());
            edt(() -> { controller.setAutomatic(true); controller.afterRefactoring(snapshot); });
            assertTrue(host.compilation.await(20, TimeUnit.SECONDS));
            host.version = 2;
            assertTrue(host.stale.await(20, TimeUnit.SECONDS), () -> host.statuses + " " + host.compiled.get());
            assertTrue(host.reports.isEmpty());
        }
    }

    @Test void sourceChangedDuringCompilationPreventsExecutingObsoleteTestCode() throws Exception {
        TestHost host = new TestHost();
        host.invalidateAfterCompilation = true;
        Path marker = directory.resolve("obsolete-test-executed");
        String escaped = marker.toString().replace("\\", "\\\\").replace("\"", "\\\"");
        WorkspaceSnapshot snapshot = workspace(1, "return n*n;",
                "java.nio.file.Files.writeString(java.nio.file.Path.of(\"" + escaped + "\"), \"executed\");");
        try (var controller = new TurboTestController(host)) {
            edt(() -> controller.run(snapshot, TurboTestController.Selection.all()));
            assertTrue(host.compilation.await(20, TimeUnit.SECONDS));
            assertTrue(host.compiled.get().success());
            assertTrue(host.stale.await(20, TimeUnit.SECONDS), () -> host.statuses.toString());
            assertFalse(Files.exists(marker), "Obsolete tests must never execute, even if their report would be discarded");
            assertTrue(host.reports.isEmpty());
            edt(() -> assertFalse(controller.busy(), "The rejected generation must release the execution lane"));
        }
    }

    private WorkspaceSnapshot workspace(long version, String calculation, String test) {
        return new WorkspaceSnapshot(directory, version, List.of(
                new SourceSnapshot(directory.resolve("Calc.java"), "class Calc { static int square(int n) { " + calculation + " } }", version, true),
                new SourceSnapshot(directory.resolve("CalcTest.java"), "class CalcTest { @org.junit.jupiter.api.Test void square() throws Exception { " + test + " } }", version, true)), List.of(directory), List.of());
    }
    private static final class TestHost implements TurboTestController.Host {
        volatile long version = 1;
        boolean invalidateAfterCompilation;
        final BlockingQueue<TestEngine.Report> reports = new LinkedBlockingQueue<>();
        final List<String> statuses = new CopyOnWriteArrayList<>();
        final AtomicReference<WorkspaceCompilationResult> compiled = new AtomicReference<>();
        final CountDownLatch compilation = new CountDownLatch(1), stale = new CountDownLatch(1);
        public boolean current(WorkspaceSnapshot snapshot) { assertTrue(SwingUtilities.isEventDispatchThread()); return snapshot.version() == version; }
        public void status(String message) { assertTrue(SwingUtilities.isEventDispatchThread()); statuses.add(message); if (message.contains("older source version")) stale.countDown(); }
        public void compilation(WorkspaceCompilationResult result) { assertTrue(SwingUtilities.isEventDispatchThread()); compiled.set(result); if(invalidateAfterCompilation)version++; compilation.countDown(); }
        public void report(TestEngine.Report report) { assertTrue(SwingUtilities.isEventDispatchThread()); reports.add(report); }
        public void output(OutputEvent event) { assertTrue(SwingUtilities.isEventDispatchThread()); }
    }
    private static void edt(Runnable operation) throws Exception { SwingUtilities.invokeAndWait(operation); }
}
