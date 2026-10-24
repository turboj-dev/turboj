package dev.turboj.app;

import dev.turboj.commandj.swing.*;
import dev.turboj.config.Settings;
import dev.turboj.ui.AsciiChrome;
import dev.turboj.ui.AsciiMenuBar;
import dev.turboj.ui.MenuStyler;
import dev.turboj.ui.themes.Theme;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import javax.accessibility.AccessibleRole;
import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.*;
import javax.swing.text.JTextComponent;

/** Actual full-window ASCII chrome, compact menus and interactive I/O; run on a desktop explicitly. */
public final class AsciiChromeSmoke {
    private static final String SOURCE = """
            public class AsciiDemo {
                public static void main(String[] args) {
                    System.out.print("ASCII input> ");
                    String value = new java.util.Scanner(System.in).nextLine();
                    System.out.println("ASCII output: " + value);
                    System.err.println("ASCII diagnostic: readable");
                }
            }
            """;
    private static final StringBuilder report = new StringBuilder();
    private static int checks;

    public static void main(String[] args) throws Exception {
        Path directory = Path.of("app/target/ascii-chrome-smoke").toAbsolutePath();
        Files.createDirectories(directory);
        System.setProperty("turboj.config", Files.createTempDirectory(directory, "config-").toString());
        System.setProperty("apple.laf.useScreenMenuBar", "false");
        TurboFrame frame = edt(() -> {
            UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
            TurboFrame value = new TurboFrame(new Settings(), Map.of());
            value.setSize(1180, 780); value.setVisible(true); value.toFront();
            value.editor().setText(SOURCE); value.editor().requestFocusInWindow();
            return value;
        });
        try {
            for (String id : Theme.BUNDLED) verifyTheme(frame, id, directory);
            note("PASS " + checks + " checks: full ASCII editor/output, interactive stdin/stdout/stderr, all menu identities at 640px, keyboard submenu access, readable three-theme dialogs and one native frame.");
        } catch (Throwable failure) {
            note("FAIL " + failure);
            edt(() -> { screenshot(frame, directory.resolve("failure.png")); return null; });
            throw failure;
        } finally {
            Files.writeString(directory.resolve("report.txt"), report.toString());
            edt(() -> { for (var dialog : OverlayHost.dialogs(frame)) dialog.dispose(); frame.shutdown(); frame.dispose(); return null; });
        }
    }

