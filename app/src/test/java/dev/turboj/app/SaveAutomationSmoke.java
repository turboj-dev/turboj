package dev.turboj.app;

import dev.turboj.commandj.core.CommandContext;
import dev.turboj.commandj.core.CommandResult;
import dev.turboj.commandj.swing.EmbeddedDialog;
import dev.turboj.commandj.swing.OverlayHost;
import dev.turboj.config.Settings;
import dev.turboj.turbotest.TestEngine;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import javax.swing.JButton;
import javax.swing.SwingUtilities;

/** Runs real registered Save/Options commands in private copies of both example projects. */
public final class SaveAutomationSmoke {
    private static final List<String> checks = new ArrayList<>();
    private static Path output;

    public static void main(String[] args) throws Exception {
        output = Path.of("app/target/save-automation-smoke").toAbsolutePath();
        Files.createDirectories(output);
        Path session = Files.createTempDirectory("turboj-save-automation-").toAbsolutePath();
        Path home = Files.createDirectories(session.resolve("home/.m2"));
        // Reuse Maven's local artifacts, with no network or user credentials involved in this test.
        Path repository = Path.of(".cache/m2").toAbsolutePath();
        Files.writeString(home.resolve("settings.xml"), "<settings><localRepository>" + repository
                + "</localRepository><offline>true</offline></settings>\n");
        System.setProperty("user.home", home.getParent().toString());
        System.setProperty("apple.laf.useScreenMenuBar", "false");
        exercise(session, false);
        exercise(session, true);
        restoredPreferencesDoNotTrustNewProject(session);
        Files.writeString(output.resolve("report.txt"), String.join("\n", checks) + "\nPrivate projects: " + session + "\n");
    }

