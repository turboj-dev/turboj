package dev.turboj.app;

import dev.turboj.commandj.swing.*;
import dev.turboj.config.Settings;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import javax.swing.*;

/** Actual command callbacks open production dialogs; Tab/Shift+Tab/Escape leave source intact.
 * This covers opening, traversal and cancellation, not every dialog's committing operation. */
public final class KeyboardDialogRoutesSmoke {
    private static TurboFrame frame;
    private static final String SOURCE="public class Demo { public static void main(String[] args) {} }\n";
    private static final StringBuilder report=new StringBuilder();
    public static void main(String[] args)throws Exception {
        Path work=Files.createTempDirectory("turboj-dialog-routes-").toRealPath();
        Path output=Files.createDirectories(Path.of("app/target/keyboard-dialog-routes"));
        System.setProperty("turboj.config",work.resolve("settings").toString());
        System.setProperty("apple.laf.useScreenMenuBar","false");
        Path source=Files.writeString(work.resolve("Demo.java"),SOURCE);
        frame=edt(()->{var f=new TurboFrame(new Settings(),Map.of());f.setVisible(true);f.toFront();
            Desktop.getDesktop().requestForeground(true);f.openPath(source);return f;});
        try {
            await(()->source.equals(frame.currentFile())&&frame.editor().isFocusOwner(),"open fixture");
            for(String id:List.of("workspace.select","file.newJavaProject","file.newMavenProject",
                    "open","openFolder","project.open","saveAs","find","replace","goto","help","keymap",
                    "theme.more","ai.settings","run.configuration","settings.general","view.focusDiagnostics",
                    "cherry.configure","navigation.goToFile","navigation.goToType","navigation.goToSymbol","navigation.searchEverywhere")) {
                var strokes=edt(()->frame.commandKeymaps().commandBindings(frame.commandRegistry(),id).getFirst().sequence().strokes());
                for(var stroke:strokes){var key=SwingKeys.toSwing(stroke);key(key.getKeyCode(),key.getModifiers());}
                await(()->OverlayHost.active(frame),"dialog from "+id);
                await(()->focus()!=null&&SwingUtilities.isDescendingFrom(focus(),top()),"initial focus "+id);
                String title=edt(()->top().getTitle());
                // Visit a full forward cycle (or all bounded controls), then exercise reverse traversal.
                Component start=edt(KeyboardDialogRoutesSmoke::focus);
                Set<Component> visited=Collections.newSetFromMap(new IdentityHashMap<>());
                for(int i=0;i<80;i++) {
                    Component owner=edt(KeyboardDialogRoutesSmoke::focus);visited.add(owner);
                    key(KeyEvent.VK_TAB,0);Thread.sleep(5);
                    if(!edt(()->focus()!=null&&SwingUtilities.isDescendingFrom(focus(),top())))throw new AssertionError("Tab escaped "+id);
                    if(edt(KeyboardDialogRoutesSmoke::focus)==start)break;
                }
                key(KeyEvent.VK_TAB,KeyEvent.SHIFT_DOWN_MASK);
                if(!edt(()->SwingUtilities.isDescendingFrom(focus(),top())))throw new AssertionError("Shift+Tab escaped "+id);
                key(KeyEvent.VK_ESCAPE,0);
                await(()->!OverlayHost.active(frame)&&frame.editor().isFocusOwner(),"Escape/source focus "+id);
                if(!edt(()->frame.editor().getText()).equals(SOURCE))throw new AssertionError("Source changed by cancellation "+id);
                String row="PASS "+id+" → "+title+"; "+visited.size()+" Tab targets; Shift+Tab; Escape restores source\n";
                report.append(row);System.out.print(row);
            }
        } finally {Files.writeString(output.resolve("report.txt"),report);
            edt(()->{for(var d:List.copyOf(OverlayHost.dialogs(frame)))d.dispose();frame.shutdown();frame.dispose();return null;});}
    }
    private static Component focus(){return KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();}
    private static EmbeddedDialog top(){return OverlayHost.dialogs(frame).getLast();}
    private static void key(int code,int mods)throws Exception{edt(()->{var queue=Toolkit.getDefaultToolkit().getSystemEventQueue();var owner=focus();
        queue.postEvent(new KeyEvent(owner,KeyEvent.KEY_PRESSED,System.currentTimeMillis(),mods,code,KeyEvent.CHAR_UNDEFINED));
        queue.postEvent(new KeyEvent(owner,KeyEvent.KEY_RELEASED,System.currentTimeMillis(),mods,code,KeyEvent.CHAR_UNDEFINED));return null;});edt(()->null);}
    private static void await(Callable<Boolean> test,String name)throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);while(System.nanoTime()<until){if(edt(test))return;Thread.sleep(10);}throw new AssertionError(name);}
    private static <T>T edt(Callable<T> action)throws Exception{var task=new FutureTask<>(action);SwingUtilities.invokeLater(task);return task.get(30,TimeUnit.SECONDS);}
}
