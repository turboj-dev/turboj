package dev.turboj.testing;

import dev.turboj.compiler.CompilerDiagnostic;
import dev.turboj.compiler.WorkspaceCompilationResult;
import dev.turboj.console.OutputLog;
import dev.turboj.turbotest.TestEngine;
import dev.turboj.ui.ConsolePane;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.FutureTask;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** The Tests tab: suites and tests; detail only for what failed. */
class TestOutputTest {
    private static final String TRACE = """
            org.opentest4j.AssertionFailedError: expected: <1> but was: <0>
                at org.junit.jupiter.api.AssertionFailureBuilder.build(AssertionFailureBuilder.java:151)
                at org.junit.jupiter.api.Assertions.assertEquals(Assertions.java:531)
                at example.CalculatorTest.fails(CalculatorTest.java:12)
                at java.base/java.lang.reflect.Method.invoke(Method.java:565)
                at java.base/java.util.ArrayList.forEach(ArrayList.java:1604)
            """;

    @Test
    void suitesListTheirTestsAndOnlyFailuresCarryDetail() throws Exception {
        var fixture = fixture();
        var tests = List.of(
                test("passes", TestEngine.Status.PASSED, 1_230_000, "", ""),
                test("fails", TestEngine.Status.FAILED, 2_340_000, "expected: <1> but was: <0>", TRACE),
                test("ignored", TestEngine.Status.SKIPPED, 0, "Disabled on this JDK", ""),
                test("aborts", TestEngine.Status.ABORTED, 3_450_000, "Assumption failed", ""));
        var report = new TestEngine.Report(42, 12_340_000, tests, List.of());
        edt(() -> {
            fixture.output.begin(false, "CalculatorTest");
            fixture.output.program("debug: computing\n", false);
            fixture.output.report(report);
            return null;
        });
        String text = edt(fixture.pane::text);
        assertEquals("""
                Test CalculatorTest — 1 failed · 1 of 4 passed · 12 ms
                example.CalculatorTest  4 tests · 1 failed · 7 ms
                  ✔ passes  1 ms
                  ✖ fails  2 ms
                      expected: <1> but was: <0>
                      at example.CalculatorTest.fails(CalculatorTest.java:12)
                  ○ ignored  <1 ms
                      Disabled on this JDK
                  ! aborts  3 ms
                      Assumption failed
                Output
                debug: computing
                """, text, "no [INFO], no Surefire summary, no JUnit or JDK frames");
        assertEquals(List.of("fails", "aborts"), edt(() -> fixture.pane.failures().stream().map(TestEngine.Test::methodName).toList()));
        assertEquals(ConsolePane.Role.ERROR, TestOutput.summaryRole(report));
    }

    @Test
    void aPassingRunHidesWhatTheTestsPrinted() throws Exception {
        var fixture = fixture();
        var report = new TestEngine.Report(1, 4_000_000, List.of(test("passes", TestEngine.Status.PASSED, 1_000_000, "", "")), List.of());
        edt(() -> {
            fixture.output.begin(false, "all tests");
            fixture.output.program("noise\n", false);
            fixture.output.report(report);
            return null;
        });
        assertEquals("Test all tests — 1 of 1 passed · 4 ms\nexample.CalculatorTest  1 test · 1 ms\n  ✔ passes  1 ms\n", edt(fixture.pane::text));
    }

    @Test
    void nextAndPreviousFailureWalkTheFailuresAndWrap() throws Exception {
        var fixture = fixture();
        var report = new TestEngine.Report(1, 0, List.of(
                test("a", TestEngine.Status.FAILED, 0, "boom", ""),
                test("b", TestEngine.Status.PASSED, 0, "", ""),
                test("c", TestEngine.Status.FAILED, 0, "bang", "")), List.of());
        edt(() -> { fixture.output.begin(false, "all"); fixture.output.report(report); return null; });
        assertEquals("a", edt(() -> fixture.pane.nextFailure(1).methodName()));
        assertEquals("c", edt(() -> fixture.pane.nextFailure(1).methodName()));
        assertEquals("a", edt(() -> fixture.pane.nextFailure(1).methodName()), "after the last, the first");
        assertEquals("c", edt(() -> fixture.pane.nextFailure(-1).methodName()), "before the first, the last");
        assertEquals("c", edt(() -> fixture.pane.selectedTest().methodName()));
        var passing = new TestEngine.Report(2, 0, List.of(test("b", TestEngine.Status.PASSED, 0, "", "")), List.of());
        edt(() -> { fixture.output.begin(false, "all"); fixture.output.report(passing); return null; });
        assertNull(edt(() -> fixture.pane.nextFailure(1)));
    }