    private static void verifyTheme(TurboFrame frame, String id, Path directory) throws Exception {
        Theme theme = Theme.bundled(id);
        edt(() -> {
            frame.setSize(1180, 780); frame.applyTheme(theme); frame.validate(); frame.console().clear();
            frame.editor().gotoOffset(SOURCE.indexOf("ASCII input"));
            frame.editor().requestFocusInWindow(); return null;
        });
        long version = edt(() -> frame.editor().version());
        int caret = edt(() -> frame.editor().caretOffset());
        var compilation = edt(() -> frame.compile(true, false)).get(30, TimeUnit.SECONDS);
        check(compilation.success(), id + " actual program compilation");
        waitFor(() -> frame.isRunning() && frame.console().text().contains("ASCII input> "), id + " input prompt");
        edt(() -> {
            frame.console().focusInput(); frame.console().setInputText("typed-" + id);
            JTextComponent input = frame.console().inputComponent();
            check(input.isShowing() && input.isEditable(), id + " input remains inside visible output");
            check(input.getAccessibleContext() != null, id + " program input accessible");
            check(MenuStyler.contrast(input.getForeground(), input.getBackground()) >= 4.5, id + " output/input contrast");
            screenshot(frame, directory.resolve("input-" + id + ".png"));
            frame.console().submitInput(); return null;
        });
        waitFor(() -> !frame.isRunning() && frame.console().text().contains("ASCII output: typed-" + id), id + " program completion");
        edt(() -> {
            check(frame.console().text().contains("ASCII diagnostic: readable"), id + " stderr remains visible");
            check(frame.editor().getText().equals(SOURCE) && frame.editor().version() == version && frame.editor().caretOffset() == caret, id + " I/O leaves editor state intact");
            check(singleFrame(frame), id + " one native window");
            check(hasAsciiBorder(frame.editor()), id + " editor uses ASCII pane boundary");
            check(hasAsciiBorder(frame.console()), id + " output uses ASCII pane boundary");
            inspectVisible(frame.getContentPane());
            screenshot(frame, directory.resolve("workspace-" + id + ".png"));
            return null;
        });
        compactMenus(frame, theme, directory);
        edt(() -> { frame.showKeymapEditor(); return null; });
        waitFor(() -> !OverlayHost.dialogs(frame).isEmpty(), id + " keymap surface");
        edt(() -> {
            EmbeddedDialog dialog = OverlayHost.dialogs(frame).getLast();
            check(dialog instanceof JPanel && SwingUtilities.isDescendingFrom(dialog, frame), id + " dialog is an editor surface");
            check(dialog.getAccessibleContext().getAccessibleRole() == AccessibleRole.DIALOG, id + " dialog accessibility role");
            check(dialog.closeButton().isFocusable() && dialog.closeButton().getAccessibleContext().getAccessibleName().contains(dialog.getTitle()), id + " close action accessible by keyboard");
            check(dialog.getBorder() instanceof AsciiBorder && dialog.getBackground().equals(AsciiChrome.editor(theme).background()), id + " dialog matches editor chrome");
            check(singleFrame(frame), id + " dialog creates no extra native window");
            inspectVisible(frame.getContentPane()); inspectVisible(dialog);
            screenshot(frame, directory.resolve("dialog-" + id + ".png"));
            dialog.dispose(); return null;
        });
    }

    private static void compactMenus(TurboFrame frame, Theme theme, Path directory) throws Exception {
        AsciiMenuBar bar = edt(() -> (AsciiMenuBar) frame.getJMenuBar());
        List<JMenu> originals = edt(bar::allMenus);
        edt(() -> { frame.setSize(640, 700); frame.validate(); bar.doLayout(); return null; });
        waitFor(() -> bar.overflowMenu().isShowing(), "compact overflow menu");
        JMenu nested = edt(() -> {
            check(frame.getWidth() == 640, theme.id() + " actual compact frame width");
            var reachable = Collections.newSetFromMap(new IdentityHashMap<JMenu,Boolean>());
            for (Component component : bar.getComponents()) if (component instanceof JMenu menu && menu.isVisible()) {
                Rectangle bounds = menu.getBounds();
                check(bounds.x >= 0 && bounds.x + bounds.width <= bar.getWidth(), theme.id() + " top menu within compact bar: " + menu.getText());
                if (menu == bar.overflowMenu()) {
                    for (Component child : menu.getMenuComponents()) if (child instanceof JMenu original) reachable.add(original);
                } else reachable.add(menu);
            }
            check(reachable.size() == originals.size() && reachable.containsAll(originals), theme.id() + " every original menu remains reachable at 640px");
            JMenu more = bar.overflowMenu(); more.doClick();
            check(more.getPopupMenu().isShowing(), theme.id() + " overflow opens inside frame");
            JMenu selected = Arrays.stream(more.getMenuComponents()).filter(JMenu.class::isInstance).map(JMenu.class::cast).findFirst().orElseThrow();
            MenuSelectionManager manager = MenuSelectionManager.defaultManager();
            manager.setSelectedPath(new MenuElement[] {bar, more, more.getPopupMenu(), selected});
            return selected;
        });
        // Keyboard handling belongs to Swing's installed InputMaps, not MenuElement listeners.
        // Allow the popup's focus transition before dispatching a key inside this same window.
        edt(() -> {
            KeyboardFocusManager manager=KeyboardFocusManager.getCurrentKeyboardFocusManager();
            Component focus=manager.getFocusOwner();
            check(focus!=null&&SwingUtilities.getWindowAncestor(focus)==frame,theme.id()+" menu keyboard focus belongs to test frame");
            manager.dispatchEvent(new KeyEvent(focus,KeyEvent.KEY_PRESSED,System.currentTimeMillis(),0,KeyEvent.VK_RIGHT,KeyEvent.CHAR_UNDEFINED));
            manager.dispatchEvent(new KeyEvent(focus,KeyEvent.KEY_RELEASED,System.currentTimeMillis(),0,KeyEvent.VK_RIGHT,KeyEvent.CHAR_UNDEFINED));
            return null;
        });
        waitFor(() -> nested.getPopupMenu().isShowing(),theme.id()+" keyboard overflow submenu");
        edt(() -> {
            check(nested.getPopupMenu().isShowing(), theme.id() + " right-arrow opens overflow submenu");
            check(singleFrame(frame), theme.id() + " compact menu uses only main window");
            inspectVisible(bar.overflowMenu().getPopupMenu()); inspectVisible(nested.getPopupMenu());
            screenshot(frame, directory.resolve("compact-menu-" + theme.id() + ".png"));
            MenuSelectionManager.defaultManager().clearSelectedPath(); return null;
        });
        edt(() -> { frame.setSize(1180,780); frame.validate(); bar.doLayout(); return null; });
        edt(() -> {
            check(originals.equals(bar.allMenus()), theme.id() + " menu identities/order survive resize");
            check(!bar.overflowMenu().isShowing(), theme.id() + " expanded frame restores top menu row");
            return null;
        });
    }

