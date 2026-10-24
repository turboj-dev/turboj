package dev.turboj.refactorj.transactions;

import static org.junit.jupiter.api.Assertions.*;
import dev.turboj.refactorj.api.*;
import dev.turboj.refactorj.api.Model.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;

class WorkspaceTransactionsTest {
    @TempDir
    Path root;

    @BeforeEach
    void canonicalRoot() throws IOException {
        root = root.toRealPath();
    }

    private Path file(String name) {
        return root.resolve(name).toAbsolutePath();
    }

    private WorkspaceSnapshot snapshot(Map<String, String> sources, boolean open) {
        return new WorkspaceSnapshot(root.toAbsolutePath(), 3, sources.entrySet().stream()
                .map(e -> new SourceSnapshot(file(e.getKey()), e.getValue(), 7, open)).toList(),
                List.of(root.toAbsolutePath()), List.of());
    }

    private Plan plan(WorkspaceSnapshot source, List<FileEdits> edits,
            List<FileOperation> operations) {
        return new Plan("preview-only", Operation.RENAME, source, List.of(), edits, operations, List
                .of(), ValidationStatus.VALID, "Test rename");
    }

    private Plan rename(WorkspaceSnapshot source, String name, String from, String to) {
        String text = source.source(file(name)).text();
        int offset = text.indexOf(from);
        return plan(source, List.of(new FileEdits(file(name), List.of(new TextEdit(offset, offset
                + from.length(), to)))), List.of());
    }

    @Test
    void previewIsPureAndOpenBuffersRemainInMemoryWithOneLogicalUndo() {
        var original = snapshot(Map.of("A.java", "class A { int old; }", "B.java", "class B {}"),
                true);
        var store = new InMemoryWorkspace(original);
        var tx = new WorkspaceTransactions(store);
        var plan = rename(original, "A.java", "old", "newName");
        assertEquals(1, tx.preview(plan).transitions().size());
        assertEquals(original, store.snapshot());
        var applied = tx.apply(plan, Cancellation.NONE);
        assertTrue(applied.snapshot().source(file("A.java")).open());
        assertFalse(Files.exists(file("A.java")));
        assertEquals("class A { int newName; }", applied.snapshot().source(file("A.java")).text());
        var undone = tx.undo(applied.transactionId(), Cancellation.NONE);
        assertEquals(original.source(file("A.java")).text(), undone.snapshot()
                .source(file("A.java")).text());
        assertTrue(undone.snapshot().source(file("A.java")).version() > applied.snapshot()
                .source(file("A.java")).version());
        assertEquals(applied.snapshot().source(file("A.java")).text(), tx.redo(applied
                .transactionId(), Cancellation.NONE).snapshot().source(file("A.java")).text());
    }

    @Test
    void multiFileMoveCreateDeleteEditSwapUndoAndRedo() {
        var original = snapshot(Map.of("A.java", "class A {}", "B.java", "class B {}",
                "Delete.java", "class Delete {}"), true);
        var store = new InMemoryWorkspace(original);
        var tx = new WorkspaceTransactions(store);
        var plan = plan(original, List.of(new FileEdits(file("A.java"), List.of(new TextEdit(6, 7,
                "Moved")))), List.of(new FileOperation(FileOperationKind.MOVE, file("A.java"),
                file("B.java"), null), new FileOperation(FileOperationKind.MOVE, file("B.java"),
                file("A.java"), null), new FileOperation(FileOperationKind.DELETE,
                file("Delete.java"), null, null), new FileOperation(FileOperationKind.CREATE,
                file("nested/New.java"), null, "class New {}")));
        var applied = tx.apply(plan, Cancellation.NONE);
        assertEquals("class Moved {}", applied.snapshot().source(file("B.java")).text());
        assertEquals("class B {}", applied.snapshot().source(file("A.java")).text());
        assertThrows(IllegalArgumentException.class, () -> applied.snapshot()
                .source(file("Delete.java")));
        var undone = tx.undo(applied.transactionId(), Cancellation.NONE).snapshot();
        assertEquals(original.sources().stream().map(SourceSnapshot::text).toList(), undone
                .sources().stream().map(SourceSnapshot::text).toList());
        assertEquals(applied.snapshot().sources().stream().map(SourceSnapshot::text).toList(), tx
                .redo(applied.transactionId(), Cancellation.NONE).snapshot().sources().stream()
                .map(SourceSnapshot::text).toList());
    }

