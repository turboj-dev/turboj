package dev.turboj.app;

import dev.turboj.commandj.swing.*;
import dev.turboj.config.Settings;
import dev.turboj.refactorj.api.JavaStructure;
import dev.turboj.turbotest.JUnitRuntime;
import dev.turboj.ui.EditorComponent;
import dev.turboj.ui.themes.Theme;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.nio.file.*;
import java.util.Map;
import java.util.List;
import java.util.concurrent.*;
import javax.swing.*;

/** Programmatic fixtures/command dispatch, real native Swing Enter/Escape and focus. */
public final class UsagesNavigationAcceptance {
    private static TurboFrame frame;
    public static void main(String[] args) throws Exception {
        System.setProperty("apple.laf.useScreenMenuBar","false");
        try(var junit=new JUnitRuntime()){ for(boolean maven:List.of(false,true)) exercise(junit,maven); }
        System.out.println("PASS plain/Maven Find Usages and test navigation; no physical keyboard-layout claim.");
    }
    private static void exercise(JUnitRuntime junit,boolean maven) throws Exception {
        Path root=Files.createTempDirectory("turboj-java06-"),source=root.resolve("src/main/java/demo/Calculator.java"),
                test=root.resolve("src/test/java/demo/CalculatorTest.java"),alternative=root.resolve("src/test/java/other/PriceChecks.java"),
                decoy=root.resolve("src/test/java/other/CalculatorTest.java");
        for(Path path:List.of(source,test,alternative,decoy,root.resolve(".turboj/source-roots.txt")))Files.createDirectories(path.getParent());
        Files.writeString(root.resolve(".turboj/source-roots.txt"),"src/main/java\nsrc/test/java\n");
        Files.writeString(root.resolve(".turboj/classpath.txt"),String.join("\n",junit.compilationClasspath().stream().map(Path::toString).toList()));
        String original="package demo; public class Calculator { public int add(int a,int b){return a+b;} }";
        Files.writeString(source,original);
        Files.writeString(test,"package demo;\n\npublic class CalculatorTest {\n    @org.junit.jupiter.api.Test\n    void verifies() {\n        new Calculator().add(1, 2);\n    }\n}\n");
        Files.writeString(alternative,"package other;\n\npublic class PriceChecks {\n    @org.junit.jupiter.api.Test\n    void alternative() {\n        new demo.Calculator().add(3, 4);\n    }\n}\n");
        Files.writeString(decoy,"package other; public class CalculatorTest {}");
        if(maven)Files.writeString(root.resolve("pom.xml"),"<project><modelVersion>4.0.0</modelVersion><groupId>fixture</groupId><artifactId>navigation</artifactId><version>1</version><properties><maven.compiler.release>21</maven.compiler.release></properties><dependencies><dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><version>5.13.4</version><scope>test</scope></dependency></dependencies></project>");
        System.setProperty("turboj.config",Files.createTempDirectory("turboj-navigation-settings-").toString());
        frame=edt(() -> { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());var f=new TurboFrame(new Settings(),Map.of());f.setVisible(true);f.toFront();if(maven)f.mavenProjects().open(root);else f.openPath(source);return f; });
        try {
            if(maven){await(() -> frame.mavenProjects().workspace()!=null&&!frame.mavenProjects().busy(),"Maven import completes");check(edt(() -> frame.mavenProjects().workspace().resolved()),"real Maven dependencies resolved");edt(() -> {frame.openPath(source);return null;});}
            await(() -> source.equals(frame.currentFile()),"source opens");command("navigation.goToTest");
            await(() -> test.equals(frame.currentFile()),"default test direct despite same filename in another package");
            check(edt(() -> OverlayHost.dialogs(frame).isEmpty()),"unique package counterpart needs no chooser");
            command("navigation.goToTest");await(() -> source.equals(frame.currentFile()),"default source direct");
            edt(() -> {frame.editor().gotoOffset(original.indexOf("add("));return null;});
            command("navigation.findUsages");await(() -> dialog("Find Usages")!=null,"alias opens Find Usages");
            check(edt(() -> results().getModel().getSize()==2),"two actual JDT usages");
            check(edt(() -> component(picker(),JSplitPane.class).getOrientation()==JSplitPane.HORIZONTAL_SPLIT),"horizontal usage/editor split");
            previewMatches();
            edt(() -> {frame.applyTheme(Theme.bundled("vscode-monokai"));return null;});
            check(edt(() -> preview().theme()==frame.editor().theme()&&preview().getFont().equals(frame.editor().getFont())),"preview follows active theme/font");
            focus(); arrows("result list");
            focusFilter(); arrows("filter field");
            check(edt(() -> component(picker(),JTextField.class).isFocusOwner()),"arrows preserve filter focus");
            edt(() -> {component(picker(),JTextField.class).setText("no-such-usage");return null;});
            key(KeyEvent.VK_DOWN);key(KeyEvent.VK_UP);
            check(edt(() -> results().getSelectedIndex()==-1),"arrows with empty filter results are safe");
            edt(() -> {component(picker(),JTextField.class).setText("PriceChecks");return null;});
            check(edt(() -> results().getModel().getSize()==1),"filter identifies alternative");previewMatches();
            await(() -> {preview().requestFocusInWindow();return preview().isFocusOwner();},"preview focus");
            String captured=edt(() -> preview().getText());
            key(KeyEvent.VK_BACK_SPACE);
            edt(() -> {var p=preview();KeyboardFocusManager.getCurrentKeyboardFocusManager().dispatchEvent(new KeyEvent(p,KeyEvent.KEY_TYPED,System.currentTimeMillis(),0,KeyEvent.VK_UNDEFINED,'x'));return null;});
            check(edt(() -> preview().isReadOnly()&&preview().getText().equals(captured)&&source.equals(frame.currentFile())),"preview typing cannot edit source or switch main buffer");
            key(KeyEvent.VK_TAB);await(() -> !preview().isFocusOwner(),"Tab leaves preview without trapping focus");
            focusFilter();
            if(!maven&&System.getProperty("turboj.acceptance.screenshot")!=null)edt(() -> {
                var image=new java.awt.image.BufferedImage(frame.getWidth(),frame.getHeight(),java.awt.image.BufferedImage.TYPE_INT_RGB);
                var graphics=image.createGraphics();frame.paint(graphics);graphics.dispose();
                javax.imageio.ImageIO.write(image,"png",Path.of(System.getProperty("turboj.acceptance.screenshot")).toFile());return null;
            });
            key(KeyEvent.VK_ENTER);
            await(() -> alternative.equals(frame.currentFile()),"Enter opens usage");
            check(edt(() -> frame.editor().getText().substring(frame.editor().caretOffset()).startsWith("add(")),"caret at actual call");
            command("back");await(() -> source.equals(frame.currentFile()),"history returns to source");
            check(edt(() -> frame.editor().caretOffset()==original.indexOf("add(")),"history restores caret");
            command("navigation.relatedTests");await(() -> dialog("Related Tests / Source")!=null,"alternatives chooser opens");
            edt(() -> {var list=results();for(int i=0;i<list.getModel().getSize();i++)if(list.getModel().getElementAt(i) instanceof JavaStructure.Target t&&t.file().equals(alternative)&&t.kind()==JavaStructure.TargetKind.TEST_USAGE){list.setSelectedIndex(i);return null;}throw new AssertionError("No verified alternative");});
            focus();key(KeyEvent.VK_ENTER);await(() -> alternative.equals(frame.currentFile()),"verified alternative opens");
            command("navigation.goToTest");await(() -> dialog("Related Tests / Source")!=null,"test without counterpart offers production target");
            focus();key(KeyEvent.VK_ENTER);await(() -> source.equals(frame.currentFile()),"reverse usage opens declaration");
            command("navigation.relatedTests");await(() -> dialog("Related Tests / Source")!=null,"chooser reopens");
            int caret=edt(() -> frame.editor().caretOffset());focus();key(KeyEvent.VK_ESCAPE);await(() -> frame.editor().isFocusOwner(),"Escape restores focus");
            check(edt(() -> frame.editor().caretOffset()==caret),"Escape preserves caret");
            command("navigation.findUsages");await(() -> dialog("Find Usages")!=null,"stale-result control opens");
            edt(() -> {frame.editor().setText(original+"\n");return null;});focus();key(KeyEvent.VK_ENTER);
            await(() -> dialog("Find Usages")==null,"stale chooser closes");
            check(edt(() -> source.equals(frame.currentFile())&&frame.statusMessages().contains("Source/workspace changed")),"changed source rejects stale navigation");
            check(Files.readString(source).equals(original),"unsaved control preserves disk");
            System.out.println("PASS mode="+(maven?"Maven":"plain")+" fixture="+root);
        }finally{edt(() -> {frame.shutdown();frame.dispose();return null;});}
    }
    private static void command(String id)throws Exception{edt(() -> {frame.runCommand(id);return null;});}
    private static EmbeddedDialog dialog(String title){return OverlayHost.dialogs(frame).stream().filter(d -> d.isVisible()&&d.getTitle().equals(title)).findFirst().orElse(null);}
    private static Container picker(){return OverlayHost.dialogs(frame).stream().filter(Component::isVisible).findFirst().orElseThrow();}
    private static <T>T component(Container parent,Class<T> type){for(Component child:parent.getComponents()){if(type.isInstance(child))return type.cast(child);if(child instanceof Container c){T found=component(c,type);if(found!=null)return found;}}return null;}
    private static JList<?> results(){return component(picker(),JList.class);}
    private static EditorComponent preview(){return component(picker(),EditorComponent.class);}
    private static void previewMatches()throws Exception{
        check(edt(() -> {var target=(JavaStructure.Target)results().getSelectedValue();return preview().getText().equals(Files.readString(target.file()))
            &&preview().getSelectedText().equals(preview().getText().substring(target.start(),target.end()));}),"preview shows full source and exact selected usage");
    }
    private static void focusFilter()throws Exception{
        await(() -> {var input=component(picker(),JTextField.class);if(!input.isFocusOwner())input.requestFocusInWindow();return input.isFocusOwner();},"filter focus");
    }
    private static void arrows(String context)throws Exception{
        key(KeyEvent.VK_DOWN);check(edt(() -> results().getSelectedIndex()==1),context+" Down selects next usage");
        previewMatches();
        key(KeyEvent.VK_DOWN);check(edt(() -> results().getSelectedIndex()==1),context+" Down stays at last usage");
        key(KeyEvent.VK_UP);check(edt(() -> results().getSelectedIndex()==0),context+" Up selects previous usage");
        previewMatches();
        key(KeyEvent.VK_UP);check(edt(() -> results().getSelectedIndex()==0),context+" Up stays at first usage");
    }
    private static void focus()throws Exception{
        edt(() -> {
            if(!frame.isActive()) {
                System.out.println("Driver reactivates native window (host focus was outside TurboJ)");
                if(Desktop.isDesktopSupported()&&Desktop.getDesktop().isSupported(Desktop.Action.APP_REQUEST_FOREGROUND))Desktop.getDesktop().requestForeground(true);
                frame.toFront();frame.requestFocus();
            }
            return null;
        });
        await(() -> {if(!results().isFocusOwner())results().requestFocusInWindow();return results().isFocusOwner();},"results focus");
    }
    private static void key(int key)throws Exception{edt(() -> {var manager=KeyboardFocusManager.getCurrentKeyboardFocusManager();var focus=manager.getFocusOwner();if(focus==null)throw new AssertionError("No focus");manager.dispatchEvent(new KeyEvent(focus,KeyEvent.KEY_PRESSED,System.currentTimeMillis(),0,key,KeyEvent.CHAR_UNDEFINED));manager.dispatchEvent(new KeyEvent(focus,KeyEvent.KEY_RELEASED,System.currentTimeMillis(),0,key,KeyEvent.CHAR_UNDEFINED));return null;});}
    private static void check(boolean value,String label){if(!value)throw new AssertionError(label);System.out.println("PASS "+label);}
    private static void await(Callable<Boolean> condition,String label)throws Exception{long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);while(System.nanoTime()<end){if(edt(condition)){System.out.println("PASS "+label);return;}Thread.sleep(25);}throw new AssertionError(label+" status="+edt(() -> frame.statusMessages()));}
    private static <T>T edt(Callable<T> task)throws Exception{var future=new FutureTask<T>(task);SwingUtilities.invokeAndWait(future);return future.get();}
}
