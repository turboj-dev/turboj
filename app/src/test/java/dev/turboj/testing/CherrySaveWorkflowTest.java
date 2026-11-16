package dev.turboj.testing;

import com.google.gson.Gson;
import dev.turboj.compiler.WorkspaceCompilationResult;
import dev.turboj.execution.OutputEvent;
import dev.turboj.refactorj.api.Model.WorkspaceSnapshot;
import dev.turboj.turbotest.TestEngine;
import dev.turboj.ui.EditorComponent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.swing.SwingUtilities;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
import static dev.turboj.testing.intelligence.LineIntelligence.*;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic transport controls; actual JUnit/JaCoCo save acceptance is CherryNativeSmoke. */
class CherrySaveWorkflowTest {
    @TempDir Path directory;

    @Test void rapidSavesCoalesceAndPreserveEveryConfiguredPhaseWithoutSelectors() throws Exception {
        try (Harness h = new Harness(true)) {
            edt(() -> { for (int i = 0; i < 20; i++) h.save(); });
            await(() -> h.completed() == 1 && !h.controller.queued());
            assertEquals(1,h.launches());
            assertEquals(Set.of("unit","integration","functional","ui"),
                    new HashSet<>(h.updates.getLast().tests().stream().map(TestResult::phase).toList()));
            assertEquals(List.of("--root",directory.toString(),"--execution","local","--no-open","--no-banner"),
                    Files.readAllLines(directory.resolve("args")));
        }
    }

    @Test void savesDuringRunQueueOneFollowupAndMarkOldResultsStale() throws Exception {
        try (Harness h = new Harness(true)) {
            Files.writeString(directory.resolve("hold"),"");
            edt(h::save);
            await(() -> h.processBusy() && exists(directory.resolve("launches")));
            edt(() -> { for (int i = 0; i < 10; i++) h.save(); });
            assertEquals(1,h.launches());
            Files.delete(directory.resolve("hold"));
            await(() -> h.completed() == 2 && !h.controller.queued());
            assertEquals(2,h.launches());
            Run old = h.updates.stream().filter(r -> r.state() == RunState.COMPLETED).findFirst().orElseThrow();
            assertTrue(old.tests().stream().allMatch(t -> t.freshness() == Freshness.STALE));
            assertTrue(h.updates.getLast().tests().stream().noneMatch(t -> t.freshness() == Freshness.STALE));
        }
    }

    @Test void nativeCompilationGateDefersCherryWithoutLosingTheSave() throws Exception {
        try (Harness h = new Harness(true)) {
            h.ready.set(false);
            edt(h::save);
            Thread.sleep(500);
            assertEquals(0,h.launches());
            h.ready.set(true);
            await(() -> h.completed() == 1);
        }
    }

    @Test void cancellationClearsTheQueuedFollowupAndStopsTheOwnedRun() throws Exception {
        try (Harness h = new Harness(true)) {
            Files.writeString(directory.resolve("hold"),"");
            edt(h::save);
            await(() -> h.processBusy() && exists(directory.resolve("launches")));
            edt(() -> { h.save(); h.controller.cancel(); });
            await(() -> !h.processBusy() && h.updates.stream().anyMatch(r -> r.state() == RunState.CANCELLED));
            Thread.sleep(500);
            assertEquals(1,h.launches());
            edt(() -> assertFalse(h.controller.queued()));
        }
    }

    @Test void plainProjectAndUntrustedProjectNeverLaunch() throws Exception {
        try (Harness h = new Harness(false)) {
            edt(h::save);
            await(() -> !h.controller.queued());
            assertEquals(0,h.launches());
            Files.writeString(directory.resolve("cherry-test.yaml"),"# configured\n");
            h.allowed.set(false);
            edt(h::save);
            await(() -> !h.controller.queued());
            assertEquals(0,h.launches());
            assertTrue(h.messages.stream().anyMatch(s -> s.contains("trust")));
        }
    }

    @Test void switchingOutsideTheProjectDropsQueuedExecution() throws Exception {
        try (Harness h = new Harness(true)) {
            h.ready.set(false);
            edt(h::save);
            Path other = directory.getParent().resolve("Other.java");
            edt(() -> { h.current.set(other); h.controller.changed(); h.ready.set(true); });
            await(() -> !h.controller.queued());
            assertEquals(0,h.launches());
        }
    }

