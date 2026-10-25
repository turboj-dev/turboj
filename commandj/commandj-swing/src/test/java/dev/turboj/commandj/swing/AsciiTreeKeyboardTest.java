package dev.turboj.commandj.swing;

import java.awt.ComponentOrientation;
import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.concurrent.FutureTask;
import javax.swing.*;
import javax.swing.plaf.InputMapUIResource;
import javax.swing.tree.DefaultMutableTreeNode;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AsciiTreeKeyboardTest {
    @Test void arrowsExpandCollapseAndNavigateRealTreePathsUnderAquaBindings() throws Exception {
        edt(() -> {
            Object previous = UIManager.get("Tree.focusInputMap");
            InputMap aqua = new InputMapUIResource();
            aqua.put(KeyStroke.getKeyStroke("LEFT"), "aquaCollapseNode");
            aqua.put(KeyStroke.getKeyStroke("RIGHT"), "aquaExpandNode");
            aqua.put(KeyStroke.getKeyStroke("alt RIGHT"), "aquaFullyExpandNode");
            aqua.put(KeyStroke.getKeyStroke("meta A"), "selectAll");
            UIManager.put("Tree.focusInputMap", aqua);
            try {
                JTree tree = tree();
                // Aqua may leave these old component actions behind when changing UI.
                tree.getActionMap().put("aquaExpandNode", new AbstractAction() {
                    @Override public void actionPerformed(ActionEvent e) { fail("Removed Aqua delegate invoked"); }
                });
                AsciiStyle.apply(tree, AsciiPalette.defaults());
                tree.setSelectionRow(0); tree.collapseRow(0);
                assertEquals(1, tree.getRowCount());
                fire(tree, "RIGHT"); assertTrue(tree.isExpanded(0)); assertEquals(3, tree.getRowCount());
                fire(tree, "RIGHT"); assertEquals(1, tree.getLeadSelectionRow());
                fire(tree, "LEFT"); assertEquals(0, tree.getLeadSelectionRow());
                fire(tree, "LEFT"); assertFalse(tree.isExpanded(0)); assertEquals(1, tree.getRowCount());
                fire(tree, "KP_RIGHT"); assertTrue(tree.isExpanded(0));
                fire(tree, "END"); assertEquals(2, tree.getLeadSelectionRow());
                fire(tree, "HOME"); assertEquals(0, tree.getLeadSelectionRow());
                fire(tree, "DOWN"); assertEquals(1, tree.getLeadSelectionRow());
                fire(tree, "UP"); assertEquals(0, tree.getLeadSelectionRow());
                assertEquals("selectAll", tree.getInputMap().get(KeyStroke.getKeyStroke("meta A")));
                InputMap keys = SwingUtilities.getUIInputMap(tree, JComponent.WHEN_FOCUSED);
                for (KeyStroke stroke : keys.allKeys()) {
                    assertFalse(keys.get(stroke).toString().startsWith("aqua"));
                    assertNotNull(tree.getActionMap().get(keys.get(stroke)));
                }
                assertSame(aqua, UIManager.get("Tree.focusInputMap"));
            } finally { UIManager.put("Tree.focusInputMap", previous); }
        });
    }

    @Test void orientationChangesKeepCompatibleExpansionBindings() throws Exception {
        edt(() -> {
            JTree tree = tree(); tree.setUI(new AsciiStyle.Tree());
            tree.setSelectionRow(0); tree.collapseRow(0);
            tree.setComponentOrientation(ComponentOrientation.RIGHT_TO_LEFT);
            fire(tree, "LEFT"); assertTrue(tree.isExpanded(0));
            fire(tree, "RIGHT"); assertFalse(tree.isExpanded(0));
            tree.setComponentOrientation(ComponentOrientation.LEFT_TO_RIGHT);
            fire(tree, "RIGHT"); assertTrue(tree.isExpanded(0));
            fire(tree, "LEFT"); assertFalse(tree.isExpanded(0));
        });
    }

    @Test void themeReapplicationPreservesApplicationEnterAndEscapeBindings() throws Exception {
        edt(() -> {
            JTree tree = tree();
            int[] opened = {0};
            tree.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "openFile");
            tree.getInputMap().put(KeyStroke.getKeyStroke("ESCAPE"), "focusEditor");
            tree.getActionMap().put("openFile", new AbstractAction() {
                @Override public void actionPerformed(ActionEvent event) { opened[0]++; }
            });
            for (int i = 0; i < 2; i++) {
                AsciiStyle.apply(tree, AsciiPalette.defaults());
                fire(tree, "ENTER");
                assertEquals("focusEditor", tree.getInputMap().get(KeyStroke.getKeyStroke("ESCAPE")));
            }
            assertEquals(2, opened[0]);
        });
    }

    private static JTree tree() {
        DefaultMutableTreeNode root = new DefaultMutableTreeNode("project");
        root.add(new DefaultMutableTreeNode("src")); root.add(new DefaultMutableTreeNode("pom.xml"));
        return new JTree(root);
    }

    private static void fire(JTree tree, String key) {
        KeyStroke stroke = KeyStroke.getKeyStroke(key);
        Object name = tree.getInputMap().get(stroke);
        Action action = tree.getActionMap().get(name);
        assertNotNull(action, key);
        KeyEvent event = new KeyEvent(tree, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), stroke.getModifiers(),
                stroke.getKeyCode(), KeyEvent.CHAR_UNDEFINED);
        assertTrue(SwingUtilities.notifyAction(action, stroke, event, tree, stroke.getModifiers()), key);
    }

    private static void edt(Runnable task) throws Exception {
        FutureTask<Void> future = new FutureTask<>(() -> { task.run(); return null; });
        SwingUtilities.invokeAndWait(future); future.get();
    }
}
