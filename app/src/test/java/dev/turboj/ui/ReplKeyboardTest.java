package dev.turboj.ui;

import static org.junit.jupiter.api.Assertions.*;

import java.awt.event.KeyEvent;
import java.util.concurrent.FutureTask;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class ReplKeyboardTest {
    @Test
    void modifiedNavigationDoesNotReplaceTheDraftOrStartCompletion() throws Exception {
        var task = new FutureTask<Void>(() -> {
            var pane = new ReplPane();
            try {
                var input = ((ConsolePane)pane.getComponent(0)).inputField();
                for (int modifier : new int[]{KeyEvent.META_DOWN_MASK, KeyEvent.CTRL_DOWN_MASK, KeyEvent.ALT_DOWN_MASK, KeyEvent.SHIFT_DOWN_MASK}) {
                    for (int key : new int[]{KeyEvent.VK_UP, KeyEvent.VK_DOWN, KeyEvent.VK_TAB}) {
                        if (modifier == KeyEvent.SHIFT_DOWN_MASK && key == KeyEvent.VK_TAB) continue; // Native traversal acceptance covers this.
                        input.setText("unsubmitted Java");
                        var event = new KeyEvent(input, KeyEvent.KEY_PRESSED, 0, modifier, key, KeyEvent.CHAR_UNDEFINED);
                        for (var listener : input.getKeyListeners()) listener.keyPressed(event);
                        assertFalse(event.isConsumed(), "Modified navigation belongs to CommandJ or the text widget");
                        assertEquals("unsubmitted Java", input.getText());
                        assertTrue(input.isEnabled() && input.isEditable(), "No completion may start from a modified Tab");
                    }
                }
            } finally { pane.close(); }
            return null;
        });
        SwingUtilities.invokeAndWait(task); task.get();
    }
}
