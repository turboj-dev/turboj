package dev.turboj.app;

import dev.turboj.commandj.swing.*;
import dev.turboj.config.Settings;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.*;
import javax.swing.*;

/** Native-window regression: unique counterparts open directly; Enter approves ambiguous choices. */
public final class SourceTestNavigationAcceptance {
    private static TurboFrame frame;
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("turboj-source-test-");
        Path source = root.resolve("src/main/java/demo/Calculator.java");
        Path test = root.resolve("src/test/java/demo/CalculatorTest.java");
        Path alternative = root.resolve("src/test/java/other/CalculatorTest.java");
        for (Path file : new Path[]{source,test,alternative,root.resolve(".turboj/source-roots.txt")}) Files.createDirectories(file.getParent());
        Files.writeString(root.resolve(".turboj/source-roots.txt"),"src/main/java\nsrc/test/java\n");
        Files.writeString(source,"package demo; public class Calculator { public int add(int a,int b) { return a+b; } }");
        Files.writeString(test,"package demo; public class CalculatorTest { void verifies() { new Calculator().add(1,2); } }");
        System.setProperty("turboj.config",Files.createTempDirectory("turboj-navigation-settings-").toString());
        System.setProperty("apple.laf.useScreenMenuBar","false");
        frame = edt(() -> {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            var value = new TurboFrame(new Settings(),Map.of()); value.setVisible(true); value.toFront(); value.openPath(source); return value;
        });
        try {
            await(() -> source.equals(frame.currentFile()),"source opens");
            edt(() -> { frame.runCommand("navigation.goToTest"); return null; });
            await(() -> test.equals(frame.currentFile()),"unique test opens directly");
            check(edt(() -> OverlayHost.dialogs(frame).isEmpty()),"no chooser for unique test");
            edt(() -> { frame.runCommand("navigation.goToTest"); return null; });
            await(() -> source.equals(frame.currentFile()),"unique source opens directly");
            check(edt(() -> OverlayHost.dialogs(frame).isEmpty()),"no chooser for unique source");
            Files.writeString(alternative,"package other; public class CalculatorTest {}");
            edt(() -> { frame.runCommand("navigation.goToTest"); return null; });
            await(() -> chooser() != null,"ambiguous matches show chooser");
            await(() -> KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner() instanceof JComboBox<?>,"file chooser focuses selection");
            edt(() -> { key(KeyEvent.VK_ENTER); return null; });
            await(() -> chooser() == null && test.equals(frame.currentFile()),"Enter opens selected file");
            // Reverse direction now has one source despite multiple possible test classes.
            edt(() -> { frame.runCommand("navigation.goToTest"); return null; });
            await(() -> source.equals(frame.currentFile()),"reverse navigation still direct");
            edt(() -> { frame.runCommand("navigation.goToTest"); return null; });
            await(() -> chooser() != null && KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner() instanceof JComboBox<?>,"second chooser ready");
            edt(() -> { key(KeyEvent.VK_ESCAPE); return null; });
            await(() -> chooser() == null,"Escape cancels chooser");
            check(edt(() -> source.equals(frame.currentFile())),"cancel leaves source open");
            System.out.println("PASS native source/test navigation and Enter/Escape; programmatic fixtures/commands, real Swing keyboard dispatch.");
        } finally { edt(() -> { frame.shutdown(); frame.dispose(); return null; }); }
    }
    private static EmbeddedDialog chooser() { return OverlayHost.dialogs(frame).stream().filter(d -> d.isVisible() && d.getTitle().equals("Source / Test")).findFirst().orElse(null); }
    private static void key(int key) {
        var manager = KeyboardFocusManager.getCurrentKeyboardFocusManager(); Component focus = manager.getFocusOwner();
        if (focus == null) throw new AssertionError("No focus");
        manager.dispatchEvent(new KeyEvent(focus,KeyEvent.KEY_PRESSED,System.currentTimeMillis(),0,key,KeyEvent.CHAR_UNDEFINED));
        manager.dispatchEvent(new KeyEvent(focus,KeyEvent.KEY_RELEASED,System.currentTimeMillis(),0,key,KeyEvent.CHAR_UNDEFINED));
    }
    private static void check(boolean value,String label) { if (!value) throw new AssertionError(label); System.out.println("PASS " + label); }
    private static void await(Callable<Boolean> condition,String label) throws Exception {
        long end=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);
        while(System.nanoTime()<end) { if(edt(condition)) { System.out.println("PASS " + label); return; } Thread.sleep(25); }
        throw new AssertionError(label);
    }
    private static <T> T edt(Callable<T> task) throws Exception { var future=new FutureTask<T>(task); SwingUtilities.invokeAndWait(future); return future.get(); }
}
