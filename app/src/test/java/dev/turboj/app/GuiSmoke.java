package dev.turboj.app;

import dev.turboj.config.Settings;
import dev.turboj.ui.themes.Theme;

import java.awt.Component;
import java.awt.Container;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;
import javax.swing.*;

/** Explicit desktop smoke test, deliberately excluded from automatic/headless JUnit runs. */
public final class GuiSmoke {
    private static final StringBuilder report = new StringBuilder();

    public static void main(String[] args) throws Exception {
        Path target = Path.of("app", "target").toAbsolutePath();
        Files.createDirectories(target);
        System.setProperty("turboj.config", target.resolve("smoke-config").toString());
        System.setProperty("apple.laf.useScreenMenuBar", "false");
        long launched = System.nanoTime();
        TurboFrame frame =
                edt(
                        () -> {
                            UIManager.setLookAndFeel(
                                    UIManager.getCrossPlatformLookAndFeelClassName());
                            TurboFrame value = new TurboFrame(new Settings(), Map.of());
                            frameForDiagnostics = value;
                            value.setVisible(true);
                            value.toFront();
                            value.editor().requestFocusInWindow();
                            return value;
                        });
        try {
            waitFor(() -> frame.editor().isShowing(), "editable window");
            note(
                    "Harness start to visible window: %.2f ms (not native launcher timing)",
                    (System.nanoTime() - launched) / 1e6);
            edt(
                    () -> {
                        action(frame, "File", "Java File");
                        frame.editor().replaceSelection(TurboFrame.HELLO);
                        return null;
                    });
            runAndWait(frame, false, "Hello TurboJ!");
            note("PASS new buffer → compile → run: Hello TurboJ!");
            edt(
                    () -> {
                        check(frame.editor().find("Hello TurboJ!", true), "find output literal");
                        frame.editor().replaceSelection("Hello again!");
                        return null;
                    });
            runAndWait(frame, false, "Hello again!");
            note("PASS edit → warm run: Hello again!");
            waitFor(() -> frame.editor().isFocusOwner(), "editor focus");
            edt(
                    () -> {
                        check(frame.editor().find("Hello again!", true), "find shortcut literal");
                        frame.editor().replaceSelection("Shortcut works!");
                        java.awt.Toolkit.getDefaultToolkit().getSystemEventQueue()
                                .postEvent(
                                        new KeyEvent(
                                                frame.editor(),
                                                KeyEvent.KEY_PRESSED,
                                                System.currentTimeMillis(),
                                                InputEvent.META_DOWN_MASK,
                                                KeyEvent.VK_R,
                                                'r'));
                        return null;
                    });
            waitFor(
                    () ->
                            !frame.isRunning()
                                    && frame.console().text().contains("Shortcut works!")
                                    && frame.console().text().contains("SUCCESS · exit 0"),
                    "Cmd+R program output");
            edt(
                    () -> {
                        java.awt.Toolkit.getDefaultToolkit().getSystemEventQueue()
                                .postEvent(
                                        new KeyEvent(
                                                frame.editor(),
                                                KeyEvent.KEY_PRESSED,
                                                System.currentTimeMillis(),
                                                0,
                                                KeyEvent.VK_F10,
                                                KeyEvent.CHAR_UNDEFINED));
                        return null;
                    });
            waitFor(
                    () -> MenuSelectionManager.defaultManager().getSelectedPath().length > 0,
                    "F10 menu");
            edt(
                    () -> {
                        java.awt.Toolkit.getDefaultToolkit().getSystemEventQueue()
                                .postEvent(
                                        new KeyEvent(
                                                frame.editor(),
                                                KeyEvent.KEY_PRESSED,
                                                System.currentTimeMillis(),
                                                0,
                                                KeyEvent.VK_ESCAPE,
                                                KeyEvent.CHAR_UNDEFINED));
                        return null;
                    });
            waitFor(
                    () -> MenuSelectionManager.defaultManager().getSelectedPath().length == 0,
                    "Escape menu close");
            note("PASS Cmd+R executes and F10/Escape open/close the keyboard menu");
            edt(
                    () -> {
                        String text = frame.editor().getText();
                        int caret = frame.editor().caretOffset();
                        for (String id : Theme.BUNDLED) {
                            frame.applyTheme(Theme.bundled(id));
                            check(text.equals(frame.editor().getText()), "theme changed source");
                            check(caret == frame.editor().caretOffset(), "theme changed caret");
                        }
                        frame.applyTheme(Theme.bundled("classic"));
                        return null;
                    });
            note("PASS three live themes preserve source and caret");
            edt(
                    () -> {
                        frame.editor()
                                .setText(
                                        TurboFrame.HELLO.replace(
                                                "\"Hello TurboJ!\"", "undefinedVariable"));
                        frame.console().clear();
                        return null;
                    });
            var invalid = edt(() -> frame.compile(true, false)).get(20, TimeUnit.SECONDS);
            check(!invalid.success(), "invalid source accepted");
            waitFor(
                    () -> frame.buildStatus().contains("BUILD FAILED"),
                    "compiler failure diagnostics");
            check(
                    edt(
                            () ->
                                    !frame.isRunning()
                                            && frame.console()
                                                    .text()
                                                    .contains("cannot find symbol")),
                    "invalid bytecode executed or diagnostic missing");
            note("PASS unresolved symbol blocks execution and reports javac diagnostic");
            edt(
                    () -> {
                        frame.editor()
                                .setText(
                                        "public class Hello { public static void main(String[]"
                                            + " args) { System.out.println(\"loop started\"); while"
                                            + " (true) {} } }");
                        return null;
                    });
            edt(() -> frame.compile(true, false)).get(20, TimeUnit.SECONDS);
            waitFor(
                    () -> frame.isRunning() && frame.console().text().contains("loop started"),
                    "loop output");
            edt(
                    () -> {
                        action(frame, "Run", "Stop program");
                        frame.editor().replaceSelection(" ");
                        return null;
                    });
            waitFor(() -> !frame.isRunning(), "stopped worker");
            note("PASS nonterminating program stops; editor accepts further edits");
            edt(
                    () -> {
                        frame.editor()
                                .setText(
                                        "public class Hello { public static void main(String[]"
                                            + " args) { var s = new java.util.Scanner(System.in);"
                                            + " System.out.print(\"Name: \");"
                                            + " System.out.println(\"Hello \" + s.nextLine()); }"
                                            + " }");
                        return null;
                    });
            edt(() -> frame.compile(true, false)).get(20, TimeUnit.SECONDS);
            waitFor(
                    () -> frame.isRunning() && frame.console().text().contains("Name: "),
                    "stdin prompt");
            edt(
                    () -> {
                        frame.console().setInputText("Luca");
                        frame.console().submitInput();
                        return null;
                    });
            waitFor(
                    () -> !frame.isRunning() && frame.console().text().contains("Hello Luca"),
                    "stdin reply");
            note("PASS console input reaches Scanner(System.in)");
            edt(
                    () -> {
                        frame.editor().setText(TurboFrame.HELLO);
                        return null;
                    });
            runAndWait(frame, true, "Hello TurboJ!");
            note("PASS isolated run");
            checkSaving(frame, target);
            checkRepl(frame);
            measureEditing(frame);
            edt(
                    () -> {
                        frame.editor().setText(TurboFrame.HELLO);
                        return null;
                    });
            runAndWait(frame, false, "Hello TurboJ!");
            BufferedImage screenshot =
                    edt(
                            () -> {
                                var image =
                                        new BufferedImage(
                                                frame.getWidth(),
                                                frame.getHeight(),
                                                BufferedImage.TYPE_INT_RGB);
                                var graphics = image.createGraphics();
                                frame.paint(graphics);
                                graphics.dispose();
                                return image;
                            });
            ImageIO.write(screenshot, "png", target.resolve("turboj-smoke.png").toFile());
            note("PASS desktop smoke complete; screenshot app/target/turboj-smoke.png");
        } finally {
            edt(
                    () -> {
                        frame.shutdown();
                        return null;
                    });
            Files.writeString(target.resolve("gui-smoke.txt"), report.toString());
            Path reports = Path.of("benchmarks", "results");
            Files.createDirectories(reports);
            String stamp = java.time.Instant.now().toString().replace(":", "-");
            Files.writeString(
                    reports.resolve("gui-smoke-" + stamp + ".md"),
                    "# Desktop GUI smoke\n\n```text\n" + report + "```\n",
                    StandardOpenOption.CREATE_NEW);
        }
    }