    @Test
    void staleVersionEvenWithSameTextAndConflictedPlansAreRejected() {
        var initial = snapshot(Map.of("A.java", "class A {}"), true);
        var store = new InMemoryWorkspace(initial);
        var tx = new WorkspaceTransactions(store);
        var pending = rename(initial, "A.java", "A", "Next");
        store.update(file("A.java"), "class A {}", true);
        assertEquals("STALE_WORKSPACE", assertThrows(TransactionException.class, () -> tx
                .apply(pending, Cancellation.NONE)).code());
        var rejected = new Plan("x", Operation.RENAME, store.snapshot(), List.of(), List.of(), List
                .of(), List.of(), ValidationStatus.CONFLICTED, "Rejected");
        assertThrows(TransactionException.class, () -> tx.apply(rejected, Cancellation.NONE));
    }

    @Test
    void invalidOverlappingUnicodeEditsAndOutsideRootNeverMutate() {
        var initial = snapshot(Map.of("A.java", "class A { String text = \"😀\"; }"), true);
        var store = new InMemoryWorkspace(initial);
        var tx = new WorkspaceTransactions(store);
        int emoji = initial.source(file("A.java")).text().indexOf("😀");
        var split = plan(initial, List.of(new FileEdits(file("A.java"), List.of(new TextEdit(emoji
                + 1, emoji + 1, "x")))), List.of());
        assertEquals("UNICODE_BOUNDARY", assertThrows(TransactionException.class, () -> tx
                .apply(split, Cancellation.NONE)).code());
        var overlap = plan(initial, List.of(new FileEdits(file("A.java"), List.of(new TextEdit(0, 5,
                ""), new TextEdit(2, 6, "")))), List.of());
        assertThrows(IllegalArgumentException.class, () -> tx.apply(overlap, Cancellation.NONE));
        var escape = plan(initial, List.of(), List.of(new FileOperation(FileOperationKind.CREATE,
                root.getParent().resolve("Escaped.java"), null, "class Escaped {}")));
        assertEquals("OUTSIDE_ROOT", assertThrows(TransactionException.class, () -> tx.apply(escape,
                Cancellation.NONE)).code());
        assertEquals(initial, store.snapshot());
    }

    @Test
    void collisionsAndMultipleOperationsOnOneIdentityAreRejected() {
        var initial = snapshot(Map.of("A.java", "class A {}", "B.java", "class B {}"), true);
        var tx = new WorkspaceTransactions(new InMemoryWorkspace(initial));
        var move = new FileOperation(FileOperationKind.MOVE, file("A.java"), file("B.java"), null);
        assertThrows(TransactionException.class, () -> tx.preview(plan(initial, List.of(), List
                .of(move))));
        var delete = new FileOperation(FileOperationKind.DELETE, file("A.java"), null, null);
        assertThrows(TransactionException.class, () -> tx.preview(plan(initial, List.of(), List
                .of(move, delete))));
    }

    @Test
    void undoPreservesUnrelatedTypingButRejectsChangedTargetsAndWrongOrder() {
        var store = new InMemoryWorkspace(snapshot(Map.of("A.java", "class A {}", "B.java",
                "class B {}"), true));
        var tx = new WorkspaceTransactions(store);
        var first = tx.apply(rename(store.snapshot(), "A.java", "A", "Longer"), Cancellation.NONE);
        var second = tx.apply(rename(store.snapshot(), "A.java", "Longer", "Latest"),
                Cancellation.NONE);
        assertEquals("HISTORY_ORDER", assertThrows(TransactionException.class, () -> tx.undo(first
                .transactionId(), Cancellation.NONE)).code());
        tx.undo(second.transactionId(), Cancellation.NONE);
        store.update(file("B.java"), "class B { int typed; }", true);
        tx.undo(first.transactionId(), Cancellation.NONE);
        assertTrue(store.snapshot().source(file("B.java")).text().contains("typed"));
        tx.redo(first.transactionId(), Cancellation.NONE);
        store.update(file("A.java"), "class UserTyped {}", true);
        assertEquals("HISTORY_CONFLICT", assertThrows(TransactionException.class, () -> tx
                .undo(first.transactionId(), Cancellation.NONE)).code());
    }

