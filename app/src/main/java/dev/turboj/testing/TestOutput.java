package dev.turboj.testing;

import dev.turboj.compiler.WorkspaceCompilationResult;
import dev.turboj.turbotest.TestEngine;
import dev.turboj.ui.ConsolePane;
import dev.turboj.ui.ConsolePane.Role;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;

/** Presents real test outcomes separately from program stdout without discarding diagnostics. */
public final class TestOutput {
    private final ConsolePane console;
    private final LinkedHashMap<String, String> compilerDetails = new LinkedHashMap<>();

    public TestOutput(ConsolePane console) { this.console = console; }

    public void begin(boolean compilationOnly) {
        compilerDetails.clear();
        console.append("\n══ " + (compilationOnly ? "PROJECT BUILD" : "TURBOTEST")
                + " ══════════════════════════════════════════════\n", Role.HEADING);
    }

    public void status(String text) { console.append(text + "\n", Role.MUTED); }

    public void compilation(WorkspaceCompilationResult result, Path root) {
        for (var source : result.diagnostics()) {
            var diagnostic = source.diagnostic();
            boolean note = !diagnostic.isError() && !diagnostic.kind().contains("WARNING");
            String location = location(source.fileName(), diagnostic.line(), root);
            String detail = diagnostic.kind() + (location.isEmpty() ? "" : " " + location)
                    + ": " + diagnostic.message();
            // javac emits the same toolchain note for main and test compilation.
            String key = note ? diagnostic.kind() + "\n" + diagnostic.message() : detail;
            if (compilerDetails.putIfAbsent(key, detail) == null && !note)
                console.append(detail + "\n", diagnostic.isError() ? Role.ERROR : Role.WARNING);
        }
    }

    public List<String> compilerDetails() { return List.copyOf(compilerDetails.values()); }

    public void report(TestEngine.Report report) {
        console.append("\n", Role.NORMAL);
        for (var test : report.tests()) {
            console.append("  [" + test.status() + "] " + name(test) + " · "
                    + duration(test.durationNanos()) + "\n", role(test.status()));
            if (test.failure() != null && !test.failure().isBlank())
                console.append("    " + test.failure().replace("\n", "\n    ") + "\n", role(test.status()));
            if (test.trace() != null && !test.trace().isBlank())
                console.append(test.trace() + (test.trace().endsWith("\n") ? "" : "\n"), Role.MUTED);
        }
        for (String failure : report.engineFailures())
            console.append("  [ENGINE FAILED] " + failure + "\n", Role.ERROR);
        console.append("\n" + outcome(report) + " · " + countLabel(report.tests().size(), "test") + " · ", summaryRole(report));
        console.append(report.passed() + " passed", report.passed() > 0 ? Role.SUCCESS : Role.MUTED);
        console.append(" · ", Role.NORMAL);
        console.append(report.failed() + " failed", report.failed() > 0 ? Role.ERROR : Role.MUTED);
        console.append(" · ", Role.NORMAL);
        console.append(count(report, TestEngine.Status.SKIPPED) + " skipped", Role.SKIPPED);
        console.append(" · ", Role.NORMAL);
        console.append(count(report, TestEngine.Status.ABORTED) + " aborted",
                count(report, TestEngine.Status.ABORTED) > 0 ? Role.WARNING : Role.MUTED);
        if (!report.engineFailures().isEmpty())
            console.append(" · " + countLabel(report.engineFailures().size(), "engine failure"), Role.ERROR);
        console.append(" · " + duration(report.durationNanos()) + "\n", Role.MUTED);
        console.append("══════════════════════════════════════════════════════════\n", Role.HEADING);
    }

    public static String summary(TestEngine.Report report) {
        return outcome(report) + " · " + report.passed() + " passed · " + report.failed() + " failed · "
                + count(report, TestEngine.Status.SKIPPED) + " skipped · " + count(report, TestEngine.Status.ABORTED)
                + " aborted" + (report.engineFailures().isEmpty() ? "" : " · " + countLabel(report.engineFailures().size(), "engine failure"))
                + " · " + duration(report.durationNanos());
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

    public static String duration(long nanos) {
        return String.format(Locale.ROOT, "%.2f ms", Math.max(0, nanos) / 1_000_000.0);
    }

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

    private static String location(String fileName, long line, Path root) {
        String file = fileName;
        if (!file.isBlank() && root != null) {
            try {
                Path path = Path.of(file).toAbsolutePath().normalize();
                if (path.startsWith(root.toAbsolutePath().normalize())) file = root.toAbsolutePath().normalize().relativize(path).toString();
            } catch (RuntimeException ignored) { /* Keep compiler-supplied names intact. */ }
        }
        return line > 0 ? file + ":" + line : file;
    }
}
