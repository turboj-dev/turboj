package dev.turboj.app;

import dev.turboj.commandj.swing.AsciiPalette;
import dev.turboj.config.Settings;
import dev.turboj.refactorj.api.JavaQuickActions.Proposal;
import dev.turboj.ui.themes.Theme;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.*;
import javax.imageio.ImageIO;
import javax.swing.*;

/** Real contextual popup and Swing key dispatch, with an isolated unresolved-type source. */
public final class QuickFixPopupSmoke {
    private static TurboFrame frame;
    public static void main(String[] args) throws Exception {
        for (String profile : new String[]{"turboj-macos", "eclipse-macos"}) exercise(profile);
    }
    private static void exercise(String profile) throws Exception {
        // RefactorJ deliberately refuses symbolic workspace roots; macOS /var aliases /private/var.
        Path directory = Files.createTempDirectory("turboj-quick-fix-").toRealPath();
        System.setProperty("turboj.config",directory.resolve("preferences").toString());
        Path source = directory.resolve("Demo.java");
        String text = "public class Demo {\n    void example() {\n        Calculator calculator = new Calculator();\n    }\n}\n";
        Files.writeString(source,text);
        try {
            frame = edt(() -> {
                UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName()); // Same bootstrap as TurboJ.main.
                Settings settings = new Settings(); settings.theme = "vscode-monokai"; settings.keymapProfile = "turboj-macos";
                TurboFrame result = new TurboFrame(settings,Map.of()); result.setVisible(true); result.toFront(); result.openPath(source); return result;
            });
            await(() -> source.equals(frame.currentFile()));
            {
                edt(() -> { frame.commandKeymaps().select(profile); frame.editor().gotoOffset(text.indexOf("Calculator") + 3);
                    frame.editor().requestFocusInWindow(); return null; });
                await(() -> frame.editor().isFocusOwner());
                keyBurst(KeyEvent.VK_1, KeyEvent.META_DOWN_MASK, 2);
                await(() -> named("quick-actions-heading") instanceof JLabel label && label.getText().equals("Quick Assists"));
                if (edt(() -> frame.editor().caretOffset()) != text.indexOf("Calculator") + 3)
                    throw new AssertionError("Repeated invocation moved the original caret");
                key(KeyEvent.VK_1,KeyEvent.META_DOWN_MASK);
                await(() -> named("quick-actions-heading") instanceof JLabel label && label.getText().startsWith("Quick Fixes"));
                await(() -> named("quick-actions-filter") instanceof JTextField field && field.isFocusOwner());
                edt(() -> {
                    JList<?> list = (JList<?>) named("quick-actions-proposals"); boolean found = false;
                    for (int i = 0; i < list.getModel().getSize(); i++)
                        if (((Proposal)list.getModel().getElementAt(i)).title().contains("Create class 'Calculator'")) found = true;
                    if (!found) throw new AssertionError("Missing contextual Create Calculator proposal for " + profile);
                    return null;
                });
                for (String theme : new String[]{"classic","vscode-monokai"}) edt(() -> {
                    frame.applyTheme(Theme.bundled(theme));
                    for (String name : new String[]{"quick-actions-filter","quick-actions-proposals","quick-actions-explanation"}) {
                        Component control = named(name);
                        if (AsciiPalette.contrast(control.getForeground(),control.getBackground()) < 4.5) throw new AssertionError(name + " contrast: " + theme);
                    }
                    JList<?> list = (JList<?>)named("quick-actions-proposals");
                    if (AsciiPalette.contrast(list.getSelectionForeground(),list.getSelectionBackground()) < 4.5) throw new AssertionError("Selected proposal contrast");
                    return null;
                });
                key(KeyEvent.VK_1, KeyEvent.META_DOWN_MASK);
                await(() -> named("quick-actions-heading") instanceof JLabel label && label.getText().equals("Quick Assists"));
                key(KeyEvent.VK_1, KeyEvent.META_DOWN_MASK);
                await(() -> named("quick-actions-heading") instanceof JLabel label && label.getText().startsWith("Quick Fixes"));
                key(KeyEvent.VK_ESCAPE,0); await(() -> frame.editor().isFocusOwner());
                if (edt(() -> frame.editor().caretOffset()) != text.indexOf("Calculator") + 3)
                    throw new AssertionError("Escape changed an explicitly selected problem location");
                System.out.println("PASS rapid pending invocation and visible fix/assist toggles preserve caret");
                System.out.println("PASS " + profile + ": Cmd+1 creates contextual proposals; theme switch and selection contrast >=4.5:1; Escape restores focus");
            }
            edt(() -> { frame.editor().gotoOffset(text.indexOf(';') + 1); return null; });
            keyBurst(KeyEvent.VK_1, KeyEvent.META_DOWN_MASK, 2);
            await(() -> named("quick-actions-heading") instanceof JLabel label && label.getText().equals("Quick Assists"));
            key(KeyEvent.VK_1,KeyEvent.META_DOWN_MASK);
            await(() -> named("quick-actions-filter") instanceof JTextField field && field.isFocusOwner());
            edt(() -> {
                JList<?> list = (JList<?>) named("quick-actions-proposals"); boolean found = false;
                for (int i = 0; i < list.getModel().getSize(); i++)
                    if (((Proposal)list.getModel().getElementAt(i)).title().contains("Create class 'Calculator'")) found = true;
                if (!found) throw new AssertionError("Caret after the statement must reach the line's Create Calculator fix");
                if (frame.editor().caretOffset() != text.lastIndexOf("Calculator"))
                    throw new AssertionError("Caret must move to the closest problem, was " + frame.editor().caretOffset());
                return null;
            });
            key(KeyEvent.VK_ESCAPE,0); await(() -> frame.editor().isFocusOwner());
            if (edt(() -> frame.editor().caretOffset()) != text.lastIndexOf("Calculator"))
                throw new AssertionError("Escape must keep the caret on the problem, as in Eclipse");
            System.out.println("PASS Eclipse-style Cmd+1 after the statement jumps to the closest problem on its line");
            edt(() -> { frame.editor().gotoOffset(text.indexOf("    }\n}")); return null; });
            key(KeyEvent.VK_1,KeyEvent.META_DOWN_MASK);
            await(() -> named("quick-actions-filter") instanceof JTextField field && field.isFocusOwner());
            edt(() -> {
                JTextArea details = (JTextArea)named("quick-actions-explanation");
                if (!details.getText().contains("No applicable")) throw new AssertionError("Expected empty-caret explanation");
                if (AsciiPalette.contrast(details.getForeground(),details.getBackground()) < 4.5) throw new AssertionError("Unreadable empty state");
                return null;
            });
            Path image = Path.of("app/target/quick-fix-popup-" + profile + ".png");
            BufferedImage capture = edt(() -> {
                BufferedImage b = new BufferedImage(frame.getWidth(),frame.getHeight(),BufferedImage.TYPE_INT_RGB);
                Graphics2D g = b.createGraphics(); frame.printAll(g); g.dispose(); return b;
            });
            ImageIO.write(capture,"png",image.toFile());
            System.out.println("PASS Monokai empty state readable; caret on a problem-free line has no diagnostic proposal. Screenshot: " + image);
            key(KeyEvent.VK_ESCAPE, 0); await(() -> frame.editor().isFocusOwner());
            edt(() -> { frame.editor().gotoOffset(text.indexOf("Calculator") + 2); return null; });
            key(KeyEvent.VK_1, KeyEvent.META_DOWN_MASK);
            await(() -> named("quick-actions-proposals") instanceof JList<?>);
            int target = edt(() -> {
                JList<?> list = (JList<?>)named("quick-actions-proposals");
                for (int i = 0; i < list.getModel().getSize(); i++)
                    if (((Proposal)list.getModel().getElementAt(i)).title().contains("Create class 'Calculator'")) return i;
                throw new AssertionError("Create class missing");
            });
            int selected = edt(() -> ((JList<?>)named("quick-actions-proposals")).getSelectedIndex());
            for (int i = selected; i < target; i++) key(KeyEvent.VK_DOWN, 0);
            for (int i = selected; i > target; i--) key(KeyEvent.VK_UP, 0);
            key(KeyEvent.VK_ENTER, 0);
            Path generated = directory.resolve("Calculator.java");
            await(() -> generated.equals(frame.currentFile()) || named("first-launch-quick-class-finish") != null);
            if (edt(() -> named("first-launch-quick-class-finish") != null)) {
                for (int i = 0; i < 30 && !edt(() -> named("first-launch-quick-class-finish").isFocusOwner()); i++)
                    key(KeyEvent.VK_TAB, 0);
                if (!edt(() -> named("first-launch-quick-class-finish").isFocusOwner()))
                    throw new AssertionError("Create class Finish is not keyboard reachable");
                key(KeyEvent.VK_ENTER, 0);
            }
            await(() -> generated.equals(frame.currentFile()) && frame.editor().isFocusOwner());
            if (!Files.readString(generated).contains("class Calculator")) throw new AssertionError("Generated class missing");
            System.out.println("PASS " + profile + ": arrows/Return apply real Create Calculator; generated file receives editor focus");
        } finally { if (frame != null) edt(() -> { frame.shutdown(); frame.dispose(); return null; }); }
    }
    private static Component named(String name) { return named(frame,name); }
    private static Component named(Component c,String name) {
        if (name.equals(c.getName()) && c.isShowing()) return c;
        if (c instanceof Container container) for (Component child : container.getComponents()) {
            Component match = named(child,name); if (match != null) return match;
        }
        return null;
    }
    private static void keyBurst(int code, int modifiers, int count) throws Exception {
        NativeWindowTestSupport.beforeKey(frame);
        edt(() -> {
            Component focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
            if (focus == null) throw new AssertionError("No keyboard focus");
            for (int i = 0; i < count; i++) for (int kind : new int[]{KeyEvent.KEY_PRESSED, KeyEvent.KEY_RELEASED})
                Toolkit.getDefaultToolkit().getSystemEventQueue().postEvent(
                        new KeyEvent(focus, kind, System.currentTimeMillis(), modifiers, code, KeyEvent.CHAR_UNDEFINED));
            return null;
        });
    }
    private static void key(int code,int modifiers) throws Exception {
        NativeWindowTestSupport.beforeKey(frame);
        edt(() -> {
            var manager = KeyboardFocusManager.getCurrentKeyboardFocusManager(); Component focus = manager.getFocusOwner();
            if (focus == null) throw new AssertionError("No keyboard focus");
            for (int kind : new int[]{KeyEvent.KEY_PRESSED,KeyEvent.KEY_RELEASED})
                Toolkit.getDefaultToolkit().getSystemEventQueue().postEvent(
                        new KeyEvent(focus,kind,System.currentTimeMillis(),modifiers,code,KeyEvent.CHAR_UNDEFINED));
            return null;
        });
        edt(() -> null);
        // Let native focus traversal settle before addressing the next control, as in the
        // first-launch keyboard driver. Never assign focus to a dialog control from the test.
        Thread.sleep(65);
    }
    private static void await(Callable<Boolean> check) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (System.nanoTime() < end) {
            if (edt(() -> { NativeWindowTestSupport.recover(frame); return check.call(); })) return;
            Thread.sleep(25);
        }
        throw new AssertionError("Timed out waiting for contextual popup/focus; file=" + edt(() -> frame.currentFile())
                + "; focus=" + edt(() -> KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner())
                + "; inspection=" + edt(QuickFixPopupSmoke::inspectionState)
                + "; status=" + edt(() -> frame.statusMessages()));
    }
    private static String inspectionState() throws Exception {
        var field = TurboFrame.class.getDeclaredField("quickFix"); field.setAccessible(true);
        Object controller = field.get(frame); var result = new StringBuilder();
        for (String name : new String[]{"epoch", "inspecting", "contextualInspection", "pendingToggle", "assists", "inspection"}) {
            var member = controller.getClass().getDeclaredField(name); member.setAccessible(true);
            result.append(name).append('=').append(member.get(controller)).append(';');
        }
        var pending = frame.intelligence().getClass().getDeclaredField("semanticRequest"); pending.setAccessible(true);
        result.append("semanticRequest=").append(pending.get(frame.intelligence()));
        result.append(";heading=").append(named("quick-actions-heading"));
        return result.toString();
    }
    private static <T> T edt(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action); SwingUtilities.invokeAndWait(task); return task.get();
    }
}
