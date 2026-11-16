package dev.turboj.app;

import dev.turboj.config.Settings;
import dev.turboj.testing.intelligence.LineIntelligence.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import javax.swing.*;

/** Real-project Save acceptance; setup and CommandJ dispatch are programmatic, not keyboard certification. */
public final class CherryProjectSaveQualification {
    private static TurboFrame frame;
    public static void main(String[] args) throws Exception {
        if (args.length != 4) throw new IllegalArgumentException("CLI jar source.java output-directory required-phases-comma-separated");
        Path source = Path.of(args[1]).toRealPath(), output = Path.of(args[2]).toAbsolutePath();
        if (!source.startsWith(Path.of("/private/tmp"))) throw new IllegalArgumentException("Use a disposable source checkout");
        Files.createDirectories(output);
        System.setProperty("turboj.config", Files.createTempDirectory(output, "settings-").toString());
        Path module = source.getParent();
        while (module != null && !Files.isRegularFile(module.resolve("pom.xml"))) module = module.getParent();
        final Path mavenModule = module;
        frame = edt(() -> {
            Settings settings = new Settings(); settings.cherryTestExecutable = args[0];
            TurboFrame app = new TurboFrame(settings, Map.of()); app.setVisible(true);
            if (mavenModule != null) app.mavenProjects().open(mavenModule); else app.openPath(source);
            return app;
        });
        String original = Files.readString(source);
        try {
            if (mavenModule != null) {
                waitFor(() -> frame.mavenProjects().workspace() != null && !frame.mavenProjects().busy(), "Maven import completed");
                if (!edt(() -> frame.mavenProjects().workspace().resolved())) throw new AssertionError("Maven classpath not resolved");
                edt(() -> {
                    javax.swing.Timer trust = new javax.swing.Timer(50, null);
                    trust.addActionListener(event -> dev.turboj.commandj.swing.OverlayHost.dialogs(frame).stream()
                            .filter(d -> d.isVisible() && d.getTitle().equals("Project Trust")).findFirst().ifPresent(d -> {
                                JOptionPane pane = optionPane(d.getContentPane());
                                if (pane != null) { pane.setValue(JOptionPane.OK_OPTION); trust.stop(); }
                            }));
                    trust.start();
                    try { if (!frame.mavenProjects().requireTrust()) throw new AssertionError("disposable project trust rejected"); }
                    finally { trust.stop(); }
                    System.out.println("PASS explicitly accepted disposable Maven session trust"); return null;
                });
                edt(() -> { frame.openPath(source); return null; });
            }
            waitFor(() -> source.equals(frame.currentFile()) && frame.editor().lineIntelligence() != null
                    && frame.editor().lineIntelligence().testingActive(), "project detected");
            Run previous = edt(() -> frame.tests().cherry().last());
            String oldId = previous == null ? "" : previous.id();
            edt(() -> { frame.editor().setSelection(frame.editor().getText().length(), frame.editor().getText().length());
                frame.editor().replaceSelection("\n// Real-project Save qualification\n"); return null; });
            waitFor(() -> frame.editor().lineIntelligence() != null && frame.editor().lineIntelligence().lines().values().stream()
                    .noneMatch(l -> l.coverage().availability() == Availability.AVAILABLE), "edit withholds old coverage");
            if (!Files.readString(source).equals(original)) throw new AssertionError("edit saved prematurely");
            edt(() -> { frame.runCommand("save"); return null; });
            waitFor(() -> frame.tests().cherry().last() != null && !frame.tests().cherry().last().id().equals(oldId)
                    && !frame.tests().cherry().busy(), "Save-triggered execution completed");
            Run run = edt(() -> frame.tests().cherry().last());
            Map<String, Long> outcomes = new TreeMap<>();
            run.tests().forEach(t -> outcomes.merge(t.phase() + "/" + t.runtime() + "/" + t.status(), 1L, Long::sum));
            String result = "run=" + run.id() + " state=" + run.state() + "\n" + outcomes + "\n" + run.message();
            Files.writeString(output.resolve("result.txt"), result);
            Files.writeString(output.resolve("console.txt"), edt(() -> frame.console().text()));
            Files.writeString(output.resolve("saved-source.java"), Files.readString(source));
            System.out.println(result);
            if (!Files.readString(source).contains("Real-project Save qualification")) throw new AssertionError("save did not persist edit");
            if (run.state() != RunState.COMPLETED && run.state() != RunState.FAILED && run.state() != RunState.INCOMPLETE)
                throw new AssertionError("no complete/failed/incomplete execution result");
            if (run.tests().isEmpty()) throw new AssertionError("no real tests executed");
            Set<String> phases = new HashSet<>(); run.tests().forEach(t -> phases.add(t.phase()));
            if (!phases.containsAll(List.of(args[3].split(",")))) throw new AssertionError("missing real test categories: " + phases);
            if (run.state() != RunState.COMPLETED) throw new AssertionError("configured workflow did not pass: " + run.state());
            if (run.coverage().values().stream().flatMap(lines -> lines.values().stream())
                    .noneMatch(c -> c.freshness() == Freshness.CURRENT))
                throw new AssertionError("expected verified coverage from the configured provenance fixture");
            waitFor(() -> frame.editor().lineIntelligence().lines().values().stream()
                    .anyMatch(l -> l.coverage().freshness() == Freshness.CURRENT), "verified coverage painted in native editor");
            java.awt.image.BufferedImage capture = edt(() -> {
                var image = new java.awt.image.BufferedImage(frame.getWidth(), frame.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
                var graphics = image.createGraphics(); frame.printAll(graphics); graphics.dispose(); return image;
            });
            javax.imageio.ImageIO.write(capture, "png", output.resolve("native.png").toFile());
        } finally {
            edt(() -> { frame.shutdown(); frame.dispose(); return null; });
            Files.writeString(source, original);
        }
        System.exit(0);
    }
    private static void waitFor(Callable<Boolean> condition, String label) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.MINUTES.toNanos(15);
        while (System.nanoTime() < deadline) {
            if (edt(condition)) { System.out.println("PASS " + label); return; }
            Thread.sleep(100);
        }
        throw new AssertionError("Timeout: " + label + "\n" + edt(() -> frame.console().text()));
    }
    private static <T> T edt(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action); SwingUtilities.invokeAndWait(task); return task.get();
    }
    private static JOptionPane optionPane(java.awt.Container root) {
        if (root instanceof JOptionPane pane) return pane;
        for (var child : root.getComponents()) if (child instanceof java.awt.Container container) {
            JOptionPane pane = optionPane(container); if (pane != null) return pane;
        }
        return null;
    }
}
