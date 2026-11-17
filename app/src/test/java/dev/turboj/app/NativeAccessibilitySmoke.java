package dev.turboj.app;

import dev.turboj.config.Settings;
import java.awt.Desktop;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.*;
import javax.swing.SwingUtilities;

/** Reads the real macOS AX bridge of this isolated TurboJ process; does not start VoiceOver. */
public final class NativeAccessibilitySmoke {
    public static void main(String[] args)throws Exception {
        if(args.length!=1)throw new IllegalArgumentException("Path to compiled editor-accessibility-probe required");
        Path output=Files.createDirectories(Path.of("app/target/native-accessibility"));
        System.setProperty("turboj.config",Files.createTempDirectory("turboj-ax-").toString());
        TurboFrame frame=edt(()->{var f=new TurboFrame(new Settings(),Map.of());f.setVisible(true);f.toFront();
            Desktop.getDesktop().requestForeground(true);f.editor().setText("class AccessibilityFixture { String text = \"日本😀\"; }\n");
            f.editor().setSelection(6,26);f.editor().requestFocusInWindow();return f;});
        try {
            long until=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
            while(!edt(()->frame.editor().isFocusOwner())&&System.nanoTime()<until)Thread.sleep(10);
            if(!edt(()->frame.editor().isFocusOwner()))throw new AssertionError("Editor focus");
            Process probe=new ProcessBuilder(args[0],Long.toString(ProcessHandle.current().pid()))
                    .redirectErrorStream(true).redirectOutput(output.resolve("report.txt").toFile()).start();
            if(!probe.waitFor(20,TimeUnit.SECONDS)){probe.destroyForcibly();throw new AssertionError("AX query timed out");}
            System.out.print(Files.readString(output.resolve("report.txt")));
            if(probe.exitValue()!=0)throw new AssertionError("AX probe exit "+probe.exitValue());
            System.out.println("PASS actual macOS AX text element exposes source and selected identifier; VoiceOver speech not tested");
        } finally {edt(()->{frame.shutdown();frame.dispose();return null;});}
    }
    private static <T>T edt(Callable<T> action)throws Exception{var task=new FutureTask<>(action);SwingUtilities.invokeLater(task);return task.get(30,TimeUnit.SECONDS);}
}
