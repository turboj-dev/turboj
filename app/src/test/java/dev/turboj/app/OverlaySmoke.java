package dev.turboj.app;

import dev.turboj.config.Settings;
import dev.turboj.commandj.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import javax.swing.*;

/** Real macOS Swing test: internal nesting, input, Escape, routing, resize and file browsing. */
public final class OverlaySmoke {
    public static void main(String[] args) throws Exception {
        Path output = Path.of("app/target/overlay-smoke").toAbsolutePath();
        Files.createDirectories(output);
        System.setProperty("turboj.config", Files.createTempDirectory(output, "config-")
                .toString());
        System.setProperty("apple.laf.useScreenMenuBar", "false");
        TurboFrame frame = edt(() -> {
            var f = new TurboFrame(new Settings(), Map.of());
            f.setVisible(true);
            f.toFront();
            f.editor().setText("class Demo {}\n");
            f.editor().requestFocusInWindow();
            return f;
        });

        try {
            waitFor(() -> frame.editor().isFocusOwner());
            KeymapEditorDialog keymap = edt(() -> {
                frame.showKeymapEditor();
                return (KeymapEditorDialog) OverlayHost.dialogs(frame).getLast();
            });
            edt(() -> {
                keymap.searchField().requestFocusInWindow();
                return null;
            });
            waitFor(() -> keymap.searchField().isFocusOwner());
            check(edt(() -> oneWindow(frame) && SwingUtilities.isDescendingFrom(keymap, frame)),
                    "keymap belongs to only native frame");
            CompletableFuture<Integer> answer = new CompletableFuture<> ();
            SwingUtilities.invokeLater(() -> answer.complete(EmbeddedOptionPane
                    .showConfirmDialog(keymap.searchField(), "Nested confirmation", "Nested",
                    JOptionPane.OK_CANCEL_OPTION)));
            waitFor(() -> OverlayHost.dialogs(frame).size() == 2);
            check(edt(() -> oneWindow(frame) && keymap.getGlassPane().isVisible()),
                    "nested confirmation blocks parent inside same frame");
            key(KeyEvent.VK_N, KeyEvent.META_DOWN_MASK);
            key(KeyEvent.VK_TAB, 0);
            check(edt(() -> SwingUtilities.isDescendingFrom(KeyboardFocusManager
                    .getCurrentKeyboardFocusManager().getFocusOwner(), OverlayHost.dialogs(frame)
                    .getLast())), "Tab keeps focus in active internal confirmation");
            check(edt(() -> frame.editor().getText().equals("class Demo {}\n")),
                    "overlay context rejects editor command");
            key(KeyEvent.VK_ESCAPE, 0);
            check(answer.get(5, TimeUnit.SECONDS) == JOptionPane.CLOSED_OPTION,
                    "Escape closes nested confirmation only");
            waitFor(() -> OverlayHost.dialogs(frame).size() == 1 && focusedInside(keymap));
            check(edt(() -> keymap.isVisible()), "nested close restores parent focus");
            key(KeyEvent.VK_ESCAPE, 0);
            waitFor(() -> !OverlayHost.active(frame) && frame.editor().isFocusOwner());
            check(true, "Escape closes parent and restores source caret focus");
            CompletableFuture<String> input = new CompletableFuture<> ();
            SwingUtilities.invokeLater(() -> input.complete(EmbeddedOptionPane
                    .showInputDialog(frame, "Name:", "old")));
            waitFor(() -> OverlayHost.active(frame));
            edt(() -> {
                var pane = (JOptionPane) OverlayHost.dialogs(frame).getLast().getContentPane();
                pane.setInputValue("new name");
                pane.setValue(JOptionPane.OK_OPTION);
                return null;
            });
            check("new name".equals(input.get(5, TimeUnit.SECONDS)),
                    "input completion returns while EDT remains responsive");
            Path fixture = Files.createTempDirectory(output, "files-");
            Files.writeString(fixture.resolve("Example.java"), "class Example {}\n");
            CompletableFuture<Integer> selected = new CompletableFuture<> ();
            EmbeddedFileChooser chooser = edt(() -> new EmbeddedFileChooser(fixture.toFile()));
            SwingUtilities.invokeLater(() -> selected.complete(chooser.showOpenDialog(frame)));
            waitFor(() -> OverlayHost.active(frame));
            waitFor(() -> find(OverlayHost.dialogs(frame).getLast(), JList.class,
                    "Files and directories").getModel().getSize() == 1);
            edt(() -> {
                var dialog = OverlayHost.dialogs(frame).getLast();
                find(dialog, JTextField.class, "File name").setText("Example.java");
                button(dialog, "Open").doClick();
                return null;
            });
            check(selected.get(5, TimeUnit.SECONDS) == JFileChooser.APPROVE_OPTION && chooser
                    .getSelectedFile().toPath().equals(fixture.resolve("Example.java")),
                    "internal file browser selects real file");
            for (String theme : List.of("classic", "dark", "light")) {
                edt(() -> {
                    frame.applyTheme(dev.turboj.ui.themes.Theme.bundled(theme));
                    frame.showKeymapEditor();
                    frame.setSize(760, 560);
                    return null;
                });
                waitFor(() -> {
                    var d = OverlayHost.dialogs(frame).getLast();
                    return d.getX() + d.getWidth() <= frame.getRootPane().getLayeredPane()
                            .getWidth() && d.getY() + d.getHeight() <= frame.getRootPane()
                            .getLayeredPane().getHeight();
                });
                check(edt(() -> oneWindow(frame)), "one-window " + theme + " overlay after resize");
                check(edt(() -> {
                    frame.applyTheme(dev.turboj.ui.themes.Theme.bundled(theme));
                    var dialog = OverlayHost.dialogs(frame).getLast();
                    dialog.doLayout();
                    var palette = dev.turboj.ui.AsciiChrome.editor(dev.turboj.ui.themes.Theme.bundled(theme));
                    return !JInternalFrame.class.isAssignableFrom(dialog.getClass())
                            && dialog.getBorder() instanceof AsciiBorder border && border.title().equals(dialog.getTitle())
                            && dialog.getBackground().equals(palette.background())
                            && dialog.getContentPane().getBackground().equals(palette.background())
                            && dialog.getFont().getFamily().equals(Font.MONOSPACED)
                            && dialog.getAccessibleContext().getAccessibleRole() == javax.accessibility.AccessibleRole.DIALOG
                            && dialog.closeButton().isFocusable();
                }), "editor surface and accessible title survive live theme refresh: " + theme);
                edt(() -> {
                    var image = new java.awt.image.BufferedImage(frame.getWidth(), frame
                            .getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
                    var g = image.createGraphics();
                    frame.paint(g);
                    g.dispose();
                    javax.imageio.ImageIO.write(image, "png", output.resolve(theme + ".png")
                            .toFile());
                    OverlayHost.dialogs(frame).getLast().dispose();
                    return null;
                });
                waitFor(() -> frame.editor().isFocusOwner());
                edt(() -> { frame.setSize(1200, 760); frame.getJMenuBar().getMenu(1).doClick(); return null; });
                waitFor(() -> frame.getJMenuBar().getMenu(1).getPopupMenu().isShowing());
                check(edt(() -> oneWindow(frame)), "menu popup remains inside main frame: " + theme);
                edt(() -> {
                    var image = new java.awt.image.BufferedImage(frame.getWidth(), frame.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
                    var graphics = image.createGraphics(); frame.paint(graphics); graphics.dispose();
                    javax.imageio.ImageIO.write(image, "png", output.resolve("menu-" + theme + ".png").toFile());
                    MenuSelectionManager.defaultManager().clearSelectedPath(); return null;
                });
            }
            Files.writeString(output.resolve("report.txt"),
                    "PASS: internal dialogs, nested modal event loop, Escape top-only, focus restoration, command routing, input, real file selection, three themes, resize, one native window.\n");
        } finally {
            edt(() -> {
                for (var d : OverlayHost.dialogs(frame))
                    d.dispose();
                frame.shutdown();
                frame.dispose();
                return null;
            });
        }
    }

    private static boolean focusedInside(Component parent) {
        Component focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
        return focus != null && SwingUtilities.isDescendingFrom(focus, parent);
    }

    private static boolean oneWindow(TurboFrame frame) {
        return Arrays.stream(Window.getWindows()).filter(Window::isVisible).count() == 1 && frame
                .isVisible();
    }

    private static void key(int code, int modifiers) throws Exception {
        edt(() -> {
            Component focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();

            if (focus == null)
                throw new AssertionError("no focused component");
            var manager = KeyboardFocusManager.getCurrentKeyboardFocusManager();
            manager.dispatchEvent(new KeyEvent(focus, KeyEvent.KEY_PRESSED, System
                    .currentTimeMillis(), modifiers, code, KeyEvent.CHAR_UNDEFINED));
            manager.dispatchEvent(new KeyEvent(focus, KeyEvent.KEY_RELEASED, System
                    .currentTimeMillis(), modifiers, code, KeyEvent.CHAR_UNDEFINED));
            return null;
        });
    }

    private static JButton button(Container root, String text) {
        for (Component c : root.getComponents()) {
            if (c instanceof JButton b && b.getText().equals(text))
                return b;
            if (c instanceof Container nested) {
                JButton b = button(nested, text);

                if (b != null)
                    return b;
            }
        }
        return null;
    }

    private static <T extends Component> T find(Container root, Class<T> type, String name) {
        for (Component c : root.getComponents()) {
            if (type.isInstance(c) && c.getAccessibleContext() != null && name.equals(c
                    .getAccessibleContext().getAccessibleName()))
                return type.cast(c);
            if (c instanceof Container nested) {
                T result = find(nested, type, name);

                if (result != null)
                    return result;
            }
        }
        return null;
    }

    private static void check(boolean condition, String text) {
        if (!condition)
            throw new AssertionError(text);
        System.out.println("PASS " + text);
    }

    private static void waitFor(Callable<Boolean> condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);

        while (System.nanoTime() < end) {
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
