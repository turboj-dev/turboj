package dev.turboj.app;

import dev.turboj.commandj.core.CommandContext;
import dev.turboj.commandj.swing.EmbeddedDialog;
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

/** Exercises real JUnit outcomes through the actual editor command and themed transcript. */
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
            String text = edt(() -> frame.console().text());
            check(text.contains("══ TURBOTEST") && text.contains("FAILED · 4 tests · 1 passed · 1 failed · 1 skipped · 1 aborted"),
                    "separate test section and exact outcome counts are visible");
            check(text.contains("Deliberate output fixture failure") && text.contains("ResultsTest.fails(ResultsTest.java:4)"),
                    "failure and source stack location remain in selectable output");
            check(!text.contains("4 tests: 1 passed, 1 failed"), "structured report replaces the duplicate plain summary");
            var statusField = TurboFrame.class.getDeclaredField("buildStatus");
            statusField.setAccessible(true);
            check(edt(() -> "TURBOTEST · FAILED".equals(statusField.get(frame))),
                    "failed tests cannot leave a successful project-build status");
            var dialogField = TurboFrame.class.getDeclaredField("testResults");
            dialogField.setAccessible(true);
            for (String id : Theme.BUNDLED) {
                edt(() -> { frame.applyTheme(Theme.bundled(id)); return null; });
                screenshot(frame, output.resolve(id + "-details.png"));
                check(edt(() -> {
                    var theme = Theme.bundled(id);
                    var document = ((JTextPane) frame.console().inputComponent()).getStyledDocument();
                    for (var test : report.tests()) {
                        int offset = text.indexOf("[" + test.status() + "]");
                        if (offset < 0 || !StyleConstants.getForeground(document.getCharacterElement(offset).getAttributes())
                                .equals(ConsolePane.colorFor(theme, TestOutput.role(test.status()), theme.messagesBackground()))) return false;
                    }
                    return true;
                }), "existing passed/failed/skipped/aborted lines recolor with " + id);
            }
            edt(() -> { ((EmbeddedDialog) dialogField.get(frame)).dispose(); return null; });
            for (String id : Theme.BUNDLED) {
                edt(() -> { frame.applyTheme(Theme.bundled(id)); return null; });
                screenshot(frame, output.resolve(id + "-output.png"));
            }
            edt(() -> {
                var component = frame.console().inputComponent();
                int start = text.indexOf("FAILED · 4 tests");
                component.select(start, text.indexOf('\n', start));
                check(component.getSelectedText().contains("1 skipped · 1 aborted"), "summary stays selectable for copying");
                return null;
            });
            check(Files.readString(source).equals(code), "test execution preserves saved source");
            Files.writeString(output.resolve("transcript.txt"), text);
            Files.writeString(output.resolve("report.txt"), checks.toString());
        } catch (Throwable error) {
            System.err.println("TEST CONSOLE:\n" + edt(() -> frame.console().text()));
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
