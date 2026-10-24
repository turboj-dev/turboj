package dev.turboj.app;

import dev.turboj.commandj.core.CommandContext;
import dev.turboj.commandj.core.CommandResult;
import dev.turboj.commandj.swing.OverlayHost;
import dev.turboj.config.Settings;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.swing.JButton;
import javax.swing.SwingUtilities;

/** A blocked native preparation lane makes cancellation before worker dispatch deterministic. */
public final class PendingAutomationStopSmoke {
    private static final StringBuilder checks = new StringBuilder();
    private static Path runMarker;

    public static void main(String[] args) throws Exception {
        Path output = Path.of("app/target/pending-stop-smoke").toAbsolutePath();
        Files.createDirectories(output);
        Path project = Files.createTempDirectory("turboj-pending-stop-");
        runMarker = project.resolve("program-executed");
        Path main = Files.createDirectories(project.resolve("src/main/java")).resolve("Main.java");
        Path test = Files.createDirectories(project.resolve("src/test/java")).resolve("MainTest.java");
        Path marker = project.resolve("tests-executed");
        Files.writeString(main, source(1));
        Files.writeString(test, "class MainTest { @org.junit.jupiter.api.Test void saved() throws Exception { java.nio.file.Files.writeString(java.nio.file.Path.of(\""
                + marker.toString().replace("\\", "\\\\").replace("\"", "\\\"")
                + "\"), Integer.toString(Main.value())); org.junit.jupiter.api.Assertions.assertTrue(Main.value()>0); } }");
        Files.writeString(project.resolve("pom.xml"), """
                <project><modelVersion>4.0.0</modelVersion><groupId>example</groupId><artifactId>stop</artifactId><version>1</version>
                <properties><maven.compiler.release>17</maven.compiler.release></properties>
                <dependencies><dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><version>5.13.4</version><scope>test</scope></dependency></dependencies></project>
                """);
        Path home = Files.createDirectories(project.resolve("private-home/.m2"));
        Files.writeString(home.resolve("settings.xml"), "<settings><localRepository>" + Path.of(".cache/m2").toAbsolutePath()
                + "</localRepository><offline>true</offline></settings>");
        System.setProperty("user.home", home.getParent().toString());
        System.setProperty("turboj.config", project.resolve("settings").toString());
        System.setProperty("apple.laf.useScreenMenuBar", "false");
        Settings settings = new Settings();
        TurboFrame frame = edt(() -> {
            var app = new TurboFrame(settings, Map.of()); app.setVisible(true); app.openPath(project.resolve("pom.xml")); return app;
        });
        Lane gate = null;
        try {
            waitFor(() -> frame.mavenProjects().workspace() != null && frame.mavenProjects().workspace().resolved() && !frame.mavenProjects().busy());
            edt(() -> { frame.openPath(main); return null; });
            waitFor(() -> main.equals(frame.currentFile()));
            // Opening the POM can have one debounced configuration publication queued.
            // Finish it before testing same-generation cancellation rather than deliberately
            // invalidating the saved generation with an unrelated model refresh.
            var syncField = frame.mavenProjects().getClass().getDeclaredField("pending");
            syncField.setAccessible(true);
            waitFor(() -> {
                var sync = (java.util.concurrent.Future<?>) syncField.get(frame.mavenProjects());
                return (sync == null || sync.isDone()) && !frame.mavenProjects().busy();
            });
            // Enabling the option still requires the actual project-trust interaction.
            var enabled = new CompletableFuture<CommandResult>();
            SwingUtilities.invokeLater(() -> frame.commandRegistry().execute("run.onSave", context()).stage()
                    .whenComplete((result, failure) -> { if (failure == null) enabled.complete(result); else enabled.completeExceptionally(failure); }));
            waitFor(() -> !OverlayHost.dialogs(frame).isEmpty());
            edt(() -> { button(OverlayHost.dialogs(frame).getFirst(), "OK").doClick(); return null; });
            check(enabled.get(10, TimeUnit.SECONDS).status() == CommandResult.Status.SUCCEEDED, "project trust is explicit");
            command(frame, "tests.onSave");

            gate = block(frame);
            command(frame, "tests.all");
            waitFor(() -> pending(frame));
            check(!edt(() -> frame.tests().busy()), "manual test selection is pending before worker dispatch");
            command(frame, "tests.stop");
            gate.close(); gate = null;
            drainPreparation(frame);
            waitFor(() -> !pending(frame) && !frame.tests().busy());
            check(!Files.exists(marker) && edt(() -> frame.tests().lastReport() == null), "Stop Tests cancels pending selection without executing a test");

            gate = block(frame);
            save(frame, main, 2);
            waitFor(() -> pending(frame));
            command(frame, "tests.stop");
            gate.close(); gate = null;
            waitFor(() -> Files.isRegularFile(runMarker) && Files.readString(runMarker).equals("2")
                    && !frame.isRunning() && !pending(frame) && !frame.tests().busy());
            check(!Files.exists(marker) && edt(() -> frame.tests().lastReport() == null), "Stop Tests preserves the independent saved program run without testing");
            check(settings.autoRunOnSave && settings.autoTestOnSave, "Stop does not change either saved preference");

            gate = block(frame);
            save(frame, main, 3);
            waitFor(() -> pending(frame));
            command(frame, "stop");
            gate.close(); gate = null;
            waitFor(() -> !pending(frame) && !frame.tests().busy() && frame.tests().lastReport() != null);
            check(Files.readString(marker).equals("3"), "Stop Program preserves the independent saved test run");
            check(edt(() -> !frame.console().text().contains("AUTO_MAIN=3") && !frame.isRunning()), "Stop Program cancels a pending automatic run before compilation completes");

            var previous = edt(() -> frame.tests().lastReport());
            save(frame, main, 4);
            waitFor(() -> frame.console().text().contains("AUTO_MAIN=4") && !frame.isRunning()
                    && frame.tests().lastReport() != previous && !frame.tests().busy());
            check(Files.readString(marker).equals("4") && edt(() -> frame.tests().lastReport().passed() == 1), "a later Save runs and tests its current generation after both cancellation paths");
            check(Files.readString(main).equals(source(4)), "saved source and edits are preserved");
            Files.writeString(output.resolve("report.txt"), checks.toString());
            Files.writeString(output.resolve("console.txt"), edt(() -> frame.console().text()));
        } catch (Throwable failure) {
            Files.writeString(output.resolve("failure.txt"), edt(() -> frame.console().text()));
            Files.writeString(output.resolve("failure-state.txt"), edt(() -> {
                StringBuilder state = new StringBuilder("source=" + frame.currentFile() + "\neditorVersion=" + frame.editor().version()
                        + "\nmainExists=" + Files.exists(runMarker) + "\nmainValue=" + (Files.exists(runMarker) ? Files.readString(runMarker) : "")
                        + "\ntestsBusy=" + frame.tests().busy() + "\nreport=" + frame.tests().lastReport() + "\n");
                for (String name : new String[]{"operation", "runRequest", "refactoringRevision", "saving", "queuedSaveAutomation", "runningSaveAutomation",
                        "pendingSaveRun", "pendingTestSnapshot", "testSelectionRequest", "saveTestRun", "pendingMavenCompile", "nativeCompileTask"}) {
                    var field = TurboFrame.class.getDeclaredField(name); field.setAccessible(true); state.append(name).append('=').append(field.get(frame)).append('\n');
                }
                return state.toString();
            }));
            throw failure;
        } finally {
            if (gate != null) gate.close();
            edt(() -> { frame.shutdown(); frame.dispose(); return null; });
        }
    }

