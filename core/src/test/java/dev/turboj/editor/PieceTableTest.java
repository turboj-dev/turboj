package dev.turboj.editor;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboj.editor.document.PieceTable;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

class PieceTableTest {
    @org.junit.jupiter.api.Test
    void workspaceHistoryCanInspectTransactionIdentityWithoutMutation() {
        var document = new dev.turboj.editor.document.PieceTable("a");
        org.junit.jupiter.api.Assertions.assertEquals(-1, document.peekUndoTransactionId());
        document.insert(1, "b");
        long first = document.peekUndoTransactionId();
        document.insert(2, "c");
        long second = document.peekUndoTransactionId();
        org.junit.jupiter.api.Assertions.assertNotEquals(first, second);
        document.undoTransaction();
        org.junit.jupiter.api.Assertions.assertEquals(first, document.peekUndoTransactionId());
        org.junit.jupiter.api.Assertions.assertEquals(second, document.peekRedoTransactionId());
        document.redoTransaction();
        org.junit.jupiter.api.Assertions.assertEquals(second, document.peekUndoTransactionId());
        org.junit.jupiter.api.Assertions.assertEquals(-1, document.peekRedoTransactionId());
    }

    @Test
    void editsMatchStringAndMaintainLineIndex() {
        Random random = new Random(81278);
        PieceTable table = new PieceTable("class Hello {\r\n    String café = \"🙂\";\r\n}\r\n");
        String expected = table.toString();
        List<String> history = new ArrayList<>();
        history.add(expected);
        String[] inserted = {"a", "\n", "\r\n", "🙂", "中文", "", "public class X {}", "\n\n"};
        for (int i = 0; i < 1500; i++) {
            int start = random.nextInt(expected.length() + 1);
            int end = start + random.nextInt(expected.length() - start + 1);
            String text = inserted[random.nextInt(inserted.length)];
            String next = expected.substring(0, start) + text + expected.substring(end);
            PieceTable.Snapshot previous = table.snapshot();
            table.replace(start, end, text);
            assertEquals(expected, previous.toString(), "snapshot mutated");
            if (!next.equals(expected)) history.add(next);
            expected = next;
            assertModel(table, expected);
        }
        for (int i = history.size() - 2; i >= 0; i--) {
            assertNotNull(table.undo());
            assertModel(table, history.get(i));
        }
        assertNull(table.undo());
        for (int i = 1; i < history.size(); i++) {
            assertNotNull(table.redo());
            assertModel(table, history.get(i));
        }
        assertNull(table.redo());
    }

    private static void assertModel(PieceTable table, String expected) {
        assertEquals(expected, table.toString());
        assertEquals(expected.length(), table.length());
        List<Integer> starts = new ArrayList<>();
        starts.add(0);
        for (int i = 0; i < expected.length(); i++) {
            assertEquals(expected.charAt(i), table.charAt(i));
            if (expected.charAt(i) == '\n') starts.add(i + 1);
        }
        assertEquals(starts.size(), table.lineCount());
        for (int line = 0; line < starts.size(); line++) {
            assertEquals(starts.get(line), table.lineStart(line));
            int end = line + 1 < starts.size() ? starts.get(line + 1) - 1 : expected.length();
            if (end > starts.get(line) && expected.charAt(end - 1) == '\r') end--;
            assertEquals(expected.substring(starts.get(line), end), table.lineText(line));
        }
        for (int offset = 0, line = 0; offset <= expected.length(); offset++) {
            if (line + 1 < starts.size() && starts.get(line + 1) == offset) line++;
            assertEquals(line, table.lineAt(offset));
        }
    }

    @Test
    void preservesMixedEndingsAndImmutableSnapshots() {
        PieceTable table = new PieceTable("a\r\nb\nc");
        assertEquals("\r\n", table.lineEnding());
        var snapshot = table.snapshot();
        long old = table.version();
        table.insert(1, "中🙂");
        table.setText("different");
        assertEquals("a\r\nb\nc", snapshot.toString());
        assertTrue(table.version() > old);
    }

