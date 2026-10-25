package dev.turboj.testing;

import com.google.gson.Gson;
import dev.turboj.testing.intelligence.LineIntelligence.Run;
import dev.turboj.testing.intelligence.LineIntelligence.RunState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

/** Transport lifecycle controls using owned synthetic CLI scripts, not Cherry execution acceptance. */
class CherryTestProcessLifecycleTest {
    @TempDir Path directory;
    private final Gson gson = new Gson();

    @BeforeEach void supportedProcessFixture() {
        org.junit.jupiter.api.Assumptions.assumeTrue(Files.isExecutable(Path.of("/bin/sh")));
    }

    @Test void cancellationTerminatesOwnedChildAndRetainsExplicitPartialResults() throws Exception {
        Path root = Files.createDirectories(directory.resolve("cancel"));
        Path childFile = root.resolve("child.pid");
        Path executable = script(root,"slow runner",start(root,"cancel") + phase("cancel") + sleep(childFile));
        var updates = new CopyOnWriteArrayList<Run>();
        try (var process = new CherryTestProcess()) {
            edt(() -> process.run(request(root,executable),value -> {
                assertTrue(SwingUtilities.isEventDispatchThread()); updates.add(value);
            },ignored -> {}));
            await(() -> Files.isRegularFile(childFile) && updates.stream().anyMatch(value -> !value.tests().isEmpty()));
            long child = Long.parseLong(Files.readString(childFile).strip());
            edt(process::cancel);
            await(() -> updates.stream().anyMatch(value -> value.state() == RunState.CANCELLED));
            Run result = updates.getLast();
            assertEquals(RunState.CANCELLED,result.state());
            assertEquals(1,result.tests().size());
            assertTrue(result.message().contains("partial"));
            await(() -> ProcessHandle.of(child).map(handle -> !handle.isAlive()).orElse(true));
            edt(() -> assertFalse(process.busy()));
        }
    }

    @Test void detachedProjectCannotPublishOverANewerRun() throws Exception {
        Path oldRoot = Files.createDirectories(directory.resolve("old")), nextRoot = Files.createDirectories(directory.resolve("next"));
        Path childFile = oldRoot.resolve("child.pid");
        Path oldScript = script(oldRoot,"old runner",start(oldRoot,"old") + sleep(childFile) + completed("old"));
        Path nextScript = script(nextRoot,"next runner",start(nextRoot,"next") + completed("next"));
        var oldUpdates = new CopyOnWriteArrayList<Run>();
        var nextUpdates = new CopyOnWriteArrayList<Run>();
        try (var process = new CherryTestProcess()) {
            edt(() -> process.run(request(oldRoot,oldScript),oldUpdates::add,ignored -> {}));
            await(() -> Files.isRegularFile(childFile));
            var afterDetach = new java.util.concurrent.atomic.AtomicInteger();
            edt(() -> {
                process.detach(); afterDetach.set(oldUpdates.size());
                process.run(request(nextRoot,nextScript),nextUpdates::add,ignored -> {});
            });
            await(() -> nextUpdates.stream().anyMatch(value -> value.state() == RunState.COMPLETED));
            edt(() -> {
                assertEquals(afterDetach.get(),oldUpdates.size(),"Queued old-project callbacks must be invalidated.");
                assertEquals("next",process.last().id());
                assertEquals(nextRoot,process.last().root());
                assertFalse(process.busy());
            });
            long child = Long.parseLong(Files.readString(childFile).strip());
            await(() -> ProcessHandle.of(child).map(handle -> !handle.isAlive()).orElse(true));
        }
    }

    @Test void nonzeroExitWithoutAProtocolResultIsIncompleteAndCreatesNoPassingTest() throws Exception {
        Path root = Files.createDirectories(directory.resolve("failure"));
        Path executable = script(root,"failure runner","printf '%s\\n' 'synthetic execution failed'\nexit 7\n");
        var updates = new CopyOnWriteArrayList<Run>();
        var output = new CopyOnWriteArrayList<String>();
        try (var process = new CherryTestProcess()) {
            edt(() -> process.run(request(root,executable),updates::add,output::add));
            await(() -> updates.stream().anyMatch(value -> value.state() == RunState.INCOMPLETE));
            Run result = updates.getLast();
            assertEquals(RunState.INCOMPLETE,result.state()); assertTrue(result.tests().isEmpty());
            assertTrue(result.message().contains("exited 7"));
            assertTrue(String.join("",output).contains("synthetic execution failed"));
        }
    }