    @Test
    void cancellationNeverCommitsAndNewApplyInvalidatesRedo() {
        var store = new InMemoryWorkspace(snapshot(Map.of("A.java", "class A {}"), true));
        var tx = new WorkspaceTransactions(store);
        var before = store.snapshot();
        assertThrows(CancellationException.class, () -> tx.apply(rename(before, "A.java", "A", "B"),
                () -> true));
        assertEquals(before, store.snapshot());
        var first = tx.apply(rename(before, "A.java", "A", "B"), Cancellation.NONE);
        tx.undo(first.transactionId(), Cancellation.NONE);
        tx.apply(rename(store.snapshot(), "A.java", "A", "C"), Cancellation.NONE);
        assertThrows(TransactionException.class, () -> tx.redo(first.transactionId(),
                Cancellation.NONE));
    }

    @Test
    void seededSparseEditsApplyAndCompletelyUndo() {
        Random random = new Random(391_204);

        for (int trial = 0; trial < 300; trial++) {
            String source = "class A { int value = 123456789; String s = \"λ😀\"; }\n";
            var initial = snapshot(Map.of("A.java", source), true);
            var store = new InMemoryWorkspace(initial);
            var tx = new WorkspaceTransactions(store);
            int position = 0;
            var edits = new ArrayList<TextEdit> ();

            while (position < 30) {
                position += random.nextInt(4) + 1;
                int end = Math.min(30, position + random.nextInt(3));

                if (position > 30)
                    break;
                edits.add(new TextEdit(position, end, random.nextBoolean() ? " " : "xy"));
                position = end;
            }
            var p = plan(initial, List.of(new FileEdits(file("A.java"), edits)), List.of());
            var applied = tx.apply(p, Cancellation.NONE);
            assertEquals(TextEdit.apply(source, edits), applied.snapshot().source(file("A.java"))
                    .text());
            if (!applied.changes().transitions().isEmpty())
                assertEquals(source, tx.undo(applied.transactionId(), Cancellation.NONE).snapshot()
                        .source(file("A.java")).text());
        }
    }

    private DiskWorkspace disk() throws IOException {
        Files.writeString(file("A.java"), "class A {}\n");
        Files.writeString(file("B.java"), "class B {}\n");
        return DiskWorkspace.open(root, List.of(root), List.of());
    }

    @Test
    void diskCommitHasDurableUndoRedoAndPreservesPermissions() throws Exception {
        var store = disk();
        var permissions = Files.getPosixFilePermissions(file("A.java"));
        var tx = new WorkspaceTransactions(store);
        var applied = tx.apply(rename(store.snapshot(), "A.java", "A", "NewName"),
                Cancellation.NONE);
        assertTrue(Files.readString(file("A.java")).contains("NewName"));
        assertEquals(permissions, Files.getPosixFilePermissions(file("A.java")));
        var reopened = DiskWorkspace.open(root, List.of(root), List.of());
        var history = new WorkspaceTransactions(reopened);
        history.undo(applied.transactionId(), Cancellation.NONE);
        assertEquals("class A {}\n", Files.readString(file("A.java")));
        var third = new WorkspaceTransactions(DiskWorkspace.open(root, List.of(root), List.of()));
        third.redo(applied.transactionId(), Cancellation.NONE);
        assertTrue(Files.readString(file("A.java")).contains("NewName"));
    }

