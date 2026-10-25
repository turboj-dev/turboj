package dev.turboj.ui;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.StringSelection;
import java.awt.event.KeyEvent;
import java.util.List;
import java.util.concurrent.FutureTask;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class KeyboardEditingTest {
    private static EditorComponent editor(String source) {
        var editor = new EditorComponent();
        editor.setSize(900, 600);
        editor.setText(source);
        editor.setClipboard(new Clipboard("isolated-test"));
        return editor;
    }
    private static void edt(Runnable test) throws Exception {
        var task = new FutureTask<Void>(() -> { test.run(); return null; });
        SwingUtilities.invokeAndWait(task);
        task.get();
    }
    private static void roundTrip(EditorComponent editor, Runnable action, String expected) {
        String before = editor.getText();
        var selections = editor.selections();
        action.run();
        assertEquals(expected, editor.getText());
        var after = editor.selections();
        editor.undo();
        assertEquals(before, editor.getText());
        assertEquals(selections, editor.selections());
        editor.redo();
        assertEquals(expected, editor.getText());
        assertEquals(after, editor.selections());
    }
    private static void typed(EditorComponent e, char c) {
        e.processKeyEvent(new KeyEvent(e, KeyEvent.KEY_TYPED, 0, 0, KeyEvent.VK_UNDEFINED, c));
    }
    private static void key(EditorComponent e, int code, int modifiers) {
        e.processKeyEvent(new KeyEvent(e, KeyEvent.KEY_PRESSED, 0, modifiers, code, KeyEvent.CHAR_UNDEFINED));
    }
    private static String clipboard(Clipboard clipboard) {
        try { return (String) clipboard.getData(DataFlavor.stringFlavor); }
        catch (Exception ex) { throw new AssertionError(ex); }
    }

    @Test void cutAndCopyLogicalLineAndExplicitSelection() throws Exception { edt(() -> {
        for (String nl : List.of("\n", "\r\n")) {
            var clipboard = new Clipboard("cut-copy");
            var e = editor("alpha" + nl + "beta" + nl + "gamma");
            e.setClipboard(clipboard);
            e.gotoLine(2);
            e.copy();
            assertEquals("beta" + nl, clipboard(clipboard));
            assertEquals("alpha" + nl + "beta" + nl + "gamma", e.getText());
            roundTrip(e, e::cut, "alpha" + nl + "gamma");
            assertEquals("beta" + nl, clipboard(clipboard));
            e.undo();
            e.setSelection(1, 4);
            roundTrip(e, e::cut, "aa" + nl + "beta" + nl + "gamma");
            assertEquals("lph", clipboard(clipboard));
            e.undo();
            e.setSelection(2, e.getText().indexOf("beta") + 2);
            String selected = e.getSelectedText();
            e.copy();
            assertEquals(selected, clipboard(clipboard));
            e.cut();
            assertEquals("alta" + nl + "gamma", e.getText());
        }
    }); }

    @Test void linePasteInsertsAtLogicalStartAndExternalTextPastesAtCaret() throws Exception { edt(() -> {
        var clipboard = new Clipboard("paste");
        var e = editor("one\ntwo"); e.setClipboard(clipboard);
        e.gotoOffset(1); e.copy();
        e.gotoOffset(5);
        roundTrip(e, e::paste, "one\none\ntwo");
        e.undo();
        clipboard.setContents(new StringSelection("X\r\nY"), null);
        roundTrip(e, e::paste, "one\ntX\nYwo");
    }); }

    @Test void deleteEveryLineBoundaryDoesNotChangeClipboard() throws Exception { edt(() -> {
        var clipboard = new Clipboard("delete"); clipboard.setContents(new StringSelection("keep"), null);
        for (String nl : List.of("\n", "\r\n")) {
            String source = "a" + nl + "b" + nl + "c";
            String[] results = {"b" + nl + "c", "a" + nl + "c", "a" + nl + "b"};
            for (int line = 1; line <= 3; line++) {
                var e = editor(source); e.setClipboard(clipboard); e.gotoLine(line);
                roundTrip(e, e::deleteLine, results[line - 1]);
                assertEquals("keep", clipboard(clipboard));
            }
            var e = editor("a" + nl); e.gotoLine(2);
            roundTrip(e, e::deleteLine, "a");
            e = editor("only"); roundTrip(e, e::deleteLine, "");
            e = editor(""); e.deleteLine(); assertEquals("", e.getText());
        }
    }); }

    @Test void deleteSelectedLinesExcludesFollowingBoundaryAndSupportsReverseSelection() throws Exception { edt(() -> {
        var e = editor("a\nb\nc\nd");
        e.setSelection(6, 2);
        roundTrip(e, e::deleteLine, "a\nd");
    }); }

    @Test void duplicateSelectionIsExactAndLineCopiesDoNotTouchClipboard() throws Exception { edt(() -> {
        var clipboard = new Clipboard("copy-lines"); clipboard.setContents(new StringSelection("keep"), null);
        var e = editor("alpha\nbeta\ngamma"); e.setClipboard(clipboard);
        e.setSelection(4, 1);
        roundTrip(e, e::duplicateLine, "alphlpha\nbeta\ngamma");
        assertEquals("lph", e.getSelectedText());
        e.undo(); e.setSelection(6, 11);
        roundTrip(e, () -> e.copyLine(-1), "alpha\nbeta\nbeta\ngamma");
        assertEquals("beta\n", e.getSelectedText());
        e.undo();
        roundTrip(e, () -> e.copyLine(1), "alpha\nbeta\nbeta\ngamma");
        assertEquals("beta\n", e.getSelectedText());
        assertEquals("keep", clipboard(clipboard));
    }); }

    @Test void finalUnterminatedLineDuplicatesAndCopiesBothDirections() throws Exception { edt(() -> {
        for (String nl : List.of("\n", "\r\n")) for (int direction : new int[] {-1, 1}) {
            var e = editor("alpha" + nl + "beta"); e.gotoOffset(e.getText().length() - 2);
            roundTrip(e, () -> e.copyLine(direction), "alpha" + nl + "beta" + nl + "beta");
            assertEquals(direction < 0 ? "alpha".length() + nl.length() + 2 : e.getText().length() - 2, e.caretOffset());
        }
    }); }

    @Test void moveSelectedLinesPreservesSelectionOrientationAndFinalSeparator() throws Exception { edt(() -> {
        for (String nl : List.of("\n", "\r\n")) {
            String source = "a" + nl + "beta" + nl + "gamma" + nl + "d";
            var e = editor(source);
            int start = source.indexOf("beta"), end = source.indexOf("d");
            e.setSelection(end, start);
            roundTrip(e, () -> e.moveLine(-1), "beta" + nl + "gamma" + nl + "a" + nl + "d");
            assertTrue(e.captureLocation().caret() < e.captureLocation().anchor());
            assertEquals("beta" + nl + "gamma" + nl, e.getSelectedText());
            e.undo();
            roundTrip(e, () -> e.moveLine(1), "a" + nl + "d" + nl + "beta" + nl + "gamma");
            assertEquals("beta" + nl + "gamma", e.getSelectedText());
            e.gotoLine(1); String before = e.getText(); e.moveLine(-1); assertEquals(before, e.getText());
            e.gotoLine(e.document().lineCount()); e.moveLine(1); assertEquals(before, e.getText());
        }
    }); }

    @Test void insertAboveBelowAndSplitPreserveIndentation() throws Exception { edt(() -> {
        for (String nl : List.of("\n", "\r\n")) {
            var e = editor("  alpha" + nl + "    beta"); e.gotoOffset(4);
            roundTrip(e, () -> e.insertLine(true), "  " + nl + "  alpha" + nl + "    beta");
            e.undo();
            roundTrip(e, () -> e.insertLine(false), "  alpha" + nl + "  " + nl + "    beta");
            e.undo();
            roundTrip(e, e::splitLine, "  al" + nl + "  pha" + nl + "    beta");
        }
    }); }

    @Test void joinAndWhitespaceConversionsAreUndoable() throws Exception { edt(() -> {
        var e = editor("a  \n    b\n  c");
        roundTrip(e, e::joinLines, "a b\n  c");
        e = editor("a \t\r\nb  \r\n");
        roundTrip(e, e::trimTrailingWhitespace, "a\r\nb\r\n");
        e = editor("\tA\tB\n  \tC");
        roundTrip(e, e::convertTabsToSpaces, "    A   B\n    C");
        e = editor("    a = \"    \";\n      b");
        roundTrip(e, e::convertSpacesToTabs, "\ta = \"    \";\n\t  b");
    }); }

    @Test void deleteWordAndLinePartsPreservesUnicodeAndCrLf() throws Exception { edt(() -> {
        var e = editor("one two\r\n🙂x"); e.gotoOffset(7);
        roundTrip(e, e::deletePreviousWord, "one \r\n🙂x");
        e.undo(); e.gotoOffset(4);
        roundTrip(e, e::deleteNextWord, "one 🙂x");
        e.undo(); e.gotoOffset(2);
        roundTrip(e, e::deleteToLineStart, "e two\r\n🙂x");
        e.undo(); e.gotoOffset(2);
        roundTrip(e, e::deleteToLineEnd, "on\r\n🙂x");
        e.undo(); e.gotoOffset(e.getText().indexOf("🙂") + 2);
        roundTrip(e, e::deleteBackward, "one two\r\nx");
    }); }

    @Test void blockCommentCaseSortReverseTransposeAndSmartMotion() throws Exception { edt(() -> {
        var e = editor("abc"); e.selectAll();
        roundTrip(e, e::toggleBlockComment, "/*abc*/");
        e.selectAll(); roundTrip(e, e::toggleBlockComment, "abc");
        e = editor("a*/b"); e.selectAll(); e.toggleBlockComment(); assertEquals("a*/b", e.getText());
        e = editor("Straße"); e.selectAll(); var upper = e;
        roundTrip(e, () -> upper.changeCase(true), "STRASSE");
        e = editor("z\r\nb\r\na"); e.selectAll(); roundTrip(e, e::sortSelectedLines, "a\r\nb\r\nz");
        roundTrip(e, e::reverseSelectedLines, "z\r\nb\r\na");
        e = editor("a🙂b"); e.gotoOffset(3); roundTrip(e, e::transposeCharacters, "ab🙂");
        e = editor("  abc  "); e.gotoOffset(4); e.smartHome(false); assertEquals(2, e.caretOffset());
        e.smartHome(false); assertEquals(0, e.caretOffset());
        e.smartEnd(false); assertEquals(5, e.caretOffset()); e.smartEnd(false); assertEquals(7, e.caretOffset());
    }); }

    @Test void multipleCaretsTypeDeleteAndUndoAsOneTransaction() throws Exception { edt(() -> {
        var e = editor("ab\ncd\nef"); e.gotoOffset(1); e.addCaretBelow(); e.addCaretBelow();
        assertEquals(3, e.selections().size());
        roundTrip(e, () -> typed(e, 'X'), "aXb\ncXd\neXf");
        roundTrip(e, () -> key(e, KeyEvent.VK_BACK_SPACE, 0), "ab\ncd\nef");
        roundTrip(e, () -> key(e, KeyEvent.VK_DELETE, 0), "a\nc\ne");
    }); }

    @Test void multipleCaretsAreNotVisualAndOverlappingSelectionsMerge() throws Exception { edt(() -> {
        var e = editor("alpha beta gamma");
        e.setSelections(List.of(new EditorComponent.Selection(5, 0), new EditorComponent.Selection(7, 3),
                new EditorComponent.Selection(7, 7), new EditorComponent.Selection(16, 11)));
        assertEquals(2, e.selections().size());
        roundTrip(e, () -> e.replaceSelection("X"), "Xeta X");
        e.removeSecondaryCarets(); assertEquals(1, e.selections().size());
    }); }

    @Test void multicaretPairedTypingSelectionAndArrowMovement() throws Exception { edt(() -> {
        var e = editor("ab\ncd"); e.gotoOffset(1); e.addCaretBelow();
        roundTrip(e, () -> typed(e, '('), "a()b\nc()d");
        assertEquals(List.of(new EditorComponent.Selection(2, 2), new EditorComponent.Selection(7, 7)), e.selections());
        typed(e, ')'); assertEquals("a()b\nc()d", e.getText());
        key(e, KeyEvent.VK_LEFT, KeyEvent.SHIFT_DOWN_MASK);
        assertEquals(")", e.getSelectedText());
        assertEquals(2, e.selections().size());
        roundTrip(e, () -> typed(e, 'x'), "a(xb\nc(xd");
    }); }

    @Test void multicaretClipboardDistributesSelectionsAndLineOperationsStayCoherent() throws Exception { edt(() -> {
        var clipboard = new Clipboard("multi");
        var e = editor("ab\ncd\nef"); e.setClipboard(clipboard);
        e.setSelections(List.of(new EditorComponent.Selection(2, 0), new EditorComponent.Selection(5, 3)));
        e.copy(); assertEquals("ab\ncd", clipboard(clipboard));
        e.setSelections(List.of(new EditorComponent.Selection(0, 0), new EditorComponent.Selection(6, 6)));
        roundTrip(e, e::paste, "abab\ncd\ncdef");
        e = editor("a\nb\nc\nd"); e.gotoLine(1); e.addCaretBelow();
        var multiple = e;
        roundTrip(e, () -> multiple.moveLine(1), "c\na\nb\nd");
        roundTrip(e, e::toggleComment, "c\n// a\n// b\nd");
        roundTrip(e, e::duplicateLine, "c\n// a\n// b\n// a\n// b\nd");
    }); }

    @Test void rectangularAndOccurrenceSelectionsPreserveUnicodeBoundaries() throws Exception { edt(() -> {
        var e = editor("  ab\n  cd\n  ef");
        e.rectangularSelection(2, 14);
        assertEquals(3, e.selections().size());
        var rectangle = e; roundTrip(e, () -> rectangle.replaceSelection("X"), "  X\n  X\n  X");
        e = editor("name = name + name;"); e.gotoOffset(2); e.selectCurrentWord();
        assertEquals("name", e.getSelectedText());
        e.selectNextOccurrence(); assertEquals(2, e.selections().size());
        e.selectAllOccurrences(); assertEquals(3, e.selections().size());
        var occurrence = e; roundTrip(e, () -> occurrence.replaceSelection("value"), "value = value + value;");
        e = editor("🙂\r\nx"); var unicode = e;
        assertThrows(IllegalArgumentException.class, () -> unicode.setSelection(1, 1));
        assertThrows(IllegalArgumentException.class, () -> unicode.setSelection(3, 3));
    }); }

    @Test void readonlyAndStalePlansDoNotMutateSourceOrClipboard() throws Exception { edt(() -> {
        var clipboard = new Clipboard("readonly"); clipboard.setContents(new StringSelection("keep"), null);
        var e = editor("abc\ndef"); e.setClipboard(clipboard); e.setReadOnly(true);
        e.cut(); e.paste(); e.deleteLine(); e.duplicateLine(); e.moveLine(1); e.copyLine(1);
        e.insertLine(true); e.joinLines(); e.splitLine(); e.indent(false); e.toggleComment();
        e.toggleBlockComment(); e.convertTabsToSpaces(); e.transposeCharacters();
        assertEquals("abc\ndef", e.getText()); assertEquals("keep", clipboard(clipboard));
        e.setReadOnly(false);
        assertFalse(e.applySemanticEdits(e.version() - 1, List.of(new EditorComponent.TextEdit(0, 1, "x")), 1, 1));
        assertFalse(e.applySemanticEdits(e.version(), List.of(new EditorComponent.TextEdit(2, 1, "x")), 1, 1));
        assertEquals("abc\ndef", e.getText());
    }); }

    @Test void sparseSemanticEditRestoresSelectionsAndBufferSwitchKeepsCarets() throws Exception { edt(() -> {
        var e = editor("ab\ncd"); e.gotoOffset(1); e.addCaretBelow();
        var before = e.selections();
        roundTrip(e, () -> assertTrue(e.applySemanticEdits(e.version(),
                List.of(new EditorComponent.TextEdit(0, 2, "xy")), 2, 0)), "xy\ncd");
        e.undo(); assertEquals(before, e.selections());
        var state = e.captureState(); e.setText("other"); e.restoreState(state);
        assertEquals(before, e.selections());
    }); }

    @Test void imeCompositionCommitsOnceAtEveryCaretAndRemainsUndoable() throws Exception { edt(() -> {
        var e = editor("a\nb"); e.gotoOffset(1); e.addCaretBelow();
        e.inputMethodTextChanged(new java.awt.event.InputMethodEvent(e,
                java.awt.event.InputMethodEvent.INPUT_METHOD_TEXT_CHANGED,
                new java.text.AttributedString("漢").getIterator(), 0, null, null));
        assertEquals("a\nb", e.getText()); assertTrue(e.hasInputComposition());
        roundTrip(e, () -> e.inputMethodTextChanged(new java.awt.event.InputMethodEvent(e,
                java.awt.event.InputMethodEvent.INPUT_METHOD_TEXT_CHANGED,
                new java.text.AttributedString("漢").getIterator(), 1, null, null)), "a漢\nb漢");
        assertFalse(e.hasInputComposition());
    }); }

    @Test void adjacentSelectionsRemainIndependentAndInvalidPlansNeverMutate() throws Exception { edt(() -> {
        var e = editor("aaaa"); e.setSelection(0, 2); e.selectAllOccurrences();
        assertEquals(2, e.selections().size());
        roundTrip(e, () -> e.replaceSelection("X"), "XX");
        e.setText("abc"); long version = e.version();
        assertFalse(e.applySemanticEdits(version, List.of(new EditorComponent.TextEdit(0, 1, "🙂")), 1, 1));
        assertFalse(e.applySemanticEdits(version, List.of(new EditorComponent.TextEdit(0, 1, "")), 9, 9));
        assertEquals("abc", e.getText()); assertEquals(version, e.version());
        var clipboard = new Clipboard("empty"); clipboard.setContents(new StringSelection("keep"), null);
        e.setClipboard(clipboard); e.setText(""); e.cut(); e.copy();
        assertEquals("keep", clipboard(clipboard));
    }); }

    @Test void surrogatePairKeyEventsFormOneUndoableMulticaretCharacter() throws Exception { edt(() -> {
        var e = editor("a\nb"); e.gotoOffset(1); e.addCaretBelow();
        typed(e, '\uD83D'); assertEquals("a\nb", e.getText());
        roundTrip(e, () -> typed(e, '\uDE42'), "a🙂\nb🙂");
        roundTrip(e, e::deleteBackward, "a\nb");
    }); }

    @Test void completionRedoRestoresItsIntendedInsertionCaret() throws Exception { edt(() -> {
        var e = editor("System.out.pr"); e.gotoOffset(e.getText().length());
        roundTrip(e, () -> assertTrue(e.applyCompletion(new EditorComponent.InlineCompletion(
                e.version(), 13, 11, 13, "println()", 8))), "System.out.println()");
        assertEquals(19, e.caretOffset());
    }); }

    @Test void seededLineMoveCasesPreserveContentAndOneStepHistory() throws Exception { edt(() -> {
        var random = new java.util.Random(20261024L);
        for (int trial = 0; trial < 200; trial++) {
            String nl = random.nextBoolean() ? "\n" : "\r\n";
            int count = 2 + random.nextInt(12), first = random.nextInt(count), last = first + random.nextInt(count - first);
            var original = new java.util.ArrayList<String>();
            for (int i = 0; i < count; i++) original.add("line" + i + "🙂");
            var e = editor(String.join(nl, original));
            int start = e.document().lineStart(first), end = e.document().lineEnd(last);
            if (random.nextBoolean()) e.setSelection(start, end); else e.setSelection(end, start);
            int direction = random.nextBoolean() ? -1 : 1;
            var expected = new java.util.ArrayList<>(original);
            if (direction < 0 && first > 0) expected.add(last, expected.remove(first - 1));
            else if (direction > 0 && last + 1 < count) expected.add(first, expected.remove(last + 1));
            if (expected.equals(original)) { e.moveLine(direction); assertEquals(String.join(nl, original), e.getText()); }
            else roundTrip(e, () -> e.moveLine(direction), String.join(nl, expected));
        }
    }); }
}
