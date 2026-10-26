package dev.turboj.app;

import dev.turboj.commandj.swing.OverlayHost;
import dev.turboj.config.Settings;
import dev.turboj.refactorj.api.JavaIntelligence.Completion;
import dev.turboj.ui.FindReplaceDialog;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.*;
import javax.swing.*;

/** Real window, TurboJ macOS keymap, a plain project: the shortcuts a MacBook user reported, as printed on the sheet. */
public final class TurboJKeysSmoke {
    private static TurboFrame frame;
    private static final int OPTION = KeyEvent.ALT_DOWN_MASK, CMD = KeyEvent.META_DOWN_MASK, SHIFT = KeyEvent.SHIFT_DOWN_MASK;

    public static void main(String[] args) throws Exception {
        Path session = Files.createTempDirectory("turboj-keys-").toRealPath();
        System.setProperty("turboj.config", session.resolve("preferences").toString());
        Path project = Files.createDirectories(session.resolve("hello-world"));
        Path sources = Files.createDirectories(project.resolve("src/main/java"));
        Files.writeString(sources.resolve("Calculator.java"), "public class Calculator {}\n");
        Path main = sources.resolve("HelloWorld.java");
        String typing = "public class HelloWorld {\n    public static void main(String[] args) {\n        Calculator calculator = new Calcu\n    }\n}\n";
        Files.writeString(main, typing);
        try {
            frame = edt(() -> {
                Settings settings = new Settings(); // TurboJ macOS is the default
                TurboFrame result = new TurboFrame(settings, Map.of()); result.setVisible(true); result.toFront(); return result;
            });
            check(edt(() -> frame.commandKeymaps().selectedId()).equals("turboj-macos"), "TurboJ macOS is the default keymap");
            edt(() -> { frame.openPath(project); return null; });
            await("plain project", () -> frame.projectStatus().contains("hello-world"));

            key(KeyEvent.VK_0, CMD);
            await("explorer shown", () -> frame.mavenProjects().panel().isShowing());
            key(KeyEvent.VK_0, CMD);
            await("explorer hidden", () -> !frame.mavenProjects().panel().isShowing());
            pass("⌘0 shows and hides the explorer of a plain Java project");

            open(main, typing.indexOf("new Calcu") + 9);
            key(KeyEvent.VK_SPACE, OPTION);
            await("⌥Space completions", () -> named("completion-choices") instanceof JList<?> list && list.getModel().getSize() > 0
                    && ((Completion) list.getModel().getElementAt(0)).text().startsWith("Calculator"));
            key(KeyEvent.VK_ESCAPE, 0);
            pass("⌥Space opens the completion list (⌃Space belongs to the macOS input-source switch)");

            edt(() -> { frame.editor().setText("public class HelloWorld {\n    void f() {\n        int x = 1\n    }\n}\n"); return null; });
            edt(() -> { frame.editor().gotoOffset(frame.editor().getText().indexOf("= 1") + 3); return null; });
            await("editor focus", () -> frame.editor().isFocusOwner());
            key(KeyEvent.VK_ENTER, CMD | SHIFT);
            await("⇧⌘↩ semicolon", () -> frame.editor().getText().contains("int x = 1;"));
            pass("⇧⌘↩ completes the statement");

            String before = edt(frame::buildStatus);
            key(KeyEvent.VK_B, CMD | OPTION);
            await("⌥⌘B compiles", () -> !frame.buildStatus().equals(before) || frame.console().text().contains("Compil"));
            pass("⌥⌘B runs Fast Compile (status: " + edt(frame::buildStatus) + ")");

            edt(() -> { frame.editor().setText("alpha beta alpha\nalpha\n"); frame.editor().gotoOffset(0); return null; });
            await("editor focus", () -> frame.editor().isFocusOwner());
            key(KeyEvent.VK_F, CMD | OPTION);
            await("Find and Replace", () -> replaceDialog() != null && replaceDialog().findField().isFocusOwner());
            edt(() -> { replaceDialog().findField().setText("alpha"); replaceDialog().replaceField().setText("gamma");
                replaceDialog().replaceAll(); return null; });
            check(edt(() -> frame.editor().getText()).equals("gamma beta gamma\ngamma\n"), "Replace All replaced every match");
            key(KeyEvent.VK_ESCAPE, 0);
            await("dialog closed", () -> replaceDialog() == null);
            edt(() -> { frame.editor().requestFocusInWindow(); return null; });
            await("editor focus", () -> frame.editor().isFocusOwner());
            key(KeyEvent.VK_Z, CMD);
            await("one undo", () -> frame.editor().getText().equals("alpha beta alpha\nalpha\n"));
            pass("⌥⌘F opens Find and Replace; Replace All is one undoable edit");

            key(KeyEvent.VK_N, CMD);
            await("File ▸ New open", () -> {
                var path = MenuSelectionManager.defaultManager().getSelectedPath();
                return path.length >= 5 && path[3] instanceof JMenu menu && menu.getText().equals("New") && path[4].getComponent().isShowing();
            });
            for (int i = 0; i < 4 && edt(() -> MenuSelectionManager.defaultManager().getSelectedPath().length) > 0; i++) key(KeyEvent.VK_ESCAPE, 0);
            await("menus closed", () -> MenuSelectionManager.defaultManager().getSelectedPath().length == 0);
            pass("⌘N opens the File ▸ New menu; Escape closes it");

            edt(() -> { frame.editor().setText("    word other\n"); frame.editor().gotoOffset(8); return null; });
            await("editor focus after the menu", () -> { if (!frame.editor().isFocusOwner()) frame.editor().requestFocusInWindow(); return frame.editor().isFocusOwner(); });
            key(KeyEvent.VK_LEFT, CMD);
            await("⌘← smart home", () -> frame.editor().caretOffset() == 4);
            key(KeyEvent.VK_RIGHT, CMD);
            await("⌘→ smart end", () -> frame.editor().caretOffset() == frame.editor().getText().indexOf('\n'));
            edt(() -> { frame.editor().gotoOffset(4); return null; });
            key(KeyEvent.VK_DELETE, OPTION); // ⌥fn⌫ on a MacBook
            await("⌥fn⌫ deletes the next word", () -> frame.editor().getText().equals("    other\n"));
            pass("⌘← / ⌘→ go to line start and end; ⌥fn⌫ deletes the next word");
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }

    private static FindReplaceDialog replaceDialog() {
        return OverlayHost.dialogs(frame).stream().filter(FindReplaceDialog.class::isInstance).map(FindReplaceDialog.class::cast)
                .filter(Component::isShowing).findFirst().orElse(null);
    }

    private static void open(Path file, int caret) throws Exception {
        edt(() -> { frame.openPath(file); return null; });
        await("open " + file.getFileName(), () -> file.equals(frame.currentFile()));
        await("first check of " + file.getFileName(), () -> !frame.buildStatus().contains("CHECKING")
                && !frame.buildStatus().contains("COMPILING") && frame.currentResult() != null);
        edt(() -> { frame.editor().gotoOffset(caret); frame.editor().requestFocusInWindow(); return null; });
        await("editor focus", () -> frame.editor().isFocusOwner());
    }

    private static Component named(String name) { return named(frame, name); }
    private static Component named(Component c, String name) {
        if (name.equals(c.getName()) && c.isShowing()) return c;
        if (c instanceof Container container) for (Component child : container.getComponents()) {
            Component match = named(child, name); if (match != null) return match;
        }
        if (c instanceof Window window) for (Window owned : window.getOwnedWindows()) {
            Component match = named(owned, name); if (match != null) return match;
        }
        return null;
    }

    /** Posted, not dispatched: a dialog the key opens must not block the event that opened it. */
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
    private static void pass(String message) { System.out.println("PASS " + message); }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void await(String what, Callable<Boolean> check) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
        while (System.nanoTime() < end) { if (edt(check)) return; Thread.sleep(25); }
        throw new AssertionError("Timed out waiting for " + what + "; status " + edt(frame::buildStatus) + "; focus "
                + edt(() -> String.valueOf(KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner()).replaceAll(",.*", ""))
                + "; dialogs " + edt(() -> OverlayHost.dialogs(frame).stream().map(d -> d.getTitle() + (d.isShowing() ? "" : " (hidden)")).toList())
                + "; editor:\n" + edt(() -> frame.editor().getText()) + "; console:\n" + edt(() -> frame.console().text()));
    }
    private static <T> T edt(Callable<T> task) throws Exception {
        var future = new FutureTask<>(task); SwingUtilities.invokeAndWait(future); return future.get();
    }
}
