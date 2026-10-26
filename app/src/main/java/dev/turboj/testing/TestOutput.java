package dev.turboj.testing;

import dev.turboj.compiler.WorkspaceCompilationResult;
import dev.turboj.console.OutputLog;
import dev.turboj.turbotest.TestEngine;
import dev.turboj.ui.ConsolePane.Role;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/**
 * A test run in the output, written the way Maven Surefire reports one (docs/output-and-status.md): a compile phase,
 * the tests' own output verbatim, one count line per class with its failures, then Results and the summary.
 */
public final class TestOutput {
    private final OutputLog log;
    private final LinkedHashMap<String, String> compilerDetails = new LinkedHashMap<>();
    private OutputLog.Action action;
    private boolean compiled = true;

    public TestOutput(OutputLog log) { this.log = log; }

    /** Opens the action: {@code [INFO] Test CalculatorTest · hello-world · Java 25}, then its compile phase. */
    public void begin(boolean compilationOnly, String subject) {
        compilerDetails.clear();
        compiled = true;
        action = log.start(compilationOnly ? "Compile Project" : "Test", subject);
        action.phase("compile");
    }

    public void begin(boolean compilationOnly) { begin(compilationOnly, ""); }

    /** The test run is still writing to the output. */
    public boolean open() { return action != null && action.open(); }

    /** Output the tests print themselves, under the test phase. */
    public void program(String text, boolean stderr) { if (open()) action.program(text, stderr); }

    /**
     * A progress message from the test controller. Phase changes become {@code --- test ---}; a message outside a run is
     * not log material, so the caller shows it in the status line instead.
     */
    public boolean status(String text) {
        if (!open()) return false;
        if (text.startsWith("Compiling")) return true; // the compile phase is already open
        if (text.startsWith("Running JUnit")) { action.phase("test"); return true; }
        if (text.equals("Tests stopped.")) { action.finish(OutputLog.Outcome.STOPPED, null, null); return true; }
        action.info(text);
        return true;
    }

    public void compilation(WorkspaceCompilationResult result, Path root) {
        for (var source : result.diagnostics()) {
            var diagnostic = source.diagnostic();
            boolean note = !diagnostic.isError() && !diagnostic.kind().contains("WARNING");
            String location = OutputLog.location(source.fileName(), Math.max(0, diagnostic.line()), Math.max(0, diagnostic.column()), root);
            String detail = diagnostic.kind() + (location.isEmpty() ? "" : " " + location) + ": " + diagnostic.message();
            // javac emits the same toolchain note for main and test compilation.
            String key = note ? diagnostic.kind() + "\n" + diagnostic.message() : detail;
            if (compilerDetails.putIfAbsent(key, detail) == null && !note && open())
                action.problem(diagnostic.isError() ? OutputLog.Level.ERROR : OutputLog.Level.WARN, source.fileName(),
                        Math.max(0, diagnostic.line()), Math.max(0, diagnostic.column()), diagnostic.message(), root);
        }
        compiled = result.success();
        if (open() && result.success()) action.info("Compiled in " + OutputLog.duration(result.durationNanos()));
    }

    public List<String> compilerDetails() { return List.copyOf(compilerDetails.values()); }

    /** The run ended without a report: compile-only, a compile failure, or a setup error. */
    public void completed(String message) {
        if (!open()) return;
        if (compiled && message != null && message.toLowerCase(Locale.ROOT).contains("compiled")) {
            action.info(message);
            action.finish(OutputLog.Outcome.SUCCESS, null, null);
        } else {
            if (message != null && !message.isBlank()) action.error(message);
            long errors = compilerDetails.values().stream().filter(detail -> detail.startsWith("ERROR")).count();
            action.finish(OutputLog.Outcome.FAILURE, errors == 0 ? null : errors + (errors == 1 ? " error" : " errors"), null);
        }
    }

