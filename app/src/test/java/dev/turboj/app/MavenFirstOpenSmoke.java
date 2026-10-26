package dev.turboj.app;

import dev.turboj.commandj.core.CommandContext;
import dev.turboj.commandj.core.CommandResult;
import dev.turboj.commandj.swing.EmbeddedDialog;
import dev.turboj.commandj.swing.OverlayHost;
import dev.turboj.config.Settings;
import dev.turboj.project.MavenRunTargets;
import java.awt.Component;
import java.awt.Container;
import java.awt.Window;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.SwingUtilities;

/** Real first-open flow with a fresh user home: online import, Run, then Run All Tests. */
public final class MavenFirstOpenSmoke {
    private static final List<String> checks = new ArrayList<>();

    public static void main(String[] args) throws Exception {
        Path output = Path.of("app/target/maven-first-open-smoke").toAbsolutePath();
        Files.createDirectories(output);
        // Outside this checkout: an ancestor .mvn/maven.config would change the local repository.
        Path session = Files.createTempDirectory("turboj-first-open-").toAbsolutePath();
        Path home = Files.createDirectories(session.resolve("home"));
        Path first = copyExample(session.resolve("opened-pom"));
        Path second = copyExample(session.resolve("opened-folder"));
        Path repository = home.resolve(".m2/repository");
        check(!Files.exists(repository), "first import starts with an empty default Maven repository");
        System.setProperty("user.home", home.toString());
        System.setProperty("turboj.config", session.resolve("preferences").toString());
        System.setProperty("apple.laf.useScreenMenuBar", "false");
        TurboFrame frame = edt(() -> {
            var created = new TurboFrame(new Settings(), Map.of());
            created.setVisible(true);
            created.toFront();
            created.openPath(first.resolve("pom.xml"));
            return created;
        });
        try {
            imported(frame, first);
            check(edt(() -> first.resolve("pom.xml").equals(frame.currentFile())), "POM remains active after import");
            check(edt(() -> !frame.mavenProjects().workspace().offline()), "new project uses online dependency resolution");
            check(edt(() -> frame.mavenProjects().workspace().localRepository().equals(repository)), "imports use the fresh default ~/.m2 repository");
            check(Files.isRegularFile(repository.resolve("org/junit/jupiter/junit-jupiter/5.13.4/junit-jupiter-5.13.4.jar")), "JUnit dependency was downloaded during import");
            check(edt(() -> !frame.mavenProjects().trusted()), "passive dependency import does not trust project code");
            runAndApprove(frame);
            calculatorOutput(frame);
            check(edt(() -> first.resolve("pom.xml").equals(frame.currentFile())), "Run works with the POM still active");
            allTests(frame);
            check(!Files.exists(first.resolve("target")) && !Files.exists(first.resolve("wrapper-invoked")), "native Run and Test never invoke Maven or its wrapper");
            screenshot(frame, output.resolve("pom-run-tests.png"));
            closeTestResults(frame);

            Path previous = session.resolve("Previous.java");
            Files.writeString(previous, "public class Previous { public static void main(String[] args) { System.out.println(\"WRONG PREVIOUS PROGRAM\"); } }\n");
            edt(() -> { frame.openPath(previous); return null; });
            waitFor(frame, "open previous source", () -> previous.equals(frame.currentFile()));
            edt(() -> { frame.openPath(second); return null; });
            imported(frame, second);
            runAndApprove(frame);
            calculatorOutput(frame);
            check(edt(() -> !frame.console().text().contains("WRONG PREVIOUS PROGRAM")), "folder Run executes imported project rather than the previous buffer");
            allTests(frame);
            closeTestResults(frame);

            Path otherMain = second.resolve("src/main/java/dev/turboj/examples/calculator/OtherMain.java");
            Files.writeString(otherMain, "package dev.turboj.examples.calculator; public class OtherMain { public static void main(String[] args) { System.out.println(\"CHOSEN OTHER MAIN\"); } }\n");
            command(frame, "run").get(10, TimeUnit.SECONDS);
            waitFor(frame, "choose among two mains", () -> dialog(frame, "Run Maven project") != null);
            check(edt(() -> choices(dialog(frame, "Run Maven project")).getItemCount() == 2), "multiple mains require an explicit in-frame choice");
            edt(() -> { button(dialog(frame, "Run Maven project"), "Cancel").doClick(); return null; });
            waitFor(frame, "cancel main chooser", () -> dialog(frame, "Run Maven project") == null);
            check(edt(() -> !frame.isRunning() && !frame.console().text().contains("--- run ")), "Cancel does not launch an arbitrary main");
            command(frame, "run").get(10, TimeUnit.SECONDS);
            waitFor(frame, "choose main again", () -> dialog(frame, "Run Maven project") != null);
            edt(() -> {
                var choice = choices(dialog(frame, "Run Maven project"));
                for (int index = 0; index < choice.getItemCount(); index++) {
                    Object value = choice.getItemAt(index);
                    if (value instanceof MavenRunTargets.Target target && target.mainClass().endsWith(".OtherMain")) choice.setSelectedIndex(index);
                }
                button(dialog(frame, "Run Maven project"), "OK").doClick();
                return null;
            });
            waitFor(frame, "selected main execution", () -> frame.console().text().contains("CHOSEN OTHER MAIN") && frame.console().text().contains("SUCCESS · exit 0"));
            check(true, "selected target executes with its project runtime");
            check(!Files.exists(second.resolve("target")) && !Files.exists(second.resolve("wrapper-invoked")), "folder Run/Test and target chooser never invoke Maven");

            Path library = Files.createDirectory(session.resolve("library"));
            Files.writeString(library.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion><groupId>dev.turboj.examples</groupId><artifactId>library</artifactId><version>1</version><properties><maven.compiler.release>21</maven.compiler.release></properties></project>\n");
            Files.createDirectories(library.resolve("src/main/java"));
            Files.writeString(library.resolve("src/main/java/Library.java"), "public class Library { public static int value() { return 7; } }\n");
            edt(() -> { frame.openPath(library.resolve("pom.xml")); return null; });
            imported(frame, library);
            runAndApprove(frame);
            waitFor(frame, "library has no main", () -> frame.console().text().contains("No runnable main class found"));
            check(edt(() -> !frame.isRunning() && OverlayHost.dialogs(frame).isEmpty()), "library without main has a clear message and no fake target");
            check(edt(() -> Arrays.stream(Window.getWindows()).filter(Window::isVisible).count() == 1), "all operations stay inside one native frame");
            Files.writeString(output.resolve("report.txt"), String.join("\n", checks) + "\nFresh home: " + home + "\n");
        } catch (Throwable failure) {
            String console = edt(() -> frame.console().text());
            Files.writeString(output.resolve("failure-console.txt"), console);
            System.err.println("MAVEN FIRST-OPEN CONSOLE:\n" + console);
            throw failure;
        } finally {
            edt(() -> {
                for (var dialog : OverlayHost.dialogs(frame)) dialog.dispose();
                frame.shutdown();
                frame.dispose();
                return null;
            });
        }
    }

    private static Path copyExample(Path destination) throws Exception {
        Path source = Path.of("examples/maven").toAbsolutePath();
        try (var files = Files.walk(source)) {
            for (Path file : files.filter(path -> !path.startsWith(source.resolve("target"))).toList()) {
                Path target = destination.resolve(source.relativize(file));
                if (Files.isDirectory(file)) Files.createDirectories(target);
                else Files.copy(file, target);
            }
        }
        Files.writeString(destination.resolve("mvnw"), "#!/bin/sh\nprintf invoked > wrapper-invoked\nexit 99\n");
        destination.resolve("mvnw").toFile().setExecutable(true);
        return destination;
    }

    private static void imported(TurboFrame frame, Path project) throws Exception {
        waitFor(frame, "import " + project.getFileName(), () -> !frame.mavenProjects().busy()
                && frame.mavenProjects().workspace() != null && frame.mavenProjects().workspace().root().equals(project));
        var model = edt(() -> frame.mavenProjects().workspace());
        check(model.resolved(), "resolved " + project.getFileName() + ": " + model.diagnostics()
                + model.modules().stream().flatMap(module -> module.diagnostics().stream()).toList());
    }

    private static void runAndApprove(TurboFrame frame) throws Exception {
        var run = command(frame, "run");
        waitFor(frame, "project trust", () -> dialog(frame, "Project Trust") != null);
        edt(() -> { button(dialog(frame, "Project Trust"), "OK").doClick(); return null; });
        var status = run.get(10, TimeUnit.SECONDS);
        check(status.status() == CommandResult.Status.SUCCEEDED, "Run command accepted after explicit project trust");
    }

    private static void calculatorOutput(TurboFrame frame) throws Exception {
        waitFor(frame, "calculator Run", () -> frame.console().text().contains("SUCCESS · exit 0"));
        check(edt(() -> frame.console().text().contains("3 + 4 = 7") && frame.console().text().contains("7 / 2 = 3.5")), "project Run produces expected calculator output");
    }

    private static void allTests(TurboFrame frame) throws Exception {
        var before = edt(() -> frame.tests().lastReport());
        var command = command(frame, "tests.all").get(10, TimeUnit.SECONDS);
        check(command.status() == CommandResult.Status.SUCCEEDED, "Run All Tests command accepted without selecting test source");
        waitFor(frame, "two JUnit tests", () -> frame.tests().lastReport() != null && frame.tests().lastReport() != before && !frame.tests().busy());
        check(edt(() -> frame.tests().lastReport().passed() == 2 && frame.tests().lastReport().failed() == 0
                && frame.tests().lastReport().tests().size() == 2), "native JUnit executes exactly two passing tests");
    }

    private static CompletableFuture<CommandResult> command(TurboFrame frame, String id) {
        var result = new CompletableFuture<CommandResult>();
        SwingUtilities.invokeLater(() -> {
            try {
                frame.commandRegistry().execute(id, CommandContext.of("workspace", "editor", "editable")).stage()
                        .whenComplete((value, failure) -> { if (failure == null) result.complete(value); else result.completeExceptionally(failure); });
            } catch (Throwable failure) { result.completeExceptionally(failure); }
        });
        return result;
    }

    private static void closeTestResults(TurboFrame frame) throws Exception {
        edt(() -> {
            for (var dialog : OverlayHost.dialogs(frame))
                if (dialog.getTitle().startsWith("TurboTest —")) dialog.closeButton().doClick();
            return null;
        });
    }

    private static EmbeddedDialog dialog(TurboFrame frame, String title) {
        return OverlayHost.dialogs(frame).stream().filter(dialog -> dialog.getTitle().equals(title)).findFirst().orElse(null);
    }

    private static JComboBox<?> choices(Container root) {
        for (Component child : root.getComponents()) {
            if (child instanceof JComboBox<?> combo) return combo;
            if (child instanceof Container nested) { var found = choices(nested); if (found != null) return found; }
        }
        return null;
    }

    private static JButton button(Container root, String label) {
        for (Component child : root.getComponents()) {
            if (child instanceof JButton button && button.getText().equals(label)) return button;
            if (child instanceof Container nested) { var found = button(nested, label); if (found != null) return found; }
        }
        return null;
    }

    private static void screenshot(TurboFrame frame, Path file) throws Exception {
        edt(() -> {
            var image = new java.awt.image.BufferedImage(frame.getWidth(), frame.getHeight(), java.awt.image.BufferedImage.TYPE_INT_RGB);
            var graphics = image.createGraphics(); frame.paint(graphics); graphics.dispose();
            javax.imageio.ImageIO.write(image, "png", file.toFile());
            return null;
        });
    }

    private static void check(boolean value, String description) {
        if (!value) throw new AssertionError(description);
        checks.add("PASS " + description);
        System.out.println("PASS " + description);
    }

    private static void waitFor(TurboFrame frame, String description, Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        while (System.nanoTime() < deadline) {
            if (edt(condition)) return;
            Thread.sleep(30);
        }
        throw new AssertionError("Timed out waiting for " + description + "\n" + edt(() -> frame.console().text()));
    }

    private static <T> T edt(Callable<T> action) throws Exception {
        var task = new FutureTask<T>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }
}
