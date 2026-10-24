package dev.turboj.app;

import dev.turboj.ai.AIConfig;
import dev.turboj.config.Settings;
import dev.turboj.ui.themes.Theme;

import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/** Explicit local-model GUI acceptance test. Requires an already running FIM server. */
public final class AILocalSmoke {
    private static final String SOURCE =
            "public class LocalSquare {\n"
                + "    static int square(int value) {\n"
                + "        \n"
                + "    }\n"
                + "    public static void main(String[] args) { System.out.println(square(3)); }\n"
                + "}\n";
    private static final StringBuilder report = new StringBuilder();

    public static void main(String[] args) throws Exception {
        if (args.length != 2)
            throw new IllegalArgumentException("Usage: AILocalSmoke <loopback URL> <FIM model>");
        Path output = Path.of("app/target/ai-local-smoke").toAbsolutePath();
        Files.createDirectories(output);
        System.setProperty(
                "turboj.config", Files.createTempDirectory(output, "settings-").toString());
        System.setProperty("apple.laf.useScreenMenuBar", "false");
        var d = AIConfig.defaults();
        var config =
                new AIConfig(
                        AIConfig.Mode.LOCAL,
                        AIConfig.LocalBackend.OLLAMA,
                        args[0],
                        args[1],
                        d.cloudEndpoint(),
                        d.cloudModel(),
                        false,
                        false,
                        false,
                        200,
                        60,
                        256,
                        12288,
                        d.exclusions(),
                        true);
        var frame =
                edt(
                        () -> {
                            UIManager.setLookAndFeel(
                                    UIManager.getCrossPlatformLookAndFeelClassName());
                            var f = new TurboFrame(new Settings(), Map.of());
                            f.setVisible(true);
                            f.toFront();
                            f.editor().requestFocusInWindow();
                            return f;
                        });
        try {
            waitFor(() -> frame.editor().isFocusOwner(), "editor focus");
            note("Live model: " + args[1] + "; server: " + args[0]);
            int caret = SOURCE.indexOf("        \n") + 8;
            edt(
                    () -> {
                        frame.aiCompletion().configure(config);
                        frame.editor().setText(SOURCE);
                        frame.editor().gotoOffset(caret);
                        frame.aiCompletion().request(true, false);
                        return null;
                    });
            waitFor(
                    () ->
                            frame.editor().isAIInlineCompletion()
                                    && frame.aiCompletion()
                                            .metrics()
                                            .contains("Total generation n=1"),
                    "completed real inference");
            check(
                    edt(() -> frame.editor().getText()).equals(SOURCE),
                    "Real suggestion is an overlay, not source");
            check(!edt(frame::isRunning), "No automatic execution");
            note(edt(() -> frame.aiCompletion().metrics()));
            for (String theme : Theme.BUNDLED) {
                edt(
                        () -> {
                            frame.applyTheme(Theme.bundled(theme));
                            var shot =
                                    new BufferedImage(
                                            frame.getWidth(),
                                            frame.getHeight(),
                                            BufferedImage.TYPE_INT_RGB);
                            var g = shot.createGraphics();
                            frame.paint(g);
                            g.dispose();
                            ImageIO.write(shot, "png", output.resolve(theme + ".png").toFile());
                            return null;
                        });
                check(
                        edt(() -> frame.editor().isAIInlineCompletion()),
                        "Live ghost retained in " + theme);
            }
            edt(
                    () -> {
                        frame.editor()
                                .dispatchEvent(
                                        new KeyEvent(
                                                frame.editor(),
                                                KeyEvent.KEY_PRESSED,
                                                System.currentTimeMillis(),
                                                0,
                                                KeyEvent.VK_TAB,
                                                KeyEvent.CHAR_UNDEFINED));
                        return null;
                    });
            String accepted = edt(() -> frame.editor().getText());
            check(!accepted.equals(SOURCE), "Tab accepts live model output");
            check(
                    accepted.startsWith(SOURCE.substring(0, caret))
                            && accepted.endsWith(SOURCE.substring(caret)),
                    "Insertion preserves prefix and suffix");
            Files.writeString(output.resolve("LocalSquare.java"), accepted);
            edt(
                    () -> {
                        frame.editor().undo();
                        return null;
                    });
            check(
                    edt(() -> frame.editor().getText()).equals(SOURCE),
                    "One Undo restores exact original buffer");
            edt(
                    () -> {
                        frame.editor().redo();
                        frame.compile(true, false);
                        return null;
                    });
            waitFor(
                    () ->
                            !frame.isRunning()
                                    && frame.console()
                                            .text()
                                            .lines()
                                            .anyMatch(line -> line.strip().equals("9")),
                    "explicit compile/run result 9");
            note("PASS Real local FIM → Tab → Undo/Redo → explicit compile/run → 9");
        } finally {
            Files.writeString(output.resolve("report.txt"), report);
            edt(
                    () -> {
                        frame.shutdown();
                        frame.dispose();
                        return null;
                    });
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        note("PASS " + message);
    }

    private static void note(String message) {
        report.append(message).append('\n');
        System.out.println(message);
    }

    private static void waitFor(Callable<Boolean> condition, String label) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(70).toNanos();
        while (System.nanoTime() < deadline) {
            if (edt(condition)) return;
            Thread.sleep(20);
        }
        throw new AssertionError("Timeout: " + label);
    }

    private static <T> T edt(Callable<T> action) throws Exception {
        var task = new FutureTask<T>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }
}
