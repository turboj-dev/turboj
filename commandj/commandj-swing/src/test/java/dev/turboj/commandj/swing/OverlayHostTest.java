package dev.turboj.commandj.swing;

import java.awt.*;
import java.util.concurrent.FutureTask;
import javax.swing.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OverlayHostTest {
    @Test
    void internalStackUsesOneRootAndClampsToViewport() throws Exception {
        edt(() -> {
            var root = new JRootPane();
            root.setSize(640, 480);
            root.getLayeredPane().setSize(640, 480);
            var parent = new EmbeddedDialog(root, "Parent", false);
            var field = new JTextField("Content");
            parent.add(field);
            parent.setSize(900, 700);
            parent.setVisible(true);
            parent.doLayout();
            assertInstanceOf(JPanel.class, parent);
            assertFalse(JInternalFrame.class.isAssignableFrom(parent.getClass()));
            assertInstanceOf(AsciiBorder.class, parent.getBorder());
            assertEquals("Parent", ((AsciiBorder) parent.getBorder()).title());
            assertEquals(javax.accessibility.AccessibleRole.DIALOG, parent.getAccessibleContext().getAccessibleRole());
            assertTrue(parent.closeButton().isFocusable());
            assertTrue(parent.closeButton().getHeight() > 0);
            assertTrue(SwingUtilities.isDescendingFrom(parent, root));
            assertTrue(parent.getWidth() <= 640 && parent.getHeight() <= 480);
            var nested = new EmbeddedDialog(field, "Nested", false);
            nested.setSize(400, 200);
            nested.setVisible(true);
            assertEquals(2, OverlayHost.dialogs(root).size());
            assertSame(OverlayHost.forOwner(root), OverlayHost.forOwner(field));
            assertTrue(parent.getGlassPane().isVisible());
            assertEquals(Boolean.TRUE, root.getClientProperty("commandj.overlay.active"));
            nested.dispose();
            assertFalse(parent.getGlassPane().isVisible());
            assertEquals(1, OverlayHost.dialogs(root).size());
            parent.dispose();
            assertFalse(OverlayHost.active(root));
            assertEquals(Boolean.FALSE, root.getClientProperty("commandj.overlay.active"));
        });
    }

    @Test
    void modalSecondaryLoopKeepsEdtAliveAndReturnsChoice() throws Exception {
        edt(() -> {
            var root = new JRootPane();
            root.setSize(640, 480);
            root.getLayeredPane().setSize(640, 480);
            SwingUtilities.invokeLater(() -> {
                var dialog = OverlayHost.dialogs(root).getFirst();
                ((JOptionPane) dialog.getContentPane()).setValue(JOptionPane.OK_OPTION);
            });
            assertEquals(JOptionPane.OK_OPTION, EmbeddedOptionPane.showConfirmDialog(root,
                    "Continue?", "Check", JOptionPane.OK_CANCEL_OPTION));
            assertFalse(OverlayHost.active(root));
        });
    }

    @Test
    void textSelectionAndBackgroundStayReadableAcrossPalettes() throws Exception {
        edt(() -> {
            var root = new JRootPane();
            root.setSize(640, 480);
            root.getLayeredPane().setSize(640, 480);
            var dialog = new EmbeddedDialog(root, "Theme", false);
            var field = new JTextField("Hello");
            dialog.add(field);
            dialog.pack();
            dialog.setVisible(true);
            for (Color[] colors : new Color[][] {{new Color(0xAAAAAA), Color.BLACK, Color.BLACK},
                    {new Color(0x292F3B), new Color(0xD8DEE9), new Color(0x596579)},
                    {new Color(0xE1E4E8), new Color(0x1D2632), new Color(0x535E6D)},
                    {Color.WHITE, Color.BLACK, new Color(0x888888)}}) {
                OverlayHost.forOwner(root).setTheme(colors[0], colors[1], colors[2]);
                assertEquals(colors[0], field.getBackground());
                assertEquals(colors[1], field.getForeground());
                assertNotEquals(field.getSelectedTextColor(), field.getSelectionColor());
                if (colors[2].equals(new Color(0x888888))) assertEquals(Color.BLACK, field.getSelectedTextColor());
            }
            dialog.dispose();
        });
    }

    @Test
    void hidingReleasesTheHostAndAllowsShowingAgain() throws Exception {
        edt(() -> {
            var root = new JRootPane(); root.setSize(640, 480); root.getLayeredPane().setSize(640, 480);
            var dialog = new EmbeddedDialog(root, "Reusable", false); dialog.setSize(300, 150);
            dialog.setVisible(true); assertTrue(OverlayHost.active(root));
            dialog.setVisible(false); assertFalse(OverlayHost.active(root));
            dialog.setVisible(true); assertEquals(1, OverlayHost.dialogs(root).size());
            dialog.dispose(); assertFalse(OverlayHost.active(root));
        });
    }

    private static void edt(Runnable task) throws Exception {
        var future = new FutureTask<Void> (() -> {
            task.run();
            return null;
        });
        SwingUtilities.invokeAndWait(future);
        future.get();
    }
}
