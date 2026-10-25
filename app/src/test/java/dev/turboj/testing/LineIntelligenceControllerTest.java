package dev.turboj.testing;

import dev.turboj.compiler.WorkspaceCompilationResult;
import dev.turboj.execution.OutputEvent;
import dev.turboj.refactorj.api.Model.WorkspaceSnapshot;
import dev.turboj.testing.intelligence.GitBufferDiff;
import dev.turboj.turbotest.TestEngine;
import dev.turboj.ui.EditorComponent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;

import static dev.turboj.testing.intelligence.LineIntelligence.*;
import static org.junit.jupiter.api.Assertions.*;

class LineIntelligenceControllerTest {
    @TempDir Path directory;
    private static final String SOURCE = "class Price {\n  int cents = 42;\n}\n";

    @Test void aChangedOrUnrecordedSourceWithholdsHistoricalTestsAndCoverageButRetainsGit() {
        Path file = directory.resolve("Price.java");
        var evidence = run(file, SOURCE, List.of(test(file,2,Attribution.TEST_DECLARATION)), coverage());
        for (String editorText : List.of(SOURCE.replace("42","43"), "// inserted\n" + SOURCE)) {
            Snapshot snapshot = LineIntelligenceController.snapshot(file,editorText,7,new Object(),directory,git(),evidence);
            assertTrue(snapshot.line(2).added());
            assertTrue(snapshot.line(2).tests().isEmpty());
            assertEquals(CoverageState.UNKNOWN,snapshot.line(2).coverage().state());
            assertNotEquals(Availability.AVAILABLE,snapshot.line(2).coverage().availability());
            assertTrue(snapshot.message().contains("withheld"));
            assertEquals(List.of(new Deletion(1,"old\n")),snapshot.deletions());
        }
        Run unrecorded = new Run("run",directory,RunState.COMPLETED,evidence.tests(),evidence.coverage(),Map.of(),"Finished",null,3);
        assertTrue(LineIntelligenceController.snapshot(file,SOURCE,8,new Object(),directory,git(),unrecorded).line(2).tests().isEmpty());
    }

    @Test void sameSourceComposesIndependentGitTestAndCoverageEvidenceWithoutInventingFreshness() {
        Path file = directory.resolve("Price.java");
        var result = test(file,2,Attribution.TEST_DECLARATION);
        var evidence = run(file,SOURCE,List.of(result),coverage());
        Snapshot snapshot = LineIntelligenceController.snapshot(file,SOURCE,9,new Object(),directory,git(),evidence);
        Line line = snapshot.line(2);
        assertTrue(line.added());
        assertEquals(List.of(result),line.tests());
        assertEquals(CoverageState.PARTIAL,line.coverage().state());
        assertEquals(1,line.coverage().missedBranches());
        assertEquals(Freshness.UNKNOWN,line.coverage().freshness());
        assertEquals(TestStatus.UNKNOWN,line.testStatus(),"A historical pass without bytecode identity is not a fresh green result.");
        assertTrue(snapshot.gitActive()); assertTrue(snapshot.testingActive());
        assertEquals(9,snapshot.source().version()); assertEquals(sha256(SOURCE),snapshot.source().sourceHash());
    }

    @Test void runLevelCoverageAvailabilityDoesNotInventMeasurementsOnAddedLines() {
        Path file = directory.resolve("Price.java");
        Run run = new Run("run",directory,RunState.COMPLETED,List.of(),Map.of(),Map.of(file,sha256(SOURCE)),
                "Measured elsewhere",null,3,Availability.AVAILABLE);
        var changed = LineIntelligenceController.snapshot(file,SOURCE.replace("42","43"),4,new Object(),directory,git(),run);
        assertEquals(CoverageState.UNKNOWN,changed.line(2).coverage().state());
        assertEquals(Availability.UNKNOWN,changed.line(2).coverage().availability());
    }

    @Test void explicitStalenessSurvivesAnEqualSourceHash() {
        Path file = directory.resolve("Price.java");
        var test = test(file,2,Attribution.TEST_DECLARATION).withFreshness(Freshness.STALE);
        var evidence = run(file,SOURCE,List.of(test),coverage().withFreshness(Freshness.STALE));
        Line line = LineIntelligenceController.snapshot(file,SOURCE,3,new Object(),directory,git(),evidence).line(2);
        assertEquals(Freshness.STALE,line.testFreshness());
        assertEquals(TestStatus.UNKNOWN,line.testStatus());
        assertEquals(Freshness.STALE,line.coverage().freshness());
    }

    @Test void heuristicAssociationsOtherFilesAndOutOfRangeLinesNeverColorThisSource() {
        Path file = directory.resolve("Price.java");
        var evidence = run(file,SOURCE,List.of(test(file,2,Attribution.HEURISTIC),
                test(directory.resolve("Other.java"),2,Attribution.TEST_DECLARATION),
                test(file,50,Attribution.TEST_DECLARATION)),coverage());
        Snapshot snapshot = LineIntelligenceController.snapshot(file,SOURCE,3,new Object(),directory,git(),evidence);
        assertTrue(snapshot.lines().values().stream().allMatch(line -> line.tests().isEmpty()));
        assertFalse(snapshot.lines().containsKey(50));
    }

