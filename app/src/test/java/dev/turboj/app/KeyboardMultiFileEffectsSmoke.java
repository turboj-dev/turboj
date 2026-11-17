package dev.turboj.app;

import static dev.turboj.app.KeyboardActionEffectsSmoke.*;
import static dev.turboj.app.KeyboardBranchEffectsSmoke.*;
import dev.turboj.commandj.swing.*;
import dev.turboj.config.Settings;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import javax.swing.*;
import javax.tools.ToolProvider;

/** Actual keyboard forms and previews for changes spanning files, with compilation and full undo checks. */
public final class KeyboardMultiFileEffectsSmoke {
    record Case(String id,Map<String,String> sources,String active,String marker,String field,String value,String target,String expected){}
    public static void main(String[] args)throws Exception {
        output=Files.createDirectories(Path.of("app/target/keyboard-multifile-effects"));work=Files.createTempDirectory("turboj-multifile-effects-").toRealPath();
        System.setProperty("turboj.config",Files.createTempDirectory("turboj-effects-settings-").toString());System.setProperty("apple.laf.useScreenMenuBar","false");
        try {for(Case c:List.of(
            new Case("refactor.pull-up",Map.of("A.java","class A {}","B.java","class B extends A { int value(){return 1;} }"),"B.java","value","Qualified superclass / interface","A","A.java","value()"),
            new Case("refactor.push-down",Map.of("A.java","class A { int value(){return 1;} }","B.java","class B extends A {}"),"A.java","value",null,null,"B.java","value()"),
            new Case("refactor.extract-interface",Map.of("A.java","public class A { public int value(){return 42;} }"),"A.java","A {","Interface name","Answer","Answer.java","int value()"),
            new Case("refactor.introduce-interface",Map.of("A.java","public class A { public int value(){return 42;} }"),"A.java","A {","Interface name","Answer","Answer.java","int value()"),
            new Case("refactor.move",Map.of("A.java","public class A {}","B.java","class B { A a; }"),"A.java","A {","Destination package / qualified type","moved","moved/A.java","package moved;")
        ))exercise(c);}finally{Files.writeString(output.resolve("effects.tsv"),report);}
    }
    static void exercise(Case c)throws Exception {
        // Each window is an independent fixture; restoring the preceding case's session
        // asynchronously would legitimately invalidate a refactoring capture.
        System.setProperty("turboj.config",Files.createTempDirectory("turboj-multifile-settings-").toString());
        Path project=Files.createDirectories(work.resolve(c.id));Path root=Files.createDirectories(project.resolve("src/main/java"));Files.createDirectories(project.resolve(".turboj"));
        Files.writeString(project.resolve(".turboj/project.properties"),"kind=java\njava.release=25\nname=MultiFile\n");
        for(var e:c.sources.entrySet())Files.writeString(root.resolve(e.getKey()),e.getValue());
        frame=edt(()->{var f=new TurboFrame(new Settings(),Map.of());f.setVisible(true);f.toFront();Desktop.getDesktop().requestForeground(true);f.openPath(project);return f;});
        try {
            await(()->frame.mavenProjects().hasProject(),"project");read(root.resolve(c.active));int at=c.sources.get(c.active).indexOf(c.marker);
            edt(()->{frame.editor().gotoOffset(at);frame.editor().requestFocusInWindow();return null;});await(()->frame.editor().isFocusOwner(),"source focus");
            action(c.id);dialog();key(KeyEvent.VK_ESCAPE,0);await(()->!OverlayHost.active(frame),"cancel parameters");
            check(edt(()->frame.editor().getText()).equals(c.sources.get(c.active)),"cancel unchanged");
            action(c.id);dialog();if(c.field!=null){field(c.field);replace(c.value);}button("OK");
            await(()->OverlayHost.active(frame)&&KeyboardBranchEffectsSmoke.top().getTitle().startsWith("RefactorJ"),"preview");
            var apply=edt(()->KeyboardRefactoringEffectsSmoke.buttonIn(KeyboardBranchEffectsSmoke.top(),"Apply changes"));
            check(apply!=null&&edt(apply::isEnabled),c.id+": "+edt(()->KeyboardRefactoringEffectsSmoke.texts(KeyboardBranchEffectsSmoke.top())));
            button("Apply changes");await(()->!OverlayHost.active(frame)&&!frame.refactoring().busy(),"applied");
            Path target=root.resolve(c.target);await(()->Files.exists(target),"target file");String targetText=read(target);check(targetText.contains(c.expected),c.id+" target="+targetText);
            Map<String,String> after=snapshot(root);compile(root,after);
            action("undo");await(()->!frame.refactoring().busy(),"undo");check(snapshot(root).equals(c.sources),c.id+" full workspace restored");
            check(edt(()->frame.refactoring().canRedo()),"inspecting previously closed target preserves Redo");
            action("redo");await(()->!frame.refactoring().busy(),"redo");var redone=snapshot(root);check(redone.equals(after),c.id+" full workspace redo: expected="+after+" actual="+redone+" status="+edt(()->frame.statusMessages()));
            if(c.id.equals("refactor.push-down")) {
                action("undo");await(()->!frame.refactoring().busy(),"undo before later edit");snapshot(root);
                edt(()->{frame.editor().gotoOffset(frame.editor().getText().length());frame.editor().replaceSelection(" // later edit");return null;});
                check(!edt(()->frame.refactoring().canRedo()),"real later edit blocks workspace Redo");
                action("undo");check(snapshot(root).equals(c.sources),"local Undo restores source");
                // Editing after Undo creates a new history branch; undoing that edit does not revive the discarded branch.
                check(!edt(()->frame.refactoring().canRedo()),"superseded workspace Redo remains unavailable");
            }
            pass(c.id,"keyboard parameter cancel/apply; target file/member; javac all files; complete workspace Undo/Redo");
        } finally {edt(()->{for(var d:List.copyOf(OverlayHost.dialogs(frame)))d.dispose();frame.shutdown();frame.dispose();return null;});}
    }
    static String read(Path file)throws Exception{edt(()->{frame.openPath(file);return null;});await(()->file.equals(frame.currentFile())&&frame.editor().isFocusOwner(),"open "+file.getFileName());return edt(()->frame.editor().getText());}
    static Map<String,String> snapshot(Path root)throws Exception{var result=new TreeMap<String,String>();List<Path> files;try(var stream=Files.walk(root)){files=stream.filter(p->p.toString().endsWith(".java")).sorted().toList();}for(Path p:files)result.put(root.relativize(p).toString(),read(p));return result;}
    static void compile(Path sourceRoot,Map<String,String> files)throws Exception{Path root=Files.createTempDirectory(work,"compile-");var options=new ArrayList<String>(List.of("-proc:none","-d",root.toString()));for(var e:files.entrySet()){Path p=root.resolve(e.getKey());Files.createDirectories(p.getParent());Files.writeString(p,e.getValue());options.add(p.toString());}var errors=new java.io.ByteArrayOutputStream();check(ToolProvider.getSystemJavaCompiler().run(null,errors,errors,options.toArray(String[]::new))==0,"javac: "+errors);}
}
