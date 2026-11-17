package dev.turboj.app;

import dev.turboj.commandj.swing.*;
import dev.turboj.config.Settings;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Predicate;
import javax.swing.*;

/** Real production nested dialogs; all navigation/activation after opening Keymap uses keys. */
public final class KeyboardNestedDialogSmoke {
    private static TurboFrame frame;
    private static int assertions;
    private static final StringBuilder report=new StringBuilder();
    public static void main(String[] args)throws Exception {
        Path work=Files.createTempDirectory("turboj-nested-dialog-").toRealPath();
        Path output=Files.createDirectories(Path.of("app/target/keyboard-nested-dialog"));
        System.setProperty("turboj.config",work.resolve("settings").toString());
        System.setProperty("apple.laf.useScreenMenuBar","false");
        frame=edt(()->{var f=new TurboFrame(new Settings(),Map.of());f.setVisible(true);f.toFront();
            Desktop.getDesktop().requestForeground(true);f.editor().setText("class Demo {}\n");f.editor().requestFocusInWindow();return f;});
        try {
            await(()->frame.editor().isFocusOwner(),"source focus");
            edt(()->{frame.showKeymapEditor();return null;}); depth(1);
            String original=edt(()->frame.commandKeymaps().selectedId());
            button("Duplicate…"); depth(2); type("Keyboard Copy"); key(KeyEvent.VK_ENTER,0); depth(1);
            check(!edt(()->frame.commandKeymaps().selectedId()).equals(original),"Duplicate profile: Enter creates and selects a real custom profile");
            button("Duplicate…"); depth(2); key(KeyEvent.VK_ESCAPE,0); depth(1);
            check(edt(()->frame.commandKeymaps().profiles().size())==5,"Cancel nested input leaves profiles unchanged");
            button("Reset profile"); depth(2); key(KeyEvent.VK_ESCAPE,0); depth(1);
            button("Remove…"); depth(2); key(KeyEvent.VK_ESCAPE,0); depth(1);
            button("Add shortcut…"); depth(2); key(KeyEvent.VK_ESCAPE,0); depth(1);
            check(true,"Reset, remove and recorder cancel back to parent with focus");
            button("Export JSON…"); depth(2);
            field("Directory path"); replace(work.toString()); key(KeyEvent.VK_ENTER,0);
            button("New folder…"); depth(3); type("exports"); key(KeyEvent.VK_ENTER,0); depth(2);
            await(()->Files.isDirectory(work.resolve("exports")),"new directory created");
            check(true,"Keymap → file chooser → New folder: keyboard creates real directory");
            field("File name"); replace(work.resolve("bindings.json").toString()); key(KeyEvent.VK_ENTER,0); depth(1);
            await(()->Files.isRegularFile(work.resolve("bindings.json")),"JSON exported");
            check(Files.readString(work.resolve("bindings.json")).contains("Keyboard Copy"),"Export contains actual custom profile");
            button("Export JSON…"); depth(2); field("File name"); replace(work.resolve("bindings.json").toString()); key(KeyEvent.VK_ENTER,0);
            await(()->top().getTitle().equals("Existing file"),"overwrite confirmation");
            key(KeyEvent.VK_ESCAPE,0); depth(2); key(KeyEvent.VK_ESCAPE,0); depth(1);
            check(Files.readString(work.resolve("bindings.json")).contains("Keyboard Copy"),"Cancel overwrite preserves exported file and restores chooser/parent");
            Files.writeString(work.resolve("invalid.json"),"not valid JSON");
            button("Import JSON…"); depth(2); field("File name"); replace(work.resolve("invalid.json").toString()); key(KeyEvent.VK_ENTER,0);
            await(()->OverlayHost.dialogs(frame).size()==2&&top().getTitle().equals("Keymap"),"import error");
            key(KeyEvent.VK_ESCAPE,0); depth(1);
            check(edt(()->frame.commandKeymaps().profiles().size())==5,"Invalid import error dismisses without damaging keymaps");
            field("Search commands or shortcuts"); replace("save");
            var editor=edt(()->(KeymapEditorDialog)top());
            tabTo(c->c==editor.commandsTable());key(KeyEvent.VK_HOME,0);
            for(int i=0;i<editor.commandsTable().getRowCount();i++) {
                if(edt(()->editor.commandsTable().getValueAt(editor.commandsTable().getSelectedRow(),0))
                        .equals(edt(()->frame.commandRegistry().find("save").orElseThrow().title())))break;
                key(KeyEvent.VK_DOWN,0);
            }
            check(edt(()->editor.commandsTable().getValueAt(editor.commandsTable().getSelectedRow(),0))
                    .equals(edt(()->frame.commandRegistry().find("save").orElseThrow().title())),"Keyboard selects actual Save command");
            var bindings=edt(()->frame.commandKeymaps().commandBindings(frame.commandRegistry(),"save"));
            button("Remove…");depth(2);key(KeyEvent.VK_ENTER,0);depth(1);
            check(edt(()->frame.commandKeymaps().commandBindings(frame.commandRegistry(),"save").size())==bindings.size()-1,"Remove Enter removes exactly one selected binding");
            button("Reset command");depth(1);
            check(edt(()->frame.commandKeymaps().commandBindings(frame.commandRegistry(),"save")).equals(bindings),"Reset command restores actual defaults");
            button("Remove…");depth(2);key(KeyEvent.VK_ENTER,0);depth(1);
            button("Reset profile");depth(2);button("OK");depth(1);
            check(edt(()->frame.commandKeymaps().commandBindings(frame.commandRegistry(),"save")).equals(bindings),"Reset profile confirmation restores actual defaults");
            button("Remove…");depth(2);key(KeyEvent.VK_ENTER,0);depth(1);
            button("Import JSON…");depth(2);field("File name");replace(work.resolve("bindings.json").toString());key(KeyEvent.VK_ENTER,0);depth(1);
            await(()->frame.commandKeymaps().commandBindings(frame.commandRegistry(),"save").equals(bindings),"valid import restores binding");
            check(edt(()->frame.commandKeymaps().profiles().size())==5,"Valid import restores exported profile without duplicates");
            Files.writeString(work.resolve("bindings.json"),"replacement sentinel");
            button("Export JSON…");depth(2);field("File name");replace(work.resolve("bindings.json").toString());key(KeyEvent.VK_ENTER,0);
            await(()->top().getTitle().equals("Existing file"),"chooser overwrite approve");button("OK");
            await(()->top().getTitle().equals("Export"),"keymap overwrite approve");button("OK");depth(1);
            await(()->Files.readString(work.resolve("bindings.json")).contains("Keyboard Copy"),"overwrite exported");
            check(true,"Both overwrite confirmations replace actual file with current keymap JSON");
            button("Reference / conflicts…"); depth(2); button("Shortcut diagnostics"); depth(3);
            key(KeyEvent.VK_TAB,0); check(edt(()->SwingUtilities.isDescendingFrom(focus(),top())),"Diagnostic Tab remains inside third-level dialog");
            key(KeyEvent.VK_ESCAPE,0); depth(2);
            button("Export Markdown…"); depth(3); field("File name"); replace(work.resolve("reference.md").toString()); key(KeyEvent.VK_ENTER,0); depth(2);
            await(()->Files.isRegularFile(work.resolve("reference.md")),"reference exported");
            check(Files.readString(work.resolve("reference.md")).contains("Keyboard Copy"),"Nested reference exports actual active profile");
            key(KeyEvent.VK_ESCAPE,0); depth(1); key(KeyEvent.VK_ESCAPE,0);
            await(()->!OverlayHost.active(frame)&&frame.editor().isFocusOwner(),"source restored");
            check(edt(()->frame.editor().getText()).equals("class Demo {}\n"),"Closing all levels restores source without leaking shortcut/typing");
            report.append("PASS ").append(assertions).append(" assertions; keyboard navigation/activation; fixture setup programmatic.\n");
        } finally { Files.writeString(output.resolve("report.txt"),report);
            edt(()->{for(var d:java.util.List.copyOf(OverlayHost.dialogs(frame)))d.dispose();frame.shutdown();frame.dispose();return null;}); }
    }
    private static void button(String title)throws Exception {tabTo(c->c instanceof JButton b&&b.getText().equals(title));key(KeyEvent.VK_SPACE,0);}
    private static void field(String name)throws Exception {tabTo(c->c instanceof JTextField&&name.equals(c.getAccessibleContext().getAccessibleName()));}
    private static void tabTo(Predicate<Component> match)throws Exception {
        for(int n=0;n<80;n++){if(edt(()->focus()!=null&&match.test(focus())))return;key(KeyEvent.VK_TAB,0);Thread.sleep(15);}
        throw new AssertionError("Tab could not reach target; focus="+edt(()->String.valueOf(focus())));
    }
    private static void replace(String value)throws Exception{key(KeyEvent.VK_A,KeyEvent.META_DOWN_MASK);type(value);}
    private static void type(String value)throws Exception{edt(()->{for(char c:value.toCharArray())Toolkit.getDefaultToolkit().getSystemEventQueue().postEvent(new KeyEvent(focus(),KeyEvent.KEY_TYPED,0,0,KeyEvent.VK_UNDEFINED,c));return null;});edt(()->null);}
    private static void key(int code,int mods)throws Exception{NativeWindowTestSupport.beforeKey(frame);edt(()->{var queue=Toolkit.getDefaultToolkit().getSystemEventQueue();var owner=focus();
        queue.postEvent(new KeyEvent(owner,KeyEvent.KEY_PRESSED,System.currentTimeMillis(),mods,code,KeyEvent.CHAR_UNDEFINED));
        queue.postEvent(new KeyEvent(owner,KeyEvent.KEY_RELEASED,System.currentTimeMillis(),mods,code,KeyEvent.CHAR_UNDEFINED));return null;});edt(()->null);}
    private static Component focus(){return KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();}
    private static EmbeddedDialog top(){return OverlayHost.dialogs(frame).getLast();}
    private static void depth(int n)throws Exception{await(()->OverlayHost.dialogs(frame).size()==n&&focus()!=null&&SwingUtilities.isDescendingFrom(focus(),top()),"dialog depth/focus "+n);}
    private static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);assertions++;report.append("PASS ").append(message).append('\n');System.out.println("PASS "+message);}
    private static void await(Callable<Boolean> test,String name)throws Exception{long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);while(System.nanoTime()<until){if(edt(test))return;edt(()->{NativeWindowTestSupport.recover(frame);return null;});Thread.sleep(10);}throw new AssertionError(name);}
    private static <T>T edt(Callable<T> action)throws Exception{var task=new FutureTask<>(action);SwingUtilities.invokeLater(task);return task.get(30,TimeUnit.SECONDS);}
}
