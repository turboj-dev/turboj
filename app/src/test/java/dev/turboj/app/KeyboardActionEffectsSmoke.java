package dev.turboj.app;

import dev.turboj.commandj.swing.*;
import dev.turboj.config.Settings;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import javax.swing.*;
import javax.tools.ToolProvider;

/** Production CommandJ callbacks, actual key events, independently specified outcomes and undo.
 * Source/selection fixtures are prepared programmatically. No handlers are replaced. */
public final class KeyboardActionEffectsSmoke {
    static TurboFrame frame;
    static Settings settings;
    static Path output, work;
    static final StringBuilder report=new StringBuilder("command\tassertion\n");
    record Edit(String id,String before,int start,int end,String after) {}
    public static void main(String[] args)throws Exception {
        output=Files.createDirectories(Path.of("app/target/keyboard-action-effects"));
        work=Files.createTempDirectory("turboj-effects-").toRealPath();
        System.setProperty("turboj.config",Files.createTempDirectory("turboj-effects-settings-").toString());
        System.setProperty("apple.laf.useScreenMenuBar","false");
        Path project=Files.createDirectories(work.resolve("project"));Files.createDirectories(project.resolve(".turboj"));
        Files.writeString(project.resolve(".turboj/project.properties"),"kind=java\njava.release=25\nname=Effects\n");
        Path source=Files.createDirectories(project.resolve("src/main/java")).resolve("C.java");Files.writeString(source,"public class C {}\n");
        settings=new Settings();
        frame=edt(()->{var f=new TurboFrame(settings,Map.of());f.setVisible(true);f.toFront();Desktop.getDesktop().requestForeground(true);f.openPath(project);return f;});
        try {
            await(()->frame.mavenProjects().hasProject(),"plain project");edt(()->{frame.openPath(source);return null;});
            await(()->source.equals(frame.currentFile())&&frame.editor().isFocusOwner(),"source focus");
            editing();preferences();javaStructure();
        } finally {Files.writeString(output.resolve("effects.tsv"),report);
            edt(()->{for(var d:List.copyOf(OverlayHost.dialogs(frame)))d.dispose();frame.shutdown();frame.dispose();return null;});}
    }
    static void editing()throws Exception {
        List<Edit> cases=List.of(
            new Edit("editor.copyLineUp","a\nb\nc",2,2,"a\nb\nb\nc"),
            new Edit("editor.copyLineDown","a\nb\nc",2,2,"a\nb\nb\nc"),
            new Edit("editor.insertLineAbove","  alpha\n    beta",4,4,"  \n  alpha\n    beta"),
            new Edit("editor.insertLineBelow","  alpha\n    beta",4,4,"  alpha\n  \n    beta"),
            new Edit("editor.splitLine","  alpha\n    beta",4,4,"  al\n  pha\n    beta"),
            new Edit("editor.joinLines","a  \n    b\n  c",0,0,"a b\n  c"),
            new Edit("editor.trimTrailingWhitespace","a \t\r\nb  \r\n",0,0,"a\r\nb\r\n"),
            new Edit("editor.convertTabsToSpaces","\tA\tB\n  \tC",0,0,"    A   B\n    C"),
            new Edit("editor.convertSpacesToTabs","    a = \"    \";\n      b",0,0,"\ta = \"    \";\n\t  b"),
            new Edit("editor.deletePreviousWord","one two\r\n🙂x",7,7,"one \r\n🙂x"),
            new Edit("editor.deleteNextWord","one two\r\n🙂x",4,4,"one 🙂x"),
            new Edit("editor.deleteToLineStart","one two\r\n🙂x",2,2,"e two\r\n🙂x"),
            new Edit("editor.deleteToLineEnd","one two\r\n🙂x",2,2,"on\r\n🙂x"),
            new Edit("editor.toggleBlockComment","abc",0,3,"/*abc*/"),
            new Edit("editor.upperCase","Straße",0,6,"STRASSE"),
            new Edit("editor.lowerCase","JAVA",0,4,"java"),
            new Edit("editor.sortSelectedLines","z\r\nb\r\na",0,7,"a\r\nb\r\nz"),
            new Edit("editor.reverseSelectedLines","a\nb\nz",0,5,"z\nb\na"),
            new Edit("editor.transposeCharacters","a🙂b",3,3,"ab🙂"),
            new Edit("moveLineUp","a\nb\nc",2,2,"b\na\nc"),
            new Edit("moveLineDown","a\nb\nc",2,2,"a\nc\nb"),
            new Edit("comment","int a;",0,0,"// int a;"),
            new Edit("indent","a\nb",0,3,"    a\n    b"),
            new Edit("unindent","    a\n    b",0,11,"a\nb"));
        for(Edit c:cases){fixture(c.before,c.start,c.end);action(c.id);await(()->frame.editor().getText().equals(c.after),c.id+" expected "+c.after);
            action("undo");await(()->frame.editor().getText().equals(c.before),c.id+" undo");
            action("redo");await(()->frame.editor().getText().equals(c.after),c.id+" redo");pass(c.id,"exact source; one Undo; Redo");}
        fixture("  abc  ",4,4);action("editor.smartHome");check(edt(()->frame.editor().caretOffset())==2,"smart home indent");
        action("editor.smartHome");check(edt(()->frame.editor().caretOffset())==0,"smart home start");pass("editor.smartHome","indent then physical start");
        action("editor.smartEnd");check(edt(()->frame.editor().caretOffset())==5,"smart end text");action("editor.smartEnd");check(edt(()->frame.editor().caretOffset())==7,"smart end line");pass("editor.smartEnd","text then physical end");
        fixture("alpha beta\n",7,7);action("editor.selectCurrentWord");check(edt(()->frame.editor().getSelectedText()).equals("beta"),"word");pass("editor.selectCurrentWord","exact word selection");
        action("editor.selectCurrentLine");check(edt(()->frame.editor().getSelectedText()).equals("alpha beta\n"),"line");pass("editor.selectCurrentLine","complete line selection");
        fixture("abc",0,3);action("editor.changeCase");dialog();key(KeyEvent.VK_ENTER,0);await(()->frame.editor().getText().equals("ABC"),"case applied");pass("editor.changeCase","choice Enter changes actual source");
        fixture("a\nb",0,3);action("editor.toggleColumnSelection");check(edt(()->frame.editor().selections().size())==2,"rectangle");pass("editor.toggleColumnSelection","two real row selections");
    }
    static void preferences()throws Exception {
        Map<String,BooleanSupplier> toggles=new LinkedHashMap<>();
        toggles.put("format.onSave",()->settings.formatOnSave);toggles.put("run.onSave",()->settings.autoRunOnSave);
        toggles.put("tests.onSave",()->settings.autoTestOnSave);toggles.put("tests.auto",()->settings.autoTest);
        toggles.put("run.clearOutputBefore",()->settings.clearOutputBeforeRun);toggles.put("view.coverageBackgrounds",()->settings.showCoverageBackgrounds);
        toggles.put("showPressedShortcuts",()->settings.showPressedShortcuts);toggles.put("settings.shortcutSymbols",()->settings.shortcutSymbols);
        fixture("public class C {}\n",0,0);
        for(var entry:toggles.entrySet()){boolean before=edt(()->entry.getValue().getAsBoolean());action(entry.getKey());check(edt(()->entry.getValue().getAsBoolean())!=before,"toggle "+entry.getKey());
            action(entry.getKey());check(edt(()->entry.getValue().getAsBoolean())==before,"toggle restore");pass(entry.getKey(),"setting changes and restores through actual callback");}
        for(var style:dev.turboj.formatting.StandardFormatters.values()) {action("format.style."+style.id());check(edt(()->settings.formatter).equals(style.id()),"formatter setting");pass("format.style."+style.id(),"active formatter setting matches selection");}
        for(String id:dev.turboj.ui.themes.Theme.BUNDLED){action("theme."+id);check(edt(()->settings.theme).equals(id),"theme setting");pass("theme."+id,"active theme changes");}
        int font=edt(()->frame.editor().getFont().getSize());action("fontUp");check(edt(()->frame.editor().getFont().getSize())==font+2,"font up");
        action("fontDown");check(edt(()->frame.editor().getFont().getSize())==font,"font restored");pass("fontUp","actual editor font increased");pass("fontDown","actual editor font restored");
        for(String profile:List.of("intellij-macos","eclipse-macos","vscode-macos","turboj-macos")){action("keymap.profile."+profile);check(edt(()->frame.commandKeymaps().selectedId()).equals(profile),"profile");pass("keymap.profile."+profile,"active profile changes");}
    }
    static void javaStructure()throws Exception {
        String members="public class C { private int count; private String name; }";
        for(var e:Map.of("generateConstructor","C(","generateGetters","getCount(","generateSetters","setCount(","generateGetterSetter","getCount(","generateEquals","equals(","generateHashCode","hashCode(","generateToString","toString(","generateJavadoc","/**").entrySet())
            semantic("java."+e.getKey(),members,members.indexOf("C {"),e.getValue());
        semantic("java.implementMethods","public class C implements java.util.function.Function<String,Integer> {}",13,"apply(");
        String derived="class Base { public int value(){return 42;} } public class C extends Base {}";
        semantic("java.overrideMethods",derived,derived.indexOf("C extends"),"super.value()");
        String body="public class C { void f(){ System.out.println(42); } }";
        semantic("java.surroundIf",body,body.indexOf("println"),"if (");
        semantic("java.surroundTryFinally",body,body.indexOf("println"),"finally");
        String incomplete="public class C { void f(){ System.out.println(42)\n} }";
        fixture(incomplete,incomplete.indexOf("42"),incomplete.indexOf("42"));action("java.completeStatement");
        await(()->frame.statusMessages().contains("The caret must end"),"complete statement invalid caret refusal");check(edt(()->frame.editor().getText()).equals(incomplete),"invalid caret does not modify source");
        fixture(incomplete,incomplete.indexOf('\n'),incomplete.indexOf('\n'));action("java.completeStatement");await(()->frame.editor().getText().contains("println(42);"),"complete statement");compile(edt(()->frame.editor().getText()));pass("java.completeStatement","invalid caret rejected; semicolon inserted at valid caret; javac succeeds");
        String methods="public class C {\n  int a(){ return 1; }\n  int b(){ return 2; }\n}\n";
        for(String id:List.of("editor.moveStatementUp","editor.moveCodeBlockUp","editor.moveStatementDown","editor.moveCodeBlockDown")) {
            int at=methods.indexOf(id.endsWith("Up")?"b()":"a()");fixture(methods,at,at);action(id);
            await(()->frame.editor().getText().indexOf("b()")<frame.editor().getText().indexOf("a()"),id);compile(edt(()->frame.editor().getText()));action("undo");await(()->frame.editor().getText().equals(methods),"move undo");pass(id,"whole methods swap; javac; one Undo");
        }
        for(String id:List.of("editor.moveElementLeft","editor.moveElementRight")) {
            String array="public class C { int[] data = {1, 2, 3}; }";int at=array.indexOf(id.endsWith("Left")?"2,":"1,");
            fixture(array,at,at);action(id);await(()->OverlayHost.active(frame)||frame.editor().getText().contains("{2, 1, 3}"),"element move");if(edt(()->OverlayHost.active(frame)))button("Apply");
            await(()->frame.editor().getText().contains("{2, 1, 3}"),"element changed");compile(edt(()->frame.editor().getText()));action("undo");await(()->frame.editor().getText().equals(array),"element undo");pass(id,"literal elements swap; javac; one Undo");
        }
        String selected="public class C { int f(){ int count=1; return count+2; } }";int at=selected.indexOf("count+2");
        for(var e:Map.of("selectCurrentStatement","return count+2;","selectCurrentMethod","int f()","selectCurrentBlock","{ int count","selectEnclosingType","public class C","selectMatchingBrackets","{ int count","expandSelection","count").entrySet()) {
            fixture(selected,at,at);action("editor."+e.getKey());await(()->frame.editor().getSelectedText().contains(e.getValue()),e.getKey());
            pass("editor."+e.getKey(),"AST selection contains expected Java construct");action("editor.shrinkSelection");await(()->frame.editor().caretOffset()==at&&frame.editor().getSelectedText().isEmpty(),"shrink");pass("editor.shrinkSelection","restores prior source caret");
        }
    }
    static void semantic(String id,String before,int caret,String expected)throws Exception {
        fixture(before,caret,caret);action(id);dialog();check(edt(()->frame.editor().getText()).equals(before),id+" preview no edit");
        key(KeyEvent.VK_ESCAPE,0);await(()->!OverlayHost.active(frame)&&frame.editor().isFocusOwner(),"cancel preview");check(edt(()->frame.editor().getText()).equals(before),"cancel no edit");
        action(id);dialog();button("Apply");await(()->frame.editor().getText().contains(expected)&&!frame.editor().getText().equals(before),id+" applied");
        String after=edt(()->frame.editor().getText());compile(after);action("undo");await(()->frame.editor().getText().equals(before),id+" undo");
        action("redo");await(()->frame.editor().getText().equals(after),id+" redo");pass(id,"preview; Escape unchanged; Apply expected member; javac; Undo/Redo");
    }
    static void compile(String source)throws Exception {Path root=Files.createTempDirectory("turboj-keyboard-javac-");Path file=Files.writeString(root.resolve("C.java"),source);
        var bytes=new java.io.ByteArrayOutputStream();int result=ToolProvider.getSystemJavaCompiler().run(null,bytes,bytes,"-proc:none","-d",root.toString(),file.toString());check(result==0,"javac: "+bytes);}
    static void fixture(String source,int start,int end)throws Exception{activateFixture();edt(()->{frame.intelligence().dismiss();frame.aiCompletion().dismiss();frame.editor().setText(source);frame.editor().setSelection(start,end);frame.editor().requestFocusInWindow();return null;});await(()->frame.editor().isFocusOwner(),"fixture focus");}
    static void action(String id)throws Exception {var keys=edt(()->frame.commandKeymaps().commandBindings(frame.commandRegistry(),id).getFirst().sequence().strokes());for(var k:keys){var stroke=SwingKeys.toSwing(k);key(stroke.getKeyCode(),stroke.getModifiers());}}
    static void button(String title)throws Exception {for(int n=0;n<80;n++){if(edt(()->focus() instanceof JButton b&&b.getText().equals(title))){key(KeyEvent.VK_SPACE,0);return;}key(KeyEvent.VK_TAB,0);Thread.sleep(10);}throw new AssertionError("Unreachable button "+title);}
    static void dialog()throws Exception{await(()->OverlayHost.active(frame)&&focus()!=null&&SwingUtilities.isDescendingFrom(focus(),OverlayHost.dialogs(frame).getLast()),"dialog");}
    static Component focus(){return KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();}
    static void key(int code,int mods)throws Exception{
        activateFixture();
        await(()->focus()!=null&&focus()!=frame&&ownedWindow(SwingUtilities.getWindowAncestor(focus())),"focus settled before key");edt(()->{var q=Toolkit.getDefaultToolkit().getSystemEventQueue();var owner=focus();q.postEvent(new KeyEvent(owner,KeyEvent.KEY_PRESSED,System.currentTimeMillis(),mods,code,KeyEvent.CHAR_UNDEFINED));q.postEvent(new KeyEvent(owner,KeyEvent.KEY_RELEASED,System.currentTimeMillis(),mods,code,KeyEvent.CHAR_UNDEFINED));return null;});edt(()->null);}
    static void activateFixture()throws Exception {if(edt(()->!ownedWindow(KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow()))){System.out.println("Driver reactivates fixture window after host focus loss");edt(()->{frame.toFront();Desktop.getDesktop().requestForeground(true);return null;});await(()->ownedWindow(KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow()),"fixture window active");}}
    static boolean ownedWindow(Window window){for(Window w=window;w!=null;w=w.getOwner())if(w==frame)return true;return false;}
    static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);}
    static void pass(String id,String oracle){report.append(id).append('\t').append(oracle).append('\n');System.out.println("PASS "+id+": "+oracle);}
    static void await(Callable<Boolean> test,String name)throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(40), nextActivation=0;
        while(System.nanoTime()<end){
            if(edt(test))return;
            // Native worker JVMs can activate/deactivate the application on macOS. Restore only
            // the owned window, never an internal focus target or the expected application state.
            if(System.nanoTime()>=nextActivation){
                nextActivation=System.nanoTime()+TimeUnit.MILLISECONDS.toNanos(500);
                if(edt(()->frame.isShowing()&&!ownedWindow(KeyboardFocusManager.getCurrentKeyboardFocusManager().getActiveWindow()))){
                    System.out.println("Driver reactivates fixture window while waiting: "+name);
                    edt(()->{frame.toFront();Desktop.getDesktop().requestForeground(true);return null;});
                }
            }
            Thread.sleep(10);
        }
        throw new AssertionError(name+"; focus="+edt(()->String.valueOf(focus()))+"; active="+edt(()->frame.isActive())+"; overlay="+edt(()->OverlayHost.active(frame))+"; source="+edt(()->frame.editor().getText())+"; status="+edt(()->frame.statusMessages()));
    }
    static <T>T edt(Callable<T> action)throws Exception{var f=new FutureTask<>(action);SwingUtilities.invokeLater(f);return f.get(50,TimeUnit.SECONDS);}
}
