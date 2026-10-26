package dev.turboj.app;

import dev.turboj.config.Settings;

import java.awt.Rectangle;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.text.JTextComponent;

/** Real-window regression for interactive stdin and replacing an already-running program. */
public final class InteractiveRunSmoke {
    private static final StringBuilder report = new StringBuilder();
    private static final String LOOP =
            """
            public class Loop {
                public static void main(String[] args) {
                    System.out.println("previous loop started");
                    while (true) { }
                }
            }
            """;

    public static void main(String[] args) throws Exception {
        Path target = Path.of("app", "target").toAbsolutePath();
        Files.createDirectories(target);
        System.setProperty("turboj.config", target.resolve("interactive-smoke-config").toString());
        System.setProperty("apple.laf.useScreenMenuBar", "false");
        String menu = Files.readString(Path.of("examples", "Menu.java"));
        TurboFrame frame =
                edt(
                        () -> {
                            UIManager.setLookAndFeel(
                                    UIManager.getCrossPlatformLookAndFeelClassName());
                            Settings settings = new Settings(); settings.clearOutputBeforeRun = true; // Each transcript starts empty.
                            TurboFrame value = new TurboFrame(settings, Map.of());
                            value.setVisible(true);
                            value.toFront();
                            value.editor().requestFocusInWindow();
                            return value;
                        });
        try {
            edt(
                    () -> {
                        frame.editor().setText(menu);
                        return null;
                    });
            launch(frame, false);
            awaitPrompt(frame, "Quanti voti vuoi inserire? ", 1);
            captureInput(frame, target.resolve("stdin-smoke.png"));
            typeText(frame, "20");
            edt(
                    () -> {
                        JTextComponent input = frame.console().inputComponent();
                        check(input instanceof JTextPane, "program input is not the output pane");
                        check(
                                !frame.console().inputField().isShowing(),
                                "separate program input row is visible");
                        check(
                                frame.console().text().endsWith("Quanti voti vuoi inserire? 20"),
                                "typed value is not immediately after the prompt: "
                                        + frame.console().text());
                        int end = input.getDocument().getLength();
                        Rectangle promptEnd = input.modelToView2D(end - 2).getBounds();
                        Rectangle caret = input.modelToView2D(input.getCaretPosition()).getBounds();
                        check(
                                input.getCaretPosition() == end,
                                "caret does not follow the pending value");
                        check(
                                caret.y == promptEnd.y && caret.x > promptEnd.x,
                                "caret/value moved onto a different line from the prompt");
                        return null;
                    });
            captureInput(frame, target.resolve("stdin-inline-value.png"));
            edt(
                    () -> {
                        frame.console().setInputText("");
                        return null;
                    });
            note(
                    "PASS stdin and its visible caret share the output line: Quanti voti vuoi"
                        + " inserire? 20; no separate input row");

            typeLine(frame, "oops");
            awaitPrompt(frame, "Quanti voti vuoi inserire? ", 2);
            typeLine(frame, "0");
            awaitPrompt(frame, "Quanti voti vuoi inserire? ", 3);
            typeLine(frame, "3");
            awaitPrompt(frame, "Inserisci il voto: ", 1);
            typeLine(frame, "bad");
            awaitPrompt(frame, "Valore non valido. Inserisci il voto? ", 1);
            typeLine(frame, "-1");
            awaitPrompt(frame, "Valore non valido. Inserisci il voto? ", 2);
            typeLine(frame, "3");
            awaitPrompt(frame, "Inserisci il voto: ", 2);
            typeLine(frame, "4");
            awaitPrompt(frame, "Inserisci il voto: ", 3);
            typeLine(frame, "5");
            waitFor(
                    () -> !frame.isRunning() && frame.console().text().contains("SUCCESS · exit 0"),
                    "Menu completion");
            edt(
                    () -> {
                        String output = frame.console().text();
                        check(output.contains("Totale note: 12.0"), "wrong total: " + output);
                        check(output.contains("Media: 4.0"), "wrong average: " + output);
                        check(
                                output.contains("4.0\n5.0\n4.0\n5.0"),
                                "missing positive grades: " + output);
                        check(
                                output.contains("oops")
                                        && output.contains("bad")
                                        && output.contains("-1"),
                                "stdin was not echoed to the transcript");
                        check(
                                menu.equals(frame.editor().getText()),
                                "typing program input changed the source");
                        return null;
                    });
            note(
                    "PASS exact Menu.java: staged hasNextInt/hasNextFloat input, invalid values,"
                        + " total 12.0, average 4.0, echo and unchanged source; no EOF submitted");

            for (boolean isolated : new boolean[] {false, true}) {
                edt(
                        () -> {
                            frame.editor().setText(LOOP);
                            return null;
                        });
                launch(frame, isolated);
                waitFor(
                        () ->
                                frame.isRunning()
                                        && frame.console().text().contains("previous loop started"),
                        "running loop");
                edt(
                        () -> {
                            frame.editor().setText(TurboFrame.HELLO);
                            return null;
                        });
                launch(frame, isolated);
                waitFor(
                        () ->
                                !frame.isRunning()
                                        && frame.console().text().contains("Hello TurboJ!")
                                        && frame.console().text().contains("SUCCESS · exit 0"),
                        "replacement Hello");
                edt(
                        () -> {
                            check(
                                    !frame.console().text().contains("already running"),
                                    "Run rejected the existing process instead of replacing it");
                            check(
                                    !frame.console().text().contains("previous loop started"),
                                    "old run output leaked into the new transcript");
                            return null;
                        });
                note(
                        "PASS "
                                + (isolated ? "Isolated Run" : "Fast Run")
                                + " replaces a nonterminating previous program");
            }

            edt(
                    () -> {
                        frame.editor().setText(LOOP);
                        return null;
                    });
            launch(frame, false);
            waitFor(
                    () ->
                            frame.isRunning()
                                    && frame.console().text().contains("previous loop started"),
                    "loop before rapid Run");
            edt(
                    () -> {
                        frame.editor()
                                .setText(
                                        TurboFrame.HELLO.replace(
                                                "Hello TurboJ!", "superseded output"));
                        frame.compile(true, false);
                        frame.editor()
                                .setText(
                                        TurboFrame.HELLO.replace("Hello TurboJ!", "latest output"));
                        frame.compile(true, false);
                        frame.compile(true, false);
                        return null;
                    });
            waitFor(
                    () ->
                            !frame.isRunning()
                                    && frame.console().text().contains("latest output")
                                    && frame.console().text().contains("SUCCESS · exit 0"),
                    "latest rapid Run");
            edt(
                    () -> {
                        check(
                                !frame.console().text().contains("superseded output"),
                                "superseded Run executed");
                        check(
                                !frame.console().text().contains("already running"),
                                "rapid Run was rejected");
                        return null;
                    });
            note("PASS rapid Run requests supersede older versions and execute the latest buffer");

            edt(
                    () -> {
                        frame.editor().setText(menu);
                        return null;
                    });
            launch(frame, false);
            awaitPrompt(frame, "Quanti voti vuoi inserire? ", 1);
            // A marker the restart's cleared output no longer holds: the new prompt cannot be confused with the old one.
            edt(() -> { frame.console().append("RESTART-MARKER\n", false); return null; });
            waitFor(() -> frame.console().text().contains("RESTART-MARKER"), "restart marker");
            typeText(frame, "99");
            edt(
                    () -> {
                        check(
                                frame.console().inputText().equals("99"),
                                "unsent input setup failed");
                        return null;
                    });
            edt(
                    () -> {
                        JTextComponent input = frame.console().inputComponent();
                        input.dispatchEvent(
                                new KeyEvent(
                                        input,
                                        KeyEvent.KEY_PRESSED,
                                        System.currentTimeMillis(),
                                        InputEvent.META_DOWN_MASK,
                                        KeyEvent.VK_R,
                                        'r'));
                        return null;
                    });
            waitFor(() -> !frame.console().text().contains("RESTART-MARKER"), "output cleared on restart");
            awaitPrompt(frame, "Quanti voti vuoi inserire? ", 1);
            waitFor(() -> frame.console().inputText().isEmpty(), "input cleared on restart");
            typeLine(frame, "1");
            awaitPrompt(frame, "Inserisci il voto: ", 1);
            typeLine(frame, "6");
            waitFor(
                    () ->
                            !frame.isRunning()
                                    && frame.console().text().contains("Totale note: 6.0")
                                    && frame.console().text().contains("SUCCESS · exit 0"),
                    "restarted Menu completion");
            edt(
                    () -> {
                        check(
                                frame.console().text().contains("Media: 6.0"),
                                "restart did not reset Scanner state");
                        check(
                                menu.equals(frame.editor().getText()),
                                "restart input changed source");
                        return null;
                    });
            note(
                    "PASS Cmd+R from the stdin field restarts the same interactive program with a"
                            + " fresh Scanner and clears unsent input");
            note("PASS all interactive stdin / restart desktop regressions");
        } finally {
            edt(
                    () -> {
                        frame.shutdown();
                        return null;
                    });
            Files.writeString(target.resolve("interactive-run-smoke.txt"), report.toString());
        }
    }