    private static String source(int value) {
        return "public class Main { public static int value() { return " + value
                + "; } public static void main(String[] args) throws Exception { java.nio.file.Files.writeString(java.nio.file.Path.of(\""
                + runMarker.toString().replace("\\", "\\\\").replace("\"", "\\\"")
                + "\"), Integer.toString(value())); System.out.println(\"AUTO_MAIN=\"+value()); } }\n";
    }

    private static void save(TurboFrame frame, Path file, int value) throws Exception {
        edt(() -> { frame.console().clear(); frame.editor().selectAll(); frame.editor().replaceSelection(source(value)); return null; });
        command(frame, "save");
        waitFor(() -> Files.readString(file).equals(source(value)));
    }

    private static Lane block(TurboFrame frame) throws Exception {
        var lane = lane(frame);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        lane.execute(() -> { entered.countDown(); try { if(!release.await(45, TimeUnit.SECONDS))throw new AssertionError("Preparation gate timed out"); } catch (InterruptedException cancelled) { Thread.currentThread().interrupt(); } });
        if (!entered.await(20, TimeUnit.SECONDS)) throw new AssertionError("Preparation lane did not enter gate");
        return new Lane(release);
    }

    private static void drainPreparation(TurboFrame frame) throws Exception {
        // First barrier flushes selected.whenComplete; second flushes any coordinator lookup it posts.
        for (int round = 0; round < 2; round++) {
            lane(frame).submit(() -> {}).get(20, TimeUnit.SECONDS);
            edt(() -> null);
        }
    }

    private static ScheduledThreadPoolExecutor lane(TurboFrame frame) throws Exception {
        var field = TurboFrame.class.getDeclaredField("nativeProjectCompiler"); field.setAccessible(true);
        return (ScheduledThreadPoolExecutor) field.get(frame);
    }

    private static boolean pending(TurboFrame frame) throws Exception {
        var field = TurboFrame.class.getDeclaredField("pendingTestSnapshot"); field.setAccessible(true); return field.getBoolean(frame);
    }

    private record Lane(CountDownLatch release) implements AutoCloseable { public void close() { release.countDown(); } }

    private static CommandContext context() { return CommandContext.of("workspace", "editor", "editable"); }
    private static void command(TurboFrame frame, String id) throws Exception {
        var result = edt(() -> frame.commandRegistry().execute(id, context())).stage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        check(result.status() == CommandResult.Status.SUCCEEDED, "registered " + id + " is available and completes");
    }

    private static JButton button(Container container, String text) {
        for (Component component : container.getComponents()) {
            if (component instanceof JButton button && text.equals(button.getText())) return button;
            if (component instanceof Container nested) { var found = button(nested, text); if (found != null) return found; }
        }
        return null;
    }

    private static void check(boolean success, String text) {
        if (!success) throw new AssertionError(text);
        checks.append("PASS ").append(text).append('\n'); System.out.println("PASS " + text);
    }

    private static void waitFor(Callable<Boolean> condition) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(40);
        while (System.nanoTime() < end) { if (edt(condition)) return; Thread.sleep(20); }
        throw new AssertionError("Timed out waiting for cancellation result");
    }

    private static <T> T edt(Callable<T> action) throws Exception {
        var task = new FutureTask<T>(action); SwingUtilities.invokeAndWait(task); return task.get();
    }
}