    private static void checkSaving(TurboFrame frame, Path target) throws Exception {
        Path file = target.resolve("smoke-config").resolve("Hello.java");
        Files.createDirectories(file.getParent());
        Files.writeString(file, TurboFrame.HELLO);
        edt(
                () -> {
                    frame.openPath(file);
                    return null;
                });
        waitFor(
                () -> frame.getTitle().startsWith("Hello.java") && !frame.getTitle().contains("*"),
                "opened saved file");
        edt(
                () -> {
                    frame.editor().find("Hello TurboJ!", true);
                    frame.editor().replaceSelection("Saved while running!");
                    action(frame, "File", "Save");
                    frame.compile(true, false);
                    return null;
                });
        waitFor(
                () ->
                        !frame.getTitle().contains("*")
                                && !frame.isRunning()
                                && frame.console().text().contains("Saved while running!"),
                "save completes despite concurrent Run");
        check(Files.readString(file).contains("Saved while running!"), "save bytes missing");
        edt(
                () -> {
                    frame.editor().find("Saved while running!", true);
                    frame.editor().replaceSelection("First queued save!");
                    action(frame, "File", "Save");
                    frame.editor().find("First queued save!", true);
                    frame.editor().replaceSelection("Second queued save!");
                    action(frame, "File", "Save");
                    return null;
                });
        waitFor(() -> !frame.getTitle().contains("*"), "coalesced saves");
        check(
                Files.readString(file).contains("Second queued save!"),
                "queued save lost latest edit");
        Files.writeString(file, "// changed externally\n");
        edt(
                () -> {
                    frame.editor().replaceSelection(" ");
                    action(frame, "File", "Save");
                    return null;
                });
        waitFor(
                () -> frame.statusMessages().contains("changed outside TurboJ"),
                "external modification diagnostic");
        check(
                Files.readString(file).equals("// changed externally\n"),
                "external edit overwritten");
        note(
                "PASS save concurrent with Run, coalesced consecutive saves and external-edit"
                    + " protection");
    }

