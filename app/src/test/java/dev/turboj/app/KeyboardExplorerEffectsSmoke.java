package dev.turboj.app;

import static dev.turboj.app.KeyboardActionEffectsSmoke.*;
import static dev.turboj.app.KeyboardBranchEffectsSmoke.*;
import dev.turboj.commandj.swing.*;
import dev.turboj.config.Settings;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import javax.swing.*;

/** Explorer callbacks through real shortcuts; isolated files, keyboard confirmations and disk/undo oracles. */
public final class KeyboardExplorerEffectsSmoke {
    static JTree tree;
    public static void main(String[] args)throws Exception {
        output=Files.createDirectories(Path.of("app/target/keyboard-explorer-effects"));work=Files.createTempDirectory("turboj-explorer-effects-").toRealPath();
        System.setProperty("turboj.config",Files.createTempDirectory("turboj-effects-settings-").toString());System.setProperty("apple.laf.useScreenMenuBar","false");
        Path project=Files.createDirectories(work.resolve("project"));Files.createDirectories(project.resolve(".turboj"));
        Files.writeString(project.resolve(".turboj/project.properties"),"kind=java\njava.release=25\nname=ExplorerEffects\n");
        Path src=Files.createDirectories(project.resolve("src/main/java"));Path source=Files.writeString(src.resolve("C.java"),"public class C {}\n");
        Path notes=Files.createDirectories(src.resolve("notes"));Files.writeString(notes.resolve("existing.txt"),"original\n");
        frame=edt(()->{var f=new TurboFrame(new Settings(),Map.of());f.setVisible(true);f.toFront();Desktop.getDesktop().requestForeground(true);f.openPath(project);return f;});
        try {
            await(()->frame.mavenProjects().hasProject(),"project");edt(()->{frame.openPath(source);return null;});await(()->source.equals(frame.currentFile()),"source");
            action("view.focusExplorer");await(()->focus() instanceof JTree,"tree focus");tree=edt(()->(JTree)focus());
            select(notes);action("explorer.expand");await(()->tree.isExpanded(tree.getSelectionPath()),"expanded");pass("explorer.expand","selected folder expands");
            action("explorer.collapse");await(()->!tree.isExpanded(tree.getSelectionPath()),"collapsed");pass("explorer.collapse","selected folder collapses");
            action("explorer.contextMenu");await(()->MenuSelectionManager.defaultManager().getSelectedPath().length>0,"context menu");
            key(KeyEvent.VK_ESCAPE,0);await(()->MenuSelectionManager.defaultManager().getSelectedPath().length==0,"context dismiss");pass("explorer.contextMenu","keyboard opens actual item menu; Escape restores tree");
            select(notes);action("explorer.newFile");dialog();replace("existing.txt");key(KeyEvent.VK_ENTER,0);await(()->!OverlayHost.active(frame),"existing file rejected");
            check(Files.readString(notes.resolve("existing.txt")).equals("original\n"),"existing file untouched");pass("explorer.newFile","existing file rejected without overwrite");
            select(notes);action("explorer.newFolder");dialog();replace("created");key(KeyEvent.VK_ENTER,0);await(()->Files.isDirectory(notes.resolve("created")),"new folder");pass("explorer.newFolder","directory created at selected location");
            select(notes.resolve("created"));action("explorer.newFile");dialog();replace("new.txt");key(KeyEvent.VK_ENTER,0);
            Path made=notes.resolve("created/new.txt");await(()->Files.exists(made)&&made.equals(frame.currentFile()),"new file");check(Files.size(made)==0,"empty created file");pass("explorer.newFile","creates empty file and opens exact path");
            select(made);action("refactor.rename");dialog();replace("renamed.txt");key(KeyEvent.VK_ENTER,0);
            Path renamed=made.resolveSibling("renamed.txt");await(()->Files.exists(renamed)&&!Files.exists(made)&&!frame.refactoring().busy(),"rename");pass("refactor.rename","Explorer plain-file rename updates actual disk path");
            action("view.focusEditor");await(()->frame.editor().isFocusOwner()&&!frame.refactoring().busy(),"history ready in editor");action("undo");await(()->Files.exists(made)&&!Files.exists(renamed),"rename undo");pass("undo","Explorer rename restored by workspace undo");
            select(made);action("explorer.delete");dialog();key(KeyEvent.VK_ESCAPE,0);await(()->!OverlayHost.active(frame),"delete cancel");check(Files.exists(made),"cancel preserves file");
            select(made);action("explorer.delete");dialog();button("OK");await(()->!Files.exists(made)&&!frame.refactoring().busy(),"delete");action("view.focusEditor");await(()->frame.editor().isFocusOwner()&&!frame.refactoring().busy(),"history ready in editor");action("undo");await(()->Files.exists(made),"delete undo");pass("explorer.delete","Escape preserves file; OK deletes; Undo restores");
            select(src);action("explorer.newPackage");dialog();replace("sample.tools");key(KeyEvent.VK_ENTER,0);await(()->Files.isDirectory(src.resolve("sample/tools")),"package");pass("explorer.newPackage","qualified package creates corresponding directories");
            select(src.resolve("sample/tools"));action("explorer.newClass");dialog();
            var name=edt(()->KeyboardPomEffectsSmoke.fields(top()).stream().filter(f->"first-launch-main-name".equals(f.getName())).findFirst().orElseThrow());
            tab(c->c==name);replace("CreatedClass");
            tab(c->"first-launch-main-create".equals(c.getName()));key(KeyEvent.VK_SPACE,0);
            Path generated=src.resolve("sample/tools/CreatedClass.java");
            await(()->generated.equals(frame.currentFile())&&Files.isRegularFile(generated)&&frame.editor().isFocusOwner(),"Explorer Java class generated");
            check(Files.readString(generated).contains("package sample.tools;"),"selected package inferred");
            check(javax.tools.ToolProvider.getSystemJavaCompiler().run(null,null,null,"-proc:none","-d",Files.createTempDirectory("turboj-explorer-javac-").toString(),generated.toString())==0,"generated class compiles");pass("explorer.newClass","selected source root/package create real compilable Java class and restore editor focus");
            select(source);action("explorer.open");await(()->source.equals(frame.currentFile())&&frame.editor().isFocusOwner(),"open selected source");pass("explorer.open","selected source opens and owns focus");
        } finally {Files.writeString(output.resolve("effects.tsv"),report);edt(()->{for(var d:List.copyOf(OverlayHost.dialogs(frame)))d.dispose();frame.shutdown();frame.dispose();return null;});}
    }
    // Locate the fixture item in the actual lazy tree. Selection setup is programmatic;
    // every action under test and every confirmation uses the production keyboard path.
    static void select(Path path)throws Exception {
        action("view.focusExplorer");await(()->frame.mavenProjects().hasExplorerFocus(),"Explorer focus");
        long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(15);
        while(System.nanoTime()<until){boolean found=edt(()->{
            for(int row=0;row<tree.getRowCount();row++){
                tree.setSelectionRow(row);Path current=frame.mavenProjects().selectedPath();
                if(path.equals(current)){tree.scrollRowToVisible(row);return true;}
                if(current!=null&&path.startsWith(current))tree.expandRow(row);
            }return false;
        });if(found)return;Thread.sleep(30);}
        throw new AssertionError("Cannot select fixture "+path);
    }
}