    @Test
    void diskMultiFileMoveCreateDeleteCanBeUndoneAfterRestart() throws Exception {
        var store = disk();
        var tx = new WorkspaceTransactions(store);
        var pending = plan(store.snapshot(), List.of(), List
                .of(new FileOperation(FileOperationKind.MOVE, file("A.java"), file("nested/A.java"),
                null), new FileOperation(FileOperationKind.DELETE, file("B.java"), null, null),
                new FileOperation(FileOperationKind.CREATE, file("C.java"), null, "class C {}\n")));
        var applied = tx.apply(pending, Cancellation.NONE);
        assertFalse(Files.exists(file("A.java")));
        assertTrue(Files.exists(file("nested/A.java")));
        new WorkspaceTransactions(DiskWorkspace.open(root, List.of(root), List.of())).undo(applied
                .transactionId(), Cancellation.NONE);
        assertTrue(Files.exists(file("A.java")));
        assertTrue(Files.exists(file("B.java")));
        assertFalse(Files.exists(file("C.java")));
        assertFalse(Files.exists(file("nested/A.java")));
    }

    @Test
    void externalChangesAndSymlinksAreNeverOverwritten() throws Exception {
        var store = disk();
        var tx = new WorkspaceTransactions(store);
        var pending = rename(store.snapshot(), "A.java", "A", "Changed");
        Files.writeString(file("A.java"), "class External {}\n");
        assertThrows(TransactionException.class, () -> tx.apply(pending, Cancellation.NONE));
        assertTrue(Files.readString(file("A.java")).contains("External"));
        Files.createSymbolicLink(file("Link.java"), file("A.java"));
        var symbolic = plan(store.snapshot(), List.of(), List
                .of(new FileOperation(FileOperationKind.CREATE, file("Link.java"), null, "bad")));
        assertEquals("SYMLINK", assertThrows(TransactionException.class, () -> tx.apply(symbolic,
                Cancellation.NONE)).code());
        assertTrue(Files.readString(file("A.java")).contains("External"));
    }

    @Test
    void failureHalfwayThroughCommitRollsBackAllFiles() throws Exception {
        var original = disk().snapshot();
        var store = new DiskWorkspace(original, (index, path) -> {
            if (index == 1)
                throw new IOException("Injected disk failure");
        });
        var tx = new WorkspaceTransactions(store);
        var pending = plan(store.snapshot(), List.of(new FileEdits(file("A.java"), List
                .of(new TextEdit(6, 7, "Changed"))), new FileEdits(file("B.java"), List
                .of(new TextEdit(6, 7, "Other")))), List.of());
        assertEquals("COMMIT_IO", assertThrows(TransactionException.class, () -> tx.apply(pending,
                Cancellation.NONE)).code());
        assertEquals("class A {}\n", Files.readString(file("A.java")));
        assertEquals("class B {}\n", Files.readString(file("B.java")));
        assertTrue(store.history().isEmpty());
    }

    @Test
    void simulatedAbruptTerminationLeavesJournalAndRestartRecovers() throws Exception {
        var original = disk().snapshot();
        var store = new DiskWorkspace(original, (index, path) -> {
            if (index == 1)
                throw new AssertionError("Simulated abrupt process death");
        });
        var pending = plan(store.snapshot(), List.of(new FileEdits(file("A.java"), List
                .of(new TextEdit(6, 7, "Changed"))), new FileEdits(file("B.java"), List
                .of(new TextEdit(6, 7, "Other")))), List.of());
        assertThrows(AssertionError.class, () -> new WorkspaceTransactions(store).apply(pending,
                Cancellation.NONE));
        assertTrue(Files.readString(file("A.java")).contains("Changed"));
        assertEquals("RECOVERY_REQUIRED", assertThrows(TransactionException.class,
                () -> DiskWorkspace.open(root, List.of(root), List.of())).code());
        var reopened = DiskWorkspace.openRecovering(root, List.of(root), List.of());
        assertEquals("class A {}\n", Files.readString(file("A.java")));
        assertEquals("class B {}\n", Files.readString(file("B.java")));
        assertTrue(reopened.history().isEmpty());
    }