    private static void exercise(Path session, boolean maven) throws Exception {
        String kind = maven ? "Maven" : "plain Java";
        Fixture fixture = copyExample(session.resolve(maven ? "maven" : "plain"), maven);
        System.setProperty("turboj.config", session.resolve(maven ? "maven-preferences" : "plain-preferences").toString());
        Settings settings = Settings.load();
        check(!settings.autoRunOnSave && !settings.autoTestOnSave && !settings.autoTest,
                kind + ": all automatic execution settings default off");
        TurboFrame frame = open(fixture, settings);
        try {
            check(edt(() -> !frame.tests().automatic()), kind + ": legacy Auto Test stays off");
            edit(frame, fixture.source(1));
            pause();
            check(edt(() -> !frame.isRunning() && !frame.tests().busy() && frame.tests().lastReport() == null),
                    kind + ": typing with default settings executes nothing");
            save(frame, fixture, 1);
            pause();
            check(edt(() -> !frame.console().text().contains("AUTO_GENERATION=") && frame.tests().lastReport() == null),
                    kind + ": Save with both toggles off executes nothing");

            if (maven) {
                var cancelled = command(frame, "run.onSave");
                waitFor(frame, "trust before enabling save execution", () -> dialog(frame, "Project Trust") != null);
                edt(() -> { button(dialog(frame, "Project Trust"), "Cancel").doClick(); return null; });
                accepted(cancelled);
                check(!settings.autoRunOnSave && !edt(() -> frame.mavenProjects().trusted()),
                        "Maven: cancelling trust leaves Auto Run on Save off");
                var enabled = command(frame, "run.onSave");
                waitFor(frame, "explicit project trust", () -> dialog(frame, "Project Trust") != null);
                edt(() -> { button(dialog(frame, "Project Trust"), "OK").doClick(); return null; });
                accepted(enabled);
            } else accepted(command(frame, "run.onSave"));
            persisted(frame, true, false);
            clear(frame);
            edit(frame, fixture.source(2));
            pause();
            check(edt(() -> !frame.console().text().contains("AUTO_GENERATION=") && frame.tests().lastReport() == null),
                    kind + ": enabling Auto Run on Save does not run while typing");
            save(frame, fixture, 2);
            ran(frame, 2);
            check(edt(() -> frame.tests().lastReport() == null), kind + ": run-only Save does not execute tests");
            check(edt(() -> frame.console().text().contains("SAVE_DISK_MATCH=true")),
                    kind + ": automatic main observes the source already written to disk");

            accepted(command(frame, "run.onSave"));
            accepted(command(frame, "tests.onSave"));
            persisted(frame, false, true);
            clear(frame);
            edit(frame, fixture.source(3));
            pause();
            check(edt(() -> frame.tests().lastReport() == null && !frame.tests().busy()),
                    kind + ": enabling Auto Test on Save does not test while typing");
            save(frame, fixture, 3);
            tested(frame, null, kind + ": test-only Save");
            check(edt(() -> !frame.console().text().contains("AUTO_GENERATION=")),
                    kind + ": test-only Save does not run the main class");

            accepted(command(frame, "run.onSave"));
            persisted(frame, true, true);
            TestEngine.Report previous = edt(() -> frame.tests().lastReport());
            clear(frame);
            edit(frame, fixture.source(4));
            pause();
            check(edt(() -> frame.tests().lastReport() == previous && !frame.console().text().contains("AUTO_GENERATION=")),
                    kind + ": both new toggles still require Save rather than a text change");
            save(frame, fixture, 4);
            ran(frame, 4);
            tested(frame, previous, kind + ": combined Run and Test");
            check(edt(() -> occurrences(frame.console().text(), "[INFO] Test ") == 1
                            && !frame.console().text().contains("[INFO] Compile Project")),
                    kind + ": combined save uses one workspace compilation before execution");
            check(edt(() -> OverlayHost.dialogs(frame).isEmpty()),
                    kind + ": automatic tests report inline without a modal result dialog");

            TestEngine.Report successful = edt(() -> frame.tests().lastReport());
            clear(frame);
            edit(frame, fixture.source(5).replace("return first + second;", "return first + ;"));
            accepted(command(frame, "save"));
            waitFor(frame, "compile failure stops automation", () -> frame.console().text().contains("Tests were not run because") && !frame.tests().busy());
            check(Files.readString(fixture.main()).contains("return first + ;"),
                    kind + ": invalid Java is saved while compilation rejects execution");
            check(edt(() -> frame.tests().lastReport() == successful && !frame.console().text().contains("AUTO_GENERATION=")),
                    kind + ": compile error launches neither main nor tests");

            clear(frame);
            var saves = edt(() -> {
                replace(frame, fixture.source(6));
                var first = frame.commandRegistry().execute("save", context()).stage();
                replace(frame, fixture.source(7));
                var second = frame.commandRegistry().execute("save", context()).stage();
                return CompletableFuture.allOf(first.toCompletableFuture(), second.toCompletableFuture());
            });
            saves.get(10, TimeUnit.SECONDS);
            waitFor(frame, "latest rapid save reaches disk", () -> Files.readString(fixture.main()).equals(fixture.source(7)));
            ran(frame, 7);
            tested(frame, successful, kind + ": coalesced rapid saves");
            check(edt(() -> !frame.console().text().contains("AUTO_GENERATION=6")),
                    kind + ": superseded save never executes stale bytecode");

            accepted(command(frame, "run.onSave"));
            accepted(command(frame, "tests.onSave"));
            persisted(frame, false, false);
            TestEngine.Report latest = edt(() -> frame.tests().lastReport());
            clear(frame);
            edit(frame, fixture.source(8));
            save(frame, fixture, 8);
            pause();
            check(edt(() -> frame.tests().lastReport() == latest && !frame.console().text().contains("AUTO_GENERATION=")),
                    kind + ": disabling both toggles stops subsequent save execution");

            accepted(command(frame, "run.onSave"));
            accepted(command(frame, "tests.onSave"));
            persisted(frame, true, true);
            clear(frame);
            edit(frame, fixture.source(9));
            Files.writeString(fixture.main(), fixture.source(10));
            accepted(command(frame, "save"));
            waitFor(frame, "external modification blocks Save", () -> frame.statusMessages().contains("changed outside")
                    || frame.statusMessages().contains("modified outside") || frame.statusMessages().contains("changed on disk"));
            pause();
            check(Files.readString(fixture.main()).equals(fixture.source(10))
                            && edt(() -> frame.tests().lastReport() == latest && !frame.console().text().contains("AUTO_GENERATION=")),
                    kind + ": failed disk write preserves external changes and triggers no automation");
            if (maven) check(!Files.exists(fixture.root().resolve("target")) && !Files.exists(fixture.root().resolve("wrapper-invoked")),
                    "Maven: every save uses native compilation with no Maven lifecycle or wrapper invocation");
            Files.writeString(output.resolve(maven ? "maven-console.txt" : "plain-console.txt"), edt(() -> frame.console().text()));
        } catch (Throwable failure) {
            String console = edt(() -> frame.console().text());
            Files.writeString(output.resolve(maven ? "maven-failure.txt" : "plain-failure.txt"), console);
            System.err.println(kind + " SAVE AUTOMATION CONSOLE:\n" + console);
            throw failure;
        } finally { close(frame); }
    }