    @Test void sourceNormalizationCannotUpgradeRawCrLfEvidenceIntoCurrentLfEvidence() {
        Path file = directory.resolve("Price.java");
        var evidence = run(file,SOURCE.replace("\n","\r\n"),List.of(test(file,2,Attribution.TEST_DECLARATION)),coverage());
        var snapshot = LineIntelligenceController.snapshot(file,SOURCE,1,new Object(),directory,git(),evidence);
        assertTrue(snapshot.line(2).tests().isEmpty());
        assertEquals(CoverageState.UNKNOWN,snapshot.line(2).coverage().state());
    }

    @Test void aPlainFileKeepsOnlyGitAndDocumentIdentitySeparatesReopenedBuffers() {
        Path file = directory.resolve("Price.java");
        Object firstDocument = new Object(), reopened = new Object();
        var first = LineIntelligenceController.snapshot(file,SOURCE,1,firstDocument,null,git(),null);
        var edited = LineIntelligenceController.snapshot(file,SOURCE,2,firstDocument,null,git(),null);
        var second = LineIntelligenceController.snapshot(file,SOURCE,1,reopened,null,git(),null);
        assertFalse(first.testingActive()); assertTrue(first.gitActive());
        assertTrue(first.line(2).added()); assertTrue(first.line(2).tests().isEmpty());
        assertEquals(first.source().documentId(),edited.source().documentId());
        assertNotEquals(first.source().documentId(),second.source().documentId());
    }

    @Test void projectDetectionSelectsNearestConfigurationAndDoesNotCreateAnything() throws Exception {
        Path workspace = Files.createDirectories(directory.resolve("workspace"));
        Files.writeString(workspace.resolve("cherry-test.yaml"),"# parent\n");
        Path module = Files.createDirectories(workspace.resolve("module"));
        Files.writeString(module.resolve("cherry-test.toml"),"# nested\n");
        assertEquals(module,LineIntelligenceController.detect(module.resolve("src/main/java/Price.java")));
        assertEquals(workspace,LineIntelligenceController.detect(workspace.resolve("src/Price.java")));
        assertNull(LineIntelligenceController.detect(directory.resolve("other/Price.java")));
        assertFalse(Files.exists(module.resolve("src")));
    }

    @Test void aQueuedOldDocumentCannotPublishAfterSwitchingFilesOrClosing() throws Exception {
        Path first = directory.resolve("First.java"), second = directory.resolve("Second.java");
        Files.writeString(first,SOURCE); Files.writeString(second,SOURCE);
        var currentFile = new AtomicReference<>(first);
        var editor = new AtomicReference<EditorComponent>();
        var controller = new AtomicReference<LineIntelligenceController>();
        try (var tests = new TurboTestController(new NoExecutionHost())) {
            edt(() -> {
                editor.set(new EditorComponent()); editor.get().setText(SOURCE);
                controller.set(new LineIntelligenceController(editor.get(),currentFile::get,tests,
                        directory.resolve("cache"),ignored -> fail("No report should exist for a plain file"),ignored -> {}));
                controller.get().changed();
                currentFile.set(second); editor.get().setText("class Second {}\n");
                controller.get().changed();
            });
            try {
                await(() -> editor.get().lineIntelligence() != null);
                edt(() -> {
                    assertEquals(second,editor.get().lineIntelligence().source().file());
                    assertEquals(sha256(editor.get().getText()),editor.get().lineIntelligence().source().sourceHash());
                    assertFalse(editor.get().lineIntelligence().testingActive());
                    controller.get().changed(); controller.get().close();
                    editor.get().setLineIntelligence(null);
                });
                Thread.sleep(250);
                edt(() -> assertNull(editor.get().lineIntelligence(),"A closed controller must not publish a pending revision."));
            } finally { controller.get().close(); }
        }
    }