    @Test
    void recoveryConflictKeepsExternalContentAndJournalUntilResolved() throws Exception {
        var original = disk().snapshot();
        var store = new DiskWorkspace(original, (index, path) -> {
            if (index == 1) {
                Files.writeString(path, "class External {}\n");
                throw new IOException("Conflict");
            }
        });
        var pending = plan(store.snapshot(), List.of(new FileEdits(file("A.java"), List
                .of(new TextEdit(6, 7, "Changed"))), new FileEdits(file("B.java"), List
                .of(new TextEdit(6, 7, "Other")))), List.of());
        assertEquals("RECOVERY_REQUIRED", assertThrows(TransactionException.class,
                () -> new WorkspaceTransactions(store).apply(pending, Cancellation.NONE)).code());
        assertTrue(Files.readString(file("B.java")).contains("External"));
        assertThrows(TransactionException.class, () -> DiskWorkspace.open(root, List.of(root), List
                .of()));
        Files.writeString(file("B.java"), "class B {}\n");
        store.recover();
        assertEquals("class A {}\n", Files.readString(file("A.java")));
    }

    @Test
    void openBuffersAreRejectedByDiskAdapterAndInvalidUtf8RemainsUntouched() throws Exception {
        var original = disk().snapshot();
        var open = new WorkspaceSnapshot(root, original.version(), original.sources().stream()
                .map(s -> new SourceSnapshot(s.file(), s.text(), s.version(), true)).toList(), List
                .of(root), List.of());
        assertEquals("OPEN_BUFFER", assertThrows(TransactionException.class,
                () -> new DiskWorkspace(open)).code());
        Files.write(file("A.java"), new byte[] {(byte) 0xc3, (byte) 0x28});
        assertThrows(TransactionException.class, () -> DiskWorkspace.open(root, List.of(root), List
                .of()));
        assertArrayEquals(new byte[] {(byte) 0xc3, (byte) 0x28}, Files
                .readAllBytes(file("A.java")));
    }

    @Test
    void cancellationDuringCommitRestoresEveryFile() throws Exception {
        var original = disk().snapshot();
        var cancelled = new AtomicBoolean();
        var store = new DiskWorkspace(original, (index, path) -> {
            if (index == 1)
                cancelled.set(true);
        });
        var pending = plan(store.snapshot(), List.of(new FileEdits(file("A.java"), List
                .of(new TextEdit(6, 7, "Changed"))), new FileEdits(file("B.java"), List
                .of(new TextEdit(6, 7, "Other")))), List.of());
        assertThrows(CancellationException.class, () -> new WorkspaceTransactions(store)
                .apply(pending, cancelled::get));
        assertEquals("class A {}\n", Files.readString(file("A.java")));
        assertEquals("class B {}\n", Files.readString(file("B.java")));
    }

    @Test
    void previewDoesNotFollowSymbolicTransactionMetadata() throws Exception {
        disk();
        Path outside = Files.createDirectory(root.resolve("outside-metadata"));
        Files.createSymbolicLink(root.resolve(".refactorj"), outside);
        assertEquals("SYMLINK", assertThrows(TransactionException.class, () -> DiskWorkspace
                .open(root, List.of(root), List.of())).code());
        assertFalse(Files.exists(outside.resolve("transactions")));
        assertEquals("class A {}\n", Files.readString(file("A.java")));
    }

    @Test
    void malformedAndPathEscapingJournalsNeverTouchSources() throws Exception {
        var store = disk();
        var tx = new WorkspaceTransactions(store);
        tx.apply(rename(store.snapshot(), "A.java", "A", "Changed"), Cancellation.NONE);
        Path journal;

        try (var entries = Files.list(root.resolve(".refactorj/transactions"))) {
            journal = entries.filter(p -> p.toString().endsWith(".json")).findFirst().orElseThrow();
        }
        String valid = Files.readString(journal);
        Files.writeString(journal, valid.replace("\"path\":\"A.java\"",
                "\"path\":\"../Escaped.java\""));
        assertThrows(TransactionException.class, () -> DiskWorkspace.open(root, List.of(root), List
                .of()));
        assertEquals("class Changed {}\n", Files.readString(file("A.java")));
        Files.writeString(journal, "{}");
        assertThrows(TransactionException.class, () -> DiskWorkspace.open(root, List.of(root), List
                .of()));
        assertEquals("class Changed {}\n", Files.readString(file("A.java")));
    }
}
