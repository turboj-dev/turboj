package dev.turboj.ui;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.concurrent.FutureTask;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class EditorTemplateTest {
    @Test void tabTracksEditsAndLinkedOccurrencesAreOneUndo() throws Exception {
        edt(() -> {
            var editor = new EditorComponent();
            editor.setText("int x = x; return 0;");
            editor.startTemplate(List.of(List.of(range(4, 5), range(8, 9)), List.of(range(18, 19))));
            editor.replaceSelection("number");
            assertEquals("int number = number; return 0;", editor.getText());
            assertTrue(editor.advanceTemplate(false));
            assertEquals("0", editor.getSelectedText());
            assertTrue(editor.advanceTemplate(true));
            assertEquals("number", editor.getSelectedText());
            assertEquals(2, editor.caretCount());
            editor.undo();
            assertEquals("int x = x; return 0;", editor.getText());
            assertFalse(editor.templateActive());
            editor.redo(); assertEquals("int number = number; return 0;", editor.getText());
        });
    }

    @Test void editingOutsideTemplateOrChangingDocumentCancelsStops() throws Exception {
        edt(() -> {
            var editor = new EditorComponent(); editor.setText("return 0;");
            editor.startTemplate(List.of(List.of(range(7, 8))));
            editor.gotoOffset(0); editor.replaceSelection("// ");
            assertFalse(editor.templateActive()); assertFalse(editor.advanceTemplate(false));
            editor.startTemplate(List.of(List.of(range(10, 11))));
            editor.setText("new document"); assertFalse(editor.templateActive());
        });
    }

    @Test void unicodeAndReverseTraversalKeepExactRanges() throws Exception {
        edt(() -> {
            var editor = new EditorComponent(); editor.setText("😀 value\r\nreturn 0;");
            editor.startTemplate(List.of(List.of(range(3, 8)), List.of(range(17, 18))));
            editor.replaceSelection("caffè"); assertTrue(editor.advanceTemplate(false));
            assertEquals("0", editor.getSelectedText());
            assertTrue(editor.advanceTemplate(false)); assertFalse(editor.templateActive());
            assertEquals(18, editor.caretOffset());
            assertThrows(IllegalArgumentException.class, () -> editor.startTemplate(List.of(List.of(range(1, 2)))));
        });
    }

    private static EditorComponent.Selection range(int start, int end) { return new EditorComponent.Selection(end, start); }
    private static void edt(Runnable body) throws Exception {
        var task = new FutureTask<Void>(() -> { body.run(); return null; });
        SwingUtilities.invokeAndWait(task); task.get();
    }
}
