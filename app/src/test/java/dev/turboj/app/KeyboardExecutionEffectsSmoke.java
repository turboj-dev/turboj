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

/** Actual compiler, program worker, JShell, formatter and persistent-settings effects via CommandJ shortcuts. */
public final class KeyboardExecutionEffectsSmoke {
    public static void main(String[] args)throws Exception {
        output=Files.createDirectories(Path.of("app/target/keyboard-execution-effects"));work=Files.createTempDirectory("turboj-execution-effects-").toRealPath();
        System.setProperty("turboj.config",Files.createTempDirectory("turboj-effects-settings-").toString());System.setProperty("apple.laf.useScreenMenuBar","false");
        Path file=Files.writeString(work.resolve("C.java"),"public class C {}\n");settings=new Settings();
        frame=edt(()->{var f=new TurboFrame(settings,Map.of());f.setVisible(true);f.toFront();Desktop.getDesktop().requestForeground(true);f.openPath(file);return f;});
        try {
            activateFixture();await(()->file.equals(frame.currentFile())&&frame.editor().isFocusOwner(),"source");programs();repl();formatters();preferences();
        } finally {Files.writeString(output.resolve("effects.tsv"),report);edt(()->{for(var d:List.copyOf(OverlayHost.dialogs(frame)))d.dispose();frame.getGraphicsConfiguration().getDevice().setFullScreenWindow(null);frame.shutdown();frame.dispose();return null;});}
    }
    static void programs()throws Exception {
        for(String id:List.of("run","isolated","run.context","smart.action")) {
            String marker="KEY04_"+id.replace('.','_');String code="public class C { public static void main(String[] args){System.out.println(\""+marker+"\");} }";
            fixture(code,0,0);action("clear");action(id);await(()->frame.console().text().contains(marker)&&!frame.isRunning(),"real run "+id);pass(id,"real Java stdout from current source and completed process");
        }
        String wait="public class C { public static void main(String[] args)throws Exception{System.out.println(\"READY_TO_STOP\");Thread.sleep(30000);} }";
        fixture(wait,0,0);action("clear");action("run");await(()->frame.console().text().contains("READY_TO_STOP")&&frame.isRunning(),"long program");action("stop");await(()->!frame.isRunning(),"stopped");pass("stop","stops running owned Java program after actual readiness output");
        String input="public class C { public static void main(String[] args){System.out.println(\"READY_FOR_EOF\");new java.util.Scanner(System.in).hasNextLine();System.out.println(\"EOF_RECEIVED\");} }";
        fixture(input,0,0);action("clear");action("run");await(()->frame.console().text().contains("READY_FOR_EOF")&&frame.isRunning(),"stdin waiting");action("eof");await(()->frame.console().text().contains("EOF_RECEIVED")&&!frame.isRunning(),"EOF completed");pass("eof","closes worker stdin; blocked Scanner proceeds and main finishes");
        action("clear");await(()->frame.console().text().isEmpty(),"console cleared");pass("clear","actual console document emptied");
        action("console.commandLine");await(()->frame.console().commandLine().isFocusOwner(),"shell command focus");replace("! printf 'KEY04_SHELL\\n'");key(KeyEvent.VK_ENTER,0);await(()->frame.outputCommands().lastResult()!=null&&frame.outputCommands().lastResult().succeeded()&&frame.console().text().contains("KEY04_SHELL"),"real shell");pass("console.commandLine","keyboard command row launches real owned printf process");
        replace("! sleep 30");key(KeyEvent.VK_ENTER,0);await(()->frame.outputCommands().isRunning(),"shell running");action("console.cancelCommand");await(()->!frame.outputCommands().isRunning()&&frame.outputCommands().lastResult().cancelled(),"shell cancelled");pass("console.cancelCommand","actual owned shell command cancelled");key(KeyEvent.VK_ESCAPE,0);await(()->frame.editor().isFocusOwner(),"shell Escape");
    }
    static void repl()throws Exception {
        fixture("21*2",0,4);action("evaluateSelection");await(()->frame.repl().text().contains("42"),"selection result");pass("evaluateSelection","actual JShell evaluates selected expression to 42");
        action("view.focusEditor");String source="public class C { void f(){int keyboardValue = 7;} }";int at=source.indexOf("keyboardValue");fixture(source,at,at);
        var prior=edt(()->frame.currentResult());action("compile");await(()->frame.currentResult()!=null&&frame.currentResult()!=prior&&frame.currentResult().success(),"compiled statement fixture");pass("compile","real compiler accepts current Java source");
        action("evaluateStatement");
        await(()->frame.repl().text().contains("keyboardValue")&&frame.repl().text().contains("7"),"statement result");pass("evaluateStatement","one invocation after explicit compile evaluates current declaration through actual JShell");
        action("replReset");await(()->frame.repl().text().contains("Session reset.")&&focus() instanceof JTextField f&&f.isEditable()&&SwingUtilities.isDescendingFrom(f,frame.repl()),"REPL ready after reset");replace("keyboardValue");key(KeyEvent.VK_ENTER,0);await(()->frame.repl().text().contains("cannot find symbol"),"reset removed binding");pass("replReset","new JShell session no longer resolves prior variable");
        action("output");await(()->!frame.repl().isShowing(),"program output selected");pass("output","selects program output instead of JShell");action("repl");await(()->frame.repl().isShowing(),"REPL selected");pass("repl","selects actual JShell panel");action("view.focusEditor");
    }
    static void formatters()throws Exception {
        String source="public class C{int sum(int a,int b){return a+b;}}";
        for(var style:dev.turboj.formatting.StandardFormatters.values()) {
            action("format.style."+style.id());fixture(source,0,0);action("format.document");await(()->!frame.formatting().isPending()&&!frame.editor().getText().equals(source),"formatted "+style.id());String after=edt(()->frame.editor().getText());compile(after);action("undo");await(()->frame.editor().getText().equals(source),"formatter undo");pass("format.document","real "+style.id()+" formatter modifies source; javac; one Undo");
        }
        for(String id:List.of("format.selection","editor.autoIndentLines")){fixture(source,0,source.length());action(id);await(()->!frame.formatting().isPending()&&!frame.editor().getText().equals(source),id);compile(edt(()->frame.editor().getText()));action("undo");await(()->frame.editor().getText().equals(source),"format range undo");pass(id,"real selected-range formatting; javac; one Undo");}
    }
    static void preferences()throws Exception {
        action("settings.general");dialog();tab(c->c instanceof JFormattedTextField);replace("3");key(KeyEvent.VK_TAB,0);boolean old=settings.autoClose;tab(c->c instanceof JCheckBox);key(KeyEvent.VK_SPACE,0);button("OK");await(()->!OverlayHost.active(frame),"settings applied");check(settings.tabWidth==3&&settings.autoClose!=old,"settings values");pass("settings.general","keyboard spinner and checkbox changes applied");
        Path theme=work.resolve("theme.json");try(var stream=KeyboardExecutionEffectsSmoke.class.getResourceAsStream("/themes/classic.json")){Files.write(theme,stream.readAllBytes());}
        action("theme.load");dialog();field("File name");replace(theme.toString());key(KeyEvent.VK_ENTER,0);await(()->!OverlayHost.active(frame)&&theme.toString().equals(settings.externalThemePath),"external theme loaded");pass("theme.load","real JSON chooser selection changes theme preference");
        action("cherry.configure");dialog();replace(work.resolve("fixture-cli.jar").toString());key(KeyEvent.VK_ENTER,0);await(()->!OverlayHost.active(frame),"Cherry preference");check(settings.cherryTestExecutable.equals(work.resolve("fixture-cli.jar").toString()),"configured Cherry path");pass("cherry.configure","keyboard input changes isolated CLI configuration without execution");
        action("fullscreen");await(()->frame.getGraphicsConfiguration().getDevice().getFullScreenWindow()==frame,"fullscreen enabled");action("fullscreen");await(()->frame.getGraphicsConfiguration().getDevice().getFullScreenWindow()!=frame,"fullscreen restored");pass("fullscreen","enters/exits actual native fullscreen; original mode restored");
    }
}