    private static void launch(TurboFrame frame, boolean isolated) throws Exception {
        var result = edt(() -> frame.compile(true, isolated)).get(20, TimeUnit.SECONDS);
        check(result.success(), "compilation failed: " + result.diagnostics());
    }

    private static void awaitPrompt(TurboFrame frame, String text, int expected) throws Exception {
        waitFor(
                () -> frame.isRunning() && occurrences(frame.console().text(), text) >= expected,
                "prompt " + text + " #" + expected);
        waitFor(
                () ->
                        frame.console().inputComponent().isFocusOwner()
                                && frame.console().inputComponent().isEditable(),
                "automatic stdin focus");
        waitFor(
                () -> frame.console().inputComponent().getCaret().isVisible(),
                "visible stdin caret");
    }

    private static void typeText(TurboFrame frame, String text) throws Exception {
        waitFor(() -> frame.console().inputComponent().isFocusOwner(), "input focus before typing");
        edt(
                () -> {
                    JTextComponent field = frame.console().inputComponent();
                    for (char c : text.toCharArray()) {
                        field.dispatchEvent(
                                new KeyEvent(
                                        field,
                                        KeyEvent.KEY_TYPED,
                                        System.currentTimeMillis(),
                                        0,
                                        KeyEvent.VK_UNDEFINED,
                                        c));
                    }
                    return null;
                });
    }