    private static void checkRepl(TurboFrame frame) throws Exception {
        long cancelStart = System.nanoTime();
        edt(
                () -> {
                    action(frame, "Run", "Java REPL");
                    frame.repl().evaluate("while (true) {}\n");
                    frame.repl().cancel();
                    frame.repl().reset();
                    return null;
                });
        waitFor(
                () ->
                        frame.repl().text().contains("Session reset.")
                                && findInput(frame.repl()).isEnabled(),
                "cancel lazy REPL startup and reset");
        check(
                System.nanoTime() - cancelStart < TimeUnit.SECONDS.toNanos(8),
                "cancelled first evaluation blocked reset");
        submitRepl(frame, "int a = 5;", null);
        submitRepl(frame, "int b = 7;", null);
        submitRepl(frame, "a * b", "\n35\n");
        edt(
                () -> {
                    JTextField input = findInput(frame.repl());
                    input.dispatchEvent(
                            new KeyEvent(
                                    input,
                                    KeyEvent.KEY_PRESSED,
                                    System.currentTimeMillis(),
                                    0,
                                    KeyEvent.VK_UP,
                                    KeyEvent.CHAR_UNDEFINED));
                    check(input.getText().equals("a * b"), "REPL history");
                    input.setText("System.out.printl");
                    input.dispatchEvent(
                            new KeyEvent(
                                    input,
                                    KeyEvent.KEY_PRESSED,
                                    System.currentTimeMillis(),
                                    0,
                                    KeyEvent.VK_TAB,
                                    '\t'));
                    return null;
                });
        waitFor(
                () ->
                        findInput(frame.repl()).isEnabled()
                                && findInput(frame.repl())
                                        .getText()
                                        .startsWith("System.out.println"),
                "REPL completion");
        submitRepl(frame, "for (int i = 0; i < 2; i++) {", null);
        submitRepl(frame, "System.out.println(\"loop\" + i);", null);
        submitRepl(frame, "}", "\nloop1\n");
        edt(
                () -> {
                    frame.editor().setText("a + b");
                    frame.editor().selectAll();
                    action(frame, "Run", "Evaluate selection");
                    return null;
                });
        waitFor(
                () -> frame.repl().text().contains("\n12\n") && findInput(frame.repl()).isEnabled(),
                "evaluate selected expression");
        edt(
                () -> {
                    frame.editor()
                            .setText(TurboFrame.HELLO.replace("Hello TurboJ!", "Statement works!"));
                    frame.editor().gotoOffset(frame.editor().getText().indexOf("System.out"));
                    return null;
                });
        waitFor(
                () ->
                        frame.currentResult() != null
                                && frame.currentResult().version() == frame.editor().version(),
                "statement syntax analysis");
        edt(
                () -> {
                    action(frame, "Run", "Evaluate current statement");
                    return null;
                });
        waitFor(
                () ->
                        frame.repl().text().contains("\nStatement works!\n")
                                && findInput(frame.repl()).isEnabled(),
                "evaluate current syntax-aware statement");
        edt(
                () -> {
                    frame.repl().evaluate("while(true) {}\n");
                    return null;
                });
        Thread.sleep(100);
        edt(
                () -> {
                    action(frame, "Run", "Stop program");
                    frame.repl().reset();
                    return null;
                });
        waitFor(() -> findInput(frame.repl()).isEnabled(), "stop running REPL and reset");
        submitRepl(frame, "6 * 7", "\n42\n");
        note(
                "PASS Java REPL: lazy-start cancellation, persistent5×7=35, history, Tab"
                    + " completion, multiline, selection/current statement, loop Stop/reset");
        edt(
                () -> {
                    action(frame, "Run", "Program output / messages");
                    return null;
                });
    }

