package dev.turboj.ui;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.Point;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.JFrame;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

/** The inline template contract: Tab and Shift+Tab wrap, Enter confirms from any field, ranges follow every edit. */
class EditorTemplateTest {
    private static final String METHOD = "public int multiply(int i, int j) {\n    return 0;\n}\n";

    /** Return type, name, parameters, returned expression; the exit after the expression. */
    private static EditorComponent method(List<Integer> entered, List<Integer> left, AtomicInteger ended) {
        var editor = new EditorComponent();
        editor.setText(METHOD);
        int body = METHOD.indexOf("0;");
        editor.startTemplate(List.of(List.of(at("int")), List.of(at("multiply")), List.of(range(METHOD.indexOf("i,"), METHOD.indexOf("i,") + 1)),
                        List.of(range(METHOD.indexOf("j)"), METHOD.indexOf("j)") + 1)), List.of(range(body, body + 1))),
                range(body + 1, body + 1), entered::add, (index, next) -> { left.add(next); return true; }, ended::incrementAndGet);
        return editor;
    }

    @Test void tabVisitsTheFieldsInOrderAndWrapsBothWaysWithoutInsertingText() throws Exception {
        edt(() -> {
            var entered = new ArrayList<Integer>();
            var editor = method(entered, new ArrayList<>(), new AtomicInteger());
            assertEquals("int", editor.getSelectedText(), "the first value is selected entirely");
            var visited = new ArrayList<String>();
            for (int i = 0; i < 5; i++) { visited.add(editor.getSelectedText()); key(editor, KeyEvent.VK_TAB, 0); }
            assertEquals(List.of("int", "multiply", "i", "j", "0"), visited);
            assertEquals("int", editor.getSelectedText(), "Tab on the last field returns to the first");
            assertTrue(editor.templateActive(), "Tab never confirms");
            key(editor, KeyEvent.VK_TAB, KeyEvent.SHIFT_DOWN_MASK);
            assertEquals("0", editor.getSelectedText(), "Shift+Tab on the first field goes to the last");
            assertEquals(METHOD, editor.getText(), "navigation inserts nothing");
            assertEquals(List.of(0, 1, 2, 3, 4, 0, 4), entered);
        });
    }

    @Test void typingReplacesTheSelectedValueAndEnterConfirmsFromAnyField() throws Exception {
        edt(() -> {
            var left = new ArrayList<Integer>();
            var ended = new AtomicInteger();
            var editor = method(new ArrayList<>(), left, ended);
            editor.replaceSelection("long");
            key(editor, KeyEvent.VK_TAB, 0);
            editor.replaceSelection("product");
            assertTrue(editor.templateModified(0) && editor.templateModified(1) && !editor.templateModified(2));
            key(editor, KeyEvent.VK_ENTER, 0);
            assertFalse(editor.templateActive(), "Enter confirms the whole template");
            assertEquals(List.of(1, EditorComponent.TEMPLATE_CONFIRM), left, "the host was asked to leave the name field, then to confirm");
            assertEquals(1, ended.get());
            String expected = "public long product(int i, int j) {\n    return 0;\n}\n";
            assertEquals(expected, editor.getText(), "unvisited fields keep their values; no line was inserted");
            assertEquals(expected.indexOf("0;") + 1, editor.caretOffset(), "the caret ends after the returned expression");
        });
    }

    @Test void theHostCanRefuseToLeaveOrConfirm() throws Exception {
        edt(() -> {
            var editor = new EditorComponent();
            editor.setText(METHOD);
            editor.startTemplate(List.of(List.of(at("int")), List.of(at("multiply"))), null, index -> {},
                    (index, next) -> next != EditorComponent.TEMPLATE_CONFIRM, () -> {});
            key(editor, KeyEvent.VK_ENTER, 0);
            assertTrue(editor.templateActive(), "an invalid value keeps the session");
            assertEquals(METHOD, editor.getText());
        });
    }

    @Test void linkedOccurrencesChangeTogetherAndRangesFollowDeletionPasteAndUnicode() throws Exception {
        edt(() -> {
            var editor = new EditorComponent();
            editor.setText("int x = x; return 0;");
            editor.startTemplate(List.of(List.of(range(4, 5), range(8, 9)), List.of(range(18, 19))));
            editor.replaceSelection("");
            assertEquals("int  = ; return 0;", editor.getText(), "a field can be emptied");
            editor.replaceSelection("caffè😀");
            assertEquals("int caffè😀 = caffè😀; return 0;", editor.getText(), "typing into an empty field fills every occurrence");
            assertEquals(List.of("caffè😀", "caffè😀"), editor.templateRanges(0).stream()
                    .map(r -> editor.getText().substring(r.start(), r.end())).toList());
            assertTrue(editor.advanceTemplate(false));
            assertEquals("0", editor.getSelectedText(), "the next field moved with the longer text");
            editor.replaceSelection("total * 2");
            assertEquals("total * 2", editor.templateText(1));
        });
    }

