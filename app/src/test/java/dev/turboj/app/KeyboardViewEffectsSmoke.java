package dev.turboj.app;

import static dev.turboj.app.KeyboardActionEffectsSmoke.*;
import dev.turboj.commandj.swing.*;
import dev.turboj.config.Settings;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import javax.swing.*;

/** Layout/focus/diagnostic effects through production callbacks; no replacement handlers. */
public final class KeyboardViewEffectsSmoke {
    public static void main(String[] args)throws Exception {
        output=Files.createDirectories(Path.of("app/target/keyboard-view-effects"));work=Files.createTempDirectory("turboj-view-effects-").toRealPath();
        System.setProperty("turboj.config",Files.createTempDirectory("turboj-effects-settings-").toString());System.setProperty("apple.laf.useScreenMenuBar","false");
        Files.createDirectories(work.resolve(".turboj"));Files.writeString(work.resolve(".turboj/project.properties"),"kind=java\njava.release=25\nname=ViewEffects\n");
        Path source=Files.writeString(Files.createDirectories(work.resolve("src/main/java")).resolve("C.java"),"public class C {}\n");
        frame=edt(()->{var f=new TurboFrame(new Settings(),Map.of());f.setVisible(true);f.toFront();Desktop.getDesktop().requestForeground(true);f.openPath(work);return f;});
        try {activateFixture();await(()->frame.mavenProjects().hasProject(),"project");edt(()->{frame.openPath(source);return null;});await(()->source.equals(frame.currentFile())&&frame.editor().isFocusOwner(),"source");
            JSplitPane split=edt(()->KeyboardBranchEffectsSmoke.find(frame,JSplitPane.class,"workspace-output-split"));Component bottom=edt(split::getBottomComponent);
            action("view.focusConsole");await(()->frame.console().hasConsoleFocus()&&bottom.isVisible(),"console focus");pass("view.focusConsole","visible Output owns keyboard focus");
            action("focus");await(()->frame.editor().isFocusOwner(),"focus editor");action("focus");await(()->frame.console().hasConsoleFocus(),"focus console");pass("focus","cycles editor/console actual focus");
            action("view.resizeOutput");await(split::isFocusOwner,"divider focus");int before=edt(split::getDividerLocation);key(KeyEvent.VK_UP,0);await(()->split.getDividerLocation()<before,"output resized");key(KeyEvent.VK_ESCAPE,0);await(()->frame.editor().isFocusOwner(),"resize Escape");pass("view.resizeOutput","Up changes actual divider geometry; Escape returns to editor");
            for(String id:List.of("zoom","view.toggleConsole")){action(id);await(()->!bottom.isVisible(),"output hidden");action(id);await(bottom::isVisible,"output restored");pass(id,"output visibility toggles and restores");}
            action("view.focusExplorer");await(()->frame.mavenProjects().hasExplorerFocus(),"explorer focus");pass("view.focusExplorer","project tree owns keyboard focus");
            action("view.toggleExplorer");await(()->!frame.mavenProjects().panel().isVisible(),"Explorer hidden");action("view.toggleExplorer");await(()->frame.mavenProjects().panel().isVisible(),"Explorer restored");pass("view.toggleExplorer","Explorer visibility toggles and restores");
            action("view.maximizeEditor");await(()->!bottom.isVisible()&&!frame.mavenProjects().panel().isVisible(),"editor maximized");pass("view.maximizeEditor","both panels hidden");action("view.restoreLayout");await(()->bottom.isVisible()&&frame.mavenProjects().panel().isVisible(),"layout restored");pass("view.restoreLayout","both prior panel states restored");
            action("view.focusEditor");await(()->frame.editor().isFocusOwner(),"editor focus");pass("view.focusEditor","editor owns focus");action("view.focusNextPanel");await(()->frame.mavenProjects().hasExplorerFocus(),"next panel");pass("view.focusNextPanel","focus moves to visible Explorer");action("view.focusPreviousPanel");await(()->frame.editor().isFocusOwner(),"previous panel");pass("view.focusPreviousPanel","focus returns to editor");
            action("view.focusTestResults");await(()->frame.outputTabTitle().equals("Tests"),"tests selected");pass("view.focusTestResults","Tests selected");action("view.toggleTestPanel");await(()->!bottom.isVisible(),"tests hidden");action("view.toggleTestPanel");await(()->bottom.isVisible()&&frame.outputTabTitle().equals("Tests"),"tests restored");pass("view.toggleTestPanel","Tests visibility toggles and restores");
            action("escape");await(()->frame.editor().isFocusOwner(),"escape source");pass("escape","tool-panel Escape returns to source");
            action("keymap.assignMissing");await(()->frame.statusMessages().contains("All actions have shortcuts"),"assign missing no-op");pass("keymap.assignMissing","already bound profile remains complete without conflict dialog");
            action("metrics");dialog();key(KeyEvent.VK_ESCAPE,0);await(()->!OverlayHost.active(frame),"metrics close");pass("metrics","actual local metrics displayed; Escape closes");
            diagnostics();
        }finally{Files.writeString(output.resolve("effects.tsv"),report);edt(()->{for(var d:List.copyOf(OverlayHost.dialogs(frame)))d.dispose();frame.shutdown();frame.dispose();return null;});}
    }
    static void diagnostics()throws Exception {
        fixture("public class C { void f(){missingOne();missingTwo();} }",0,0);var previous=edt(()->frame.currentResult());action("compile");await(()->frame.currentResult()!=null&&frame.currentResult()!=previous&&!frame.currentResult().success(),"actual compile errors");
        var errors=edt(()->frame.currentResult().diagnostics().stream().filter(x->x.isError()).sorted(java.util.Comparator.comparingLong(x->x.start())).toList());check(errors.size()>=2,"two real errors");
        action("navigation.nextError");await(()->frame.editor().caretOffset()==errors.getFirst().start(),"next error first");action("navigation.nextError");await(()->frame.editor().caretOffset()==errors.get(1).start(),"next error second");pass("navigation.nextError","moves through actual compiler diagnostics");
        action("navigation.previousError");await(()->frame.editor().caretOffset()==errors.getFirst().start(),"previous error");pass("navigation.previousError","returns to previous actual diagnostic");
        edt(()->{frame.editor().gotoOffset(frame.editor().getText().length());return null;});action("error");await(()->frame.editor().caretOffset()==0,"first error line");pass("error","moves from end of source to first compiler-error line");
        action("view.focusDiagnostics");dialog();key(KeyEvent.VK_ENTER,0);await(()->!OverlayHost.active(frame)&&frame.editor().isFocusOwner(),"diagnostic Enter");pass("view.focusDiagnostics","Enter navigates selected real diagnostic and restores editor focus");
        for(String id:List.of("navigation.nextWarning","navigation.previousWarning")){action(id);await(()->frame.statusMessages().contains("No current warnings"),"no warnings");pass(id,"no-warning state is explicit and preserves source");}
    }
}
