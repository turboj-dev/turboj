package dev.turboj.app;

import static dev.turboj.app.KeyboardActionEffectsSmoke.*;
import dev.turboj.commandj.swing.*;
import dev.turboj.config.Settings;
import java.awt.*;
import java.nio.file.*;
import java.util.List;
import java.util.Map;

/** Actual JUnit command effects, including failure identity and cancellation after a test has started. */
public final class KeyboardTestEffectsSmoke {
    public static void main(String[] args)throws Exception {
        output=Files.createDirectories(Path.of("app/target/keyboard-test-effects"));work=Files.createTempDirectory("turboj-test-effects-").toRealPath();
        System.setProperty("turboj.config",Files.createTempDirectory("turboj-test-settings-").toString());System.setProperty("apple.laf.useScreenMenuBar","false");Files.createDirectories(work.resolve(".turboj"));
        Files.writeString(work.resolve(".turboj/junit.properties"),"runtime=bundled-jupiter-5.13.4\n");
        Files.writeString(work.resolve(".turboj/project.properties"),"kind=java\njava.release=25\nname=TestEffects\n");
        Files.writeString(work.resolve(".turboj/source-roots.txt"),"src/main/java\nsrc/test/java\n");Files.createDirectories(work.resolve("src/main/java"));
        Path source=Files.createDirectories(work.resolve("src/test/java")).resolve("SampleTest.java");String code="public class SampleTest {\n @org.junit.jupiter.api.Test void passes(){org.junit.jupiter.api.Assertions.assertEquals(4,2+2);}\n @org.junit.jupiter.api.Test void fails(){org.junit.jupiter.api.Assertions.fail(\"KEY04_FAILURE\");}\n}\n";Files.writeString(source,code);
        frame=edt(()->{var f=new TurboFrame(new Settings(),Map.of());f.setVisible(true);f.toFront();Desktop.getDesktop().requestForeground(true);f.openPath(source);return f;});
        try {
            activateFixture();await(()->source.equals(frame.currentFile())&&frame.editor().isFocusOwner(),"source");
            for(String id:List.of("tests.current","tests.class","tests.failed","tests.all","run.context")) {
                action("view.focusEditor");edt(()->{frame.editor().gotoOffset(code.indexOf("assertEquals"));return null;});var before=edt(()->frame.tests().lastReport());action(id);
                await(()->!frame.tests().busy()&&frame.tests().lastReport()!=null&&frame.tests().lastReport()!=before,"JUnit result "+id);
                var result=edt(()->frame.tests().lastReport());long expected=id.equals("tests.current")?0:1;
                check(result.failed()==expected,"actual failure count "+id);check(result.tests().size()==(id.equals("tests.current")||id.equals("tests.failed")?1:2),"actual selected test count "+id);
                if(expected==1)check(result.tests().stream().anyMatch(t->t.failure().contains("KEY04_FAILURE")),"real assertion failure");
                pass(id,"actual JUnit selection/results: "+result.passed()+" passed, "+result.failed()+" failed");
            }
            for(String id:List.of("tests.nextFailure","tests.previousFailure","navigation.testFailure")) {action("view.focusEditor");edt(()->{frame.editor().gotoOffset(0);return null;});action(id);await(()->source.equals(frame.currentFile())&&frame.editor().caretOffset()>=code.lastIndexOf("\n",code.indexOf("void fails"))+1,"failure location");pass(id,"opens actual assertion failure source location");}
            for(String id:List.of("compileProject","make")) {
                var prior=edt(()->frame.tests().lastReport());long completed=edt(()->frame.statusMessages().lines().filter(line->line.contains("Project compiled.")).count());action(id);
                await(()->!frame.tests().busy()&&frame.statusMessages().lines().filter(line->line.contains("Project compiled.")).count()>completed,"project compilation");
                check(edt(()->frame.tests().lastReport())==prior,"compile does not replace test results");pass(id,"actual project compilation completes without rerunning JUnit");
            }
            action("tests.results");await(()->frame.outputTabTitle().equals("Tests"),"test panel selected");pass("tests.results","native Tests panel displays actual last report");
            Path marker=work.resolve("test-started");String slow="public class SampleTest { @org.junit.jupiter.api.Test void slow()throws Exception{java.nio.file.Files.writeString(java.nio.file.Path.of(\""+marker+"\"),\"started\");Thread.sleep(30000);} }";
            action("view.focusEditor");KeyboardBranchEffectsSmoke.dirty(slow);edt(()->{frame.editor().gotoOffset(slow.indexOf("slow()"));return null;});action("tests.class");await(()->Files.exists(marker)&&frame.tests().busy(),"actual slow test starts");action("tests.stop");await(()->!frame.tests().busy(),"tests stopped");pass("tests.stop","cancels worker after real test writes readiness marker");
            check(Files.readString(source).equals(code),"test commands preserve saved source");
        }finally{Files.writeString(output.resolve("effects.tsv"),report);edt(()->{for(var d:List.copyOf(OverlayHost.dialogs(frame)))d.dispose();frame.shutdown();frame.dispose();return null;});}
    }
}
