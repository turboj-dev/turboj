package dev.turboj.testing;

import dev.turboj.compiler.WorkspaceCompilationResult;
import dev.turboj.console.OutputLog;
import dev.turboj.turbotest.TestEngine;
import dev.turboj.ui.ConsolePane.Role;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * A test run in the Tests tab (docs/output-and-status.md): suites, their tests, and detail only for what failed. A
 * compile-only run (Compile Project) has no tab of its own: its problems and outcome go to the status line.
 */
public final class TestOutput {
    private final TestResultsPane pane;
    private final Consumer<OutputLog.Notice> notices;
    private final LinkedHashMap<String, String> compilerDetails = new LinkedHashMap<>();
    private boolean open, compilationOnly, compiled = true;
    private String subject = "";

    public TestOutput(TestResultsPane pane, Consumer<OutputLog.Notice> notices) {
        this.pane = Objects.requireNonNull(pane);
        this.notices = Objects.requireNonNull(notices);
    }

    /** Opens the run: {@code Test CalculatorTest · hello-world · Java 25}. */
    public void begin(boolean compilationOnly, String subject) {
        compilerDetails.clear();
        compiled = true;
        open = true;
        this.compilationOnly = compilationOnly;
        this.subject = (compilationOnly ? "Compile Project" : "Test") + (subject == null || subject.isBlank() ? "" : " " + subject);
        if (!compilationOnly) pane.begin(this.subject);
    }

    public void begin(boolean compilationOnly) { begin(compilationOnly, ""); }

    /** The run is still in progress. */
    public boolean open() { return open; }

    /** Output the tests print themselves: kept, and shown only if the run does not pass. */
    public void program(String text, boolean stderr) { if (open && !compilationOnly) pane.output(text); }

    /**
     * A progress message from the test controller. Phase changes need no words; any other message is not results
     * material, so the caller shows it in the status line instead.
     */
    public boolean status(String text) {
        if (!open) return false;
        if (text.startsWith("Compiling") || text.startsWith("Running JUnit")) return true;
        if (text.equals("Tests stopped.")) {
            if (!compilationOnly) pane.completed("stopped", false);
            open = false;
            notices.accept(new OutputLog.Notice(OutputLog.Level.WARN, subject + " stopped"));
            return true;
        }
        return false;
    }

    public void compilation(WorkspaceCompilationResult result, Path root) {
        for (var source : result.diagnostics()) {
            var diagnostic = source.diagnostic();
            boolean note = !diagnostic.isError() && !diagnostic.kind().contains("WARNING");
            String location = OutputLog.location(source.fileName(), Math.max(0, diagnostic.line()), Math.max(0, diagnostic.column()), root);
            String detail = diagnostic.kind() + (location.isEmpty() ? "" : " " + location) + ": " + diagnostic.message();
            // javac emits the same toolchain note for main and test compilation.
            String key = note ? diagnostic.kind() + "\n" + diagnostic.message() : detail;
            if (compilerDetails.putIfAbsent(key, detail) == null && diagnostic.isError() && open && !compilationOnly)
                pane.problem(location + (location.isEmpty() ? "" : " ") + diagnostic.message());
        }
        compiled = result.success();
    }

    public List<String> compilerDetails() { return List.copyOf(compilerDetails.values()); }

    /** The run ended without a report: compile-only, a compile failure, or a setup error. */
    public void completed(String message) {
        if (!open) return;
        open = false;
        boolean success = compiled && message != null && message.toLowerCase(Locale.ROOT).contains("compiled");
        long errors = compilerDetails.values().stream().filter(detail -> detail.startsWith("ERROR")).count();
        if (!compilationOnly) pane.completed(message, success);
        String first = compilerDetails.values().stream().filter(detail -> detail.startsWith("ERROR")).findFirst()
                .map(detail -> " · " + detail.substring(detail.indexOf(' ') + 1)).orElse("");
        notices.accept(success ? new OutputLog.Notice(OutputLog.Level.INFO, subject + " done · " + message)
                : new OutputLog.Notice(OutputLog.Level.ERROR, subject + " failed"
                        + (errors == 0 ? (message == null || message.isBlank() ? "" : " · " + message)
                        : " · " + errors + (errors == 1 ? " error" : " errors") + first)));
    }

    /** The report: suites, tests, and the detail of what failed, in the Tests tab. */
    public void report(TestEngine.Report report) {
        if (!open) begin(false, "");
        open = false;
        pane.report(report);
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
