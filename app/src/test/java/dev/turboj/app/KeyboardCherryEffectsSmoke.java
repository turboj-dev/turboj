package dev.turboj.app;

import static dev.turboj.app.KeyboardActionEffectsSmoke.*;
import static dev.turboj.app.KeyboardBranchEffectsSmoke.*;
import dev.turboj.commandj.swing.*;
import dev.turboj.config.Settings;
import dev.turboj.testing.intelligence.LineIntelligence.*;
import java.awt.*;
import java.awt.event.*;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import javax.swing.*;

/** Actual Cherry CLI invocation, configuration/results dialogs and cancellation via production shortcuts. */
public final class KeyboardCherryEffectsSmoke {
    public static void main(String[] args)throws Exception {
        if((args.length!=1&&args.length!=2)||!Files.isRegularFile(Path.of(args[0])))throw new IllegalArgumentException("Pass the real Cherry Java CLI JAR");
        output=Files.createDirectories(Path.of("app/target/keyboard-cherry-effects"));work=Files.createTempDirectory("turboj-cherry-effects-").toRealPath();
        Path fixture=Path.of("integration-tests/fixtures/cherry-native");try(var files=Files.walk(fixture)){for(Path source:files.toList()){Path to=work.resolve(fixture.relativize(source));if(Files.isDirectory(source))Files.createDirectories(to);else Files.copy(source,to);}}
        Path maven;try(var paths=Files.walk(Path.of(".cache/maven/wrapper/dists"))){maven=paths.filter(p->p.endsWith("bin/mvn")).findFirst().orElseThrow().toAbsolutePath();}
        // Cherry uses the project's real wrapper. Use the already cached pinned Maven,
        // offline, rather than assuming that the desktop launch PATH contains mvn.
        Files.writeString(work.resolve("mvnw"),"#!/bin/sh\nexec '"+maven.toString().replace("'","'\"'\"'")+"' -o \"$@\"\n");
        work.resolve("mvnw").toFile().setExecutable(true);
        Files.writeString(output.resolve("maven-runtime.txt"),maven+"\nOffline; existing user Maven cache contains the pinned fixture dependencies and provenance hook.\n");
        System.setProperty("turboj.config",Files.createTempDirectory("turboj-cherry-settings-").toString());System.setProperty("apple.laf.useScreenMenuBar","false");
        settings=new Settings();settings.cherryTestExecutable=Path.of(args[0]).toAbsolutePath().toString();Path source=work.resolve("src/main/java/demo/Price.java");
        frame=edt(()->{var f=new TurboFrame(settings,Map.of());f.setVisible(true);f.toFront();Desktop.getDesktop().requestForeground(true);f.openPath(source);return f;});
        try {
            await(()->source.equals(frame.currentFile())&&frame.editor().lineIntelligence()!=null&&frame.editor().lineIntelligence().testingActive(),"Cherry detected");
            action("cherry.execution");dialog();tab(c->c instanceof JComboBox<?>);
            var modes=edt(()->(JComboBox<?>)focus());key(KeyEvent.VK_DOWN,KeyEvent.ALT_DOWN_MASK);
            await(modes::isPopupVisible,"execution choices open");key(KeyEvent.VK_HOME,0);key(KeyEvent.VK_DOWN,0);key(KeyEvent.VK_ENTER,0);
            await(()->"local".equals(modes.getSelectedItem()),"local choice selected");button("OK");await(()->!OverlayHost.active(frame)&&"local".equals(settings.cherryTestExecution.get(work.toString())),"local execution mode");pass("cherry.execution","keyboard selects and stores local mode for the actual project");
            action("cherry.run");await(()->!frame.tests().cherry().busy()&&frame.tests().cherry().last()!=null,"real Cherry completed");var completed=edt(()->frame.tests().cherry().last());
            check(completed.state()==RunState.COMPLETED&&completed.tests().size()==2&&completed.tests().stream().allMatch(t->t.status()==TestStatus.PASSED),"two real Cherry/JUnit passes: "+completed.state());
            pass("cherry.run","real configured Maven/JUnit/JaCoCo workflow: two passing tests");
            action("editor.inspectLineIntelligence");dialog();
            tab(c->c instanceof JList<?>);
            var sections=edt(()->(JList<?>)focus());
            check(edt(()->sections.getModel().getSize()==3&&sections.getModel().getElementAt(0).equals("Git")
                    &&sections.getModel().getElementAt(1).equals("Tests")&&sections.getModel().getElementAt(2).equals("Coverage")),"three independent inspection sections");
            key(KeyEvent.VK_HOME,0);key(KeyEvent.VK_DOWN,0);key(KeyEvent.VK_DOWN,0);key(KeyEvent.VK_ENTER,0);
            await(()->focus() instanceof JTextArea&&KeyboardRefactoringEffectsSmoke.texts(top()).contains("Coverage legend"),"keyboard reaches actual coverage details");
            key(KeyEvent.VK_ESCAPE,0);await(()->!OverlayHost.active(frame)&&frame.editor().isFocusOwner(),"inspection closes");
            pass("editor.inspectLineIntelligence","real Cherry data; Git, Tests and Coverage details remain separate; Escape restores source");
            action("cherry.results");dialog();check(edt(()->top().getTitle().startsWith("Cherry Test")),"native results title");key(KeyEvent.VK_ESCAPE,0);await(()->!OverlayHost.active(frame)&&frame.editor().isFocusOwner(),"results closed");pass("cherry.results","real current execution opens native result tree; Escape restores source");
            check(completed.htmlReport()!=null&&Files.isRegularFile(completed.htmlReport()),"real HTML report exists");
            Files.writeString(output.resolve("report-artifact.txt"),completed.htmlReport().toString()+"\n"+dev.turboj.testing.intelligence.LineIntelligence.sha256(Files.readString(completed.htmlReport()))+"\n");
            if(args.length==2&&args[1].equals("--browser")) verifyBrowser(completed.htmlReport());
            action("cherry.run");await(()->frame.tests().cherry().busy()&&frame.tests().cherry().last()!=null&&!frame.tests().cherry().last().id().equals(completed.id())&&frame.tests().cherry().last().state()==RunState.RUNNING,"second real process started");
            action("cherry.cancel");await(()->!frame.tests().cherry().busy()&&frame.tests().cherry().last().state()==RunState.CANCELLED,"cancelled execution");pass("cherry.cancel","cancels actual running CLI execution; result is CANCELLED, never success");
        }finally{Files.writeString(output.resolve("effects.tsv"),report);Files.writeString(output.resolve("console.txt"),edt(()->frame.console().text()));edt(()->{for(var d:List.copyOf(OverlayHost.dialogs(frame)))d.dispose();frame.shutdown();frame.dispose();return null;});}
    }
    // The report action crosses into the default browser. Verify its actual address
    // using OS keyboard events, not a mocked Desktop callback or a screenshot claim.
    static void verifyBrowser(Path reportFile)throws Exception {
        var clipboard=Toolkit.getDefaultToolkit().getSystemClipboard();var before=clipboard.getContents(null);
        try {
            action("cherry.report");
            long until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(15);boolean browser=false;
            while(System.nanoTime()<until){
                var frontProcess=new ProcessBuilder("/usr/bin/lsappinfo","front").start();
                String front=new String(frontProcess.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).strip();frontProcess.waitFor();
                var infoProcess=new ProcessBuilder("/usr/bin/lsappinfo","info","-only","bundleID",front).start();
                String info=new String(infoProcess.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);infoProcess.waitFor();
                browser=List.of("com.apple.Safari","com.google.Chrome","org.mozilla.firefox","com.microsoft.edgemac").stream().anyMatch(info::contains);
                if(browser)break;Thread.sleep(100);
            }
            check(browser,"a supported browser must be foreground before sending any OS keys");
            var robot=new Robot();robot.setAutoDelay(70);
            robot.keyPress(KeyEvent.VK_META);robot.keyPress(KeyEvent.VK_L);robot.keyRelease(KeyEvent.VK_L);robot.keyRelease(KeyEvent.VK_META);
            robot.keyPress(KeyEvent.VK_META);robot.keyPress(KeyEvent.VK_C);robot.keyRelease(KeyEvent.VK_C);robot.keyRelease(KeyEvent.VK_META);
            String location="";boolean matched=false;
            until=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(5);
            while(System.nanoTime()<until){
                if(clipboard.isDataFlavorAvailable(java.awt.datatransfer.DataFlavor.stringFlavor)){
                    location=(String)clipboard.getData(java.awt.datatransfer.DataFlavor.stringFlavor);
                    try {matched=Files.isSameFile(reportFile,Path.of(java.net.URI.create(location.strip())));}catch(Exception ignored){}
                }
                if(matched)break;Thread.sleep(50);
            }
            if(!matched && Files.isExecutable(Path.of("app/target/browser-report-probe"))) {
                var frontProcess=new ProcessBuilder("/usr/bin/lsappinfo","front").start();
                String front=new String(frontProcess.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8).strip();frontProcess.waitFor();
                var infoProcess=new ProcessBuilder("/usr/bin/lsappinfo","info","-only","pid",front).start();
                String info=new String(infoProcess.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);infoProcess.waitFor();
                var pid=java.util.regex.Pattern.compile("(?:pid|PID)[^0-9]*([0-9]+)").matcher(info);
                check(pid.find(),"foreground browser PID available");
                var probe=new ProcessBuilder("app/target/browser-report-probe",pid.group(1),reportFile.toString()).redirectErrorStream(true).start();
                String proof=new String(probe.getInputStream().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
                matched=probe.waitFor()==0;Files.writeString(output.resolve("browser-accessibility.txt"),proof);
                if(matched)location=reportFile.toUri().toString();
            }
            check(matched,"default browser document matches the generated report through address bar or native AX document URL");
            Files.writeString(output.resolve("browser-location.txt"),location+"\n");
            // Only close the tab after proving it is the report just opened by this test.
            robot.keyPress(KeyEvent.VK_META);robot.keyPress(KeyEvent.VK_W);robot.keyRelease(KeyEvent.VK_W);robot.keyRelease(KeyEvent.VK_META);
            activateFixture();
            pass("cherry.report","actual foreground browser document URL matches generated report through OS address bar or native accessibility; owned tab close requested; clipboard restored");
        }finally{if(before!=null)clipboard.setContents(before,null);}
    }

}
