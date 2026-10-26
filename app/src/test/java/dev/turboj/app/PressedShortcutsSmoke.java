package dev.turboj.app;

import dev.turboj.commandj.core.Availability;
import dev.turboj.commandj.keymap.KeyBinding;
import dev.turboj.commandj.swing.EmbeddedDialog;
import dev.turboj.config.Settings;
import dev.turboj.ui.AsciiMenuBar;
import dev.turboj.ui.themes.Theme;
import java.awt.Component;
import java.awt.KeyboardFocusManager;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.*;

/** Real shortcut dispatch, Options toggle, chord display and themed editor painting. */
public final class PressedShortcutsSmoke {
    private static final StringBuilder report = new StringBuilder();

    public static void main(String[] args) throws Exception {
        Path output = Path.of("app/target/pressed-shortcuts-smoke").toAbsolutePath();
        Files.createDirectories(output);
        System.setProperty("turboj.config", Files.createTempDirectory(output, "settings-").toString());
        System.setProperty("apple.laf.useScreenMenuBar", "false");
        var calls = new AtomicInteger();
        TurboFrame frame = edt(() -> {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            Settings words = new Settings(); words.shortcutSymbols = false; // Asserts the spelled-out form.
            var app = new TurboFrame(words, Map.of());
            app.registerCommand("shortcut.probe", "Example action", "Test", calls::incrementAndGet,
                    context -> Availability.available());
            check(app.commandKeymaps().replaceBindings("shortcut.probe", List.of(KeyBinding.of("CTRL+ALT+F12"))).applied(), "custom shortcut registered");
            app.setVisible(true); app.toFront(); app.editor().requestFocusInWindow();
            return app;
        });
        try {
            waitFor(() -> frame.isActive() && frame.editor().isFocusOwner());
            check(edt(() -> !frame.editor().showsPressedShortcuts()), "existing default has no shortcut badge");
            press(frame.editor(), KeyEvent.VK_F12, InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK);
            check(calls.get() == 1 && edt(() -> frame.editor().pressedShortcutText().isEmpty()), "disabled display does not change command execution");
            JCheckBoxMenuItem toggle = edt(() -> toggle(frame));
            edt(() -> { toggle.doClick(); return null; });
            check(edt(() -> toggle.isSelected() && frame.editor().showsPressedShortcuts()), "Options checkbox enables the display");
            waitFor(() -> Settings.load().showPressedShortcuts);
            check(true, "enabled preference is saved");
            for (String id : Theme.BUNDLED) {
                edt(() -> { frame.applyTheme(Theme.bundled(id)); frame.editor().requestFocusInWindow(); return null; });
                waitFor(frame.editor()::isFocusOwner);
                press(frame.editor(), KeyEvent.VK_F12, InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK);
                check(edt(() -> frame.editor().pressedShortcutText().equals("Ctrl+Option+F12  Example action")), "actual customized shortcut appears in " + id);
                screenshot(frame, output.resolve(id + ".png"));
            }
            waitFor(() -> frame.editor().pressedShortcutText().isEmpty());
            check(true, "hint disappears after its brief timeout");
            edt(() -> {
                check(frame.commandKeymaps().replaceBindings("shortcut.probe", List.of(KeyBinding.of("CTRL+ALT+F10, CTRL+ALT+F11"))).applied(), "custom chord registered");
                return null;
            });
            press(frame.editor(), KeyEvent.VK_F10, InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK);
            check(edt(() -> frame.editor().pressedShortcutText().isEmpty()), "incomplete chord does not invent an executed action");
            press(frame.editor(), KeyEvent.VK_F11, InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK);
            check(edt(() -> frame.editor().pressedShortcutText().equals("Ctrl+Option+F10, Ctrl+Option+F11  Example action")), "completed chord displays both actual strokes");
            edt(() -> { toggle.doClick(); return null; });
            check(edt(() -> !toggle.isSelected() && frame.editor().pressedShortcutText().isEmpty()), "switching off clears the hint immediately");
            waitFor(() -> !Settings.load().showPressedShortcuts);
            check(true, "disabled preference is saved");
            edt(() -> { toggle.doClick(); return null; });
            press(frame.editor(), KeyEvent.VK_A, 0);
            check(edt(() -> frame.editor().pressedShortcutText().isEmpty()), "ordinary letter presses are not recorded");
            JPasswordField password = new JPasswordField();
            EmbeddedDialog dialog = edt(() -> {
                var value = new EmbeddedDialog(frame, "Input", false);
                value.add(password); value.setSize(350, 160); value.setVisible(true);
                password.requestFocusInWindow(); return value;
            });
            waitFor(password::isFocusOwner);
            int before = calls.get();
            press(password, KeyEvent.VK_F10, InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK);
            press(password, KeyEvent.VK_F11, InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK);
            check(calls.get() == before && edt(() -> frame.editor().pressedShortcutText().isEmpty()), "dialog/password input is not displayed or dispatched as editor commands");
            edt(() -> { dialog.dispose(); return null; });
            Files.writeString(output.resolve("report.txt"), report.toString());
        } finally {
            edt(() -> { frame.shutdown(); frame.dispose(); return null; });
        }
    }

    private static JCheckBoxMenuItem toggle(TurboFrame frame) {
        for (JMenu menu : ((AsciiMenuBar) frame.getJMenuBar()).allMenus()) {
            for (Component child : menu.getMenuComponents())
                if (child instanceof JCheckBoxMenuItem item && "showPressedShortcuts".equals(item.getActionCommand())) return item;
        }
        throw new AssertionError("Missing Options checkbox");
    }

    private static void press(Component target, int key, int modifiers) throws Exception {
        edt(() -> {
            var manager = KeyboardFocusManager.getCurrentKeyboardFocusManager();
            manager.dispatchEvent(new KeyEvent(target, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), modifiers, key, KeyEvent.CHAR_UNDEFINED));
            manager.dispatchEvent(new KeyEvent(target, KeyEvent.KEY_RELEASED, System.currentTimeMillis(), modifiers, key, KeyEvent.CHAR_UNDEFINED));
            return null;
        });
    }

    private static void screenshot(TurboFrame frame, Path file) throws Exception {
        edt(() -> {
            var image = new BufferedImage(frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_INT_RGB);
            var g = image.createGraphics(); frame.paint(g); g.dispose();
            javax.imageio.ImageIO.write(image, "png", file.toFile()); return null;
        });
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        report.append("PASS ").append(message).append('\n'); System.out.println("PASS " + message);
    }

    private static void waitFor(Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) { if (edt(condition)) return; Thread.sleep(20); }
        throw new AssertionError("Timed out waiting for shortcut display");
    }

    private static <T> T edt(Callable<T> action) throws Exception {
        var task = new FutureTask<T>(action); SwingUtilities.invokeAndWait(task); return task.get();
    }
}
