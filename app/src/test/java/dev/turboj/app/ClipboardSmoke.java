package dev.turboj.app;

import dev.turboj.commandj.keymap.*;
import dev.turboj.commandj.swing.EmbeddedDialog;
import dev.turboj.config.Settings;
import java.awt.*;
import java.awt.datatransfer.*;
import java.awt.event.*;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import javax.swing.*;
import javax.swing.text.JTextComponent;

/** Real keyboard/menu clipboard routing, with a private app config and restored system clipboard. */
public final class ClipboardSmoke {
    private static final StringBuilder report = new StringBuilder();

    public static void main(String[] args) throws Exception {
        Path output = Path.of("app/target/clipboard-smoke").toAbsolutePath();
        Files.createDirectories(output);
        System.setProperty("turboj.config", Files.createTempDirectory(output, "config-").toString());
        System.setProperty("apple.laf.useScreenMenuBar", "false");
        Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
        Transferable previous = clipboard.getContents(null);
        TurboFrame frame = edt(() -> {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            Settings settings = new Settings(); settings.keymapProfile = "intellij-macos"; // Custom Copy uses a free IntelliJ key.
            TurboFrame value = new TurboFrame(settings, Map.of());
            value.setVisible(true); value.toFront();
            value.editor().setText("public class ClipboardSource {}\n");
            value.console().clear(); value.console().append("dependency could not be resolved\n", true);
            value.console().focusInput();
            return value;
        });
        try {
            JTextComponent text = frame.console().inputComponent();
            waitFor(() -> text.isFocusOwner() && frame.isActive());
            edt(() -> { text.select(0, 10); return null; });
            press(text, KeyEvent.VK_C, InputEvent.META_DOWN_MASK);
            check("dependency".equals(clipboardText()), "Cmd+C copies selected output, not source");
            press(text, KeyEvent.VK_A, InputEvent.META_DOWN_MASK);
            check(edt(() -> text.getSelectedText().equals(frame.console().text())), "Cmd+A selects the output pane");
            edt(() -> {
                text.select(0, 10);
                JMenu edit = findMenu(frame.getJMenuBar(), "Edit");
                JMenuItem copy = findItem(edit, "copy");
                MenuSelectionManager.defaultManager().setSelectedPath(new MenuElement[] {frame.getJMenuBar(), edit, edit.getPopupMenu(), copy});
                check(copy.isEnabled(), "Edit Copy is enabled for selected output");
                copy.doClick(); MenuSelectionManager.defaultManager().clearSelectedPath();
                return null;
            });
            check("dependency".equals(clipboardText()), "Edit Copy retains the output target through menu focus");
            edt(() -> { frame.console().append("another dependency message\n", false); return null; });
            edt(() -> null);
            check(edt(() -> "dependency".equals(text.getSelectedText())), "streaming diagnostics preserve selected text");

            edt(() -> {
                var changed = frame.commandKeymaps().replaceBindings("copy", List.of(new KeyBinding(KeySequence.parse("META+SHIFT+C"), BindingContext.any())));
                check(changed.applied(), "custom Copy shortcut is accepted without conflicts");
                text.requestFocusInWindow(); return null;
            });
            waitFor(text::isFocusOwner);
            clipboard.setContents(new StringSelection("before custom shortcut"), null);
            press(text, KeyEvent.VK_C, InputEvent.META_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK);
            check("dependency".equals(clipboardText()), "custom CommandJ shortcut copies output");

            edt(() -> {
                frame.console().setProgramRunning(true);
                frame.console().setInputText("unsent");
                text.select(text.getDocument().getLength() - 6, text.getDocument().getLength());
                return null;
            });
            String transcript = edt(() -> frame.console().text().substring(0, frame.console().text().length() - 6));
            press(text, KeyEvent.VK_X, InputEvent.META_DOWN_MASK);
            check("unsent".equals(clipboardText()) && edt(() -> frame.console().text().equals(transcript)), "Cut removes only selected unsent input");
            press(text, KeyEvent.VK_V, InputEvent.META_DOWN_MASK);
            check(edt(() -> frame.console().inputText().equals("unsent") && frame.console().text().startsWith(transcript)), "Paste edits stdin without changing transcript");
            edt(() -> { frame.console().setProgramRunning(false); frame.editor().requestFocusInWindow(); frame.editor().selectAll(); return null; });
            waitFor(frame.editor()::isFocusOwner);
            press(frame.editor(), KeyEvent.VK_C, InputEvent.META_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK);
            check("public class ClipboardSource {}\n".equals(clipboardText()), "Copy returns to the source editor when focused");

            JTextField field = new JTextField("dialog text");
            EmbeddedDialog dialog = edt(() -> {
                var surface = new EmbeddedDialog(frame, "Clipboard field", false);
                surface.add(field); surface.setSize(400, 180); surface.setVisible(true);
                field.selectAll(); field.requestFocusInWindow(); return surface;
            });
            waitFor(field::isFocusOwner);
            press(field, KeyEvent.VK_C, InputEvent.META_DOWN_MASK);
            check("dialog text".equals(clipboardText()), "embedded ASCII dialog retains native Cmd+C editing");
            edt(() -> { dialog.dispose(); return null; });
        } finally {
            Files.writeString(output.resolve("report.txt"), report.toString());
            edt(() -> { frame.shutdown(); frame.dispose(); return null; });
            if (previous != null) clipboard.setContents(previous, null);
        }
    }

    private static String clipboardText() throws Exception {
        return (String) Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);
    }
    private static void press(Component target, int key, int modifiers) throws Exception {
        edt(() -> {
            var manager = KeyboardFocusManager.getCurrentKeyboardFocusManager();
            manager.dispatchEvent(new KeyEvent(target, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), modifiers, key, KeyEvent.CHAR_UNDEFINED));
            manager.dispatchEvent(new KeyEvent(target, KeyEvent.KEY_RELEASED, System.currentTimeMillis(), modifiers, key, KeyEvent.CHAR_UNDEFINED));
            return null;
        });
    }
    private static JMenu findMenu(JMenuBar bar, String title) {
        for (int i = 0; i < bar.getMenuCount(); i++) if (bar.getMenu(i).getText().equals(title)) return bar.getMenu(i);
        throw new AssertionError("Missing menu " + title);
    }
    private static JMenuItem findItem(JMenu menu, String command) {
        for (Component child : menu.getMenuComponents()) if (child instanceof JMenuItem item && command.equals(item.getActionCommand())) return item;
        throw new AssertionError("Missing command " + command);
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        report.append("PASS ").append(message).append('\n'); System.out.println("PASS " + message);
    }
    private static void waitFor(Callable<Boolean> condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < until) { if (edt(condition)) return; Thread.sleep(20); }
        throw new AssertionError("Focus was not acquired");
    }
    private static <T> T edt(Callable<T> task) throws Exception {
        FutureTask<T> result = new FutureTask<>(task); SwingUtilities.invokeAndWait(result); return result.get();
    }
}