    private static void restoredPreferencesDoNotTrustNewProject(Path session) throws Exception {
        Fixture fixture = copyExample(session.resolve("untrusted-maven"), true);
        System.setProperty("turboj.config", session.resolve("untrusted-preferences").toString());
        var stored = new Settings();
        stored.autoRunOnSave = stored.autoTestOnSave = true;
        stored.save();
        var loaded = Settings.load();
        check(loaded.autoRunOnSave && loaded.autoTestOnSave && !loaded.autoTest,
                "both save options restore independently of legacy Auto Test");
        TurboFrame frame = open(fixture, loaded);
        try {
            check(!edt(() -> frame.mavenProjects().trusted()), "restored options do not trust an unfamiliar Maven workspace");
            clear(frame);
            edit(frame, fixture.source(1));
            save(frame, fixture, 1);
            waitFor(frame, "untrusted automatic execution blocked", () -> frame.statusMessages().contains("Save automation paused:"));
            check(edt(() -> frame.tests().lastReport() == null && !frame.tests().busy()
                            && !frame.isRunning() && !frame.console().text().contains("AUTO_GENERATION=")),
                    "untrusted Maven Save writes source but runs neither main nor tests");
            check(!Files.exists(fixture.root().resolve("wrapper-invoked")), "untrusted Save never launches the Maven wrapper");
        } finally { close(frame); }
    }

    private record Fixture(Path root, Path main, String template, boolean maven) {
        String source(int generation) { return template.replace("SAVE_VALUE", Integer.toString(generation)); }
    }

    private static Fixture copyExample(Path destination, boolean maven) throws Exception {
        Path source = Path.of(maven ? "examples/maven" : "examples/plain-java").toAbsolutePath();
        try (var files = Files.walk(source)) {
            for (Path file : files.filter(path -> !path.startsWith(source.resolve("target"))).toList()) {
                Path target = destination.resolve(source.relativize(file));
                if (Files.isDirectory(file)) Files.createDirectories(target); else Files.copy(file, target);
            }
        }
        Path main = destination.resolve((maven ? "src/main/java/" : "src/") + "dev/turboj/examples/calculator/Calculator.java");
        Path test = destination.resolve((maven ? "src/test/java/" : "test/") + "dev/turboj/examples/calculator/CalculatorTest.java");
        String template = Files.readString(main).replace("public static void main(String[] args) {", """
                // SAVE_GENERATION=SAVE_VALUE
                public static int savedGeneration() { return SAVE_VALUE; }
                public static boolean diskMatchesGeneration() {
                    try { return java.nio.file.Files.readString(java.nio.file.Path.of("SOURCE_PATH"))
                            .contains("// SAVE_GENERATION=" + savedGeneration()); }
                    catch (java.io.IOException failure) { throw new IllegalStateException(failure); }
                }
                public static void main(String[] args) {
                    System.out.println("AUTO_GENERATION=" + savedGeneration());
                    System.out.println("SAVE_DISK_MATCH=" + diskMatchesGeneration());
                """.replace("SOURCE_PATH", main.toString().replace("\\", "\\\\").replace("\"", "\\\"")));
        Files.writeString(main, template.replace("SAVE_VALUE", "0"));
        String tests = Files.readString(test).replace("void addsPositiveAndNegativeNumbers() {",
                "void addsPositiveAndNegativeNumbers() {\n        org.junit.jupiter.api.Assertions.assertTrue(Calculator.diskMatchesGeneration());")
                .replace("void dividesWithoutTruncationAndRejectsZero() {",
                        "void dividesWithoutTruncationAndRejectsZero() {\n        org.junit.jupiter.api.Assertions.assertTrue(Calculator.diskMatchesGeneration());");
        Files.writeString(test, tests);
        if (maven) {
            Files.writeString(destination.resolve("mvnw"), "#!/bin/sh\nprintf invoked > wrapper-invoked\nexit 99\n");
            destination.resolve("mvnw").toFile().setExecutable(true);
        }
        return new Fixture(destination, main, template, maven);
    }

    private static TurboFrame open(Fixture fixture, Settings settings) throws Exception {
        TurboFrame frame = edt(() -> {
            var created = new TurboFrame(settings, Map.of());
            created.setVisible(true);
            created.openPath(fixture.maven() ? fixture.root().resolve("pom.xml") : fixture.main());
            return created;
        });
        if (fixture.maven()) {
            waitFor(frame, "resolved Maven workspace", () -> !frame.mavenProjects().busy()
                    && frame.mavenProjects().workspace() != null && frame.mavenProjects().workspace().resolved());
            edt(() -> { frame.openPath(fixture.main()); return null; });
        }
        waitFor(frame, "active calculator source", () -> fixture.main().equals(frame.currentFile()));
        return frame;
    }