    @Test
    void redoIsInvalidatedByNewEdit() {
        PieceTable table = new PieceTable("hello");
        table.insert(5, " world");
        table.undo();
        table.insert(5, " Java");
        assertFalse(table.canRedo());
        assertEquals("hello Java", table.toString());
    }

    @Test
    void snapshotsCanBeReadAcrossThreadsWhileDocumentChanges() throws Exception {
        PieceTable table = new PieceTable("line\n".repeat(10_000));
        var snapshot = table.snapshot();
        var reader =
                java.util.concurrent.CompletableFuture.runAsync(
                        () -> {
                            for (int i = 0; i < 50; i++)
                                assertEquals("line\n".repeat(10_000), snapshot.toString());
                        });
        for (int i = 0; i < 500; i++) table.insert(3, "x");
        reader.get();
        assertEquals(50_000, snapshot.length());
        assertEquals(50_500, table.length());
    }

    @Test
    void sparseFormattingTransactionHasOneUndoAndKeepsEarlierTyping() {
        PieceTable table = new PieceTable("class X{\nint a=1;\nint b=2;\n}\n");
        table.insert(table.length(), "// typed\n");
        String before = table.toString();
        var snapshot = table.snapshot();
        var edits =
                List.of(
                        new PieceTable.Edit(7, 7, " "),
                        new PieceTable.Edit(9, 9, "    "),
                        new PieceTable.Edit(18, 18, "    "));
        var result = table.replaceAll(edits);
        assertEquals(3, result.changes().size(), "unchanged source is not replaced");
        String formatted = table.toString();
        assertEquals(before, snapshot.toString());
        assertEquals(result.id(), table.undoTransaction().id());
        assertModel(table, before);
        table.undo();
        assertFalse(table.toString().contains("// typed"));
        table.redo();
        table.redoTransaction();
        assertModel(table, formatted);
    }

    @Test
    void invalidBatchNeverPartiallyMutatesDocument() {
        PieceTable table = new PieceTable("abc\ndef");
        long version = table.version();
        assertThrows(
                IndexOutOfBoundsException.class,
                () ->
                        table.replaceAll(
                                List.of(
                                        new PieceTable.Edit(0, 1, "A"),
                                        new PieceTable.Edit(30, 31, "B"))));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        table.replaceAll(
                                List.of(
                                        new PieceTable.Edit(1, 5, "A"),
                                        new PieceTable.Edit(3, 4, "B"))));
        assertModel(table, "abc\ndef");
        assertEquals(version, table.version());
        assertFalse(table.canUndo());
        assertNull(table.replaceAll(List.of(new PieceTable.Edit(0, 3, "abc"))));
        assertFalse(table.canUndo());
    }

    @Test
    void generatedBatchEditsUndoAndRedoMatchStringModel() {
        Random random = new Random(90123);
        for (int round = 0; round < 300; round++) {
            String source = "abc\ndef\r\nghi🙂\n".repeat(20);
            PieceTable table = new PieceTable(source);
            List<PieceTable.Edit> edits = new ArrayList<>();
            int at = 0;
            for (int i = 0; i < 8; i++) {
                int start = at + random.nextInt(6), end = start + random.nextInt(5);
                edits.add(new PieceTable.Edit(start, end, i % 2 == 0 ? "\r\n    " : " "));
                at = end + 1;
            }
            StringBuilder expected = new StringBuilder(source);
            for (int i = edits.size() - 1; i >= 0; i--) {
                var edit = edits.get(i);
                expected.replace(edit.start(), edit.end(), edit.text());
            }
            table.replaceAll(edits);
            assertModel(table, expected.toString());
            table.undoTransaction();
            assertModel(table, source);
            table.redoTransaction();
            assertModel(table, expected.toString());
        }
    }
}
