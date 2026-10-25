package dev.turboj.console;

import java.awt.event.KeyEvent;

/** Local editor gesture: Escape arms only the next typed character, never a physical colon key. */
public final class OutputCommandGesture {
    private boolean armed;

    public void arm() { armed = true; }
    public void reset() { armed = false; }

    public boolean accepts(KeyEvent event, boolean composing) {
        if (!armed) return false;
        if (composing || event.isMetaDown() || event.isControlDown() || event.isAltDown()
                || event.isAltGraphDown()) { reset(); return false; }
        if (event.getID() == KeyEvent.KEY_TYPED) {
            reset();
            return event.getKeyChar() == ':';
        }
        if (event.getID() == KeyEvent.KEY_PRESSED) {
            int code = event.getKeyCode();
            if (code != KeyEvent.VK_SHIFT && (event.isActionKey()
                    || code == KeyEvent.VK_ENTER || code == KeyEvent.VK_TAB
                    || code == KeyEvent.VK_BACK_SPACE || code == KeyEvent.VK_DELETE
                    || code == KeyEvent.VK_ESCAPE)) reset();
        }
        return false;
    }
}
