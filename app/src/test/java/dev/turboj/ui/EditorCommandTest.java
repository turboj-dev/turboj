package dev.turboj.ui;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.event.KeyEvent;
import javax.swing.SwingUtilities;
import java.util.concurrent.FutureTask;
import org.junit.jupiter.api.Test;

class EditorCommandTest {
    @Test
    void lineCommandsPreserveContentAndUndoWithBothLineEndings() throws Exception {
        edt(() -> {
            for (String nl : new String[] {"\n", "\r\n"}) {
                var e = new EditorComponent();
                String original = "alpha" + nl + "beta" + nl + "gamma";
                e.setText(original);
                e.gotoOffset(original.indexOf("beta") + 2);
                e.duplicateLine();
                assertEquals("alpha" + nl + "beta" + nl + "beta" + nl + "gamma", e.getText());
                e.undo();
                assertEquals(original, e.getText());
                e.gotoOffset(original.indexOf("beta") + 2);
                e.moveLine(-1);
                assertEquals("beta" + nl + "alpha" + nl + "gamma", e.getText());
                assertEquals(2, e.caretOffset());
                e.undo();
                assertEquals(original, e.getText());
                e.gotoOffset(original.indexOf("beta"));
                e.deleteLine();
                assertEquals("alpha" + nl + "gamma", e.getText());
                e.undo();
                assertEquals(original, e.getText());
            }
        });
    }

    @Test
    void externalDispatchPreventsLegacyUndoButKeepsTyping() throws Exception {
        edt(() -> {
            var e = new EditorComponent();
            e.setText("a");
            e.gotoOffset(1);
            e.replaceSelection("b");
            e.setExternalCommandBindings(true);
            e.processKeyEvent(new KeyEvent(e, KeyEvent.KEY_PRESSED, 0, KeyEvent.META_DOWN_MASK,
                    KeyEvent.VK_Z, 'z'));
            assertEquals("ab", e.getText());
            e.processKeyEvent(new KeyEvent(e, KeyEvent.KEY_TYPED, 0, 0, KeyEvent.VK_UNDEFINED,
                    'c'));
            assertEquals("abc", e.getText());
            e.setReadOnly(true);
            e.duplicateLine();
            e.deleteLine();
            e.moveLine(1);
            assertEquals("abc", e.getText());
        });
    }

    private static void edt(Runnable r) throws Exception {
        var t = new FutureTask<Void> (() -> {
            r.run();
            return null;
        });
        SwingUtilities.invokeAndWait(t);
        t.get();
    }
}
