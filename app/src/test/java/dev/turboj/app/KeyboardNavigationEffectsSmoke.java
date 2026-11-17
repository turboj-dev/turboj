package dev.turboj.app;

import static dev.turboj.app.KeyboardActionEffectsSmoke.*;
import static dev.turboj.app.KeyboardBranchEffectsSmoke.*;
import dev.turboj.commandj.swing.*;
import dev.turboj.config.Settings;
import dev.turboj.ui.ProjectSearchDialog;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import javax.swing.*;

/** Actual search/navigation callbacks with exact caret, file and source-effect assertions. */
public final class KeyboardNavigationEffectsSmoke {
    static Path c,d;
    static final String SOURCE="public class C {\n int first(){return 1;}\n int second(){return first();}\n}\n";
    public static void main(String[] args)throws Exception {
        output=Files.createDirectories(Path.of("app/target/keyboard-navigation-effects"));work=Files.createTempDirectory("turboj-navigation-effects-").toRealPath();
        System.setProperty("turboj.config",Files.createTempDirectory("turboj-effects-settings-").toString());System.setProperty("apple.laf.useScreenMenuBar","false");
        Files.createDirectories(work.resolve(".turboj"));Files.writeString(work.resolve(".turboj/project.properties"),"kind=java\njava.release=25\nname=Navigation\n");
        Path root=Files.createDirectories(work.resolve("src/main/java"));c=Files.writeString(root.resolve("C.java"),SOURCE);d=Files.writeString(root.resolve("D.java"),"class D extends C { int use(){return second();} }");
        frame=edt(()->{var f=new TurboFrame(new Settings(),Map.of());f.setVisible(true);f.toFront();Desktop.getDesktop().requestForeground(true);f.openPath(work);return f;});
        try {
            await(()->frame.mavenProjects().hasProject(),"project");open(c);search();
            fixture(SOURCE,SOURCE.indexOf("first()"),SOURCE.indexOf("first()"));
            action("navigation.nextMethod");await(()->frame.editor().caretOffset()==SOURCE.indexOf("second()"),"next method");pass("navigation.nextMethod","caret at second declaration");
            action("navigation.previousMethod");await(()->frame.editor().caretOffset()==SOURCE.indexOf("first()"),"previous method");pass("navigation.previousMethod","caret at first declaration");
            action("navigation.callHierarchy");await(()->frame.editor().caretOffset()==SOURCE.lastIndexOf("first()"),"caller");pass("navigation.callHierarchy","navigates to resolved call site");
            fixture(SOURCE,SOURCE.indexOf("second()"),SOURCE.indexOf("second()"));action("navigation.calledMethods");await(()->frame.editor().caretOffset()==SOURCE.lastIndexOf("first()"),"callee");pass("navigation.calledMethods","navigates to actual call in selected method");
            action("declaration");await(()->frame.editor().caretOffset()==SOURCE.indexOf("first()"),"declaration");pass("declaration","resolved call navigates to declaration");
            action("back");await(()->frame.editor().caretOffset()==SOURCE.lastIndexOf("first()"),"back");pass("back","restores call-site caret");action("forward");await(()->frame.editor().caretOffset()==SOURCE.indexOf("first()"),"forward");pass("forward","restores declaration caret");
            fixture(SOURCE,SOURCE.indexOf('{'),SOURCE.indexOf('{'));action("navigation.matchingBrace");await(()->frame.editor().caretOffset()==SOURCE.lastIndexOf('}'),"matching brace");pass("navigation.matchingBrace","caret at matching class brace");
            fixture(SOURCE,SOURCE.indexOf("C {"),SOURCE.indexOf("C {"));action("navigation.goToImplementation");await(()->d.equals(frame.currentFile()),"implementation");check(edt(()->frame.editor().caretOffset())==6,"subclass declaration offset");pass("navigation.goToImplementation","opens concrete subclass at its declaration");
            action("navigation.typeHierarchy");dialog();check(edt(()->component(top(),JList.class).getModel().getSize())>=2,"hierarchy has base and subclass");key(KeyEvent.VK_ESCAPE,0);await(()->!OverlayHost.active(frame),"hierarchy close");pass("navigation.typeHierarchy","resolved base/subclass entries; Escape preserves source");
            for(String id:List.of("navigation.goToFile","navigation.goToType","navigation.goToSymbol","navigation.searchEverywhere")) {
                open(c);action(id);dialog();replace(id.endsWith("Symbol")?"use":"D");await(()->((ProjectSearchDialog)top()).results().getModel().getSize()>0,"indexed search "+id);key(KeyEvent.VK_ENTER,0);await(()->d.equals(frame.currentFile())&&!OverlayHost.active(frame),"search opens D");pass(id,"query finds real project item; Enter opens correct source");
            }
            action("navigation.previousEditorTab");await(()->c.equals(frame.currentFile()),"previous file");pass("navigation.previousEditorTab","cycles to previous open source");action("navigation.nextEditorTab");await(()->d.equals(frame.currentFile()),"next file");pass("navigation.nextEditorTab","cycles to next open source");
            action("navigation.recentLocations");dialog();key(KeyEvent.VK_ENTER,0);await(()->!OverlayHost.active(frame)&&c.equals(frame.currentFile()),"recent location");pass("navigation.recentLocations","Enter restores preceding C.java visit");
            open(d);action("navigation.recentFiles");dialog();key(KeyEvent.VK_ENTER,0);await(()->!OverlayHost.active(frame)&&c.equals(frame.currentFile()),"recent file");pass("navigation.recentFiles","Enter opens actual first recent source");
            fixture(SOURCE,0,0);replace("// first edit\n");open(d);edt(()->{frame.editor().gotoOffset(0);return null;});replace("// second edit\n");
            action("navigation.previousEditLocation");await(()->c.equals(frame.currentFile()),"previous edit");pass("navigation.previousEditLocation","returns to previous edited document");
            action("navigation.nextEditLocation");await(()->d.equals(frame.currentFile()),"next edit");pass("navigation.nextEditLocation","returns to newer edited document");
        }finally{Files.writeString(output.resolve("effects.tsv"),report);edt(()->{for(var dialog:List.copyOf(OverlayHost.dialogs(frame)))dialog.dispose();frame.shutdown();frame.dispose();return null;});}
    }
    static void search()throws Exception {
        fixture("one two one\nlast\n",0,0);action("find");dialog();replace("one");key(KeyEvent.VK_ENTER,0);await(()->frame.editor().getSelectedText().equals("one"),"find first");pass("find","selected actual query match");
        int first=edt(()->frame.editor().selectionStart());action("findNext");await(()->frame.editor().selectionStart()!=first,"find next");pass("findNext","moves to other occurrence");action("findPrevious");await(()->frame.editor().selectionStart()==first,"find previous");pass("findPrevious","returns to first occurrence");
        action("replace");dialog();field("Find");replace("one");field("Replace with");replace("three");button("Replace All");check(edt(()->frame.editor().getText()).equals("three two three\nlast\n"),"replace all exact source");key(KeyEvent.VK_ESCAPE,0);await(()->!OverlayHost.active(frame),"replace close");action("undo");await(()->frame.editor().getText().equals("one two one\nlast\n"),"replace one undo");pass("replace","keyboard Replace All; exact source; one Undo");
        action("goto");dialog();replace("2");key(KeyEvent.VK_ENTER,0);await(()->frame.editor().caretOffset()==12,"line 2");pass("goto","Enter moves to requested line start");
    }
    static void open(Path file)throws Exception{edt(()->{frame.openPath(file);return null;});await(()->file.equals(frame.currentFile())&&frame.editor().isFocusOwner(),"open");}
    static void fixture(String source,int start,int end)throws Exception{edt(()->{frame.intelligence().dismiss();frame.aiCompletion().dismiss();if(!frame.editor().getText().equals(source)){frame.editor().selectAll();frame.editor().replaceSelection(source);}frame.editor().setSelection(start,end);frame.editor().requestFocusInWindow();return null;});await(()->frame.editor().isFocusOwner(),"fixture focus");}
    static <T>T component(Container root,Class<T> type){for(Component c:root.getComponents()){if(type.isInstance(c))return type.cast(c);if(c instanceof Container child){T found=component(child,type);if(found!=null)return found;}}return null;}
}
