package com.turboj.editor;

import static org.junit.jupiter.api.Assertions.*;

import com.turboj.editor.document.PieceTable;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

class PieceTableTest {
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
}
