package dev.turboj.app;

import dev.turboj.config.Settings;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import javax.swing.*;

/** Real Swing focus/keyboard events and a separate mouse-routing regression probe; no shell mocks. */
public final class OutputCommandKeyboardSmoke {
    private static TurboFrame frame;
    private static int checks;
    private static final StringBuilder report = new StringBuilder();

    public static void main(String[] args) throws Exception {
        Path output = Path.of("app/target/output-command-keyboard-smoke").toAbsolutePath();
        Files.createDirectories(output);
        Path fixture = Files.createTempDirectory(output, "workspace-");
        System.setProperty("turboj.config", fixture.resolve("settings").toString());
        System.setProperty("apple.laf.useScreenMenuBar", "false");
        report.append("Started: ").append(java.time.Instant.now()).append('\n')
                .append("Real Swing keyboard/focus dispatch; fixture and profile selection programmatic.\n");
        Path artifact = Path.of(TurboFrame.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        report.append("App artifact SHA-256: ").append(java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(artifact)))).append('\n');
        frame = edt(() -> {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            var settings = new Settings(); settings.workspaceDirectory = fixture.toString();
            var result = new TurboFrame(settings, Map.of());
            result.setVisible(true); result.toFront(); result.editor().requestFocusInWindow(); return result;
        });
        try {
            waitFor(() -> frame.isActive() && frame.editor().isFocusOwner(), "initial focus");
            for (String profile : List.of("intellij-macos", "eclipse-macos", "vscode-macos", "turboj-macos")) {
                edt(() -> { frame.commandKeymaps().select(profile); frame.editor().setText(""); return null; });
                type(":");
                check(edt(() -> frame.editor().getText().equals(":")), profile + ": ordinary colon edits source");
                key(KeyEvent.VK_ESCAPE, 0); type(":");
                waitFor(() -> frame.console().commandLine().isFocusOwner(), "command focus " + profile);
                check(edt(() -> frame.editor().getText().equals(":")), profile + ": Esc colon opens without editing source");
                type("! printf 'native-" + profile + "\\n'"); key(KeyEvent.VK_ENTER, 0);
                waitFor(() -> frame.outputCommands().lastCompletion() != null
                        && frame.outputCommands().lastCompletion().isDone(), "command completion");
                check(edt(() -> frame.outputCommands().lastResult().succeeded()
                        && frame.console().text().contains("native-" + profile + "\n")), profile + ": real shell output and exit zero");
                type("! retained draft"); key(KeyEvent.VK_ESCAPE, 0);
                waitFor(() -> frame.editor().isFocusOwner(), "Escape restores editor");
                check(edt(() -> !frame.console().isCommandLineVisible()), profile + ": Escape closes command row");
                key(KeyEvent.VK_ESCAPE, 0); type(":");
                waitFor(() -> frame.console().commandLine().isFocusOwner(), "reopen command line");
                check(edt(() -> frame.console().commandLine().getText().equals("! retained draft")), profile + ": draft survives Escape");
                key(KeyEvent.VK_A, KeyEvent.META_DOWN_MASK); key(KeyEvent.VK_BACK_SPACE, 0);
                key(KeyEvent.VK_ESCAPE, 0);
                waitFor(() -> frame.editor().isFocusOwner(), "return editor");
            }
            key(KeyEvent.VK_ESCAPE, 0); type("a:");
            check(edt(() -> frame.editor().getText().equals(":a:") && !frame.console().isCommandLineVisible()),
                    "Typing another character cancels armed colon");
            key(KeyEvent.VK_ESCAPE, 0); key(KeyEvent.VK_LEFT, 0); type(":");
            check(edt(() -> !frame.console().isCommandLineVisible()), "Caret movement cancels armed colon");
            key(KeyEvent.VK_ESCAPE, 0); type(":");
            waitFor(() -> frame.console().commandLine().isFocusOwner(), "cancel test focus");
            type("! sleep 30"); key(KeyEvent.VK_ENTER, 0);
            waitFor(() -> frame.outputCommands().isRunning(), "owned process starts");
            key(KeyEvent.VK_C, KeyEvent.CTRL_DOWN_MASK);
            waitFor(() -> frame.outputCommands().lastCompletion().isDone(), "owned process cancels");
            check(edt(() -> frame.outputCommands().lastResult().cancelled()), "Control+C cancels only the output command");
            key(KeyEvent.VK_UP, 0);
            check(edt(() -> frame.console().commandLine().getText().equals("! sleep 30")), "Up retrieves command history");
            key(KeyEvent.VK_ESCAPE, 0);
            waitFor(() -> frame.editor().isFocusOwner(), "final focus");
            // Separate mouse regression: program-output clicks must not jump to the shell row.
            edt(() -> {
                frame.console().setProgramRunning(true);
                frame.console().showCommandLine();
                return null;
            });
            waitFor(() -> frame.console().commandLine().isFocusOwner(), "mouse probe initial focus");
            edt(() -> {
                var target = frame.console().inputComponent();
                target.setCaretPosition(target.getDocument().getLength());
                target.dispatchEvent(new java.awt.event.MouseEvent(target,
                        java.awt.event.MouseEvent.MOUSE_RELEASED, System.currentTimeMillis(), 0,
                        1, 1, 1, false, java.awt.event.MouseEvent.BUTTON1));
                return null;
            });
            waitFor(() -> frame.console().inputComponent().isFocusOwner(), "program input click focus");
            check(edt(() -> frame.console().isCommandLineVisible()),
                    "Separate synthetic mouse probe: clicking program output focuses stdin while preserving shell row");
            report.append("PASS complete: ").append(checks).append(" assertions.\n");
        } catch (Throwable failure) {
            report.append("FAIL ").append(failure).append('\n'); throw failure;
        } finally {
            Files.writeString(output.resolve("report.txt"), report.toString());
            edt(() -> { frame.shutdown(); frame.dispose(); return null; });
        }
    }

    private static void type(String text) throws Exception {
        for (char character : text.toCharArray()) edt(() -> {
            int code = KeyEvent.getExtendedKeyCodeForChar(character);
            int modifiers = Character.isUpperCase(character) ? KeyEvent.SHIFT_DOWN_MASK : 0;
            dispatch(KeyEvent.KEY_PRESSED, code, character, modifiers);
            dispatch(KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED, character, modifiers);
            dispatch(KeyEvent.KEY_RELEASED, code, character, modifiers); return null;
        });
    }
    private static void key(int code, int modifiers) throws Exception {
        edt(() -> { dispatch(KeyEvent.KEY_PRESSED, code, KeyEvent.CHAR_UNDEFINED, modifiers);
            dispatch(KeyEvent.KEY_RELEASED, code, KeyEvent.CHAR_UNDEFINED, modifiers); return null; });
    }
    private static void dispatch(int kind, int code, char character, int modifiers) {
        Component target = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        if (target == null) throw new AssertionError("Focus lost");
        KeyboardFocusManager.getCurrentKeyboardFocusManager().dispatchEvent(
                new KeyEvent(target, kind, System.currentTimeMillis(), modifiers, code, character));
    }
    private static void check(boolean value, String label) {
        if (!value) throw new AssertionError(label);
        checks++; report.append("PASS ").append(label).append('\n'); System.out.println("PASS " + label);
    }
    private static void waitFor(Callable<Boolean> test, String label) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) { if (edt(test)) return; Thread.sleep(15); }
        throw new AssertionError("Timeout: " + label);
    }
    private static <T> T edt(Callable<T> action) throws Exception {
        var task = new FutureTask<>(action); SwingUtilities.invokeAndWait(task); return task.get();
    }
}