    private static void edit(TurboFrame frame, String source) throws Exception {
        edt(() -> { replace(frame, source); return null; });
    }

    private static void replace(TurboFrame frame, String source) {
        frame.editor().selectAll(); frame.editor().replaceSelection(source);
    }

    private static void save(TurboFrame frame, Fixture fixture, int generation) throws Exception {
        accepted(command(frame, "save"));
        waitFor(frame, "source saved", () -> Files.readString(fixture.main()).equals(fixture.source(generation)));
    }

    private static void ran(TurboFrame frame, int generation) throws Exception {
        waitFor(frame, "automatic main generation " + generation, () -> !frame.isRunning()
                && frame.console().text().contains("AUTO_GENERATION=" + generation)
                && frame.console().text().contains("SUCCESS · exit 0"));
    }

    private static void tested(TurboFrame frame, TestEngine.Report previous, String description) throws Exception {
        waitFor(frame, "automatic JUnit completion", () -> !frame.tests().busy() && frame.tests().lastReport() != null
                && frame.tests().lastReport() != previous);
        check(edt(() -> frame.tests().lastReport().passed() == 2 && frame.tests().lastReport().failed() == 0
                        && frame.tests().lastReport().tests().size() == 2), description + " executes two passing tests against saved source");
        var workspace = TurboFrame.class.getDeclaredField("testWorkspace"); workspace.setAccessible(true);
        check(edt(() -> {
            var snapshot = (dev.turboj.refactorj.api.Model.WorkspaceSnapshot) workspace.get(frame);
            return frame.tests().lastReport().sourceVersion() == snapshot.version()
                    && snapshot.sources().stream().anyMatch(source -> source.file().equals(frame.currentFile())
                            && source.text().equals(frame.editor().getText()));
        }),
                description + " publishes only the current source generation");
    }

    private static void persisted(TurboFrame frame, boolean run, boolean tests) throws Exception {
        waitFor(frame, "saved option preferences", () -> {
            Settings loaded = Settings.load();
            return loaded.autoRunOnSave == run && loaded.autoTestOnSave == tests && !loaded.autoTest;
        });
        check(true, "save options persist independently: Run=" + run + ", Tests=" + tests);
    }

    private static void clear(TurboFrame frame) throws Exception { edt(() -> { frame.console().clear(); return null; }); }
    private static void pause() throws InterruptedException { Thread.sleep(1000); }
    private static CommandContext context() { return CommandContext.of("workspace", "editor", "editable"); }
    private static void accepted(CompletableFuture<CommandResult> future) throws Exception {
        if (future.get(10, TimeUnit.SECONDS).status() != CommandResult.Status.SUCCEEDED)
            throw new AssertionError("Registered command did not succeed");
    }

    private static CompletableFuture<CommandResult> command(TurboFrame frame, String id) {
        var result = new CompletableFuture<CommandResult>();
        SwingUtilities.invokeLater(() -> {
            try {
                frame.commandRegistry().execute(id, context()).stage().whenComplete((value, failure) -> {
                    if (failure == null) result.complete(value); else result.completeExceptionally(failure);
                });
            } catch (Throwable failure) { result.completeExceptionally(failure); }
        });
        return result;
    }

    private static int occurrences(String text, String part) {
        int count = 0;
        for (int from = 0; (from = text.indexOf(part, from)) >= 0; from += part.length()) count++;
        return count;
    }

    private static EmbeddedDialog dialog(TurboFrame frame, String title) {
        return OverlayHost.dialogs(frame).stream().filter(value -> value.getTitle().equals(title)).findFirst().orElse(null);
    }

    private static JButton button(Container root, String title) {
        for (Component child : root.getComponents()) {
            if (child instanceof JButton button && title.equals(button.getText())) return button;
            if (child instanceof Container nested) { var found = button(nested, title); if (found != null) return found; }
        }
        return null;
    }

    private static void close(TurboFrame frame) throws Exception {
        edt(() -> { for (var dialog : OverlayHost.dialogs(frame)) dialog.dispose(); frame.shutdown(); frame.dispose(); return null; });
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
        checks.add("PASS " + description); System.out.println("PASS " + description);
    }

    private static void waitFor(TurboFrame frame, String description, Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        while (System.nanoTime() < deadline) {
            if (edt(condition)) return;
            Thread.sleep(30);
        }
        throw new AssertionError("Timed out waiting for " + description + "\n" + edt(() -> frame.console().text()));
    }

    private static <T> T edt(Callable<T> action) throws Exception {
        var task = new FutureTask<T>(action); SwingUtilities.invokeAndWait(task); return task.get();
    }
}