    @Test
    void zeroTestsAbortsAndEngineFailuresCannotProduceSuccessfulSummary() throws Exception {
        var empty = new TestEngine.Report(1, 0, List.of(), List.of());
        assertTrue(TestOutput.summary(empty).startsWith("NO TESTS"));
        assertEquals(ConsolePane.Role.WARNING, TestOutput.summaryRole(empty));
        var aborted = new TestEngine.Report(1, 0,
                List.of(test("aborts", TestEngine.Status.ABORTED, 0, "reason", "")), List.of());
        assertTrue(TestOutput.summary(aborted).startsWith("ABORTED"));
        assertEquals(ConsolePane.Role.WARNING, TestOutput.summaryRole(aborted));
        var skipped = new TestEngine.Report(1, 0,
                List.of(test("ignored", TestEngine.Status.SKIPPED, 0, "reason", "")), List.of());
        assertTrue(TestOutput.summary(skipped).startsWith("COMPLETED"));
        assertEquals(ConsolePane.Role.SKIPPED, TestOutput.summaryRole(skipped));
        var failure = new TestEngine.Report(1, 0, List.of(), List.of("JUnit engine initialization failed\n\tat somewhere"));
        var fixture = fixture();
        edt(() -> { fixture.output.report(failure); return null; });
        String text = edt(fixture.pane::text);
        assertTrue(text.contains("Errors\n  JUnit engine initialization failed\n"), text);
        assertFalse(text.contains("somewhere"));
        assertTrue(text.startsWith("Test — 1 failed · 0 of 0 passed"), text);
    }

    @Test
    void parameterizedDisplayNamesStayDistinct() {
        var first = new TestEngine.Test("first", "[1] -2, 3", "CalculatorTest", "adds",
                TestEngine.Status.PASSED, 1, "", "");
        var second = new TestEngine.Test("second", "[2] 0, 3", "CalculatorTest", "adds",
                TestEngine.Status.PASSED, 1, "", "");
        assertEquals("CalculatorTest#adds [[1] -2, 3]", TestOutput.name(first));
        assertNotEquals(TestOutput.name(first), TestOutput.name(second));
    }

    @Test
    void compileErrorsAreTheRunsProblemsAndCompileOnlyReportsInTheStatusLine() throws Exception {
        var fixture = fixture();
        Path root = Path.of("/project");
        var result = new WorkspaceCompilationResult(4, false, List.of(), List.of(
                diagnostic("/project/pom.xml", "NOTE", "Resident javac 25, project JDK 21", -1),
                diagnostic("/project/module/pom.xml", "NOTE", "Resident javac 25, project JDK 21", -1),
                diagnostic("/project/src/Main.java", "ERROR", "Cannot resolve Calculator", 8),
                diagnostic("/project/src/Main.java", "WARNING", "Unchecked conversion", 10)), Map.of(), 0);
        edt(() -> {
            fixture.output.begin(false, "all tests");
            fixture.output.compilation(result, root);
            fixture.output.completed("Test compilation failed.");
            return null;
        });
        String text = edt(fixture.pane::text);
        assertEquals("Test all tests — failed\n  src/Main.java:[8] Cannot resolve Calculator\n  Test compilation failed.\n", text);
        var details = edt(fixture.output::compilerDetails);
        assertEquals(3, details.size());
        assertEquals("NOTE pom.xml: Resident javac 25, project JDK 21", details.getFirst());
        assertThrows(UnsupportedOperationException.class, () -> details.add("mutated"));
        assertEquals(OutputLog.Level.ERROR, fixture.notices.getLast().level());
        assertTrue(fixture.notices.getLast().text().startsWith("Test all tests failed · 1 error · "), fixture.notices.getLast().text());

        var compileOnly = fixture();
        edt(() -> {
            compileOnly.output.begin(true, "hello-world");
            compileOnly.output.compilation(new WorkspaceCompilationResult(5, true, List.of(), List.of(), Map.of(), 0), root);
            compileOnly.output.completed("Project compiled.");
            return null;
        });
        assertTrue(edt(compileOnly.pane::text).startsWith("No test run yet"), "compile-only leaves the Tests tab alone");
        assertEquals(new OutputLog.Notice(OutputLog.Level.INFO, "Compile Project hello-world done · Project compiled."),
                compileOnly.notices.getLast());
    }

    private static TestEngine.Test test(String name, TestEngine.Status status, long nanos, String failure, String trace) {
        return new TestEngine.Test(name, name + "()", "example.CalculatorTest", name, status, nanos, failure, trace);
    }

    private static WorkspaceCompilationResult.SourceDiagnostic diagnostic(String file, String kind, String message, long line) {
        return new WorkspaceCompilationResult.SourceDiagnostic(file, new CompilerDiagnostic(kind, message, line, -1, -1, -1));
    }

    private static Fixture fixture() throws Exception {
        return edt(() -> {
            var pane = new TestResultsPane();
            var notices = new CopyOnWriteArrayList<OutputLog.Notice>();
            return new Fixture(pane, new TestOutput(pane, notices::add), notices);
        });
    }

    private static <T> T edt(Callable<T> action) throws Exception {
        var task = new FutureTask<T>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }

    private record Fixture(TestResultsPane pane, TestOutput output, List<OutputLog.Notice> notices) {}
}
