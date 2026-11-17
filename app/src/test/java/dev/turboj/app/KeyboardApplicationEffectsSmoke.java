package dev.turboj.app;

import static dev.turboj.app.KeyboardActionEffectsSmoke.*;
import static dev.turboj.app.KeyboardBranchEffectsSmoke.*;
import dev.turboj.commandj.swing.*;
import dev.turboj.config.Settings;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.*;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import javax.swing.*;

/** File/project selection, save-all, multicaret commands and the actual generated cheat-sheet action. */
public final class KeyboardApplicationEffectsSmoke {
    public static void main(String[] args)throws Exception {
        output=Files.createDirectories(Path.of("app/target/keyboard-application-effects"));work=Files.createTempDirectory("turboj-application-effects-").toRealPath();
        System.setProperty("turboj.config",Files.createTempDirectory("turboj-application-settings-").toString());System.setProperty("apple.laf.useScreenMenuBar","false");
        Files.createDirectories(work.resolve(".turboj"));Files.writeString(work.resolve(".turboj/project.properties"),"kind=java\njava.release=25\nname=ApplicationEffects\n");
        Path c=Files.writeString(Files.createDirectories(work.resolve("src/main/java")).resolve("C.java"),"class C {}\n");Path d=Files.writeString(c.resolveSibling("D.java"),"class D {}\n");settings=new Settings();
        var clipboard=Toolkit.getDefaultToolkit().getSystemClipboard();var prior=clipboard.getContents(null);
        frame=edt(()->{var f=new TurboFrame(settings,Map.of());f.setVisible(true);f.toFront();Desktop.getDesktop().requestForeground(true);f.openPath(c);return f;});
        try {
            await(()->c.equals(frame.currentFile())&&frame.editor().isFocusOwner(),"source");
            for(String id:List.of("openFolder","project.open")){action(id);dialog();field("File name");replace(work.toString());key(KeyEvent.VK_ENTER,0);await(()->!OverlayHost.active(frame)&&frame.mavenProjects().hasProject(),"project selected");pass(id,"keyboard chooser opens actual plain project");}
            for(Path file:List.of(c,d)){action("open");dialog();field("File name");replace(file.toString());key(KeyEvent.VK_ENTER,0);await(()->file.equals(frame.currentFile())&&!OverlayHost.active(frame),"file opened");dirty("class "+file.getFileName().toString().charAt(0)+" { int saved; }\n");}
            pass("open","keyboard file chooser opens both expected source paths");action("file.saveAll");await(()->frame.unsavedFiles().isEmpty(),"save all complete");check(Files.readString(c).contains("int saved;")&&Files.readString(d).contains("int saved;"),"both disk writes");pass("file.saveAll","two dirty buffers saved to their exact files");
            boolean visible=edt(()->frame.mavenProjects().panel().isVisible());action("project.explorer");await(()->frame.mavenProjects().panel().isVisible()!=visible,"Explorer toggle");action("project.explorer");await(()->frame.mavenProjects().panel().isVisible()==visible,"Explorer restored");pass("project.explorer","actual Explorer visibility changes and restores");
            fixture("foo foo\nfoo",0,0);action("editor.selectNextOccurrence");action("editor.selectNextOccurrence");await(()->frame.editor().caretCount()==2,"next occurrence");pass("editor.selectNextOccurrence","two matching identifier selections");action("editor.selectAllOccurrences");await(()->frame.editor().caretCount()==3,"all occurrences");pass("editor.selectAllOccurrences","three matching identifier selections");action("editor.removeSecondaryCarets");await(()->frame.editor().caretCount()==1,"one caret");pass("editor.removeSecondaryCarets","secondary selections removed");
            fixture("abc\ndef",5,5);action("editor.addCaretAbove");await(()->frame.editor().caretCount()==2,"caret above");edt(()->{Toolkit.getDefaultToolkit().getSystemEventQueue().postEvent(new KeyEvent(focus(),KeyEvent.KEY_TYPED,0,0,KeyEvent.VK_UNDEFINED,'X'));return null;});await(()->frame.editor().getText().equals("aXbc\ndXef"),"actual multicaret typing");action("undo");await(()->frame.editor().getText().equals("abc\ndef"),"multicaret undo");pass("editor.addCaretAbove","adds upper-row caret for actual simultaneous editing");action("editor.removeSecondaryCarets");
            action("view.focusJShell");await(()->frame.repl().hasConsoleFocus(),"JShell focus");pass("view.focusJShell","actual JShell input owns focus");action("view.focusConsole");
            edt(()->{frame.console().append("KEY04_COPY_OUTPUT\n",false);return null;});await(()->frame.console().text().contains("KEY04_COPY_OUTPUT"),"output ready");edt(()->{frame.console().inputComponent().selectAll();return null;});action("console.copy");check(((String)clipboard.getData(DataFlavor.stringFlavor)).contains("KEY04_COPY_OUTPUT"),"copied actual output");pass("console.copy","selected console text reaches system clipboard");action("view.focusEditor");
            action("help.cheatSheet");await(()->frame.statusMessages().contains("Cheat sheet:"),"cheat-sheet generated and opened");
            Path html=Settings.directory().resolve("cheatsheets/cheatsheet-"+edt(()->frame.commandKeymaps().selectedId())+".html");String page=Files.readString(html);
            for(var command:edt(()->frame.commandRegistry().commands()))check(page.contains("data-command=\""+command.id()+"\""),"generated command "+command.id());pass("help.cheatSheet","actual generation/open callback completes; exported HTML includes every registered command");
        }finally{Files.writeString(output.resolve("effects.tsv"),report);edt(()->{for(var dialog:List.copyOf(OverlayHost.dialogs(frame)))dialog.dispose();frame.shutdown();frame.dispose();return null;});if(prior!=null)clipboard.setContents(prior,null);}
    }
}
