package dev.turboj.console;

import java.awt.Canvas;
import java.awt.event.KeyEvent;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OutputCommandGestureTest {
    private final OutputCommandGesture gesture = new OutputCommandGesture();
    private final Canvas source = new Canvas();
    private KeyEvent typed(char character, int modifiers) {
        return new KeyEvent(source, KeyEvent.KEY_TYPED, 0, modifiers, KeyEvent.VK_UNDEFINED, character);
    }
    private KeyEvent pressed(int code, int modifiers) {
        return new KeyEvent(source, KeyEvent.KEY_PRESSED, 0, modifiers, code, KeyEvent.CHAR_UNDEFINED);
    }

    @Test void normalColonIsSourceText() {
        assertFalse(gesture.accepts(typed(':', 0), false));
    }
    @Test void armedColonUsesTheTypedCharacterAcrossLayouts() {
        gesture.arm();
        assertFalse(gesture.accepts(pressed(KeyEvent.VK_SHIFT, KeyEvent.SHIFT_DOWN_MASK), false));
        assertFalse(gesture.accepts(pressed(KeyEvent.VK_PERIOD, KeyEvent.SHIFT_DOWN_MASK), false));
        assertTrue(gesture.accepts(typed(':', KeyEvent.SHIFT_DOWN_MASK), false));
        assertFalse(gesture.accepts(typed(':', 0), false));
    }
    @Test void ordinaryTypingDisarmsTheGesture() {
        gesture.arm();
        assertFalse(gesture.accepts(typed('a', 0), false));
        assertFalse(gesture.accepts(typed(':', 0), false));
    }
    @Test void NavigationOrEditingBeforeColonDisarms() {
        for (int key : new int[]{KeyEvent.VK_LEFT, KeyEvent.VK_HOME, KeyEvent.VK_ENTER,
                KeyEvent.VK_BACK_SPACE, KeyEvent.VK_DELETE, KeyEvent.VK_TAB, KeyEvent.VK_ESCAPE}) {
            gesture.arm();
            assertFalse(gesture.accepts(pressed(key, 0), false));
            assertFalse(gesture.accepts(typed(':', 0), false), "key " + key);
        }
    }
    @Test void CompositionAndSystemModifiersRetainInput() {
        gesture.arm();
        assertFalse(gesture.accepts(typed(':', 0), true));
        assertFalse(gesture.accepts(typed(':', 0), false));
        for (int modifiers : new int[]{KeyEvent.META_DOWN_MASK, KeyEvent.CTRL_DOWN_MASK,
                KeyEvent.ALT_DOWN_MASK, KeyEvent.ALT_GRAPH_DOWN_MASK}) {
            gesture.arm();
            assertFalse(gesture.accepts(typed(':', modifiers), false));
            assertFalse(gesture.accepts(typed(':', 0), false));
        }
    }
    @Test void FocusOrAnotherCommandCancelsArmedGesture() {
        gesture.arm(); gesture.reset();
        assertFalse(gesture.accepts(typed(':', 0), false));
    }
}
