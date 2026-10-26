package dev.turboj.testing;

import dev.turboj.compiler.CompilerDiagnostic;
import dev.turboj.compiler.WorkspaceCompilationResult;
import dev.turboj.turbotest.TestEngine;
import dev.turboj.ui.ConsolePane;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TestOutputTest {
    @Test
    void reportPreservesEveryOutcomeDurationAndCopyableFailureDetail() throws Exception {
        var fixture = fixture();
        var tests = List.of(
                test("passes", TestEngine.Status.PASSED, 1_230_000, "", ""),
                test("fails", TestEngine.Status.FAILED, 2_340_000, "expected <4> but was <5>", "at CalculatorTest.fails(CalculatorTest.java:12)"),
                test("ignored", TestEngine.Status.SKIPPED, 0, "Disabled on this JDK", ""),
                test("aborts", TestEngine.Status.ABORTED, 3_450_000, "Assumption failed", "at CalculatorTest.aborts(CalculatorTest.java:18)"));
        var report = new TestEngine.Report(42, 12_340_000, tests, List.of());
        edt(() -> { fixture.output.begin(false, "CalculatorTest"); fixture.output.report(report); return null; });
        String text = edt(fixture.console::text);
        assertTrue(text.contains("[INFO] Test CalculatorTest\n[INFO] --- compile ---\n"), text);
        assertTrue(text.contains("[INFO] Tests run: 4, Failures: 1, Errors: 0, Skipped: 2, Time elapsed: 7 ms -- in example.CalculatorTest"), text);
        assertTrue(text.contains("[ERROR] example.CalculatorTest#fails · expected <4> but was <5>\nat CalculatorTest.fails(CalculatorTest.java:12)\n"), text);
        assertTrue(text.contains("[WARN] example.CalculatorTest#ignored skipped · Disabled on this JDK"));
        assertTrue(text.contains("[WARN] example.CalculatorTest#aborts aborted · Assumption failed"));
        assertTrue(text.contains("[INFO] Results:\n[INFO]\n[ERROR] Failures:\n[ERROR]   example.CalculatorTest#fails · expected <4> but was <5>\n"), text);
        assertTrue(text.contains("[INFO] Tests run: 4, Failures: 1, Errors: 0, Skipped: 2\n"));
        assertTrue(text.contains("[ERROR] TEST FAILURE · 1 of 4 passed\n"), text);
        assertFalse(text.contains("#passes"), "passed tests are counted, not listed, as in Surefire");
        assertEquals(ConsolePane.Role.ERROR, TestOutput.summaryRole(report));
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
        var failure = new TestEngine.Report(1, 0, List.of(), List.of("JUnit engine initialization failed"));
        var fixture = fixture();
        edt(() -> { fixture.output.report(failure); return null; });
        String text = edt(fixture.console::text);
        assertTrue(text.contains("[ERROR] Errors:\n[ERROR]   JUnit engine initialization failed\n"), text);
        assertTrue(text.contains("[INFO] Tests run: 0, Failures: 0, Errors: 1, Skipped: 0"));
        assertTrue(text.contains("[ERROR] TEST FAILURE · no tests"), text);
        assertFalse(text.contains("SUCCESS"));
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
    void repeatedToolchainNoteIsRetainedOnceInDetailsAndRealDiagnosticsRemainVisible() throws Exception {
        var fixture = fixture();
        Path root = Path.of("/project");
        var result = new WorkspaceCompilationResult(4, false, List.of(), List.of(
                diagnostic("/project/pom.xml", "NOTE", "Resident javac 25, project JDK 21", -1),
                diagnostic("/project/module/pom.xml", "NOTE", "Resident javac 25, project JDK 21", -1),
                diagnostic("/project/src/Main.java", "ERROR", "Cannot resolve Calculator", 8),
                diagnostic("/project/src/Main.java", "WARNING", "Unchecked conversion", 10)), Map.of(), 0);
        edt(() -> { fixture.output.begin(false); fixture.output.compilation(result, root); return null; });
        String text = edt(fixture.console::text);
        assertFalse(text.contains("NOTE"));
        assertFalse(text.contains(":-1"));
        assertTrue(text.contains("[ERROR] src/Main.java:[8] Cannot resolve Calculator"), text);
        assertTrue(text.contains("[WARN] src/Main.java:[10] Unchecked conversion"), text);
        var details = edt(fixture.output::compilerDetails);
        assertEquals(3, details.size());
        assertEquals("NOTE pom.xml: Resident javac 25, project JDK 21", details.getFirst());
        assertThrows(UnsupportedOperationException.class, () -> details.add("mutated"));
        edt(() -> { fixture.output.begin(false); return null; });
        assertTrue(edt(fixture.output::compilerDetails).isEmpty());
    }

    private static TestEngine.Test test(String name, TestEngine.Status status, long nanos, String failure, String trace) {
        return new TestEngine.Test(name, name + "()", "example.CalculatorTest", name, status, nanos, failure, trace);
    }

    private static WorkspaceCompilationResult.SourceDiagnostic diagnostic(String file, String kind, String message, long line) {
        return new WorkspaceCompilationResult.SourceDiagnostic(file, new CompilerDiagnostic(kind, message, line, -1, -1, -1));
    }

    private static Fixture fixture() throws Exception {
        return edt(() -> {
            var console = new ConsolePane(ignored -> {});
            return new Fixture(console, new TestOutput(new dev.turboj.console.OutputLog(console)));
        });
    }

    private static <T> T edt(Callable<T> action) throws Exception {
        var task = new FutureTask<T>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }

    private record Fixture(ConsolePane console, TestOutput output) {}
}
