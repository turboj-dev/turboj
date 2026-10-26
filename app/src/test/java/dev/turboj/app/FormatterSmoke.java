package dev.turboj.app;

import dev.turboj.config.Settings;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.*;
import javax.swing.*;

/** Real window: IntelliJ IDEA's style by default, Classic 80 on demand, Undo/Redo in one step, Format on Save writes once. Needs the javac exports. */
public final class FormatterSmoke {
    private static TurboFrame frame;
    private static final String SOURCE = "public class Hello {\nint count=0;\npublic static void main(String[] args){System.out.println(\"hi\");}\n}\n";

    public static void main(String[] args) throws Exception {
        Path session = Files.createTempDirectory("turboj-formatter-").toRealPath();
        System.setProperty("turboj.config", session.resolve("preferences").toString());
        try {
            frame = edt(() -> { TurboFrame result = new TurboFrame(new Settings(), Map.of()); result.setVisible(true); result.toFront(); return result; });
            check(edt(() -> menuItem("Formatter", "IntelliJ IDEA").isSelected()), "IntelliJ IDEA's default style is TurboJ's default");
            check(edt(() -> !menuItemExists("Formatter", "JavaFMT (on hold)")), "JavaFMT is on hold and not offered");
            edt(() -> { frame.editor().setText(SOURCE); frame.editor().gotoOffset(0); frame.editor().requestFocusInWindow(); return null; });
            await("editor focus", () -> frame.editor().isFocusOwner());
            key(KeyEvent.VK_L, KeyEvent.META_DOWN_MASK | KeyEvent.ALT_DOWN_MASK);
            await("IntelliJ layout", () -> frame.editor().getText().contains("\n    int count = 0;\n\n    public static void main(String[] args) {\n        System.out.println(\"hi\");"));
            await("notice", () -> frame.statusMessages().contains("IntelliJ IDEA ·"));
            System.out.println("PASS ⌥⌘L formats with IntelliJ IDEA's style (4 spaces)");

            edt(() -> { menuItem("Formatter", "Classic 80").doClick(0); return null; });
            await("Classic selected", () -> menuItem("Formatter", "Classic 80").isSelected());
            edt(() -> { frame.editor().setText("class C {\n    int total(java.util.List<Integer> values) {\n        return values.stream().filter(value -> value > 0).mapToInt(Integer::intValue).sum();\n    }\n}\n");
                frame.editor().requestFocusInWindow(); return null; });
            await("editor focus", () -> frame.editor().isFocusOwner());
            String before = edt(() -> frame.editor().getText());
            key(KeyEvent.VK_L, KeyEvent.META_DOWN_MASK | KeyEvent.ALT_DOWN_MASK);
            await("Classic 80 layout", () -> frame.editor().getText().contains("\n                .filter(value -> value > 0)\n"));
            check(edt(() -> frame.editor().getText().lines().allMatch(line -> line.length() <= 80)), "Classic 80 keeps lines within 80 columns");
            System.out.println("PASS Code ▸ Formatter ▸ Classic 80 wraps the chain at 80 columns");
            key(KeyEvent.VK_Z, KeyEvent.META_DOWN_MASK);
            await("one undo", () -> frame.editor().getText().equals(before));
            key(KeyEvent.VK_Z, KeyEvent.META_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK);
            await("one redo", () -> frame.editor().getText().contains("\n                .filter(value -> value > 0)\n"));
            System.out.println("PASS one ⌘Z restores the source, one ⇧⌘Z reapplies the formatting");
            await("saved choice", () -> Settings.load().formatter.equals("classic80") && Settings.load().tabWidth == 4);
            System.out.println("PASS the choice and its 4-space indent persist");

            // Format on Save: the file on disk is the formatted text the editor shows, written once.
            Path file = session.resolve("Saved.java");
            Files.writeString(file, "class Saved{int a=1;}\n");
            edt(() -> { menuItem("Formatter", "AOSP").doClick(0); frame.openPath(file); return null; });
            await("open", () -> file.equals(frame.currentFile()));
            edt(() -> { frame.editor().gotoOffset(frame.editor().getText().length()); frame.editor().replaceSelection("// edit\n");
                if (!menuItem("Code", "Format on Save").isSelected()) menuItem("Code", "Format on Save").doClick(0);
                frame.editor().requestFocusInWindow(); return null; });
            await("editor focus", () -> frame.editor().isFocusOwner());
            key(KeyEvent.VK_S, KeyEvent.META_DOWN_MASK);
            await("saved formatted", () -> Files.readString(file).equals(frame.editor().getText()) && frame.editor().getText().contains("    int a = 1;"));
            Thread.sleep(800);
            check(edt(() -> frame.statusBar().history().stream().filter(m -> m.contains("Saved Saved.java")).count()) == 1, "saved once, no save loop");
            check(Files.readString(file).equals(edt(() -> frame.editor().getText())), "disk and editor agree after Format on Save");
            System.out.println("PASS Format on Save writes the formatted text once:\n" + Files.readString(file).strip());
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }

    private static boolean menuItemExists(String submenu, String text) {
        try { menuItem(submenu, text); return true; } catch (AssertionError missing) { return false; }
    }

    private static JMenuItem menuItem(String submenu, String text) {
        JMenuBar bar = frame.getJMenuBar();
        for (int i = 0; i < bar.getMenuCount(); i++) {
            JMenuItem found = find(bar.getMenu(i), submenu, text, bar.getMenu(i).getText().equals(submenu));
            if (found != null) return found;
        }
        throw new AssertionError("Missing " + submenu + " ▸ " + text);
    }

    private static JMenuItem find(JMenu menu, String submenu, String text, boolean inside) {
        for (Component child : menu.getMenuComponents()) {
            if (child instanceof JMenu nested) { JMenuItem found = find(nested, submenu, text, inside || nested.getText().equals(submenu)); if (found != null) return found; }
            else if (inside && child instanceof JMenuItem item && text.equals(item.getText())) return item;
        }
        return null;
    }

    private static void key(int code, int modifiers) throws Exception {
        edt(() -> {
            Component focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
            if (focus == null) throw new AssertionError("No keyboard focus");
            for (int kind : new int[]{KeyEvent.KEY_PRESSED, KeyEvent.KEY_RELEASED})
                Toolkit.getDefaultToolkit().getSystemEventQueue().postEvent(
                        new KeyEvent(focus, kind, System.currentTimeMillis(), modifiers, code, KeyEvent.CHAR_UNDEFINED));
            return null;
        });
        Thread.sleep(60);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); System.out.println("PASS " + message); }
    private static void await(String what, Callable<Boolean> check) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < end) { if (edt(check)) return; Thread.sleep(25); }
        throw new AssertionError("Timed out waiting for " + what + "; editor:\n" + edt(() -> frame.editor().getText())
                + "; console:\n" + edt(() -> frame.console().text()));
    }
    private static <T> T edt(Callable<T> task) throws Exception {
        var future = new FutureTask<>(task); SwingUtilities.invokeAndWait(future); return future.get();
    }
}