    private static void submitRepl(TurboFrame frame, String source, String expected)
            throws Exception {
        edt(
                () -> {
                    JTextField input = findInput(frame.repl());
                    input.setText(source);
                    input.postActionEvent();
                    return null;
                });
        waitFor(
                () ->
                        findInput(frame.repl()).isEnabled()
                                && (expected == null || frame.repl().text().contains(expected)),
                "REPL " + source);
    }

    private static void measureEditing(TurboFrame frame) throws Exception {
        StringBuilder source = new StringBuilder("public class Hello {\n// typing: \n");
        for (int i = 0; i < 1000; i++)
            source.append("    int value").append(i).append(" = ").append(i).append(";\n");
        source.append("    public static void main(String[] args) {}\n}\n");
        edt(
                () -> {
                    frame.editor().setText(source.toString());
                    frame.editor().gotoLine(2);
                    frame.editor().gotoOffset(frame.editor().document().lineEnd(1));
                    frame.editor().requestFocusInWindow();
                    frame.editor().resetInputToPaintSamples();
                    frame.compile(false, false);
                    return null;
                });
        CompletableFuture<Void> typed = new CompletableFuture<>();
        edt(
                () -> {
                    int[] count = {0};
                    Timer timer = new Timer(12, null);
                    timer.addActionListener(
                            e -> {
                                frame.editor()
                                        .dispatchEvent(
                                                new KeyEvent(
                                                        frame.editor(),
                                                        KeyEvent.KEY_TYPED,
                                                        System.currentTimeMillis(),
                                                        0,
                                                        KeyEvent.VK_UNDEFINED,
                                                        'x'));
                                if (++count[0] == 120) {
                                    timer.stop();
                                    typed.complete(null);
                                }
                            });
                    timer.start();
                    return null;
                });
        typed.get(15, TimeUnit.SECONDS);
        waitFor(
                () ->
                        frame.currentResult() != null
                                && frame.currentResult().version() == frame.editor().version(),
                "latest live diagnostics");
        check(
                edt(() -> frame.editor().getText().contains("// typing: " + "x".repeat(120))),
                "synthetic key events not inserted");
        double[] samples =
                edt(
                        () ->
                                Arrays.stream(frame.editor().inputToPaintSamples())
                                        .mapToDouble(n -> n / 1e6)
                                        .sorted()
                                        .toArray());
        check(samples.length > 50, "insufficient paint samples: " + samples.length);
        note(
                "1000-line file, compiler active, 120 synthetic keys: %d paints; handler/edit→paint"
                    + " p50 %.3f / p95 %.3f / p99 %.3f ms",
                samples.length,
                percentile(samples, .5),
                percentile(samples, .95),
                percentile(samples, .99));
        note(
                "Measurement excludes OS input queue, compositor and display scanout; coalesced"
                    + " paints report oldest pending edit.");
        note(
                "PASS rapid edits: only source version %d is current",
                edt(() -> frame.editor().version()));
    }

