package dev.turboj.app;

import static dev.turboj.app.KeyboardActionEffectsSmoke.*;
import dev.turboj.commandj.swing.*;
import dev.turboj.config.Settings;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import javax.swing.*;

/** Command → real parameter form → RefactorJ preview → apply → compile → workspace undo. */
public final class KeyboardRefactoringEffectsSmoke {
    record Case(String id,String source,String selected,boolean range,String formField,String input,String expected,boolean absent) {}
    public static void main(String[] args)throws Exception {
        output=Files.createDirectories(Path.of("app/target/keyboard-refactoring-effects"));work=Files.createTempDirectory("turboj-refactor-effects-").toRealPath();
        System.setProperty("turboj.config",Files.createTempDirectory("turboj-effects-settings-").toString());System.setProperty("apple.laf.useScreenMenuBar","false");
        Path project=Files.createDirectories(work.resolve("project"));Files.createDirectories(project.resolve(".turboj"));
        Files.writeString(project.resolve(".turboj/project.properties"),"kind=java\njava.release=25\nname=RefactorEffects\n");
        Path source=Files.createDirectories(project.resolve("src/main/java")).resolve("C.java");Files.writeString(source,"class C {}\n");
        frame=edt(()->{var f=new TurboFrame(new Settings(),Map.of());f.setVisible(true);f.toFront();Desktop.getDesktop().requestForeground(true);f.openPath(project);return f;});
        try {
            await(()->frame.mavenProjects().hasProject(),"project");edt(()->{frame.openPath(source);return null;});await(()->source.equals(frame.currentFile())&&frame.editor().isFocusOwner(),"source");
            boolean started=args.length==0;
            for(Case c:cases()){if(args.length>0&&c.id.equals(args[0]))started=true;if(started)exercise(c);}
        } finally {Files.writeString(output.resolve("effects.tsv"),report);edt(()->{for(var d:List.copyOf(OverlayHost.dialogs(frame)))d.dispose();frame.shutdown();frame.dispose();return null;});}
    }
    static List<Case> cases(){return List.of(
        simple("refactor.anonymous-to-lambda","class C { Runnable r = new Runnable() { public void run() { System.out.println(1); } }; }","new Runnable","->"),
        simple("refactor.lambda-to-method-reference","class C { java.util.function.Function<String,Integer> f = x -> Integer.parseInt(x); }","x ->","Integer::parseInt"),
        simple("refactor.method-reference-to-lambda","class C { java.util.function.Function<String,Integer> f = Integer::parseInt; }","Integer::","->"),
        simple("refactor.switch-to-expression","class C { int f(int n) { switch(n) { case 1: return 2; default: return 3; } } }","switch","->"),
        simple("refactor.type-to-var","class C { int f() { int n = 42; return n; } }","int n","var n"),
        simple("refactor.var-to-type","class C { int f() { var n = 42; return n; } }","var n","int n"),
        simple("organizeImports","class C { ArrayList<String> names = new ArrayList<>(); }","class C","import java.util.ArrayList;"),
        new Case("refactor.add-import","class C { List<String> names; }","List",false,"Qualified type / member","java.util.List","import java.util.List;",false),
        new Case("refactor.remove-import","import java.util.List; class C {}","java.util.List",false,null,null,"import",true),
        simple("refactor.implement-methods","class C implements Runnable { }","class C","void run()"),
        simple("refactor.create-method","class C { int f() { return missing(1); } }","missing","int missing("),
        simple("refactor.create-constructor","class C { static C f() { return new C(1); } }","new C","C(int"),
        simple("refactor.create-field","class C { void f() { count = 1; } }","count","int count"),
        simple("refactor.create-local","class C { void f() { count = 1; } }","count","int count"),
        simple("refactor.fix-type-mismatch","class C { void f() { int value = \"text\"; System.out.println(value); } }","value =","String value"),
        new Case("refactor.remove-unreachable","class C { void f() { return; System.out.println(1); } }","System.out",false,null,null,"println",true),
        new Case("refactor.extract-variable","class C { int f(int a){return a + 2;} }","a + 2",true,null,null,"value",false),
        new Case("refactor.extract-constant","class C { int f(){return 42;} }","42",true,null,null,"VALUE",false),
        new Case("refactor.extract-field","class C { int f(){int value=42;return value;} }","value=",false,null,null,"private int value",false),
        new Case("refactor.extract-parameter","class C { int f(){return 42;} }","42",true,null,null,"value",false),
        new Case("refactor.extract-method","class C { void f(){System.out.println(42);} }","System.out.println(42);",true,null,null,"extracted(",false),
        new Case("refactor.inline-variable","class C { int f(){int value=42;return value;} }","value=",false,null,null,"value",true),
        simple("refactor.inline-method","class C { int plus(int n){return n+2;} int f(){return plus(40);} }","plus(40)","40"),
        simple("refactor.encapsulate-field","class C { public int count; int f(){return count;} }","count;","getCount("),
        simple("refactor.constructor-to-factory","class C { C(int n){} static C f(){return new C(1);} }","C(int","create("),
        new Case("refactor.change-signature","class C { int f(int n){return n+2;} int g(){return f(40);} }","f(int",false,"Method name","calculate","calculate(40)",false),
        new Case("refactor.rename","class C { int f(){return 42;} int g(){return f();} }","f()",false,"New name","answer","return answer();",false),
        new Case("refactor.safe-delete","class C { private void unused(){} int answer(){return 42;} }","unused",false,null,null,"unused",true)
    );}
    static Case simple(String id,String source,String selection,String expected){return new Case(id,source,selection,false,null,null,expected,false);}
    static void exercise(Case c)throws Exception {
        int at=c.source.indexOf(c.selected);check(at>=0,"fixture selection");fixture(c.source,at,c.range?at+c.selected.length():at);
        action(c.id);dialog();
        if(!top().getTitle().startsWith("RefactorJ")) {
            check(edt(()->frame.editor().getText()).equals(c.source),"parameter form doesn't modify source");
            key(KeyEvent.VK_ESCAPE,0);await(()->!OverlayHost.active(frame),"parameter cancel");check(edt(()->frame.editor().getText()).equals(c.source),"cancel no edit");
            action(c.id);dialog();
            if(c.id.equals("refactor.rename")){KeyboardBranchEffectsSmoke.replace(c.input);button("Preview");}
            else {if(c.formField!=null){KeyboardBranchEffectsSmoke.field(c.formField);KeyboardBranchEffectsSmoke.replace(c.input);}button("OK");}
        }
        await(()->OverlayHost.active(frame)&&top().getTitle().startsWith("RefactorJ"),"preview "+c.id);
        check(edt(()->frame.editor().getText()).equals(c.source),"preview hasn't applied "+c.id);
        JButton apply=edt(()->buttonIn(top(),"Apply changes"));
        check(apply!=null&&edt(apply::isEnabled),"valid plan "+c.id+"; "+edt(()->texts(top())));
        button("Apply changes");await(()->!OverlayHost.active(frame)&&!frame.refactoring().busy()&&!frame.editor().getText().equals(c.source),"applied "+c.id);
        String after=edt(()->frame.editor().getText());check(c.absent?!after.contains(c.expected):after.contains(c.expected),c.id+" expected "+c.expected+" in "+after);
        compile(after);action("undo");await(()->!frame.refactoring().busy()&&frame.editor().getText().equals(c.source),"workspace undo "+c.id);
        action("redo");await(()->!frame.refactoring().busy()&&frame.editor().getText().equals(after),"workspace redo "+c.id);
        // Drain that transaction before the next independently prepared fixture.
        action("undo");await(()->!frame.refactoring().busy()&&frame.editor().getText().equals(c.source),"reset transaction");
        pass(c.id,"actual parameter/preview callbacks; expected source; javac; workspace Undo/Redo");
    }
    static EmbeddedDialog top(){return OverlayHost.dialogs(frame).getLast();}
    static JButton buttonIn(Container root,String text){for(Component c:root.getComponents()){if(c instanceof JButton b&&text.equals(b.getText()))return b;if(c instanceof Container child){var b=buttonIn(child,text);if(b!=null)return b;}}return null;}
    static String texts(Container root){StringBuilder value=new StringBuilder();for(Component c:root.getComponents()){if(c instanceof JTextArea t)value.append(t.getText()).append('\n');if(c instanceof Container child)value.append(texts(child));}return value.toString();}
}