    @Test void switchingProjectsImmediatelyInvalidatesThePreviousRunTarget() throws Exception {
        Path firstRoot = Files.createDirectories(directory.resolve("first-project"));
        Path secondRoot = Files.createDirectories(directory.resolve("second-project"));
        Files.writeString(firstRoot.resolve("cherry-test.yaml"),"# first\n");
        Files.writeString(secondRoot.resolve("cherry-test.yaml"),"# second\n");
        Path firstFile = firstRoot.resolve("First.java"), secondFile = secondRoot.resolve("Second.java");
        Files.writeString(firstFile,SOURCE); Files.writeString(secondFile,SOURCE);
        var file = new AtomicReference<>(firstFile);
        var editor = new AtomicReference<EditorComponent>();
        var controller = new AtomicReference<LineIntelligenceController>();
        try (var tests = new TurboTestController(new NoExecutionHost())) {
            edt(() -> {
                editor.set(new EditorComponent()); editor.get().setText(SOURCE);
                controller.set(new LineIntelligenceController(editor.get(),file::get,tests,
                        directory.resolve("cache"),ignored -> {},ignored -> {}));
                controller.get().changed();
            });
            try {
                await(() -> firstRoot.equals(controller.get().project()));
                edt(() -> {
                    file.set(secondFile); editor.get().setText("class Second {}\n");
                    controller.get().changed();
                    controller.get().start(directory.resolve("must-not-be-launched").toString(),List.of());
                    assertFalse(tests.cherry().busy(),"Run must not launch the previous project's suite during asynchronous detection.");
                });
                await(() -> secondRoot.equals(controller.get().project()));
            } finally { controller.get().close(); }
        }
    }

    @Test void historyReloadCannotResurrectAReportedSuccessRejectedByProcessCompletion() throws Exception {
        Path root = Files.createDirectories(directory.resolve("history-project"));
        Files.writeString(root.resolve("cherry-test.yaml"),"# historical fixture\n");
        Path file = Files.writeString(root.resolve("Price.java"),SOURCE);
        Path cache = directory.resolve("cache");
        Path history = Files.createDirectories(cache.resolve(sha256(root.toString()).substring(0,24)));
        Path events = history.resolve("run-fixture.jsonl");
        var gson = new com.google.gson.Gson();
        String start = gson.toJson(Map.of("schemaVersion",1,"event","run.started","runId","run","sequence",0,
                "workspaceRoot",root.toString(),"sourceBefore",Map.of("Price.java",sha256(SOURCE))));
        String end = gson.toJson(Map.of("schemaVersion",1,"event","run.completed","runId","run","sequence",1,
                "sourceAfter",Map.of("Price.java",sha256(SOURCE)),"outcome","passed","complete",true));
        Files.writeString(events,start + "\n" + end + "\n");
        CherryExecutionReceipt.write(events,new Run("run",root,RunState.INCOMPLETE,List.of(),Map.of(),Map.of(),
                "Cherry CLI exited 9 after reporting success",null,1));
        var controller = new AtomicReference<LineIntelligenceController>();
        var restored = new AtomicReference<Run>();
        try (var tests = new TurboTestController(new NoExecutionHost())) {
            edt(() -> {
                var editor = new EditorComponent(); editor.setText(SOURCE);
                controller.set(new LineIntelligenceController(editor,() -> file,tests,cache,restored::set,ignored -> {}));
                controller.get().changed();
            });
            try {
                await(() -> restored.get() != null);
                assertEquals(RunState.INCOMPLETE,restored.get().state());
                assertTrue(restored.get().message().contains("exited 9"));
                assertEquals(sha256(SOURCE),restored.get().sourceHashes().get(file));
            } finally { controller.get().close(); }
        }
    }

    private GitBufferDiff.Result git() {
        return new GitBufferDiff.Result(directory,"HEAD","fixture-blob",Map.of(2,true),
                List.of(new GitBufferDiff.Deletion(1,"old\n")),"");
    }
    private Run run(Path file,String source,List<TestResult> tests,Coverage coverage) {
        return new Run("run",directory,RunState.COMPLETED,tests,Map.of(file,Map.of(2,coverage,99,coverage)),
                Map.of(file,sha256(source)),"Finished",null,3,Availability.AVAILABLE);
    }
    private TestResult test(Path file,int line,Attribution attribution) {
        return new TestResult("test-" + file + line + attribution,"run","suite","discount","project","module","package",
                "unit","maven","first",TestStatus.PASSED,Freshness.UNKNOWN,1,"",
                List.of(new Association(file,line,attribution,"fixture")));
    }
    private static Coverage coverage() {
        return new Coverage(CoverageState.PARTIAL,Freshness.UNKNOWN,Availability.AVAILABLE,Precision.INSTRUCTIONS_AND_BRANCHES,
                2,0,1,1,"JaCoCo","unit","run","","","","Fixture counters, compiled identity unavailable");
    }
    private static void edt(Runnable action) throws Exception { SwingUtilities.invokeAndWait(action); }
    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            var satisfied = new java.util.concurrent.atomic.AtomicBoolean();
            edt(() -> satisfied.set(condition.getAsBoolean()));
            if (satisfied.get()) return;
            Thread.sleep(20);
        }
        fail("Timed out waiting for controller publication");
    }
    private static final class NoExecutionHost implements TurboTestController.Host {
        public boolean current(WorkspaceSnapshot snapshot) { return true; }
        public void status(String value) { fail("Unexpected test execution: " + value); }
        public void compilation(WorkspaceCompilationResult value) { fail("Unexpected compilation"); }
        public void report(TestEngine.Report value) { fail("Unexpected test result"); }
        public void output(OutputEvent value) { fail("Unexpected test output"); }
    }
}
