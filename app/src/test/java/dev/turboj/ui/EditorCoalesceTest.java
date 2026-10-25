package dev.turboj.ui;

import java.util.concurrent.FutureTask;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EditorCoalesceTest {
    @Test void aggregateUndoRestoresOriginalSelectionAndRedoRestoresCurrentSelection() throws Exception {
        edt(() -> {
            var editor = new EditorComponent(); editor.setText("int a = a;\n");
            editor.gotoOffset(0); editor.replaceSelection("// previous\n");
            long checkpoint = editor.document().peekUndoTransactionId();
            int name = editor.getText().indexOf("a ="); editor.setSelection(name, name + 1);
            String before = editor.getText(); var location = editor.captureLocation();
            editor.replaceSelection("alpha");
            editor.gotoOffset(editor.getText().lastIndexOf('a')); editor.setSelection(editor.caretOffset(), editor.caretOffset() + 1);
            editor.replaceSelection("alpha");
            String after = editor.getText(); var afterLocation = editor.captureLocation(); long version = editor.version();
            long combined = editor.coalesceEdits(checkpoint, before, location);
            assertEquals(combined, editor.document().peekUndoTransactionId()); assertEquals(version, editor.version());
            editor.undo(); assertEquals(before, editor.getText()); assertEquals(location, editor.captureLocation()); assertEquals("a", editor.getSelectedText());
            editor.redo(); assertEquals(after, editor.getText()); assertEquals(afterLocation, editor.captureLocation());
            editor.undo(); editor.undo(); assertEquals("int a = a;\n", editor.getText());
        });
    }

    @Test void badBaselineDoesNotDiscardEditorUndoMetadata() throws Exception {
        edt(() -> {
            var editor = new EditorComponent(); editor.setText("abc"); editor.setSelection(1, 2);
            var before = editor.captureLocation(); editor.replaceSelection("XYZ"); long head = editor.document().peekUndoTransactionId();
            assertThrows(IllegalArgumentException.class, () -> editor.coalesceEdits(-1, "wrong", before));
            assertEquals(head, editor.document().peekUndoTransactionId());
            editor.undo(); assertEquals("abc", editor.getText()); assertEquals(before, editor.captureLocation());
        });
    }

    private static void edt(Runnable body) throws Exception {
        var task = new FutureTask<Void>(() -> { body.run(); return null; }); SwingUtilities.invokeAndWait(task); task.get();
    }
}
