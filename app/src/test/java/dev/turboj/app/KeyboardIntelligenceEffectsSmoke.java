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

/** Real JDT proposals/documentation and application-menu callbacks; no provider or command stubs. */
public final class KeyboardIntelligenceEffectsSmoke {
    public static void main(String[] args)throws Exception {
        output=Files.createDirectories(Path.of("app/target/keyboard-intelligence-effects"));work=Files.createTempDirectory("turboj-intelligence-effects-").toRealPath();
        System.setProperty("turboj.config",Files.createTempDirectory("turboj-intelligence-settings-").toString());System.setProperty("apple.laf.useScreenMenuBar","false");
        Path file=Files.writeString(work.resolve("C.java"),"public class C {}\n");settings=new Settings();
        frame=edt(()->{var f=new TurboFrame(settings,Map.of());f.setVisible(true);f.toFront();Desktop.getDesktop().requestForeground(true);f.openPath(file);return f;});
        try {await(()->file.equals(frame.currentFile())&&frame.editor().isFocusOwner(),"source");completion();documentation();menus();ai();}
        finally{Files.writeString(output.resolve("effects.tsv"),report);edt(()->{for(var d:List.copyOf(OverlayHost.dialogs(frame)))d.dispose();frame.shutdown();frame.dispose();return null;});}
    }
    static void completion()throws Exception {
        String code="public class C { void f(){String s = \"abc\"; s.} }";int at=code.indexOf("s.}")+2;
        for(String id:List.of("complete")){
            fixture(code,at,at);action(id);await(()->frame.intelligence().alternativesVisible(),"JDT choices");
            JList<?> choices=edt(()->find(frame,JList.class,"completion-choices"));check(choices!=null&&edt(()->choices.getModel().getSize())>1,"real String methods");pass(id,"real JDT proposals for String receiver");
            int selected=edt(choices::getSelectedIndex);action("completion.next");await(()->choices.getSelectedIndex()==selected+1,"next proposal");pass("completion.next","selected JDT proposal advances");
            action("completion.previous");await(()->choices.getSelectedIndex()==selected,"previous proposal");pass("completion.previous","selected JDT proposal returns");
            action("completion.accept");await(()->!frame.editor().getText().equals(code),"accepted actual proposal");pass("completion.accept","selected JDT proposal changes actual source");action("undo");await(()->frame.editor().getText().equals(code),"completion undo");
        }
        String smart="public class C { int count; String content; void f(){ int n = co; } }";int smartAt=smart.indexOf("co;")+2;
        fixture(smart,smartAt,smartAt);action("completion.smart");await(()->frame.intelligence().alternativesVisible(),"smart proposals");
        check(edt(()->frame.intelligence().lastCompletion().proposals().stream().anyMatch(p->p.text().equals("count"))&&!frame.intelligence().lastCompletion().proposals().stream().anyMatch(p->p.text().equals("content"))),"expected-type filtering");
        action("completion.accept");await(()->frame.editor().getText().contains("int n = count;"),"smart result");compile(edt(()->frame.editor().getText()));pass("completion.smart","int-compatible count offered/applied; incompatible String content excluded; javac");
        fixture(code,at,at);action("complete");await(()->frame.intelligence().alternativesVisible(),"dismiss fixture");action("completion.dismiss");await(()->!frame.intelligence().alternativesVisible()&&frame.editor().inlineCompletion()==null,"dismissed");check(edt(()->frame.editor().getText()).equals(code),"dismiss preserves source");pass("completion.dismiss","popup and ghost removed without editing source");
    }
    static void documentation()throws Exception {
        String code="public class C { /** Adds two values. */ int add(int a,int b){return a+b;} int f(){return add(1,2);} }";
        int call=code.lastIndexOf("add(");fixture(code,call,call);action("documentation");await(()->frame.intelligence().documentationVisible(),"real documentation");
        check(edt(()->frame.intelligence().lastDocumentation())!=null,"resolved docs");pass("documentation","resolved source method documentation visible");
        action("documentation.focus");await(()->focus() instanceof JTextArea&&"Java documentation".equals(focus().getAccessibleContext().getAccessibleName()),"documentation text focus");pass("documentation.focus","keyboard focus reaches selectable documentation text");key(KeyEvent.VK_ESCAPE,0);await(()->frame.editor().isFocusOwner()&&!frame.intelligence().documentationVisible(),"docs Escape");
        fixture(code,call+5,call+5);action("completion.parameterInformation");await(()->frame.intelligence().lastSignature()!=null&&!frame.intelligence().lastSignature().signatures().isEmpty(),"signature help");pass("completion.parameterInformation","actual invocation signatures resolved");key(KeyEvent.VK_ESCAPE,0);
        String imports="import java.util.List;\nimport java.util.Map;\npublic class C { List<String> xs; }\n";fixture(imports,imports.indexOf("List<String>"),imports.indexOf("List<String>"));action("organizeImports");dialog();button("Apply changes");
        await(()->frame.editor().getText().contains("import java.util.List;")&&!frame.editor().getText().contains("import java.util.Map;"),"organized imports");compile(edt(()->frame.editor().getText()));pass("organizeImports","unused import removed; used import retained; javac");action("undo");await(()->frame.editor().getText().equals(imports),"imports undo");
    }
    static void menus()throws Exception {
        for(String id:List.of("menu","new","java.generate")){action(id);await(()->MenuSelectionManager.defaultManager().getSelectedPath().length>0,"menu open");key(KeyEvent.VK_ESCAPE,0);edt(()->{MenuSelectionManager.defaultManager().clearSelectedPath();return null;});pass(id,"actual application menu opens via shortcut");}
        String code="public class C { int f(){int n=2;return n;} }";int at=code.indexOf("n=2");fixture(code,at,at);
        for(String id:List.of("refactor.this","refactor.inline")){action(id);dialog();var list=edt(()->KeyboardNavigationEffectsSmoke.component(top(),JList.class));check(edt(()->list.getModel().getSize())>0,"contextual refactorings");key(KeyEvent.VK_ESCAPE,0);await(()->!OverlayHost.active(frame),"chooser cancellation");check(edt(()->frame.editor().getText()).equals(code),"no edit on cancel");pass(id,"real semantic action chooser; Escape preserves source");}
        action("file.newFile");await(()->frame.currentFile()==null&&frame.editor().getText().isEmpty()&&frame.editor().isFocusOwner(),"new file");pass("file.newFile","new empty untitled buffer owns typing focus");
    }
    static void ai()throws Exception {
        action("ai.disabled");await(()->settings.ai.mode()==dev.turboj.ai.AIConfig.Mode.DISABLED,"disabled");pass("ai.disabled","actual configuration disables AI");
        action("ai.test");await(()->frame.statusMessages().contains("AI is disabled"),"disabled test explanation");pass("ai.test","disabled mode explains why no request is sent");
        action("ai.download");await(()->frame.statusMessages().contains("Downloads go through Ollama"),"download unavailable");pass("ai.download","disabled mode refuses download with explanation");
        for(String id:List.of("ai.local","ai.cloud")){action(id);dialog();key(KeyEvent.VK_ESCAPE,0);await(()->!OverlayHost.active(frame),"AI form cancel");check(settings.ai.mode()==dev.turboj.ai.AIConfig.Mode.DISABLED,"cancel no AI mode change");pass(id,"mode-specific settings opens; cancellation preserves disabled config");}
        Object old=Toolkit.getDefaultToolkit().getSystemClipboard().getContents(null);
        try {action("ai.diagnostics");await(()->frame.statusMessages().contains("AI diagnostics copied"),"diagnostics copied");String actual=(String)Toolkit.getDefaultToolkit().getSystemClipboard().getData(DataFlavor.stringFlavor);check(actual.contains("Local runtime:"),"diagnostics data");pass("ai.diagnostics","actual runtime diagnostic text copied to clipboard");}
        finally{if(old instanceof java.awt.datatransfer.Transferable transferable)Toolkit.getDefaultToolkit().getSystemClipboard().setContents(transferable,null);}
    }
}
