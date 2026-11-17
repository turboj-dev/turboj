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

/** Maven edit forms → actual preview → unsaved XML → undo; no build of edited fixture dependencies. */
public final class KeyboardPomEffectsSmoke {
    public static void main(String[] args)throws Exception {
        output=Files.createDirectories(Path.of("app/target/keyboard-pom-effects"));work=Files.createTempDirectory("turboj-pom-effects-").toRealPath();
        System.setProperty("turboj.config",Files.createTempDirectory("turboj-effects-settings-").toString());System.setProperty("apple.laf.useScreenMenuBar","false");
        String original="<project><modelVersion>4.0.0</modelVersion><groupId>fixture.keyboard</groupId><artifactId>pom-effects</artifactId><version>1</version><packaging>pom</packaging></project>";
        Path pom=Files.writeString(work.resolve("pom.xml"),original);
        frame=edt(()->{var f=new TurboFrame(new Settings(),Map.of());f.setVisible(true);f.toFront();Desktop.getDesktop().requestForeground(true);f.openPath(work);return f;});
        try {
            await(()->frame.mavenProjects().workspace()!=null&&!frame.mavenProjects().busy(),"Maven model");edt(()->{frame.openPath(pom);return null;});await(()->pom.equals(frame.currentFile())&&frame.editor().isFocusOwner(),"POM focus");
            edit("project.pom.add-dependency",List.of("org.junit.jupiter","junit-jupiter","5.13.4","test"),"<artifactId>junit-jupiter</artifactId>",false);
            String dependency=edt(()->frame.editor().getText());
            edit("project.pom.update-dependency-version",List.of("org.junit.jupiter","junit-jupiter","5.13.3"),"<version>5.13.3</version>",false);
            action("undo");await(()->frame.editor().getText().equals(dependency),"restore dependency version");
            edit("project.pom.remove-dependency",List.of("org.junit.jupiter","junit-jupiter"),"<artifactId>junit-jupiter</artifactId>",true);
            edit("project.pom.add-module",List.of("child"),"<module>child</module>",false);
            check(Files.readString(pom).equals(original),"POM previews/applications never silently save disk");pass("project.pom.add-module","all edits remain unsaved; original disk bytes preserved");
            // Restore the valid on-disk model before exercising metadata views.
            fixture(original,0,0);
            for(String id:List.of("project.structure","project.dependencies","project.dependencyTree","project.effectivePom")) {
                action(id);dialog();String text=edt(()->KeyboardRefactoringEffectsSmoke.texts(top()));check(!text.isBlank(),id+" contains actual model text");key(KeyEvent.VK_ESCAPE,0);await(()->!OverlayHost.active(frame),"metadata close");pass(id,"real imported model text is readable; Escape closes view");
            }
            action("project.profiles");dialog();replace("keyboard-profile");key(KeyEvent.VK_ESCAPE,0);await(()->!OverlayHost.active(frame),"profiles cancel");pass("project.profiles","cancel retains current imported configuration");
            action("project.profiles");dialog();replace("keyboard-profile,!disabled-profile");key(KeyEvent.VK_ENTER,0);
            await(()->!frame.mavenProjects().busy()&&frame.mavenProjects().importRequest().activeProfiles().contains("keyboard-profile"),"profiles applied");
            check(edt(()->frame.mavenProjects().importRequest().inactiveProfiles().contains("disabled-profile")),"inactive profile preserved");
            pass("project.profiles","Enter persists active and explicitly inactive profile sets and reimports");
            action("project.settings");dialog();var fields=edt(()->fields(top()));check(fields.size()==6,"Maven settings field count");
            var repo=fields.get(2);tab(c->c==repo);Path local=Path.of(".cache/m2").toAbsolutePath();replace(local.toString());
            tab(c->c instanceof JCheckBox);if(!edt(()->((JCheckBox)focus()).isSelected()))key(KeyEvent.VK_SPACE,0);
            button("OK");await(()->!OverlayHost.active(frame)&&!frame.mavenProjects().busy(),"Maven settings applied");
            check(edt(()->frame.mavenProjects().importRequest().offline()&&frame.mavenProjects().importRequest().localRepository().equals(local)),"offline/repository apply");
            pass("project.settings","keyboard edits local repository and offline preference; model reimports");
            for(String id:List.of("project.reload","maven.refresh")) {
                var before=edt(()->frame.mavenProjects().workspace());action(id);
                await(()->!frame.mavenProjects().busy()&&frame.mavenProjects().workspace()!=before,"model refreshed");pass(id,"new imported workspace replaces prior snapshot");
            }
            for(String id:List.of("project.findFile","project.findModule","navigation.mavenModule")) {
                action(id);dialog();replace(id.equals("project.findFile")?"pom.xml":"pom-effects");key(KeyEvent.VK_ENTER,0);
                await(()->OverlayHost.active(frame)&&top().getTitle().equals("Project Search"),"project search selection");key(KeyEvent.VK_ENTER,0);
                await(()->!OverlayHost.active(frame)&&pom.equals(frame.currentFile()),"project search open");pass(id,"query and keyboard choice open actual module POM");
            }
            String propertyPom=original.replace("</project>","<properties><keyboard.value>42</keyboard.value></properties><name>${keyboard.value}</name></project>");
            fixture(propertyPom,propertyPom.indexOf("${keyboard.value}")+3,propertyPom.indexOf("${keyboard.value}")+3);
            action("project.pomDeclaration");await(()->frame.editor().caretOffset()==propertyPom.indexOf("42"),"actual POM property declaration");
            pass("project.pomDeclaration","property reference navigates to its actual declaration in the unsaved POM");
            fixture(original,0,0);
            for(String id:List.of("project.sources","project.javadoc")) {
                int priorNotices=edt(()->frame.statusMessages().split("No external dependencies in this module",-1).length);
                action(id);await(()->frame.statusMessages().split("No external dependencies in this module",-1).length>priorNotices,"no attachment candidate explanation");
                check(!edt(()->OverlayHost.active(frame)),"no fake attachment dialog");pass(id,"guard: dependency-free module explains no candidates; artifact download not exercised");
            }
            action("project.parentPom");await(()->frame.statusMessages().contains("This POM has no parent"),"no parent explanation");pass("project.parentPom","parentless model reports no parent without changing source");
            action("navigation.projectDependency");await(()->frame.mavenProjects().hasExplorerFocus(),"dependency focus");pass("navigation.projectDependency","focus reaches real dependency tree");
        }finally{Files.writeString(output.resolve("effects.tsv"),report);edt(()->{for(var d:List.copyOf(OverlayHost.dialogs(frame)))d.dispose();frame.shutdown();frame.dispose();return null;});}
    }
    static void edit(String id,List<String> values,String expected,boolean absent)throws Exception {
        String before=edt(()->frame.editor().getText());
        action(id);dialog();fill(values);button("OK");await(()->OverlayHost.active(frame)&&top().getTitle().endsWith("— Preview"),"POM preview");
        check(edt(()->frame.editor().getText()).equals(before),"preview not applied");key(KeyEvent.VK_ESCAPE,0);await(()->!OverlayHost.active(frame),"preview cancelled");check(edt(()->frame.editor().getText()).equals(before),"cancel no edit");
        action(id);dialog();fill(values);button("OK");await(()->OverlayHost.active(frame)&&top().getTitle().endsWith("— Preview"),"POM preview again");button("OK");
        await(()->!OverlayHost.active(frame)&&!frame.editor().getText().equals(before),"POM applied");String after=edt(()->frame.editor().getText());check(absent?!after.contains(expected):after.contains(expected),id+": "+after);
        var factory=javax.xml.parsers.DocumentBuilderFactory.newInstance();factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);factory.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(after.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        action("undo");await(()->frame.editor().getText().equals(before),"POM undo");action("redo");await(()->frame.editor().getText().equals(after),"POM redo");pass(id,"keyboard parameters; preview cancel/apply; expected valid XML; one Undo/Redo");
    }
    static void fill(List<String> values)throws Exception {var fields=edt(()->fields(top()));check(fields.size()==values.size(),"expected form fields");for(int i=0;i<values.size();i++){var target=fields.get(i);tab(c->c==target);replace(values.get(i));}}
    static List<JTextField> fields(Container root){var out=new ArrayList<JTextField>();for(Component c:root.getComponents()){if(c instanceof JTextField f)out.add(f);else if(c instanceof Container child)out.addAll(fields(child));}return out;}
}
