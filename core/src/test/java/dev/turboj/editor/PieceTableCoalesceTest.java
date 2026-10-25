package dev.turboj.editor;

import dev.turboj.editor.document.PieceTable;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PieceTableCoalesceTest {
    @Test void mergesSeveralChangesWithoutMutatingTextVersionOrOlderUndo() {
        var document = new PieceTable("class Example {}\n");
        document.insert(0, "// older\n");
        long checkpoint = document.peekUndoTransactionId(); String baseline = document.toString();
        int name = baseline.indexOf("Example");
        document.replace(name, name + 7, "Other"); document.insert(document.length() - 2, " int value; ");
        String current = document.toString(); long version = document.version();
        var transaction = document.coalesceSince(checkpoint, baseline);
        assertNotNull(transaction); assertEquals(current, document.toString()); assertEquals(version, document.version());
        assertEquals(transaction.id(), document.undoTransaction().id()); assertEquals(baseline, document.toString());
        assertEquals(checkpoint, document.peekUndoTransactionId());
        document.redo(); assertEquals(current, document.toString());
        document.undo(); document.undo(); assertEquals("class Example {}\n", document.toString());
    }

    @Test void originalEmptyHistoryCheckpointSupportsOneUndoAndRedoAcrossMultiEdits() {
        var document = new PieceTable("a + b + c");
        document.replaceAll(List.of(new PieceTable.Edit(0, 1, "alpha"), new PieceTable.Edit(8, 9, "gamma")));
        document.insert(0, "("); document.insert(document.length(), ")");
        String current = document.toString();
        var aggregate = document.coalesceSince(-1, "a + b + c");
        assertEquals(current, document.toString()); assertEquals(1, aggregate.changes().size());
        document.undo(); assertEquals("a + b + c", document.toString()); assertFalse(document.canUndo());
        document.redo(); assertEquals(current, document.toString());
    }

    @Test void nonexistentOrDiscardedCheckpointAndWrongBaselineAreRejectedWithoutSideEffects() {
        var document = new PieceTable("base"); document.insert(4, "1");
        long checkpoint = document.peekUndoTransactionId(); document.insert(5, "2");
        long head = document.peekUndoTransactionId(); long version = document.version();
        assertFalse(document.canCoalesceSince(99_999));
        assertThrows(IllegalArgumentException.class, () -> document.coalesceSince(99_999, "base1"));
        assertThrows(IllegalArgumentException.class, () -> document.coalesceSince(checkpoint, "wrong"));
        assertEquals("base12", document.toString()); assertEquals(head, document.peekUndoTransactionId()); assertEquals(version, document.version());
        document.undo(); document.undo();
        assertFalse(document.canCoalesceSince(checkpoint), "A redo-only checkpoint is not a current undo baseline");
        document.insert(4, "new branch"); assertFalse(document.canCoalesceSince(checkpoint));
    }

    @Test void trimmedHistoryNeverPretendsMinusOneIsOriginalBase() {
        var document = new PieceTable("");
        for (int i = 0; i < 2001; i++) document.insert(document.length(), "x");
        assertFalse(document.canCoalesceSince(-1));
        assertThrows(IllegalArgumentException.class, () -> document.coalesceSince(-1, ""));
        document.setText("reset"); assertTrue(document.canCoalesceSince(-1));
    }

    @Test void netZeroRemovesOnlyCoveredHistoryAndClearsRedo() {
        var document = new PieceTable("base"); document.insert(0, "older ");
        long checkpoint = document.peekUndoTransactionId(); String before = document.toString();
        document.insert(0, "temporary"); document.delete(0, 9); document.insert(0, "redo"); document.undo();
        assertTrue(document.canRedo()); long version = document.version();
        assertNull(document.coalesceSince(checkpoint, before));
        assertEquals(checkpoint, document.peekUndoTransactionId()); assertFalse(document.canRedo());
        assertEquals(version, document.version()); document.undo(); assertEquals("base", document.toString());
    }

    @Test void preflightIsReadOnlyIncludingRedoAndRejectsWrongBaseline() {
        var document = new PieceTable("baseline"); document.insert(8, " one");
        long checkpoint = document.peekUndoTransactionId(); String baseline = document.toString();
        document.insert(document.length(), " two"); long undo = document.peekUndoTransactionId();
        document.insert(document.length(), " three"); document.undo();
        long redo = document.peekRedoTransactionId(), version = document.version(); String current = document.toString();
        document.validateCoalesceSince(checkpoint, baseline);
        assertThrows(IllegalArgumentException.class, () -> document.validateCoalesceSince(checkpoint, "wrong"));
        assertEquals(current, document.toString()); assertEquals(version, document.version());
        assertEquals(undo, document.peekUndoTransactionId()); assertEquals(redo, document.peekRedoTransactionId());
    }

    @Test void coalescedDiffNeverSplitsSurrogateOrCrLfBoundaries() {
        var emoji = new PieceTable("😀\r\nkeep"); emoji.replace(0, 2, "😁");
        var unicode = emoji.coalesceSince(-1, "😀\r\nkeep").changes().getFirst();
        assertEquals(0, unicode.start()); assertEquals("😀", unicode.removed()); assertEquals("😁", unicode.inserted());
        emoji.undo(); assertEquals("😀\r\nkeep", emoji.toString());
        var newline = new PieceTable("a\r\nb"); newline.delete(1, 2);
        var crlf = newline.coalesceSince(-1, "a\r\nb").changes().getFirst();
        assertEquals("\r\n", crlf.removed()); assertEquals("\n", crlf.inserted());
        newline.undo(); assertEquals("a\r\nb", newline.toString()); newline.redo(); assertEquals("a\nb", newline.toString());
    }
}