    @Test void aCompletedCliCannotLeaveAnObservedChildServerBehind() throws Exception {
        Path root = Files.createDirectories(directory.resolve("orphan"));
        Path childFile = root.resolve("child.pid");
        String child = "sleep 60 &\nprintf '%s\\n' \"$!\" > " + quote(childFile.toString()) + "\nsleep 0.4\n";
        Path executable = script(root,"parent exits",start(root,"orphan") + child + completed("orphan"));
        var updates = new CopyOnWriteArrayList<Run>();
        ProcessHandle ownedChild = null;
        try (var process = new CherryTestProcess()) {
            edt(() -> process.run(request(root,executable),updates::add,ignored -> {}));
            await(() -> Files.isRegularFile(childFile));
            long pid = Long.parseLong(Files.readString(childFile).strip());
            ownedChild = ProcessHandle.of(pid).orElseThrow();
            await(() -> updates.stream().anyMatch(value -> value.state() == RunState.COMPLETED) && !process.busy());
            ProcessHandle observed = ownedChild;
            await(() -> !observed.isAlive());
        } finally {
            // The regression must clean up this exact owned fixture even when the production cleanup fails.
            if (ownedChild != null && ownedChild.isAlive()) ownedChild.destroyForcibly();
        }
    }

    @Test void aSuccessEventFollowedByNonzeroExitCannotPublishCompleteSuccess() throws Exception {
        Path root = Files.createDirectories(directory.resolve("contradictory-exit"));
        Path executable = script(root,"late failure",start(root,"late") + phase("late")
                + completed("late") + "sleep 0.15\nexit 9\n");
        var updates = new CopyOnWriteArrayList<Run>();
        try (var process = new CherryTestProcess()) {
            edt(() -> process.run(request(root,executable),updates::add,ignored -> {}));
            await(() -> !process.busy() && updates.stream().anyMatch(value -> value.state() == RunState.INCOMPLETE));
            assertTrue(updates.stream().noneMatch(value -> value.state() == RunState.COMPLETED));
            assertEquals(1,updates.getLast().tests().size(),"Retain actual reported test evidence.");
            assertTrue(updates.getLast().message().contains("exited 9"));
            var reader = new dev.turboj.testing.intelligence.CherryEvents(root);
            for (String event : Files.readAllLines(root.resolve("events.jsonl"))) reader.accept(event);
            assertEquals(RunState.COMPLETED,reader.snapshot().state(),"The fixture really emitted a contradictory success.");
            assertEquals(RunState.INCOMPLETE,CherryExecutionReceipt.restore(root.resolve("events.jsonl"),reader.snapshot()).state(),
                    "Reloading history must preserve the verified process failure.");
        }
    }

    private CherryTestProcess.Request request(Path root,Path script) {
        return new CherryTestProcess.Request(root,script.toString(),root.resolve("events.jsonl"),List.of("fixture"));
    }
    private Path script(Path root,String name,String body) throws Exception {
        Path file = root.resolve(name); Files.writeString(file,"#!/bin/sh\nset -eu\n" + body);
        assertTrue(file.toFile().setExecutable(true)); return file;
    }
    private String start(Path root,String id) {
        var event = event("run.started",id,0);
        event.put("workspaceRoot",root.toString()); event.put("sourceBefore",Map.of());
        event.put("scope",List.of("unit")); event.put("coverageEnabled",false);
        return emit(event);
    }
    private String phase(String id) {
        var event = event("phase.completed",id,1);
        event.put("projectId","fixture"); event.put("moduleId","module"); event.put("packageId","unit");
        event.put("phaseId","unit"); event.put("runtimeId","synthetic"); event.put("completeness","complete");
        event.put("tests",List.of(Map.of("id","one","name","synthetic passing result","status","passed","durationMs",1)));
        return emit(event);
    }
    private String completed(String id) {
        var event = event("run.completed",id,2);
        event.put("sourceAfter",Map.of()); event.put("outcome","passed"); event.put("complete",true);
        return emit(event);
    }
    private static Map<String,Object> event(String type,String id,long sequence) {
        var value = new LinkedHashMap<String,Object>(); value.put("schemaVersion",1); value.put("event",type);
        value.put("runId",id); value.put("sequence",sequence); return value;
    }
    private String emit(Map<String,Object> event) { return "printf '%s\\n' " + quote(gson.toJson(event)) + " >> \"$CHERRY_TEST_IDE_EVENTS\"\n"; }
    private static String sleep(Path pid) { return "sleep 60 &\nchild=$!\nprintf '%s\\n' \"$child\" > " + quote(pid.toString()) + "\nwait \"$child\"\n"; }
    private static String quote(String value) { return "'" + value.replace("'","'\\''") + "'"; }
    private static void edt(Runnable action) throws Exception { SwingUtilities.invokeAndWait(action); }
    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) { if (condition.getAsBoolean()) return; Thread.sleep(20); }
        fail("Timed out waiting for owned CLI lifecycle transition");
    }
}