    @Test void closeDiscardsPendingDetectionAndExecution() throws Exception {
        try (Harness h = new Harness(true)) {
            edt(() -> { h.save(); h.controller.close(); });
            Thread.sleep(500);
            assertEquals(0,h.launches());
        }
    }

    private final class Harness implements AutoCloseable {
        final AtomicBoolean ready = new AtomicBoolean(true), allowed = new AtomicBoolean(true);
        final AtomicReference<Path> current = new AtomicReference<>();
        final List<Run> updates = new CopyOnWriteArrayList<>();
        final List<String> messages = new CopyOnWriteArrayList<>();
        final TurboTestController tests = new TurboTestController(new NoNativeExecution());
        final Path executable;
        LineIntelligenceController controller;
        Harness(boolean configured) throws Exception {
            if (configured) Files.writeString(directory.resolve("cherry-test.yaml"),"# fixture\n");
            current.set(directory.resolve("Price.java"));
            Files.writeString(current.get(),"class Price {}\n");
            executable = directory.resolve("runner");
            StringBuilder script = new StringBuilder("#!/bin/sh\nset -eu\nprintf '%s\\n' \"$@\" > args\nprintf 'run\\n' >> launches\n");
            script.append(emit("run.started",0,Map.of("workspaceRoot",directory.toString(),"sourceBefore",Map.of(),
                    "scope",List.of("unit","integration","functional","ui"),"coverageEnabled",false)));
            script.append("while test -e hold; do sleep 0.05; done\n");
            int sequence = 1;
            for (String phase : List.of("unit","integration","functional","ui")) {
                script.append(emit("phase.completed",sequence++,Map.of("projectId","fixture","moduleId","module",
                        "packageId","suite","phaseId",phase,"runtimeId","synthetic","completeness","complete",
                        "tests",List.of(Map.of("id",phase,"name",phase,"status","passed","durationMs",1)))));
            }
            script.append(emit("run.completed",sequence,Map.of("sourceAfter",Map.of(),"outcome","passed","complete",true)));
            Files.writeString(executable,script);
            assertTrue(executable.toFile().setExecutable(true));
            edt(() -> {
                EditorComponent editor = new EditorComponent(); editor.setText("class Price {}\n");
                controller = new LineIntelligenceController(editor,current::get,tests,directory.resolve("cache"),updates::add,messages::add);
                controller.changed();
            });
            await(() -> !configured || controller.project() != null);
        }
        void save() { controller.saved(current.get(),executable.toString(),ready::get,allowed::get); }
        int completed() { return (int) updates.stream().filter(r -> r.state() == RunState.COMPLETED).count(); }
        boolean processBusy() { return tests.cherry().busy(); }
        int launches() throws Exception { return exists(directory.resolve("launches")) ? Files.readAllLines(directory.resolve("launches")).size() : 0; }
        @Override public void close() throws Exception { edt(controller::close); tests.close(); }
    }
    private static String emit(String type,int sequence,Map<String,Object> fields) {
        Map<String,Object> event = new LinkedHashMap<>(fields);
        event.put("schemaVersion",1); event.put("event",type); event.put("runId","fixture"); event.put("sequence",sequence);
        return "printf '%s\\n' '" + new Gson().toJson(event).replace("'","'\\''") + "' >> \"$CHERRY_TEST_IDE_EVENTS\"\n";
    }
    private static boolean exists(Path path) { return Files.isRegularFile(path); }
    private static void edt(Runnable action) throws Exception { SwingUtilities.invokeAndWait(action); }
    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            AtomicBoolean done = new AtomicBoolean(); edt(() -> done.set(condition.getAsBoolean()));
            if (done.get()) return;
            Thread.sleep(20);
        }
        fail("Timed out waiting for grouped workflow");
    }
    private static final class NoNativeExecution implements TurboTestController.Host {
        public boolean current(WorkspaceSnapshot snapshot) { return true; }
        public void status(String value) { fail("Unexpected native runner: " + value); }
        public void compilation(WorkspaceCompilationResult value) { fail("Unexpected native compilation"); }
        public void report(TestEngine.Report value) { fail("Unexpected native report"); }
        public void output(OutputEvent value) { fail("Unexpected native output"); }
    }
}
