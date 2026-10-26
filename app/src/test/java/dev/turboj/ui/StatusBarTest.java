package dev.turboj.ui;

import static org.junit.jupiter.api.Assertions.*;

import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;

class StatusBarTest {
    private static final String KEYS = "Tab: next · Shift+Tab: previous · Enter: confirm · Esc: cancel";

    @Test
    void aHeldMessageGivesWayOnlyToProblems() throws Exception {
        SwingUtilities.invokeAndWait(
                () -> {
                    var status = new StatusBar();
                    status.hold(KEYS);
                    status.flash("Refactoring applied", StatusBar.Level.SUCCESS);
                    assertEquals(KEYS, status.text(StatusBar.Segment.MESSAGE), "routine feedback does not cover the keys");
                    assertEquals("Refactoring applied", status.lastMessage(), "it is still in the history");
                    status.flash("Duplicate parameter name", StatusBar.Level.WARNING);
                    assertEquals("Duplicate parameter name", status.text(StatusBar.Segment.MESSAGE));
                    status.hold(KEYS);
                    status.release();
                    assertEquals("", status.text(StatusBar.Segment.MESSAGE), "release clears the keys it showed");
                    status.flash("Saved Calculator.java", StatusBar.Level.NORMAL);
                    assertEquals("Saved Calculator.java", status.text(StatusBar.Segment.MESSAGE));
                });
    }
}
