package dev.turboj.refactoring;

import dev.turboj.config.SafeFiles;
import dev.turboj.refactorj.api.*;
import dev.turboj.refactorj.api.Model.*;
import dev.turboj.refactorj.transactions.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.swing.SwingUtilities;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeWorkspaceCommitterTest {
    @TempDir Path root;
    Path opened, closed;
    @BeforeEach void setup() throws Exception {
        root = root.toRealPath(); opened = root.resolve("Open.java"); closed = root.resolve("Closed.java");
        Files.writeString(opened, "class Open { int n = 1; }"); Files.writeString(closed, "class Closed { int n = 1; }");
    }

    @Test void publishesUnsavedBuffersAndClosedDiskAsOneUndoableOperation() throws Exception {
        FakeHost host = new FakeHost(); Plan plan = plan(host, List.of());
        try (var committer = new NativeWorkspaceCommitter(host)) {
            var result = await(edt(() -> committer.apply(plan, host.capture())));
            assertEquals("", result.warning());
            assertTrue(host.capture().buffers().get(opened).text().contains("= 2"));
            assertTrue(Files.readString(opened).contains("= 1"), "Unsaved buffer edits must not be autosaved");
            assertTrue(Files.readString(closed).contains("= 2"));
            await(edt(committer::undo));
            assertTrue(host.capture().buffers().get(opened).text().contains("= 1"));
            assertTrue(Files.readString(closed).contains("= 1"));
            await(edt(committer::redo));
            assertTrue(Files.readString(closed).contains("= 2"));
            assertEquals(3, host.publications);
        }
    }

    @Test void rollsDiskBackWhenTypingInvalidatesPublication() throws Exception {
        FakeHost host = new FakeHost(); Plan plan = plan(host, List.of());
        try (var committer = new NativeWorkspaceCommitter(host)) {
            host.invalidateOnCapture = 3;
            var pending = edt(() -> committer.apply(plan, host.capture()));
            assertThrows(ExecutionException.class, () -> await(pending));
            assertTrue(Files.readString(closed).contains("= 1"));
            assertEquals(0, host.publications);
            assertTrue(host.capture().buffers().get(opened).text().endsWith("// typed"));
        }
    }

    @Test void externalDiskChangeInvalidatesPreparedPlan() throws Exception {
        FakeHost host = new FakeHost(); Plan plan = plan(host, List.of());
        Files.writeString(closed, "class Closed { int n = 9; }");
        try (var committer = new NativeWorkspaceCommitter(host)) {
            var pending = edt(() -> committer.apply(plan, host.capture()));
            assertThrows(ExecutionException.class, () -> await(pending));
            assertTrue(Files.readString(closed).contains("= 9"));
            assertEquals(0, host.publications);
        }
    }

    @Test void movesSavedImageWhilePreservingDirtyBuffer() throws Exception {
        FakeHost host = new FakeHost(); host.text = host.text + " // unsaved";
        Path destination = root.resolve("Moved.java");
        Plan plan = plan(host, List.of(new FileOperation(FileOperationKind.MOVE, opened, destination, null)));
        try (var committer = new NativeWorkspaceCommitter(host)) {
            await(edt(() -> committer.apply(plan, host.capture())));
            assertFalse(Files.exists(opened)); assertFalse(Files.readString(destination).contains("unsaved"));
            assertTrue(host.capture().buffers().get(destination).text().contains("unsaved"));
            await(edt(committer::undo));
            assertTrue(Files.exists(opened)); assertFalse(Files.exists(destination));
            assertTrue(host.capture().buffers().get(opened).text().contains("unsaved"));
        }
    }

    @Test void explicitlyRecoversCrashBetweenDiskCommitAndBufferPublication() throws Exception {
        var disk = DiskWorkspace.open(root, List.of(root), List.of());
        WorkspaceSnapshot before = disk.snapshot();
        SourceSnapshot old = before.source(closed);
        String id = UUID.randomUUID().toString();
        disk.commit(before, new ChangeSet(List.of(new ChangeSet.Transition(closed, old,
                new SourceSnapshot(closed, old.text().replace("= 1", "= 2"), old.version(), false)))),
                new WorkspaceStore.Metadata(id, id, WorkspaceStore.Direction.APPLY), Cancellation.NONE);
        Path marker = root.resolve(".refactorj/native-transactions/" + id + ".json");
        Files.createDirectories(marker.getParent());
        Files.writeString(marker, new com.google.gson.Gson().toJson(Map.of("schema", 1, "root", root.toString(), "historyId", id, "beforeApplied", false)));
        NativeWorkspaceCommitter.recover(root);
        assertTrue(Files.readString(closed).contains("= 1")); assertFalse(Files.exists(marker));
    }

    @Test void junitConfigurationAndTestCreationShareDiskUndoRedo() throws Exception {
        Path pom = root.resolve("pom.xml"), test = root.resolve("src/test/java/ExampleTest.java");
        String beforePom = "<project/>"; String afterPom = "<project><dependencies/></project>";
        Files.writeString(pom, beforePom);
        FakeHost host = new FakeHost();
        var sources = new ArrayList<>(plan(host, List.of()).before().sources());
        sources.add(new SourceSnapshot(pom, beforePom, 1, false));
        var before = new WorkspaceSnapshot(root, 1, sources, List.of(root), List.of());
        var change = new Plan("junit", Operation.CREATE_CLASS, before, List.of(),
                List.of(new FileEdits(pom, List.of(new TextEdit(0, beforePom.length(), afterPom)))),
                List.of(new FileOperation(FileOperationKind.CREATE, test, null, "class ExampleTest {}\n")),
                List.of(), ValidationStatus.VALID, "Configure JUnit and create test");
        try (var committer = new NativeWorkspaceCommitter(host)) {
            await(edt(() -> committer.apply(change, host.capture())));
            assertEquals(afterPom, Files.readString(pom)); assertTrue(Files.exists(test));
            await(edt(committer::undo));
            assertEquals(beforePom, Files.readString(pom)); assertFalse(Files.exists(test));
            await(edt(committer::redo));
            assertEquals(afterPom, Files.readString(pom)); assertTrue(Files.exists(test));
        }
    }

    private Plan plan(FakeHost host, List<FileOperation> operations) throws Exception {
        String disk = Files.readString(closed);
        var before = new WorkspaceSnapshot(root, 1, List.of(new SourceSnapshot(opened, host.text, 1, true),
                new SourceSnapshot(closed, disk, 1, false)), List.of(root), List.of());
        int openOffset = host.text.indexOf("1"), closedOffset = disk.indexOf("1");
        return new Plan("test", Operation.RENAME, before, List.of(),
                List.of(new FileEdits(opened, List.of(new TextEdit(openOffset, openOffset + 1, "2"))),
                        new FileEdits(closed, List.of(new TextEdit(closedOffset, closedOffset + 1, "2")))),
                operations, List.of(), ValidationStatus.VALID, "Test native transaction");
    }

    private final class FakeHost implements NativeWorkspaceCommitter.Host {
        String text = "class Open { int n = 1; }";
        Path path = opened;
        long revision = 1;
        final Object identity = new Object();
        final String fingerprint;
        int captures, invalidateOnCapture = -1, publications;
        FakeHost() throws Exception { fingerprint = HexFormat.of().formatHex(SafeFiles.fingerprint(opened)); }
        public NativeWorkspaceCommitter.Capture capture() {
            if (++captures == invalidateOnCapture) { revision++; text += " // typed"; }
            return new NativeWorkspaceCommitter.Capture(revision, Map.of(path,
                    new NativeWorkspaceCommitter.BufferSnapshot(path, text, revision, identity, fingerprint)));
        }
        public boolean historyReady(Object token, NativeWorkspaceCommitter.Direction direction) { return token != null; }
        public NativeWorkspaceCommitter.Publication publish(NativeWorkspaceCommitter.Capture expected, Plan plan,
                NativeWorkspaceCommitter.Direction direction, Object token) {
            assertTrue(SwingUtilities.isEventDispatchThread()); publications++;
            if (direction == NativeWorkspaceCommitter.Direction.UNDO) {
                path = opened; text = plan.before().source(opened).text();
            } else {
                text = TextEdit.apply(plan.before().source(opened).text(), plan.edits().stream()
                        .filter(edit -> edit.file().equals(opened)).flatMap(edit -> edit.edits().stream()).toList());
                path = plan.fileOperations().stream().filter(op -> op.kind() == FileOperationKind.MOVE).map(FileOperation::destination).findFirst().orElse(opened);
            }
            revision++;
            return new NativeWorkspaceCommitter.Publication(capture(), token == null ? new Object() : token);
        }
    }
    private static <T> T edt(Callable<T> operation) throws Exception {
        FutureTask<T> task = new FutureTask<>(operation); SwingUtilities.invokeAndWait(task); return task.get();
    }
    private static <T> T await(CompletableFuture<T> result) throws Exception { return result.get(20, TimeUnit.SECONDS); }
}
