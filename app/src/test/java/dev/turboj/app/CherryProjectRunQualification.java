package dev.turboj.app;

import dev.turboj.config.Settings;
import dev.turboj.testing.intelligence.LineIntelligence.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javax.swing.*;

/** Real configured project execution through the native command, without editing project sources. */
public final class CherryProjectRunQualification {
    private static TurboFrame frame;
    public static void main(String[] args) throws Exception {
        if (args.length != 5) throw new IllegalArgumentException("CLI root source output execution");
        Path root = Path.of(args[1]).toRealPath(), source = Path.of(args[2]).toRealPath(), output = Path.of(args[3]);
        Files.createDirectories(output);
        System.setProperty("turboj.config",Files.createTempDirectory(output,"settings-").toString());
        byte[] original = Files.readAllBytes(source);
        frame = edt(() -> {
            Settings settings = new Settings(); settings.cherryTestExecutable = args[0];
            settings.cherryTestExecution.put(root.toString(),args[4]);
            TurboFrame app = new TurboFrame(settings,Map.of()); app.setVisible(true); app.openPath(source); return app;
        });
        try {
            waitFor(() -> frame.editor().lineIntelligence() != null && frame.editor().lineIntelligence().testingActive(),"project detected");
            edt(() -> { frame.runCommand("cherry.run"); return null; });
            waitFor(() -> frame.tests().cherry().busy(),"native command started configured suites");
            waitFor(() -> !frame.tests().cherry().busy(),"configured suites completed");
            Run run = edt(() -> frame.tests().cherry().last());
            Map<String,Long> counts = new TreeMap<>();
            run.tests().forEach(t -> counts.merge(t.project()+"/"+t.module()+"/"+t.packageName()+"/"+t.phase()+"/"+t.runtime()+"/"+t.status(),1L,Long::sum));
            Files.writeString(output.resolve("result.txt"),run.id()+" "+run.state()+" coverage="+run.coverageAvailability()+"\n"+counts+"\n"+run.message());
            Files.writeString(output.resolve("console.txt"),edt(() -> frame.console().text()));
            if (run.state() != RunState.COMPLETED || run.tests().isEmpty()) throw new AssertionError("Native execution did not pass: "+run.state());
            var image = edt(() -> {
                var capture = new java.awt.image.BufferedImage(frame.getWidth(),frame.getHeight(),java.awt.image.BufferedImage.TYPE_INT_RGB);
                var graphics = capture.createGraphics(); frame.printAll(graphics); graphics.dispose(); return capture;
            });
            javax.imageio.ImageIO.write(image,"png",output.resolve("native.png").toFile());
            System.out.println("PASS "+run.id()+" tests="+run.tests().size()+" coverage="+run.coverageAvailability());
        } finally {
            edt(() -> { frame.shutdown(); frame.dispose(); return null; });
            if (!Arrays.equals(original,Files.readAllBytes(source))) throw new AssertionError("Read-only acceptance changed source");
        }
        System.exit(0);
    }
    private static void waitFor(Callable<Boolean> condition,String label) throws Exception {
        long deadline=System.nanoTime()+TimeUnit.MINUTES.toNanos(60);
        while(System.nanoTime()<deadline) { if(edt(condition)) { System.out.println("PASS "+label);return; } Thread.sleep(100); }
        throw new AssertionError("Timeout: "+label);
    }
    private static <T> T edt(Callable<T> action) throws Exception {
        FutureTask<T> task=new FutureTask<>(action);SwingUtilities.invokeAndWait(task);return task.get();
    }
}
