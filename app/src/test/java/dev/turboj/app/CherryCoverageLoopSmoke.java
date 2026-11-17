package dev.turboj.app;

import static dev.turboj.app.KeyboardActionEffectsSmoke.*;
import static dev.turboj.app.KeyboardBranchEffectsSmoke.*;
import static dev.turboj.testing.intelligence.LineIntelligence.*;
import dev.turboj.commandj.swing.OverlayHost;
import dev.turboj.config.Settings;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.nio.file.*;
import java.util.List;
import java.util.Map;

/** CH-10: genuine uncovered code → conventional test → keyboard edit/save → actual Cherry/JaCoCo current coverage. */
public final class CherryCoverageLoopSmoke {
    public static void main(String[] args) throws Exception {
        if (args.length != 1 || !Files.isRegularFile(Path.of(args[0]))) throw new IllegalArgumentException("Pass the real Cherry CLI JAR");
        output = Files.createDirectories(Path.of("app/target/cherry-coverage-loop"));
        work = Files.createTempDirectory("turboj-cherry-coverage-loop-").toRealPath();
        Path fixture = Path.of("integration-tests/fixtures/cherry-native");
        try (var files = Files.walk(fixture)) { for (Path from : files.toList()) {
            Path to = work.resolve(fixture.relativize(from));
            if (Files.isDirectory(from)) Files.createDirectories(to); else Files.copy(from, to);
        } }
        Path maven; try (var files = Files.walk(Path.of(".cache/maven/wrapper/dists"))) {
            maven = files.filter(p -> p.endsWith("bin/mvn")).findFirst().orElseThrow().toAbsolutePath();
        }
        Files.writeString(work.resolve("mvnw"), "#!/bin/sh\nexec '" + maven.toString().replace("'", "'\"'\"'") + "' -o \"$@\"\n");
        work.resolve("mvnw").toFile().setExecutable(true);
        System.setProperty("turboj.config", work.resolve("preferences").toString());
        System.setProperty("apple.laf.useScreenMenuBar", "false");
        settings = new Settings(); settings.cherryTestExecutable = Path.of(args[0]).toAbsolutePath().toString();
        settings.cherryTestExecution.put(work.toString(), "local");
        Path source = work.resolve("src/main/java/demo/Price.java"), test = work.resolve("src/test/java/demo/PriceTest.java");
        String originalSource = Files.readString(source);
        int line = (int) originalSource.substring(0, originalSource.indexOf("return 500;")).chars().filter(c -> c == '\n').count() + 1;
        frame = edt(() -> { var f = new TurboFrame(settings, Map.of()); f.setVisible(true); f.toFront(); Desktop.getDesktop().requestForeground(true); f.openPath(source); return f; });
        try {
            await(() -> source.equals(frame.currentFile()) && frame.editor().lineIntelligence() != null && frame.editor().lineIntelligence().testingActive(), "Cherry configured context");
            action("cherry.run"); await(() -> !frame.tests().cherry().busy() && frame.tests().cherry().last() != null, "baseline Cherry run");
            Run first = edt(() -> frame.tests().cherry().last());
            check(first.state() == RunState.COMPLETED && first.tests().size() == 2 && first.tests().stream().allMatch(t -> t.status() == TestStatus.PASSED), "two baseline tests passed");
            await(() -> frame.editor().lineIntelligence() != null && frame.editor().lineIntelligence().line(line).coverage().state() == CoverageState.UNCOVERED
                    && frame.editor().lineIntelligence().line(line).coverage().freshness() == Freshness.CURRENT, "delivery fee genuinely uncovered");
            var before = edt(() -> frame.editor().lineIntelligence().line(line).coverage());
            check(before.missedInstructions() > 0 && before.coveredInstructions() == 0 && before.proof() != null, "measured uncovered instructions with provenance");
            go(line); action("editor.inspectLineIntelligence"); dialog();
            key(KeyEvent.VK_END, 0); key(KeyEvent.VK_ENTER, 0); key(KeyEvent.VK_ESCAPE, 0);
            await(() -> !OverlayHost.active(frame) && frame.editor().isFocusOwner(), "inspection closes");
            action("navigation.goToTest"); await(() -> test.equals(frame.currentFile()) && frame.editor().isFocusOwner(), "conventional PriceTest opens directly");
            String testBefore = edt(() -> frame.editor().getText());
            int lastLine = (int) testBefore.substring(0, testBefore.lastIndexOf('}')).chars().filter(c -> c == '\n').count() + 1;
            go(lastLine); key(KeyEvent.VK_HOME, 0);
            LiveTemplateWorkflowSmoke.type("    @Test void coversDeliveryFee() { assertEquals(500, Price.deliveryFee()); }");
            key(KeyEvent.VK_ENTER, 0);
            check(edt(() -> frame.editor().getText()).contains("coversDeliveryFee"), "actual keyboard inserted new test");
            action("save");
            await(() -> frame.tests().cherry().last() != null && !frame.tests().cherry().last().id().equals(first.id())
                    && !frame.tests().cherry().busy(), "Save automatically reruns configured Cherry suites");
            Run second = edt(() -> frame.tests().cherry().last());
            check(second.state() == RunState.COMPLETED && second.tests().size() == 3 && second.tests().stream().allMatch(t -> t.status() == TestStatus.PASSED), "three real passing tests after Save: " + second.message());
            action("navigation.goToTest"); await(() -> source.equals(frame.currentFile()), "back to production source");
            await(() -> frame.editor().lineIntelligence() != null && frame.editor().lineIntelligence().line(line).coverage().state() == CoverageState.COVERED
                    && frame.editor().lineIntelligence().line(line).coverage().freshness() == Freshness.CURRENT, "current measured coverage after new test");
            var after = edt(() -> frame.editor().lineIntelligence().line(line).coverage());
            check(after.coveredInstructions() > 0 && after.missedInstructions() == 0 && after.proof() != null, "actual covered instructions and proof");
            check(after.sourceHash().equals(sha256(originalSource)) && !after.classIdentity().isBlank() && !after.processId().isBlank(), "exact source, class and instrumented process");
            check(edt(() -> frame.editor().lineIntelligence().line(line).tests().isEmpty()), "aggregate coverage does not invent per-test attribution");
            Files.writeString(output.resolve("coverage-proof.json"), new com.google.gson.GsonBuilder().setPrettyPrinting()
                    .registerTypeHierarchyAdapter(Path.class, (com.google.gson.JsonSerializer<Path>) (p, type, context) -> new com.google.gson.JsonPrimitive(p.toString()))
                    .create().toJson(Map.of(
                    "sourceHash", sha256(originalSource), "beforeRun", first.id(), "afterRun", second.id(), "line", line,
                    "beforeCoveredInstructions", before.coveredInstructions(), "beforeMissedInstructions", before.missedInstructions(),
                    "afterCoveredInstructions", after.coveredInstructions(), "afterMissedInstructions", after.missedInstructions(),
                    "proof", after.proof(), "tests", second.tests().stream().map(t -> t.name() + ":" + t.status()).toList())) + "\n");
            Files.writeString(output.resolve("PriceTest.java.txt"), Files.readString(test));
            pass("CH-10", "uncovered deliveryFee → inspect → PriceTest → keyboard adds test → Save automatically runs Cherry → three passes → current covered source/class/process proof; Tests lane remains unattributed");
        } finally {
            Files.writeString(output.resolve("effects.tsv"), report); Files.writeString(output.resolve("console.txt"), edt(() -> frame.console().text()));
            edt(() -> { for (var d : List.copyOf(OverlayHost.dialogs(frame))) d.dispose(); frame.shutdown(); frame.dispose(); return null; });
        }
    }
    static void go(int line) throws Exception {
        action("goto"); dialog(); replace(Integer.toString(line)); key(KeyEvent.VK_ENTER, 0);
        await(() -> frame.editor().isFocusOwner() && !OverlayHost.active(frame), "Go to Line applied");
    }
}
