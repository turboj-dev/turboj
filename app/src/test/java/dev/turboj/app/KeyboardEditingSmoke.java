package dev.turboj.app;

import dev.turboj.commandj.keymap.KeyStrokeSpec;
import dev.turboj.commandj.swing.CommandPaletteDialog;
import dev.turboj.commandj.swing.OverlayHost;
import dev.turboj.config.Settings;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.KeyEvent;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import javax.swing.*;

/** Actual Swing window/key-dispatch walkthrough. Fixture/profile setup is programmatic;
 * all tested actions, palette selection and panel transitions use key events, never mouse events.
 * It does not measure physical keyboard layouts or replace a human accessibility audit. */
public final class KeyboardEditingSmoke {
    private static final StringBuilder report = new StringBuilder();
    private static TurboFrame frame;
    private static int checks;
    public static void main(String[] args) throws Exception {
        Path output = Path.of("app/target/keyboard-editing-smoke").toAbsolutePath();
        Files.createDirectories(output);
        report.append("Started UTC: ").append(java.time.Instant.now()).append('\n');
        Path artifact = Path.of(TurboFrame.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        report.append("Artifact: ").append(artifact).append('\n');
        if (Files.isRegularFile(artifact)) report.append("SHA-256: ").append(java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(artifact)))).append('\n');
        report.append("Runtime: ").append(System.getProperty("java.version")).append("; actual Swing key dispatch, no mouse events.\n");
        var systemClipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        var previousClipboard = systemClipboard.getContents(null);
        System.setProperty("turboj.config", Files.createTempDirectory(output, "settings-").toString());
        System.setProperty("apple.laf.useScreenMenuBar", "false");
        frame = edt(() -> {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            var f = new TurboFrame(new Settings(), Map.of());
            f.setVisible(true); f.toFront(); f.editor().requestFocusInWindow(); return f;
        });
        try {
            waitFor(() -> frame.isActive() && frame.editor().isFocusOwner());
            for (String profile : List.of("intellij-macos", "eclipse-macos", "vscode-macos", "turboj-macos")) {
                edt(() -> { frame.commandKeymaps().select(profile); return null; });
                replaceDocument("alpha\nbeta\ngamma");
                key(KeyEvent.VK_HOME, KeyEvent.META_DOWN_MASK); key(KeyEvent.VK_DOWN, 0);
                action("cut"); expect("alpha\ngamma", profile + ": Cut no selection removes logical line");
                check("beta\n".equals(clipboard()), profile + ": system clipboard contains the cut line");
                action("undo"); expect("alpha\nbeta\ngamma", profile + ": single Undo restores cut");
                action("copy"); check("beta\n".equals(clipboard()), profile + ": Copy without selection copies line");
                key(KeyEvent.VK_HOME, KeyEvent.META_DOWN_MASK); action("paste");
                expect("beta\nalpha\nbeta\ngamma", profile + ": line clipboard inserts at line start");
                action("undo"); key(KeyEvent.VK_HOME, KeyEvent.META_DOWN_MASK);
                key(KeyEvent.VK_RIGHT, 0); key(KeyEvent.VK_RIGHT, KeyEvent.SHIFT_DOWN_MASK);
                key(KeyEvent.VK_RIGHT, KeyEvent.SHIFT_DOWN_MASK); action("cut");
                expect("aha\nbeta\ngamma", profile + ": explicit partial selection remains Cut Selection");
                check("lp".equals(clipboard()), profile + ": selected clipboard text is exact");
                action("undo"); action("editor.removeSecondaryCarets");
                key(KeyEvent.VK_HOME, KeyEvent.META_DOWN_MASK); key(KeyEvent.VK_DOWN, 0);
                String oldClipboard = clipboard(); action("duplicateLine");
                expect("alpha\nbeta\nbeta\ngamma", profile + ": profile-specific Duplicate Line runs");
                check(oldClipboard.equals(clipboard()), profile + ": Duplicate leaves clipboard untouched");
                action("undo"); action("deleteLine"); expect("alpha\ngamma", profile + ": profile-specific Delete Line runs");
                check(oldClipboard.equals(clipboard()), profile + ": Delete leaves clipboard untouched");
                action("undo");
                multiCaret(profile);
                String source = edt(() -> frame.editor().getText());
                action("view.focusConsole");
                waitFor(() -> !frame.editor().isFocusOwner() && focus() instanceof javax.swing.text.JTextComponent);
                key(KeyEvent.VK_HOME, KeyEvent.META_DOWN_MASK); key(KeyEvent.VK_END, KeyEvent.SHIFT_DOWN_MASK);
                key(KeyEvent.VK_C, KeyEvent.META_DOWN_MASK);
                check(source.equals(edt(() -> frame.editor().getText())), profile + ": console keys do not mutate editor");
                key(KeyEvent.VK_ESCAPE, 0); waitFor(() -> frame.editor().isFocusOwner());
                check(true, profile + ": Escape returns from console to editor");
            }
            Path project = Files.createTempDirectory(output, "reactor-");
            Files.createDirectories(project.resolve("src/main/java"));
            Files.writeString(project.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion><groupId>dev.turboj.keyboard</groupId><artifactId>focus</artifactId><version>1.0.0</version><properties><maven.compiler.release>21</maven.compiler.release></properties></project>");
            Files.writeString(project.resolve("src/main/java/Example.java"), "class Example {}\n");
            edt(() -> { frame.mavenProjects().open(project); return null; });
            waitFor(() -> !frame.mavenProjects().busy() && frame.mavenProjects().workspace() != null);
            String source = edt(() -> frame.editor().getText());
            action("view.focusExplorer"); waitFor(() -> frame.mavenProjects().hasExplorerFocus());
            key(KeyEvent.VK_HOME, 0); key(KeyEvent.VK_RIGHT, 0); key(KeyEvent.VK_DOWN, 0); key(KeyEvent.VK_LEFT, 0);
            check(source.equals(edt(() -> frame.editor().getText())), "Explorer navigation does not mutate source");
            key(KeyEvent.VK_ESCAPE, 0); waitFor(() -> frame.editor().isFocusOwner());
            check(true, "Explorer Escape restores editor focus");
            edt(() -> {
                var image = new java.awt.image.BufferedImage(frame.getWidth(), frame.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
                var graphics = image.createGraphics(); frame.paint(graphics); graphics.dispose();
                javax.imageio.ImageIO.write(image, "png", output.resolve("keyboard-editing.png").toFile()); return null;
            });
            report.append("Completed UTC: ").append(java.time.Instant.now()).append('\n');
            report.append("PASS complete: ").append(checks).append(" assertions; four keymap profiles.\n");
            Files.writeString(output.resolve("report.txt"), report.toString());
        } catch (Throwable failure) {
            Files.writeString(output.resolve("report.txt"), report + "FAIL " + failure + "\n");
            throw failure;
        } finally {
            edt(() -> { frame.shutdown(); frame.dispose(); return null; });
            if (previousClipboard != null) systemClipboard.setContents(previousClipboard, null);
        }
    }

    private static void multiCaret(String profile) throws Exception {
        replaceDocument("a\nb\nc"); key(KeyEvent.VK_HOME, KeyEvent.META_DOWN_MASK); key(KeyEvent.VK_RIGHT, 0);
        action("editor.addCaretBelow"); action("editor.addCaretBelow");
        check(edt(() -> frame.editor().caretCount()) == 3, profile + ": keyboard adds three independent carets");
        type("x"); expect("ax\nbx\ncx", profile + ": multicaret typing edits all lines");
        action("undo"); expect("a\nb\nc", profile + ": one Undo reverses multicaret typing");
        action("redo"); expect("ax\nbx\ncx", profile + ": Redo restores all carets and text");
        action("comment"); expect("// ax\n// bx\n// cx", profile + ": multicaret comment toggles all lines once");
        action("undo"); key(KeyEvent.VK_BACK_SPACE, 0); expect("a\nb\nc", profile + ": multicaret Backspace edits all lines");
        action("undo"); action("editor.copyLineDown");
        expect("ax\nbx\ncx\nax\nbx\ncx", profile + ": selected caret lines copy as one block");
        action("undo"); key(KeyEvent.VK_ESCAPE, 0);
        check(edt(() -> frame.editor().caretCount()) == 1, profile + ": Escape removes secondary carets");
    }

    private static void replaceDocument(String text) throws Exception {
        if (edt(() -> frame.editor().caretCount()) > 1) action("editor.removeSecondaryCarets");
        action("selectAll"); type(text);
        expect(text, "Fixture entered using keyboard events");
    }
    private static void action(String id) throws Exception {
        var bindings = edt(() -> frame.commandKeymaps().commandBindings(frame.commandRegistry(), id));
        if (!bindings.isEmpty()) {
            for (var stroke : bindings.getFirst().sequence().strokes()) stroke(stroke);
        } else palette(id);
        edt(() -> null);
    }
    private static void palette(String id) throws Exception {
        var binding = edt(() -> frame.commandKeymaps().commandBindings(frame.commandRegistry(), "palette").getFirst());
        for (var stroke : binding.sequence().strokes()) stroke(stroke);
        waitFor(() -> palette() != null && palette().searchField().isFocusOwner());
        String query = edt(() -> frame.commandRegistry().find(id).orElseThrow().title());
        type(query);
        waitFor(() -> palette() != null && palette().results().getModel().getSize() > 0);
        int index = edt(() -> {
            var list = palette().results();
            String canonical = frame.commandRegistry().canonicalId(id);
            for (int i = 0; i < list.getModel().getSize(); i++)
                if (list.getModel().getElementAt(i).command().id().equals(canonical)) return i;
            throw new AssertionError("Command not found in palette: " + id);
        });
        int selected = edt(() -> palette().results().getSelectedIndex());
        for (int i = selected; i < index; i++) key(KeyEvent.VK_DOWN, 0);
        for (int i = selected; i > index; i--) key(KeyEvent.VK_UP, 0);
        key(KeyEvent.VK_ENTER, 0);
        waitFor(() -> palette() == null);
    }
    private static CommandPaletteDialog palette() {
        for (var dialog : OverlayHost.dialogs(frame)) if (dialog instanceof CommandPaletteDialog palette && palette.isVisible()) return palette;
        return null;
    }
    private static void stroke(KeyStrokeSpec stroke) throws Exception {
        int modifiers = 0;
        for (var modifier : stroke.modifiers()) modifiers |= switch (modifier) {
            case META -> KeyEvent.META_DOWN_MASK; case ALT -> KeyEvent.ALT_DOWN_MASK;
            case CTRL -> KeyEvent.CTRL_DOWN_MASK; case SHIFT -> KeyEvent.SHIFT_DOWN_MASK;
        };
        int code = KeyEvent.class.getField("VK_" + stroke.key()).getInt(null);
        key(code, modifiers);
    }
    private static void type(String text) throws Exception {
        for (char c : text.toCharArray()) {
            if (c == '\n') key(KeyEvent.VK_ENTER, 0);
            else edt(() -> {
                Component target = focus();
                int code = KeyEvent.getExtendedKeyCodeForChar(c), modifiers = Character.isUpperCase(c) ? KeyEvent.SHIFT_DOWN_MASK : 0;
                dispatch(target, KeyEvent.KEY_PRESSED, code, c, modifiers);
                dispatch(target, KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED, c, modifiers);
                dispatch(target, KeyEvent.KEY_RELEASED, code, c, modifiers);
                return null;
            });
        }
    }
    private static void key(int code, int modifiers) throws Exception {
        edt(() -> { Component target = focus(); dispatch(target, KeyEvent.KEY_PRESSED, code, KeyEvent.CHAR_UNDEFINED, modifiers);
            dispatch(target, KeyEvent.KEY_RELEASED, code, KeyEvent.CHAR_UNDEFINED, modifiers); return null; });
    }
    private static void dispatch(Component target, int kind, int code, char c, int modifiers) {
        if (target == null) throw new AssertionError("Keyboard focus lost");
        KeyboardFocusManager.getCurrentKeyboardFocusManager().dispatchEvent(new KeyEvent(target, kind, System.currentTimeMillis(), modifiers, code, c));
    }
    private static Component focus() { return KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner(); }
    private static String clipboard() throws Exception { return (String) Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor); }
    private static void expect(String expected, String label) throws Exception { check(expected.equals(edt(() -> frame.editor().getText())), label + "; actual=" + edt(() -> frame.editor().getText()).replace("\n", "\\n")); }
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label); checks++; report.append("PASS ").append(label).append('\n'); System.out.println("PASS " + label); }
    private static void waitFor(Callable<Boolean> test) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) { if (edt(test)) return; Thread.sleep(15); }
        throw new AssertionError("Timeout; focus=" + edt(() -> String.valueOf(focus())));
    }
    private static <T> T edt(Callable<T> action) throws Exception {
        var task = new FutureTask<>(action); SwingUtilities.invokeAndWait(task); return task.get();
    }
}