    @Test void theHostReplacesADerivedFieldWithoutEndingTheTemplateOrClaimingTheUsersEdit() throws Exception {
        edt(() -> {
            var editor = method(new ArrayList<>(), new ArrayList<>(), new AtomicInteger());
            editor.replaceSelection("String");
            assertTrue(editor.replaceTemplateText(4, "null"));
            assertTrue(editor.templateActive());
            assertEquals("public String".length(), editor.caretOffset(), "the caret stays where the user typed");
            assertEquals("null", editor.templateText(4));
            assertFalse(editor.templateModified(4), "a value the host wrote is still the host's");
            editor.setTemplateErrors(Map.of(2, "Duplicate parameter name"));
            assertEquals(Map.of(2, "Duplicate parameter name"), editor.templateErrors());
        });
    }

    @Test void identicalTextIsNotTheSameFieldAndAMultilineEditKeepsTheOthers() throws Exception {
        edt(() -> {
            var editor = new EditorComponent();
            editor.setText("int value; int other;");
            int first = editor.getText().indexOf("int");
            int second = editor.getText().indexOf("int", first + 1);
            editor.startTemplate(List.of(List.of(range(first, first + 3)), List.of(range(second, second + 3))));
            editor.replaceSelection("long");
            assertEquals("long", editor.templateText(0));
            assertEquals("int", editor.templateText(1), "the other int is a different field");
            assertTrue(editor.advanceTemplate(false));
            assertEquals(second + 1, editor.currentTemplateRanges().getFirst().start(), "the longer replacement shifts the other field");
            editor.replaceSelection("byte\n    ");
            assertTrue(editor.templateText(1).startsWith("byte"), editor.templateText(1));
            assertEquals("long", editor.templateText(0));
            assertTrue(editor.templateActive());
        });
    }

    @Test void arrowsEditInsideTheFieldAndAClickActivatesAnotherField() throws Exception {
        edt(() -> {
            var editor = method(new ArrayList<>(), new ArrayList<>(), new AtomicInteger());
            key(editor, KeyEvent.VK_LEFT, 0);
            key(editor, KeyEvent.VK_RIGHT, 0);
            assertEquals(METHOD, editor.getText(), "motion keys insert nothing");
            assertTrue(editor.templateActive(), "moving inside the active field keeps the session");
            assertEquals(0, editor.templateIndex());
            var window = new JFrame();
            try {
                window.add(editor);
                window.setSize(900, 320);
                window.setVisible(true);
                editor.paintImmediately(0, 0, 900, 280);
                int name = METHOD.indexOf("multiply") + 2;
                Point point = pointFor(editor, name);
                editor.dispatchEvent(new MouseEvent(editor, MouseEvent.MOUSE_PRESSED, 0, 0,
                        point.x, point.y, 1, false, MouseEvent.BUTTON1));
                assertEquals(1, editor.templateIndex(), "a click on the name makes that field active");
                var nameRange = editor.templateRanges(1).getFirst();
                assertTrue(editor.caretOffset() >= nameRange.start() && editor.caretOffset() <= nameRange.end());
            } finally {
                window.dispose();
            }
        });
    }

    @Test void escapeBelongsToTheHostAndDoesNotConfirmOrInsert() throws Exception {
        edt(() -> {
            var ended = new AtomicInteger();
            var editor = method(new ArrayList<>(), new ArrayList<>(), ended);
            editor.replaceSelection("long");
            key(editor, KeyEvent.VK_ESCAPE, 0);
            assertTrue(editor.templateActive(), "the editor leaves cancellation to the host");
            assertEquals(0, ended.get());
            assertTrue(editor.getText().startsWith("public long multiply("));
        });
    }

    @Test void editingOutsideTheFieldsEndsTheTemplateOnce() throws Exception {
        edt(() -> {
            var ended = new AtomicInteger();
            var editor = method(new ArrayList<>(), new ArrayList<>(), ended);
            editor.gotoOffset(0);
            editor.replaceSelection("// ");
            assertFalse(editor.templateActive());
            assertEquals(1, ended.get());
            editor.startTemplate(List.of(List.of(range(0, 2))));
            editor.setText("new document");
            assertFalse(editor.templateActive());
            assertThrows(IllegalArgumentException.class, () -> editor.startTemplate(List.of(List.of(range(1, 99)))));
        });
    }

    private static EditorComponent.Selection at(String text) {
        int start = METHOD.indexOf(text);
        return range(start, start + text.length());
    }
    private static EditorComponent.Selection range(int start, int end) { return new EditorComponent.Selection(end, start); }
    private static Point pointFor(EditorComponent editor, int offset) {
        for (int y = 2; y < editor.getHeight(); y += 3) {
            for (int x = 2; x < editor.getWidth(); x += 2) {
                if (editor.offsetAt(new Point(x, y)) == offset) return new Point(x, y);
            }
        }
        throw new AssertionError("No painted point for offset " + offset);
    }

    private static void key(EditorComponent editor, int code, int modifiers) {
        editor.processKeyEvent(new KeyEvent(editor, KeyEvent.KEY_PRESSED, 0, modifiers, code, KeyEvent.CHAR_UNDEFINED));
    }
    private static void edt(Runnable body) throws Exception {
        var task = new FutureTask<Void>(() -> { body.run(); return null; });
        SwingUtilities.invokeAndWait(task); task.get();
    }
}