    /**
     * <pre>
     * [INFO] Tests run: 2, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 3 ms -- in CalculatorTest
     * [ERROR] CalculatorTest#testAdd · expected: &lt;4&gt; but was: &lt;5&gt;
     * [INFO]
     * [INFO] Results:
     * [ERROR] Failures:
     * [ERROR]   CalculatorTest#testAdd · expected: &lt;4&gt; but was: &lt;5&gt;
     * [INFO] Tests run: 2, Failures: 1, Errors: 0, Skipped: 0
     * </pre>
     * Failures are failed assertions, Errors are engine failures, Skipped counts skipped and aborted tests.
     */
    public void report(TestEngine.Report report) {
        if (!open()) begin(false, "");
        var classes = new LinkedHashMap<String, List<TestEngine.Test>>();
        for (var test : report.tests()) classes.computeIfAbsent(className(test), name -> new ArrayList<>()).add(test);
        classes.forEach((name, tests) -> {
            long failed = tests.stream().filter(test -> test.status() == TestEngine.Status.FAILED).count();
            long skipped = tests.stream().filter(test -> test.status() == TestEngine.Status.SKIPPED || test.status() == TestEngine.Status.ABORTED).count();
            long elapsed = tests.stream().mapToLong(TestEngine.Test::durationNanos).sum();
            action.info(OutputLog.counts(tests.size(), failed, 0, skipped) + ", Time elapsed: " + OutputLog.duration(elapsed) + " -- in " + name,
                    failed > 0 ? Role.ERROR : Role.SUCCESS);
            for (var test : tests) {
                String reason = test.failure() == null || test.failure().isBlank() ? "" : " · " + test.failure().lines().findFirst().orElse("");
                switch (test.status()) {
                    case FAILED -> { action.error(name(test) + reason); action.detail(test.trace()); }
                    case ABORTED -> action.warn(name(test) + " aborted" + reason);
                    case SKIPPED -> action.warn(name(test) + " skipped" + reason);
                    case PASSED -> { }
                }
            }
        });
        action.blank();
        action.info("Results:");
        action.blank();
        var failures = report.tests().stream().filter(test -> test.status() == TestEngine.Status.FAILED).toList();
        if (!failures.isEmpty()) {
            action.error("Failures:");
            for (var test : failures)
                action.error("  " + name(test) + (test.failure() == null || test.failure().isBlank() ? ""
                        : " · " + test.failure().lines().findFirst().orElse("")));
        }
        if (!report.engineFailures().isEmpty()) {
            action.error("Errors:");
            for (String failure : report.engineFailures()) action.error("  " + failure);
        }
        if (report.tests().isEmpty() && report.engineFailures().isEmpty()) action.warn("No tests to run.");
        action.info(OutputLog.counts(report.tests().size(), report.failed(), report.engineFailures().size(),
                count(report, TestEngine.Status.SKIPPED) + count(report, TestEngine.Status.ABORTED)), summaryRole(report));
        // As Surefire: no tests is not a failure, only a warning above.
        action.finish(report.successful() ? OutputLog.Outcome.SUCCESS : OutputLog.Outcome.FAILURE,
                report.tests().isEmpty() ? "no tests" : report.passed() + " of " + report.tests().size() + " passed", report.durationNanos());
    }

    public static String summary(TestEngine.Report report) {
        return outcome(report) + " · " + report.passed() + " passed · " + report.failed() + " failed · "
                + count(report, TestEngine.Status.SKIPPED) + " skipped · " + count(report, TestEngine.Status.ABORTED)
                + " aborted" + (report.engineFailures().isEmpty() ? "" : " · " + countLabel(report.engineFailures().size(), "engine failure"))
                + " · " + OutputLog.duration(report.durationNanos());
    }

    public static String name(TestEngine.Test test) {
        String name = test.className();
        if (name == null || name.isBlank()) return test.name();
        if (test.methodName() != null && !test.methodName().isBlank()) name += "#" + test.methodName();
        if (test.name() != null && !test.name().isBlank()
                && !test.name().equals(test.methodName()) && !test.name().equals(test.methodName() + "()"))
            name += " [" + test.name() + "]";
        return name;
    }

    private static String className(TestEngine.Test test) {
        return test.className() == null || test.className().isBlank() ? test.name() : test.className();
    }

    public static String duration(long nanos) { return OutputLog.duration(nanos); }

    public static Role role(TestEngine.Status status) {
        return switch (status) {
            case PASSED -> Role.SUCCESS;
            case FAILED -> Role.ERROR;
            case ABORTED -> Role.WARNING;
            case SKIPPED -> Role.SKIPPED;
        };
    }

    public static Role summaryRole(TestEngine.Report report) {
        if (!report.successful()) return Role.ERROR;
        if (report.tests().isEmpty() || count(report, TestEngine.Status.ABORTED) > 0) return Role.WARNING;
        return report.passed() > 0 ? Role.SUCCESS : Role.SKIPPED;
    }

    public static String outcome(TestEngine.Report report) {
        if (!report.successful()) return "FAILED";
        if (report.tests().isEmpty()) return "NO TESTS";
        if (count(report, TestEngine.Status.ABORTED) > 0) return "ABORTED";
        return count(report, TestEngine.Status.SKIPPED) > 0 ? "COMPLETED" : "PASSED";
    }

    private static long count(TestEngine.Report report, TestEngine.Status status) {
        return report.tests().stream().filter(test -> test.status() == status).count();
    }

    private static String countLabel(long count, String label) { return count + " " + label + (count == 1 ? "" : "s"); }
}