    private static void runAndWait(TurboFrame frame, boolean isolated, String expected)
            throws Exception {
        var result = edt(() -> frame.compile(true, isolated)).get(20, TimeUnit.SECONDS);
        check(result.success(), "compile failed: " + result.diagnostics());
        waitFor(
                () ->
                        !frame.isRunning()
                                && frame.console().text().contains(expected)
                                && frame.console().text().contains("SUCCESS · exit 0"),
                expected);
    }

    private static void action(TurboFrame frame, String menu, String label) {
        for (int m = 0; m < frame.getJMenuBar().getMenuCount(); m++) {
            JMenu candidate = frame.getJMenuBar().getMenu(m);
            if (!candidate.getText().equals(menu)) continue;
            JMenuItem item = item(candidate, label);
            if (item != null) {
                item.getAction().actionPerformed(new ActionEvent(item, ActionEvent.ACTION_PERFORMED, label));
                return;
            }
        }
        throw new AssertionError("missing action " + menu + " / " + label);
    }

    /** The command item with this label anywhere in the menu, submenus included. */
    private static JMenuItem item(JMenu menu, String label) {
        for (Component component : menu.getMenuComponents()) {
            if (component instanceof JMenu submenu) { JMenuItem found = item(submenu, label); if (found != null) return found; }
            else if (component instanceof JMenuItem item && item.getText().equals(label)) return item;
        }
        return null;
    }

    private static JTextField findInput(Container root) {
        for (Component component : root.getComponents()) {
            if (component instanceof JTextField field) return field;
            if (component instanceof Container container) {
                JTextField found = findInput(container);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void waitFor(Callable<Boolean> predicate, String label) throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(20).toNanos();
        while (System.nanoTime() < end) {
            if (edt(predicate)) return;
            Thread.sleep(20);
        }
        throw new AssertionError("Timed out: " + label + "; focus " + edt(() -> String.valueOf(java.awt.KeyboardFocusManager
                .getCurrentKeyboardFocusManager().getFocusOwner()).replaceAll(",.*", "")) + "; dialogs "
                + edt(() -> dev.turboj.commandj.swing.OverlayHost.dialogs(frameForDiagnostics).stream().map(d -> d.getTitle()).toList())
                + "; console:\n" + edt(() -> frameForDiagnostics.console().text()));
    }
    private static TurboFrame frameForDiagnostics;

    private static <T> T edt(Callable<T> action) throws Exception {
        CompletableFuture<T> result = new CompletableFuture<>();
        SwingUtilities.invokeLater(
                () -> {
                    try {
                        result.complete(action.call());
                    } catch (Throwable error) {
                        result.completeExceptionally(error);
                    }
                });
        return result.get(20, TimeUnit.SECONDS);
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static double percentile(double[] sorted, double p) {
        return sorted[Math.min(sorted.length - 1, (int) Math.ceil(sorted.length * p) - 1)];
    }

    private static void note(String format, Object... values) {
        String line = String.format(Locale.ROOT, format, values);
        System.out.println(line);
        report.append(line).append('\n');
    }
}
