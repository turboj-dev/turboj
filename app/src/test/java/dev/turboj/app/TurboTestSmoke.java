package dev.turboj.app;

import dev.turboj.config.Settings;
import dev.turboj.commandj.core.CommandContext;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.*;
import javax.swing.*;

/** Actual native command routing, shared semantic test selection and resident JUnit worker. */
public final class TurboTestSmoke {
    public static void main(String[] args) throws Exception {
        Path output = Path.of("app/target/turbotest-smoke").toAbsolutePath();
        Files.createDirectories(output);
        Path project = Files.createTempDirectory(output, "project-");
        Files.createDirectories(project.resolve(".turboj"));
        Path source = project.resolve("SampleTest.java");
        String code = "public class SampleTest { @org.junit.jupiter.api.Test void passes() { org.junit.jupiter.api.Assertions.assertEquals(4, 2 + 2); } @org.junit.jupiter.api.Test void fails() { org.junit.jupiter.api.Assertions.fail(\"expected fixture\"); } }\n";
        Files.writeString(source, code);
        System.setProperty("turboj.config", project.resolve(".config").toString());
        System.setProperty("apple.laf.useScreenMenuBar", "false");
        TurboFrame frame = edt(() -> {
            var f = new TurboFrame(new Settings(), Map.of());
            f.setVisible(true);
            f.openPath(source);
            return f;
        });

        try {
            waitFor(() -> source.equals(frame.currentFile()));
            check(edt(() -> !frame.tests().automatic()), "Auto Test defaults off");
            edt(() -> {
                frame.editor().gotoOffset(code.indexOf("assertEquals"));
                return null;
            });
            command(frame, "tests.current");
            waitFor(() -> frame.tests().lastReport() != null);
            check(edt(() -> frame.tests().lastReport().tests().size() == 1 && frame.tests()
                    .lastReport().passed() == 1),
                    "current-method command resolves enclosing Java test and runs exactly one test");
            var first = edt(() -> frame.tests().lastReport());
            command(frame, "tests.class");
            waitFor(() -> frame.tests().lastReport() != first);
            check(edt(() -> frame.tests().lastReport().tests().size() == 2 && frame.tests()
                    .lastReport().failed() == 1),
                    "test-class command reports one pass and one deliberate failure");
            check(edt(() -> frame.tests().lastReport().tests().getFirst().failure()
                    .contains("expected fixture")),
                    "failure is listed first with real assertion details");
            var second = edt(() -> frame.tests().lastReport());
            command(frame, "tests.failed");
            waitFor(() -> frame.tests().lastReport() != second);
            check(edt(() -> frame.tests().lastReport().tests().size() == 1 && frame.tests()
                    .lastReport().failed() == 1), "Run Failed uses JUnit unique IDs");
            check(Files.readString(source).equals(code), "test commands never rewrite source");
            command(frame, "tests.auto");
            check(edt(() -> frame.tests().automatic()), "Auto Test can be enabled explicitly");
            var previous = edt(() -> frame.tests().lastReport());
            var prepared = edt(() -> frame.refactoring().prepare(new dev.turboj.refactorj.api.Requests.Rename(
                    new dev.turboj.refactorj.api.Model.Location(source, code.indexOf("passes")), "succeeds"))).get(90, TimeUnit.SECONDS);
            check(prepared.plan().valid(), "native test method Rename prepares successfully");
            edt(() -> { frame.refactoring().showPreview(prepared); return null; });
            check(edt(() -> frame.tests().lastReport() == previous && !frame.tests().busy()), "refactoring preview does not execute tests");
            edt(() -> frame.refactoring().apply(prepared)).get(40, TimeUnit.SECONDS);
            waitFor(() -> frame.tests().lastReport() != previous);
            check(edt(() -> frame.tests().lastReport().tests().size() == 2 && frame.tests().lastReport().tests().stream().anyMatch(test -> test.methodName().equals("succeeds"))),
                    "Auto Test compiles and runs only after the whole native refactoring is published");
            check(Files.readString(source).equals(code), "automatic tests preserve unsaved refactored buffer");
            command(frame, "tests.auto");
            check(edt(() -> !frame.tests().automatic()), "Auto Test can be disabled immediately");
            Files.writeString(output.resolve("report.txt"),
                    "PASS native current-method/class/failed commands, real JUnit output, auto-test after real refactoring, preview never runs tests, unchanged saved source.\n");
        } catch (Throwable error) {
            System.err.println("NATIVE CONSOLE:\n" + edt(() -> frame.console().text()));
            throw error;
        } finally {
            edt(() -> {
                frame.shutdown();
                frame.dispose();
                return null;
            });
        }
    }

    private static void command(TurboFrame f, String id) throws Exception {
        var result = edt(() -> f.commandRegistry().execute(id, CommandContext.of("editor",
                "editable"))).stage().toCompletableFuture().get(10, TimeUnit.SECONDS);

        if (result.status() != dev.turboj.commandj.core.CommandResult.Status.SUCCEEDED)
            throw new AssertionError(result);
    }

    private static void check(boolean value, String text) {
        if (!value)
            throw new AssertionError(text);
        System.out.println("PASS " + text);
    }

    private static void waitFor(Callable<Boolean> condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);

        while (System.nanoTime() < end) {
            if (edt(condition))
                return;
            Thread.sleep(20);
        }
        throw new AssertionError("Timed out");
    }

    private static <T> T edt(Callable<T> action) throws Exception {
        var task = new FutureTask<T> (action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }
}
