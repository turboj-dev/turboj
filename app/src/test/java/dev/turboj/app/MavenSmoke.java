package dev.turboj.app;

import dev.turboj.commandj.core.CommandContext;
import dev.turboj.commandj.swing.*;
import dev.turboj.config.Settings;
import dev.turboj.refactorj.api.Model;
import dev.turboj.refactorj.api.Requests;
import dev.turboj.ui.themes.Theme;
import java.awt.*;
import java.nio.file.*;
import java.util.List;
import java.util.*;
import java.util.concurrent.*;
import javax.swing.*;

/** Real single-frame Maven import, semantic refactoring, native compile/run and Maven lifecycle. */
public final class MavenSmoke {
    public static void main(String[] args) throws Exception {
        Path output = Path.of("app/target/maven-smoke").toAbsolutePath(); Files.createDirectories(output);
        Path root = Files.createTempDirectory(output, "reactor-");
        String parent = "<project><modelVersion>4.0.0</modelVersion><groupId>fixture.mavenui</groupId><artifactId>reactor</artifactId><version>1.0</version><packaging>pom</packaging><properties><maven.compiler.release>17</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties><modules><module>common</module><module>application</module></modules></project>\n";
        Files.writeString(root.resolve("pom.xml"), parent);
        for (String name : List.of("common", "application", "extra")) {
            Files.createDirectories(root.resolve(name + "/src/main/java/demo"));
            Files.writeString(root.resolve(name + "/pom.xml"), "<project><modelVersion>4.0.0</modelVersion><parent><groupId>fixture.mavenui</groupId><artifactId>reactor</artifactId><version>1.0</version></parent><artifactId>" + name + "</artifactId>"
                    + (name.equals("application") ? "<dependencies><dependency><groupId>fixture.mavenui</groupId><artifactId>common</artifactId><version>1.0</version></dependency><dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><version>5.13.4</version><scope>test</scope></dependency></dependencies>" : "") + "</project>");
        }
        Path common = root.resolve("common/src/main/java/demo/Numbers.java"), main = root.resolve("application/src/main/java/demo/Main.java");
        Files.writeString(common, "package demo; public class Numbers { public static int value() { return 42; } }\n");
        String source = "package demo;public class Main{public static int result(){return Numbers.value();}public static void main(String[] args){System.out.println(result());}}\n";
        Files.writeString(main, source);
        Path tests = Files.createDirectories(root.resolve("application/src/test/java/demo"));
        Files.writeString(tests.resolve("MainTest.java"), "package demo; public class MainTest { @org.junit.jupiter.api.Test void seesLatestGeneration() { org.junit.jupiter.api.Assertions.assertEquals(44, Main.result()); } }\n");
        Path installedMaven;
        try (var candidates = Files.walk(Path.of(".cache/maven/wrapper/dists"))) {
            installedMaven = candidates.filter(path -> path.endsWith("bin/mvn")).findFirst().orElseThrow().toAbsolutePath();
        }
        Path marker = root.resolve("lifecycle-marker");
        Files.writeString(root.resolve("mvnw"), "#!/bin/sh\nprintf 'requested\\n' >> '" + marker + "'\nexec '" + installedMaven + "' \"$@\"\n");
        root.resolve("mvnw").toFile().setExecutable(true);
        System.setProperty("turboj.config", root.resolve(".test-settings").toString());
        System.setProperty("apple.laf.useScreenMenuBar", "false");
        TurboFrame frame = edt(() -> {
            TurboFrame value = new TurboFrame(new Settings(), Map.of()); value.setVisible(true); value.toFront(); value.mavenProjects().open(root); return value;
        });
        try {
            waitFor(() -> frame.mavenProjects().workspace() != null && !frame.mavenProjects().busy());
            SwingUtilities.invokeLater(() -> frame.mavenProjects().showSettings());
            waitFor(() -> !OverlayHost.dialogs(frame).isEmpty());
            edt(() -> { fields(OverlayHost.dialogs(frame).getLast()).get(2).setText(Path.of(".cache/m2").toAbsolutePath().toString()); button(OverlayHost.dialogs(frame).getLast(), "OK").doClick(); return null; });
            waitFor(() -> frame.mavenProjects().workspace() != null && frame.mavenProjects().workspace().resolved() && !frame.mavenProjects().busy());
            var workspace = edt(() -> frame.mavenProjects().workspace());
            check(workspace.modules().size() == 3 && workspace.resolved(), "real offline reactor import resolves parent and two modules");
            check(workspace.moduleFor(main).orElseThrow().mainJava().languageLevel().equals("17"), "project Java 17 overrides application Java 25");
            check(!Files.exists(marker) && !frame.mavenProjects().trusted(), "passive import executes no wrapper or project code");
            edt(() -> { frame.openPath(main); return null; }); waitFor(() -> main.equals(frame.currentFile()));
            var result = edt(() -> frame.compile(false, false)).get(60, TimeUnit.SECONDS);
            check(result.success(), "Fast Compile resolves current reactor output without mvn install");
            check(!Files.exists(root.resolve("common/target")) && !Files.exists(root.resolve("application/target")), "native outputs stay outside Maven target directories");
            long generation = workspace.generation();
            edt(() -> { frame.editor().selectAll(); frame.editor().replaceSelection(source.replace("Numbers.value()", "Numbers.value()+1")); return null; });
            result = edt(() -> frame.compile(false, false)).get(60, TimeUnit.SECONDS);
            check(result.success() && frame.mavenProjects().workspace().generation() == generation, "Java edits compile without dependency refresh");
            check(Files.readString(main).equals(source) && !Files.exists(marker), "unsaved buffer compilation preserves source files and never launches Maven");
            CompletableFuture<dev.turboj.formatting.FormattingController.Outcome> formatted = new CompletableFuture<>();
            edt(() -> { frame.formatting().format(false, formatted::complete); return null; });
            check(formatted.get(60, TimeUnit.SECONDS) == dev.turboj.formatting.FormattingController.Outcome.APPLIED, "FormatJ formats Maven Java 17 source in-process");
            String current = edt(() -> frame.editor().getText());
            var prepared = edt(() -> frame.refactoring().prepare(new Requests.Rename(new Model.Location(main, current.indexOf("Numbers")), "Values"))).get(90, TimeUnit.SECONDS);
            check(prepared.plan().valid() && prepared.plan().edits().size() >= 2, "RefactorJ resolves a class across Maven module boundaries");
            edt(() -> frame.refactoring().apply(prepared)).get(90, TimeUnit.SECONDS);
            result = edt(() -> frame.compile(false, false)).get(60, TimeUnit.SECONDS);
            check(result.success() && Files.exists(common.resolveSibling("Values.java")), "cross-module rename publishes safely and recompiles natively");
            check(frame.mavenProjects().workspace().generation() == generation, "source rename does not refresh Maven metadata");
            check(!Files.exists(marker), "rename and formatting never launch Maven");

            // Trust is an actual embedded dialog; the test presses its visible OK button.
            CompletableFuture<Boolean> trusted = new CompletableFuture<>();
            SwingUtilities.invokeLater(() -> trusted.complete(frame.mavenProjects().requireTrust()));
            approveTop(frame); check(trusted.get(10, TimeUnit.SECONDS), "project execution requires an explicit in-frame trust choice");
            edt(() -> frame.compile(true, false)).get(60, TimeUnit.SECONDS);
            waitFor(() -> frame.statusMessages().contains(" done · exit 0"));
            check(edt(() -> frame.console().text().contains("43")), "native Run uses selected project runtime and reactor classpath");
            edt(() -> frame.commandRegistry().execute("tests.auto", CommandContext.of())).stage().toCompletableFuture().get(5, TimeUnit.SECONDS);
            check(edt(() -> frame.tests().automatic()), "Auto Test enabled through the real CommandJ action");
            edt(() -> { String before = frame.editor().getText(); frame.editor().selectAll(); frame.editor().replaceSelection(before.replace("+ 1", "+ 2")); return null; });
            waitFor(() -> frame.tests().lastReport() != null && !frame.tests().busy());
            check(edt(() -> frame.tests().lastReport().passed() == 1 && frame.tests().lastReport().failed() == 0 && frame.tests().lastReport().sourceVersion() > 0),
                    "editing Maven source triggers real JUnit against the latest compiled generation");
            check(!Files.exists(marker), "Auto Test never launches Maven or the wrapper");
            edt(() -> frame.commandRegistry().execute("tests.auto", CommandContext.of())).stage().toCompletableFuture().get(5, TimeUnit.SECONDS);

            edt(() -> { frame.openPath(root.resolve("pom.xml")); return null; }); waitFor(() -> root.resolve("pom.xml").equals(frame.currentFile()));
            check(edt(() -> frame.editor().xmlSyntax()), "POM uses XML syntax instead of Java analysis");
            CompletableFuture<Void> requested = new CompletableFuture<>();
            SwingUtilities.invokeLater(() -> { frame.mavenProjects().editPom("Add Module"); requested.complete(null); });
            waitFor(() -> !OverlayHost.dialogs(frame).isEmpty());
            edt(() -> { JTextField input = fields(OverlayHost.dialogs(frame).getLast()).getFirst(); input.setText("extra"); button(OverlayHost.dialogs(frame).getLast(), "OK").doClick(); return null; });
            requested.get(10, TimeUnit.SECONDS); approveTop(frame);
            waitFor(() -> frame.mavenProjects().workspace().modules().size() == 4 && !frame.mavenProjects().busy());
            check(Files.readString(root.resolve("pom.xml")).equals(parent), "POM action previews and changes only the unsaved buffer");
            String edited = edt(() -> frame.editor().getText());
            edt(() -> { frame.editor().selectAll(); frame.editor().replaceSelection(edited + "<broken"); return null; });
            waitFor(() -> frame.buildStatus().contains("XML DIAGNOSTICS"));
            check(edt(() -> frame.editor().getText().endsWith("<broken")), "invalid POM remains editable and receives XML diagnostics");
            edt(() -> { frame.editor().selectAll(); frame.editor().replaceSelection(edited); return null; });
            waitFor(() -> !frame.mavenProjects().busy() && frame.mavenProjects().workspace().modules().size() == 4);

            for (String theme : List.of("classic", "dark", "light")) {
                edt(() -> {
                    frame.applyTheme(Theme.bundled(theme)); frame.mavenProjects().showStructure(); return null;
                });
                check(edt(() -> Arrays.stream(Window.getWindows()).filter(Window::isVisible).count() == 1), "project dialog stays inside one native window: " + theme);
                edt(() -> {
                    screenshot(frame, output.resolve("project-" + theme + ".png"));
                    OverlayHost.dialogs(frame).getLast().dispose(); return null;
                });
                waitFor(() -> frame.editor().isFocusOwner());
                edt(() -> { JMenu project = projectMenu(frame); project.doClick(); return null; });
                waitFor(() -> projectMenu(frame).getPopupMenu().isShowing());
                edt(() -> { screenshot(frame, output.resolve("menu-" + theme + ".png")); MenuSelectionManager.defaultManager().clearSelectedPath(); return null; });
            }
            SwingUtilities.invokeLater(() -> frame.mavenProjects().runLifecycle("validate"));
            approveTop(frame);
            waitFor(() -> Files.exists(marker) && !frame.mavenProjects().lifecycleRunning() && frame.statusMessages().contains("Maven ") && frame.statusMessages().matches("(?s).*Maven [^\n]* done.*"));
            check(Files.readAllLines(marker).size() == 1, "explicit Maven Validate uses the project wrapper exactly once");
            check(edt(() -> frame.console().text().contains("BUILD SUCCESS")), "real Maven offline lifecycle produces its actual output and exit status");
            Files.writeString(output.resolve("report.txt"), "PASS real offline reactor import, Java17 model, warm native compile/run, unsaved buffers, cross-module RefactorJ rename, FormatJ, actual Auto Test after Java edit with current-generation JUnit pass, POM preview/edit/diagnostics, compact explorer, theme menus/internal dialogs, trust and explicit real Maven wrapper validate; no Maven invocation for typing/formatting/refactoring/automatic tests.\n");
        } finally {
            edt(() -> { for (var dialog : OverlayHost.dialogs(frame)) dialog.dispose(); frame.shutdown(); frame.dispose(); return null; });
        }
    }
    private static void approveTop(TurboFrame frame) throws Exception {
        waitFor(() -> !OverlayHost.dialogs(frame).isEmpty() && button(OverlayHost.dialogs(frame).getLast(), "OK") != null);
        edt(() -> { button(OverlayHost.dialogs(frame).getLast(), "OK").doClick(); return null; });
    }
    private static List<JTextField> fields(Container root) {
        List<JTextField> values = new ArrayList<>();
        for (Component component : root.getComponents()) { if (component instanceof JTextField field) values.add(field); else if (component instanceof Container child) values.addAll(fields(child)); }
        return values;
    }
    private static JButton button(Container root, String text) {
        for (Component component : root.getComponents()) { if (component instanceof JButton button && text.equals(button.getText())) return button; if (component instanceof Container child) { JButton found = button(child, text); if (found != null) return found; } }
        return null;
    }
    private static void screenshot(TurboFrame frame, Path target) throws Exception {
        var image = new java.awt.image.BufferedImage(frame.getWidth(), frame.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics(); frame.paint(graphics); graphics.dispose(); javax.imageio.ImageIO.write(image, "png", target.toFile());
    }
    private static JMenu projectMenu(TurboFrame frame) { return Arrays.stream(frame.getJMenuBar().getComponents()).filter(component -> component instanceof JMenu menu && menu.getText().equals("Project")).map(JMenu.class::cast).findFirst().orElseThrow(); }
    private static void check(boolean success, String text) { if (!success) throw new AssertionError(text); System.out.println("PASS " + text); }
    private static void waitFor(Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        while (System.nanoTime() < deadline) { if (edt(condition)) return; Thread.sleep(30); }
        throw new AssertionError("Maven GUI condition timed out");
    }
    private static <T> T edt(Callable<T> action) throws Exception { FutureTask<T> task = new FutureTask<>(action); SwingUtilities.invokeAndWait(task); return task.get(); }
}