    private static void typeLine(TurboFrame frame, String text) throws Exception {
        typeText(frame, text);
        edt(
                () -> {
                    JTextComponent field = frame.console().inputComponent();
                    check(
                            frame.console().inputText().equals(text),
                            "input keystrokes were lost: " + frame.console().inputText() + " · output:\n" + frame.console().text()
                                    + "\n· caret " + field.getCaretPosition() + " of " + field.getDocument().getLength() + " editable " + field.isEditable());
                    field.dispatchEvent(
                            new KeyEvent(
                                    field,
                                    KeyEvent.KEY_PRESSED,
                                    System.currentTimeMillis(),
                                    0,
                                    KeyEvent.VK_ENTER,
                                    '\n'));
                    return null;
                });
        waitFor(() -> frame.console().inputText().isEmpty(), "input submission");
    }

    private static void captureInput(TurboFrame frame, Path path) throws Exception {
        BufferedImage image =
                edt(
                        () -> {
                            var field = frame.console().inputComponent();
                            Rectangle caret =
                                    field.modelToView2D(field.getCaretPosition()).getBounds();
                            check(caret.height >= 10, "caret has no visible height");
                            check(
                                    field.getWidth() >= 100 && field.getHeight() >= 15,
                                    "inline input surface is not usable");
                            check(
                                    !field.getCaretColor().equals(field.getBackground()),
                                    "caret has no contrast");
                            note(
                                    "Stdin caret: "
                                            + field.getCaret().getClass().getSimpleName()
                                            + ", visible="
                                            + field.getCaret().isVisible()
                                            + ", bounds="
                                            + caret);
                            var painted =
                                    new BufferedImage(
                                            frame.getWidth(),
                                            frame.getHeight(),
                                            BufferedImage.TYPE_INT_RGB);
                            var graphics = painted.createGraphics();
                            frame.paint(graphics);
                            graphics.dispose();
                            return painted;
                        });
        ImageIO.write(image, "png", path.toFile());
    }

    private static int occurrences(String source, String needle) {
        int count = 0;
        for (int start = 0; (start = source.indexOf(needle, start)) >= 0; start += needle.length())
            count++;
        return count;
    }

    private static void waitFor(Callable<Boolean> predicate, String label) throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < end) {
            if (edt(predicate)) return;
            Thread.sleep(20);
        }
        throw new AssertionError("Timed out: " + label);
    }

    private static <T> T edt(Callable<T> action) throws Exception {
        var future = new CompletableFuture<T>();
        SwingUtilities.invokeLater(
                () -> {
                    try {
                        future.complete(action.call());
                    } catch (Throwable error) {
                        future.completeExceptionally(error);
                    }
                });
        return future.get(20, TimeUnit.SECONDS);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static void note(String message) {
        System.out.println(message);
        report.append(message).append('\n');
    }
}