    private static boolean hasAsciiBorder(Component component) {
        for (Component at=component; at!=null && !(at instanceof Window); at=at.getParent())
            if(at instanceof JComponent swing && swing.getBorder() instanceof AsciiBorder) return true;
        return false;
    }
    private static void inspectVisible(Component component) {
        if(!component.isShowing())return;
        if(component instanceof JInternalFrame)throw new AssertionError("Visible desktop-window chrome: "+component.getClass().getName());
        if(component instanceof JComponent swing){
            inspectBorder(swing.getBorder(),swing.getClass().getSimpleName());
            if(swing.getUI()!=null && swing.getUI().getClass().getName().contains("Aqua"))throw new AssertionError("Native delegate in editor surface: "+swing.getClass().getName());
        }
        if(component instanceof Container container)for(Component child:container.getComponents())inspectVisible(child);
    }
    private static void inspectBorder(Border border,String owner){
        if(border==null)return;
        if(border instanceof BevelBorder || border instanceof EtchedBorder || border instanceof TitledBorder || border.getClass().getName().contains("Aqua"))throw new AssertionError("Raised/native border on "+owner+": "+border.getClass().getName());
        if(border instanceof CompoundBorder compound){inspectBorder(compound.getOutsideBorder(),owner);inspectBorder(compound.getInsideBorder(),owner);}
    }
    private static boolean singleFrame(TurboFrame frame){return frame.isVisible()&&Arrays.stream(Window.getWindows()).filter(Window::isVisible).count()==1;}
    private static void screenshot(TurboFrame frame,Path destination)throws Exception{
        var image=new BufferedImage(frame.getWidth(),frame.getHeight(),BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics=image.createGraphics();frame.paintAll(graphics);graphics.dispose();ImageIO.write(image,"png",destination.toFile());
    }
    private static void check(boolean condition,String message){if(!condition)throw new AssertionError(message);checks++;note("PASS "+message);}
    private static void note(String message){report.append(message).append('\n');System.out.println(message);}
    private static void waitFor(Callable<Boolean> condition,String description)throws Exception{
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(20);
        while(System.nanoTime()<end){if(edt(condition))return;Thread.sleep(20);}throw new AssertionError("Timeout: "+description);
    }
    private static <T>T edt(Callable<T> operation)throws Exception{FutureTask<T> task=new FutureTask<>(operation);SwingUtilities.invokeAndWait(task);return task.get();}
}
