package dev.turboj.app;

import dev.turboj.config.Settings;
import dev.turboj.commandj.core.*;
import dev.turboj.commandj.keymap.*;
import dev.turboj.commandj.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.*;

public final class CommandJSmoke {
    public static void main(String[] args) throws Exception {
        Path output = Path.of("app/target/commandj-smoke").toAbsolutePath();
        Files.createDirectories(output);
        System.setProperty("turboj.config", Files.createTempDirectory(output, "config-")
                .toString());
        System.setProperty("apple.laf.useScreenMenuBar", "false");
        TurboFrame f = edt(() -> {
            var w = new TurboFrame(new Settings(), Map.of());
            w.setVisible(true);
            w.toFront();
            w.editor().requestFocusInWindow();
            return w;
        });

        try {
            waitFor(() -> f.editor().isFocusOwner());
            check(edt(() -> f.commandKeymaps().selectedId().equals("intellij-macos")),
                    "new-install IntelliJ profile");
            AtomicInteger invoked = new AtomicInteger();
            edt(() -> {
                f.registerCommand("smoke.action", "Smoke action", "Testing",
                        invoked::incrementAndGet, c -> Availability.available());
                f.commandKeymaps().replaceBindings("smoke.action", java.util.List.of(KeyBinding
                        .of("META+K, META+U")));
                return null;
            });
            press(f, KeyEvent.VK_K, KeyEvent.META_DOWN_MASK);
            check(invoked.get() == 0, "chord waits for second stroke");
            press(f, KeyEvent.VK_U, KeyEvent.META_DOWN_MASK);
            waitFor(() -> invoked.get() == 1);
            check(invoked.get() == 1, "chord invokes one registry action");
            edt(() -> {
                f.editor().setText("abc");
                f.editor().gotoOffset(3);
                f.editor().replaceSelection("d");
                f.commandKeymaps().replaceBindings("undo", java.util.List.of(KeyBinding
                        .of("META+ALT+Z")));
                return null;
            });
            press(f, KeyEvent.VK_Z, KeyEvent.META_DOWN_MASK);
            check(edt(() -> f.editor().getText().equals("abcd")), "old Undo binding is removed");
            press(f, KeyEvent.VK_Z, KeyEvent.META_DOWN_MASK | KeyEvent.ALT_DOWN_MASK);
            check(edt(() -> f.editor().getText().equals("abc")), "custom Undo binding works");
            press(f, KeyEvent.VK_A, KeyEvent.META_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK);
            waitFor(() -> dialog(CommandPaletteDialog.class) != null);
            edt(() -> {
                var d = dialog(CommandPaletteDialog.class);
                d.searchField().setText("smoke action");
                return null;
            });
            waitFor(() -> dialog(CommandPaletteDialog.class).results().getModel().getSize() == 1);
            edt(() -> {
                var d = dialog(CommandPaletteDialog.class);
                d.searchField().postActionEvent();
                return null;
            });
            waitFor(() -> invoked.get() == 2);
            check(invoked.get() == 2, "palette search and Enter execute registry command");
            edt(() -> {
                f.showKeymapEditor();
                return null;
            });
            waitFor(() -> dialog(KeymapEditorDialog.class) != null);
            edt(() -> {
                var d = dialog(KeymapEditorDialog.class);
                d.searchField().setText("smoke action");
                return null;
            });
            waitFor(() -> dialog(KeymapEditorDialog.class).commandsTable().getRowCount() == 1);
            check(true, "keymap dialog searches live registry");
            edt(() -> {
                dialog(KeymapEditorDialog.class).dispose();
                f.commandKeymaps().select("vscode-macos");
                f.applyTheme(dev.turboj.ui.themes.Theme.bundled("light"));
                return null;
            });
            check(edt(() -> f.commandKeymaps().selectedId().equals("vscode-macos")),
                    "theme changes leave keymap unchanged");
            waitFor(() -> Files.exists(Settings.directory().resolve("commandj-keymap.json")));
            var restored = new KeymapManager();
            KeymapJson.read(restored, Files.readString(Settings.directory()
                    .resolve("commandj-keymap.json")));
            check(restored.selectedId().equals("vscode-macos"), "profile persisted atomically");
            edt(() -> {
                var image = new java.awt.image.BufferedImage(f.getWidth(), f.getHeight(),
                        java.awt.image.BufferedImage.TYPE_INT_RGB);
                var g = image.createGraphics();
                f.paint(g);
                g.dispose();
                javax.imageio.ImageIO.write(image, "png", output.resolve("native-commandj.png")
                        .toFile());
                return null;
            });
            Files.writeString(output.resolve("report.txt"),
                    "PASS native frame: chord, palette, custom binding, keymap dialog, theme independence, persistence.\n");
        } finally {
            edt(() -> {
                for (Window w : Window.getWindows())
                    if (w != f && w.isDisplayable())
                        w.dispose();
                f.shutdown();
                f.dispose();
                return null;
            });
        }
    }

    private static void press(TurboFrame f, int code, int modifiers) throws Exception {
        edt(() -> {
            f.toFront();
            f.editor().requestFocusInWindow();
            return null;
        });
        waitFor(() -> f.editor().isFocusOwner());
        edt(() -> {
            KeyboardFocusManager manager = KeyboardFocusManager.getCurrentKeyboardFocusManager();
            manager.dispatchEvent(new KeyEvent(f.editor(), KeyEvent.KEY_PRESSED, System
                    .currentTimeMillis(), modifiers, code, KeyEvent.CHAR_UNDEFINED));
            manager.dispatchEvent(new KeyEvent(f.editor(), KeyEvent.KEY_RELEASED, System
                    .currentTimeMillis(), modifiers, code, KeyEvent.CHAR_UNDEFINED));
            return null;
        });
    }

    private static <T extends EmbeddedDialog> T dialog(Class<T> type) {
        for (Window w : Window.getWindows())
            for (EmbeddedDialog d : OverlayHost.dialogs(w))
                if (type.isInstance(d) && d.isVisible())
                    return type.cast(d);
        return null;
    }

    private static void check(boolean value, String label) {
        if (!value)
            throw new AssertionError(label);
        System.out.println("PASS " + label);
    }

    private static void waitFor(Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);

        while (System.nanoTime() < deadline) {
            if (edt(condition))
                return;
            Thread.sleep(20);
        }
        throw new AssertionError("Timed out");
    }

    private static <T> T edt(Callable<T> action) throws Exception {
        var task = new FutureTask<T> (action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }
}
