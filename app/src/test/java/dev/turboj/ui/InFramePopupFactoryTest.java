package dev.turboj.ui;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.*;
import javax.swing.*;
import org.junit.jupiter.api.Test;

class InFramePopupFactoryTest {
    @Test void oversizedMenusStayWithinHostAndScrollInsteadOfOpeningNativeWindows() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            PopupFactory previous = PopupFactory.getSharedInstance();
            try {
                var root = new JRootPane(); root.setSize(320, 180); root.doLayout();
                var owner = new JButton("Options"); root.getContentPane().add(owner);
                InFramePopupFactory.install(root);
                var menu = new JPopupMenu();
                for (int i = 0; i < 50; i++) menu.add(new JMenuItem("Theme " + i));
                Popup popup = PopupFactory.getSharedInstance().getPopup(owner, menu, 1000, 1000);
                popup.show();
                var layer = root.getLayeredPane();
                Component[] children = layer.getComponentsInLayer(JLayeredPane.POPUP_LAYER);
                assertEquals(1, children.length);
                Component surface = children[0];
                assertEquals("turboj-in-frame-popup", surface.getName());
                assertTrue(new Rectangle(0, 0, layer.getWidth(), layer.getHeight()).contains(surface.getBounds()));
                assertTrue(SwingUtilities.isDescendingFrom(menu, surface));
                assertNotNull(SwingUtilities.getAncestorOfClass(JScrollPane.class, menu));
                assertNull(SwingUtilities.getWindowAncestor(menu));
                popup.hide(); assertEquals(0, layer.getComponentsInLayer(JLayeredPane.POPUP_LAYER).length);
                assertNull(menu.getParent());
            } finally { PopupFactory.setSharedInstance(previous); }
        });
    }

    @Test void comboPopupInsideInternalDialogUsesOuterApplicationLayer() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            PopupFactory previous = PopupFactory.getSharedInstance();
            try {
                var root = new JRootPane(); root.setSize(400, 240); root.doLayout();
                var internal = new JInternalFrame(); internal.setSize(300, 160);
                root.getLayeredPane().add(internal, JLayeredPane.MODAL_LAYER);
                var combo = new JComboBox<>(new String[] {"One", "Two"}); internal.add(combo);
                InFramePopupFactory.install(root);
                var choices = new JPopupMenu(); choices.add(new JMenuItem("One"));
                Popup popup = PopupFactory.getSharedInstance().getPopup(combo, choices, -50, -20); popup.show();
                assertEquals(1, root.getLayeredPane().getComponentsInLayer(JLayeredPane.POPUP_LAYER).length);
                assertEquals(0, internal.getLayeredPane().getComponentsInLayer(JLayeredPane.POPUP_LAYER).length);
                assertNull(SwingUtilities.getWindowAncestor(choices)); popup.hide();
            } finally { PopupFactory.setSharedInstance(previous); }
        });
    }
}
