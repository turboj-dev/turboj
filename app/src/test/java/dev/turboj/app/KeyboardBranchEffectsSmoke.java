package dev.turboj.app;

import static dev.turboj.app.KeyboardActionEffectsSmoke.*;
import dev.turboj.ai.*;
import dev.turboj.commandj.swing.*;
import dev.turboj.config.Settings;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.atomic.*;
import java.util.function.Predicate;
import javax.swing.*;

/** Real callback branches: keyboard confirms/cancels; isolated disk/settings and memory credentials. */
public final class KeyboardBranchEffectsSmoke {
    public static void main(String[] args)throws Exception {
        output=Files.createDirectories(Path.of("app/target/keyboard-branch-effects"));
        work=Files.createTempDirectory("turboj-branches-").toRealPath();
        System.setProperty("turboj.config",Files.createTempDirectory("turboj-effects-settings-").toString());System.setProperty("apple.laf.useScreenMenuBar","false");
        settings=new Settings();settings.workspaceDirectory=work.toString();
        Path source=Files.writeString(work.resolve("C.java"),"class C {}\n");
        frame=edt(()->{var f=new TurboFrame(settings,Map.of());f.setVisible(true);f.toFront();Desktop.getDesktop().requestForeground(true);f.openPath(source);return f;});
        try {
            await(()->source.equals(frame.currentFile())&&frame.editor().isFocusOwner(),"initial source");
            workspace();saveClose(source);cloudConsent();maven();
            // Quit is tested last. It shuts down only this fixture frame/process.
            action("file.saveAll");await(()->frame.unsavedFiles().isEmpty(),"all saved before quit");
            action("quit");await(()->!frame.isDisplayable(),"clean quit disposes owned frame");pass("quit","clean quit closes native frame");
        } finally {Files.writeString(output.resolve("effects.tsv"),report);edt(()->{for(var d:List.copyOf(OverlayHost.dialogs(frame)))d.dispose();frame.shutdown();frame.dispose();return null;});}
    }
    static void workspace()throws Exception {
        action("workspace.select");dialog();button("Browse…");await(()->OverlayHost.dialogs(frame).size()==2,"workspace chooser child");
        key(KeyEvent.VK_ESCAPE,0);await(()->OverlayHost.dialogs(frame).size()==1,"child cancellation");
        field("Workspace");replace("");button("Launch");
        await(()->find(top(),JLabel.class,"first-launch-workspace-error").getText().contains("Enter a directory"),"inline invalid directory");
        check(edt(()->OverlayHost.dialogs(frame).size())==1,"invalid directory retains form");
        field("Workspace");replace(work.toString());button("Launch");await(()->!OverlayHost.active(frame),"workspace launched");
        check(edt(()->settings.workspaceDirectory).equals(work.toString()),"workspace selected");pass("workspace.select","Browse child Escape; invalid path stays in form; valid Launch changes workspace");
        action("file.newJavaProject");dialog();field("Project name");replace("");button("Create");
        await(()->!find(top(),JLabel.class,"first-launch-project-error").getText().isBlank(),"invalid project name");
        key(KeyEvent.VK_ESCAPE,0);await(()->!OverlayHost.active(frame),"cancel invalid project");pass("file.newJavaProject","invalid project form retains input; Escape leaves no project");
    }
    static void saveClose(Path source)throws Exception {
        String dirty="class C { int changed; }\n";dirty(dirty);
        for(String id:List.of("file.close","quit")){action(id);dialog();key(KeyEvent.VK_ESCAPE,0);await(()->!OverlayHost.active(frame),"cancel "+id);
            check(edt(()->frame.currentFile()).equals(source)&&edt(()->frame.editor().getText()).equals(dirty),"cancel preserves dirty file");pass(id,"Escape at unsaved confirmation preserves dirty source and window");}
        action("file.close");dialog();button("Yes");await(()->!source.equals(frame.currentFile()),"saved and closed");
        check(Files.readString(source).equals(dirty),"Yes saves actual file before close");pass("file.close","Yes writes exact dirty source before closing");
        action("file.reopenClosed");await(()->source.equals(frame.currentFile()),"reopened");check(edt(()->frame.editor().getText()).equals(dirty),"reopen exact");pass("file.reopenClosed","restores closed source/path");
        dirty("class C { int notSaved; }\n");action("file.close");dialog();button("No");await(()->!source.equals(frame.currentFile()),"discard close");check(Files.readString(source).equals(dirty),"No leaves disk unchanged");pass("file.close","No closes without disk write");
        action("file.reopenClosed");await(()->source.equals(frame.currentFile()),"reopen discarded buffer");
        action("save");await(()->frame.unsavedFiles().isEmpty(),"save reopened buffer");pass("save","saves reopened dirty buffer");
        Path target=Files.writeString(work.resolve("Copy.java"),"class Copy {}\n");
        action("saveAs");dialog();field("File name");replace(target.toString());key(KeyEvent.VK_ENTER,0);
        await(()->top().getTitle().equals("Existing file"),"chooser overwrite");key(KeyEvent.VK_ESCAPE,0);
        await(()->top().getTitle().equals("Save file"),"return chooser");key(KeyEvent.VK_ESCAPE,0);await(()->!OverlayHost.active(frame),"cancel SaveAs");
        check(Files.readString(target).equals("class Copy {}\n"),"cancel keeps existing target");pass("saveAs","overwrite Escape returns chooser; cancel preserves target");
        action("saveAs");dialog();field("File name");replace(target.toString());key(KeyEvent.VK_ENTER,0);await(()->top().getTitle().equals("Existing file"),"chooser overwrite");button("OK");
        await(()->OverlayHost.active(frame)&&top().getTitle().equals("Save"),"second overwrite confirmation");button("Yes");await(()->target.equals(frame.currentFile())&&frame.unsavedFiles().isEmpty(),"SaveAs complete");
        check(Files.readString(target).equals(edt(()->frame.editor().getText())),"SaveAs exact bytes");pass("saveAs","both overwrite confirmations apply; disk and active path updated");
    }
    static void cloudConsent()throws Exception {
        var saved=new AtomicReference<AIConfig>();var writes=new AtomicInteger();var reads=new AtomicInteger();
        CredentialStore credentials=new CredentialStore(){public String read(String endpoint){reads.incrementAndGet();return "fixture-only-not-a-real-token";}
            public void write(String endpoint,char[] value){writes.incrementAndGet();}public void delete(String endpoint){throw new AssertionError("unexpected delete");}};
        AISettingsDialog form=edt(()->new AISettingsDialog(frame,AIConfig.defaults(),credentials,saved::set,x->{}));
        // Configuration setup is a fixture; every consent/apply/cancel interaction below uses keys.
        edt(()->{find(form,JComboBox.class,"ai-mode").setSelectedItem(AIConfig.Mode.CLOUD);return null;});
        SwingUtilities.invokeLater(()->form.setVisible(true));dialog();
        named("ai-cloud-consent");key(KeyEvent.VK_SPACE,0);button("Apply");await(()->OverlayHost.dialogs(frame).size()==2,"cloud confirmation");
        key(KeyEvent.VK_ESCAPE,0);await(()->OverlayHost.dialogs(frame).size()==1,"cloud cancel");
        check(saved.get()==null&&reads.get()==0&&writes.get()==0,"cloud cancel no credentials/save");pass("ai.settings","cloud confirmation Escape leaves settings open, no save or credential access");
        button("Apply");await(()->OverlayHost.dialogs(frame).size()==2,"cloud confirmation again");button("OK");
        await(()->form.isClosed()&&saved.get()!=null,"cloud config callback");check(saved.get().cloudConsent()&&reads.get()==1,"explicit cloud consent saved");
        pass("ai.settings","keyboard confirms cloud configuration; in-memory credentials; no provider request");
        AISettingsDialog changed=edt(()->new AISettingsDialog(frame,saved.get(),credentials,saved::set,x->{}));SwingUtilities.invokeLater(()->changed.setVisible(true));dialog();
        named("ai-cloud-endpoint");replace("https://changed.example/v1");key(KeyEvent.VK_TAB,0);
        check(!edt(()->find(changed,JCheckBox.class,"ai-cloud-consent").isSelected()),"endpoint revokes consent");
        button("Cancel");await(()->!OverlayHost.active(frame),"cancel changed endpoint");check(!saved.get().cloudEndpoint().contains("changed.example"),"cancel retains config");pass("ai.settings","endpoint edit revokes consent; Cancel preserves applied configuration");
    }
    static void maven()throws Exception {
        Path root=Files.createDirectories(work.resolve("maven"));
        Path repo=Path.of(".cache/m2").toAbsolutePath();
        String pom="<project><modelVersion>4.0.0</modelVersion><groupId>fixture.keyboard</groupId><artifactId>branches</artifactId><version>1.0</version><packaging>pom</packaging><build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-help-plugin</artifactId><version>3.5.2</version></plugin></plugins></build></project>";
        Files.writeString(root.resolve("pom.xml"),pom);
        Path mvn;try(var paths=Files.walk(Path.of(".cache/maven/wrapper/dists"))){mvn=paths.filter(p->p.endsWith("bin/mvn")).findFirst().orElseThrow().toAbsolutePath();}
        Path marker=root.resolve("invocations");
        Files.writeString(root.resolve("mvnw"),"#!/bin/sh\nprintf '%s\\n' \"$*\" >> '"+marker+"'\n'"+mvn+"' -o '-Dmaven.repo.local="+repo+"' \"$@\"\nresult=$?\nprintf 'exit=%s\\n' \"$result\" >> '"+marker+"'\nexit \"$result\"\n");root.resolve("mvnw").toFile().setExecutable(true);
        edt(()->{frame.openPath(root);return null;});await(()->frame.mavenProjects().workspace()!=null&&!frame.mavenProjects().busy(),"Maven import");
        action("maven.build.custom");dialog();replace("validate");key(KeyEvent.VK_ENTER,0);await(()->top().getTitle().equals("Project Trust"),"trust confirmation");key(KeyEvent.VK_ESCAPE,0);await(()->!OverlayHost.active(frame),"trust cancelled");
        check(!Files.exists(marker)&&!edt(()->frame.mavenProjects().trusted()),"trust cancellation executes no code");pass("maven.build.custom","goal input → trust Escape; no wrapper invocation");
        action("maven.build.validate");dialog();button("OK");await(()->OverlayHost.active(frame)&&top().getTitle().equals("Run Maven Build"),"build confirmation");key(KeyEvent.VK_ESCAPE,0);await(()->!frame.mavenProjects().lifecycleRunning(),"build cancellation");
        check(!Files.exists(marker),"build cancellation executes no wrapper");pass("maven.build.validate","trust accepted, build cancelled without process");
        for(String goal:List.of("validate","compile","test","package","verify","install","dependency:tree","help:effective-pom","clean")) {
            action("maven.build."+goal);await(()->OverlayHost.active(frame)&&top().getTitle().equals("Run Maven Build"),"build prompt "+goal);button("OK");
            await(()->Files.exists(marker)&&!frame.mavenProjects().lifecycleRunning(),"Maven finishes "+goal);
            check(Files.readString(marker).contains(goal),"actual wrapper goal "+goal);
            check(Files.readAllLines(marker).getLast().equals("exit=0"),"current Maven goal exit status "+goal+": "+edt(()->frame.console().text()));
            check(edt(()->frame.console().text()).contains("BUILD SUCCESS"),"real Maven success "+goal);pass("maven.build."+goal,"keyboard confirmation → real offline Maven POM-project goal succeeds");
        }
        action("maven.build.custom");dialog();replace("validate");key(KeyEvent.VK_ENTER,0);
        await(()->OverlayHost.active(frame)&&top().getTitle().equals("Run Maven Build"),"custom confirmation");button("OK");
        await(()->!frame.mavenProjects().lifecycleRunning(),"custom Maven finishes");
        check(Files.readAllLines(marker).getLast().equals("exit=0"),"custom goal succeeds");pass("maven.build.custom","custom goal input and confirmation execute real Maven validate");
        // A deliberately waiting, owned wrapper makes cancellation deterministic, without a slow network goal.
        Path ready=root.resolve("wrapper-waiting");String wrapper=Files.readString(root.resolve("mvnw"));
        Files.writeString(root.resolve("mvnw"),wrapper.replace("#!/bin/sh\n","#!/bin/sh\nprintf ready > '"+ready+"'\nsleep 30\n"));
        action("maven.build.validate");dialog();button("OK");await(()->Files.exists(ready)&&frame.mavenProjects().lifecycleRunning(),"owned wrapper waiting");
        action("maven.stop");await(()->!frame.mavenProjects().lifecycleRunning(),"owned wrapper cancelled");pass("maven.stop","stops actual owned wrapper while it waits before Maven invocation");
    }
    static EmbeddedDialog top(){return OverlayHost.dialogs(frame).getLast();}
    static void dirty(String text)throws Exception{edt(()->{frame.editor().selectAll();frame.editor().replaceSelection(text);frame.editor().requestFocusInWindow();return null;});await(()->frame.editor().isFocusOwner(),"dirty source focus");}
    static void field(String label)throws Exception{tab(c->c instanceof JTextField&&label.equals(c.getAccessibleContext().getAccessibleName()));}
    static void named(String name)throws Exception{tab(c->name.equals(c.getName()));}
    static void tab(Predicate<Component> match)throws Exception{for(int i=0;i<100;i++){if(edt(()->focus()!=null&&match.test(focus())))return;key(KeyEvent.VK_TAB,0);Thread.sleep(10);}throw new AssertionError("Tab target unreachable");}
    static void replace(String text)throws Exception{key(KeyEvent.VK_A,KeyEvent.META_DOWN_MASK);key(KeyEvent.VK_BACK_SPACE,0);edt(()->{for(char c:text.toCharArray())Toolkit.getDefaultToolkit().getSystemEventQueue().postEvent(new KeyEvent(focus(),KeyEvent.KEY_TYPED,0,0,KeyEvent.VK_UNDEFINED,c));return null;});edt(()->null);}
    static <T extends Component>T find(Container root,Class<T> type,String name){for(Component c:root.getComponents()){if(type.isInstance(c)&&name.equals(c.getName()))return type.cast(c);if(c instanceof Container child){T found=find(child,type,name);if(found!=null)return found;}}return null;}
}
