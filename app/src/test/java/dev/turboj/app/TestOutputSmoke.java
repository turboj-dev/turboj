package dev.turboj.app;

import dev.turboj.commandj.core.CommandContext;
import dev.turboj.config.Settings;
import dev.turboj.testing.TestOutput;
import dev.turboj.turbotest.TestEngine;
import dev.turboj.ui.ConsolePane;
import dev.turboj.ui.themes.Theme;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javax.swing.*;
import javax.swing.text.StyleConstants;

/** Exercises real JUnit outcomes through the actual editor command and the themed Tests tab. */
public final class TestOutputSmoke {
    private static final StringBuilder checks = new StringBuilder();

    public static void main(String[] args) throws Exception {
        Path output = Path.of("app/target/test-output-smoke").toAbsolutePath();
        Files.createDirectories(output);
        Path project = Files.createTempDirectory(output, "project-");
        Files.createDirectories(project.resolve(".turboj"));
        Path source = project.resolve("ResultsTest.java");
        String code = """
                import org.junit.jupiter.api.*;
                public class ResultsTest {
                    @Test void passes() { Assertions.assertEquals(7, 3 + 4); }
                    @Test void fails() { Assertions.fail("Deliberate output fixture failure"); }
                    @Test @Disabled("Disabled fixture") void skipped() {}
                    @Test void aborted() { Assumptions.assumeTrue(false, "Fixture assumption"); }
                }
                """;
        Files.writeString(source, code);
        System.setProperty("turboj.config", project.resolve(".config").toString());
        System.setProperty("apple.laf.useScreenMenuBar", "false");
        TurboFrame frame = edt(() -> {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            var value = new TurboFrame(new Settings(), Map.of());
            value.setVisible(true); value.openPath(source); return value;
        });
        try {
            waitFor(() -> source.equals(frame.currentFile()));
            var executed = edt(() -> frame.commandRegistry().execute("tests.all", CommandContext.of("editor", "editable")))
                    .stage().toCompletableFuture().get(10, TimeUnit.SECONDS);
            check(executed.status() == dev.turboj.commandj.core.CommandResult.Status.SUCCEEDED, "Run All Tests command accepted");
            waitFor(() -> frame.tests().lastReport() != null);
            var report = edt(() -> frame.tests().lastReport());
            check(report.tests().size() == 4 && report.passed() == 1 && report.failed() == 1
                    && report.tests().stream().anyMatch(test -> test.status() == TestEngine.Status.SKIPPED)
                    && report.tests().stream().anyMatch(test -> test.status() == TestEngine.Status.ABORTED),
                    "resident JUnit worker returned four actual outcomes");
            String text = edt(() -> frame.testResults().text());
            check(text.startsWith("✖ ResultsTest 4 tests  ") && text.contains(" · 2 failed")
                    && text.strip().lines().toList().getLast().contains("1 failed · 1 of 4 passed"),
                    "the Tests tab names the suite and the exact outcome");
            check(text.contains("\n  ✔ passes ") && text.contains("\n  ✖ fails ") && text.contains("\n  ○ skipped ") && text.contains("\n  ! aborted "),
                    "every test is listed with its outcome");
            check(text.contains("Deliberate output fixture failure") && text.contains("at ResultsTest.fails(ResultsTest.java:4)")
                    && !text.contains("org.junit"), "the failure keeps its message and the project's frame, without JUnit frames");
            check(!edt(() -> frame.console().text()).contains("ResultsTest"), "tests write nothing to the terminal");
            check(edt(() -> frame.outputTabTitle()).equals("Tests"), "an explicit run shows the Tests tab");
            var statusField = TurboFrame.class.getDeclaredField("buildStatus");
            statusField.setAccessible(true);
            check(edt(() -> "TURBOTEST · FAILED".equals(statusField.get(frame))),
                    "failed tests cannot leave a successful project-build status");
            for (String id : Theme.BUNDLED) {
                edt(() -> { frame.applyTheme(Theme.bundled(id)); return null; });
                screenshot(frame, output.resolve(id + "-tests.png"));
                check(edt(() -> {
                    var theme = Theme.bundled(id);
                    var document = frame.testResults().view().getStyledDocument();
                    // Failures are written in the error role and aborted tests in the warning role.
                    for (var marker : java.util.Map.of("  ✖ fails", ConsolePane.Role.ERROR, "  ! aborted", ConsolePane.Role.WARNING).entrySet()) {
                        int offset = text.indexOf(marker.getKey()) + 2;
                        if (offset < 2 || !StyleConstants.getForeground(document.getCharacterElement(offset).getAttributes())
                                .equals(ConsolePane.colorFor(theme, marker.getValue(), theme.messagesBackground()))) return false;
                    }
                    return true;
                }), "failure and warning lines recolor with " + id);
            }
            edt(() -> {
                var view = frame.testResults().view();
                int start = text.indexOf("Deliberate output fixture failure");
                view.select(start, start + "Deliberate output fixture failure".length());
                check("Deliberate output fixture failure".equals(view.getSelectedText()), "failure detail stays selectable for copying");
                return null;
            });
            check(Files.readString(source).equals(code), "test execution preserves saved source");
            Files.writeString(output.resolve("transcript.txt"), text);
            Files.writeString(output.resolve("report.txt"), checks.toString());
        } catch (Throwable error) {
            System.err.println("TESTS TAB:\n" + edt(() -> frame.testResults().text()));
            throw error;
        } finally {
            edt(() -> { frame.shutdown(); frame.dispose(); return null; });
        }
    }

    private static void screenshot(TurboFrame frame, Path file) throws Exception {
        edt(() -> {
            var image = new BufferedImage(frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_INT_RGB);
            var graphics = image.createGraphics(); frame.paint(graphics); graphics.dispose();
            javax.imageio.ImageIO.write(image, "png", file.toFile()); return null;
        });
    }

    private static void check(boolean success, String message) {
        if (!success) throw new AssertionError(message);
        checks.append("PASS ").append(message).append('\n'); System.out.println("PASS " + message);
    }

    private static void waitFor(Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        while (System.nanoTime() < deadline) { if (edt(condition)) return; Thread.sleep(20); }
        throw new AssertionError("Timed out waiting for real test output");
    }

    private static <T> T edt(Callable<T> action) throws Exception {
        var task = new FutureTask<T>(action); SwingUtilities.invokeAndWait(task); return task.get();
    }
}
