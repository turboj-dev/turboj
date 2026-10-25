package dev.turboj.commandj.swing;

import java.awt.event.ActionEvent;
import java.awt.event.KeyEvent;
import java.util.concurrent.FutureTask;
import javax.swing.*;
import javax.swing.plaf.InputMapUIResource;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Headless binding/action tests; the app's keyboard smoke checks real popup focus. */
class AsciiComboKeyboardTest {
    @Test void basicDelegateReplacesIncompatibleHostMapWithoutChangingGlobalDefaults() throws Exception {
        edt(() -> {
            Object previous = UIManager.get("ComboBox.ancestorInputMap");
            InputMap aqua = new InputMapUIResource();
            aqua.put(KeyStroke.getKeyStroke("DOWN"), "aquaSelectNext");
            aqua.put(KeyStroke.getKeyStroke("alt DOWN"), "aquaOpenPopupOrhighlightLast");
            aqua.put(KeyStroke.getKeyStroke("ESCAPE"), "aquaHidePopup");
            UIManager.put("ComboBox.ancestorInputMap", aqua);
            try {
                JComboBox<String> combo = styled();
                for (String key : new String[] {"UP", "DOWN", "KP_UP", "KP_DOWN", "alt DOWN", "alt UP",
                        "alt KP_UP", "alt KP_DOWN", "HOME", "END", "PAGE_UP", "PAGE_DOWN", "F4", "SPACE", "ENTER", "ESCAPE"}) {
                    Object name = combo.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).get(KeyStroke.getKeyStroke(key));
                    assertNotNull(name, key);
                    assertFalse(name.toString().startsWith("aqua"), key);
                    assertNotNull(combo.getActionMap().get(name), key + " must reach a real Basic action");
                }
                assertSame(aqua, UIManager.get("ComboBox.ancestorInputMap"), "Other native controls keep their LAF map");
            } finally { UIManager.put("ComboBox.ancestorInputMap", previous); }
        });
    }

    @Test void homeEndAndPageKeysExecuteRealSelectionActions() throws Exception {
        edt(() -> {
            Object previous = UIManager.get("ComboBox.noActionOnKeyNavigation");
            UIManager.put("ComboBox.noActionOnKeyNavigation", false);
            try {
                JComboBox<String> combo = styled();
                combo.setMaximumRowCount(2);
                fire(combo, "END"); assertEquals(3, combo.getSelectedIndex());
                fire(combo, "HOME"); assertEquals(0, combo.getSelectedIndex());
                fire(combo, "PAGE_DOWN"); assertEquals(2, combo.getSelectedIndex());
                fire(combo, "PAGE_UP"); assertEquals(0, combo.getSelectedIndex());
            } finally { UIManager.put("ComboBox.noActionOnKeyNavigation", previous); }
        });
    }

    @Test void clientBindingsSurviveUiReinstallationAndUnboundLettersKeepTypeAhead() throws Exception {
        edt(() -> {
            JComboBox<String> combo = styled();
            KeyStroke down = KeyStroke.getKeyStroke("DOWN");
            combo.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).put(down, "clientNext");
            int[] invoked = {0};
            combo.getActionMap().put("clientNext", new AbstractAction() {
                @Override public void actionPerformed(ActionEvent event) { invoked[0]++; }
            });
            AsciiStyle.apply(combo, AsciiPalette.defaults());
            fire(combo, "DOWN"); assertEquals(1, invoked[0]);
            assertEquals("clientNext", combo.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).get(down));
            assertNull(SwingUtilities.getUIInputMap(combo, JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                    .get(KeyStroke.getKeyStroke(KeyEvent.VK_V, 0)), "Letter input remains with the type-ahead listener");
            assertTrue(combo.selectWithKeyChar('v'));
            assertEquals("VS Code", combo.getSelectedItem());
        });
    }

    @Test void editableComboRetainsTextInputAndSpaceDoesNotOpenPopup() throws Exception {
        edt(() -> {
            JComboBox<String> combo = styled(); combo.setEditable(true);
            JTextField editor = (JTextField) combo.getEditor().getEditorComponent();
            editor.setText("custom"); editor.setCaretPosition(editor.getDocument().getLength());
            editor.replaceSelection(" profile");
            fire(combo, "SPACE");
            assertFalse(combo.isPopupVisible());
            assertEquals("custom profile", editor.getText());
            assertEquals("IntelliJ", combo.getSelectedItem());
            assertNotNull(editor.getActionMap().get(editor.getInputMap().get(KeyStroke.getKeyStroke("LEFT"))),
                    "Text editor retains its focused navigation actions");
        });
    }

    private static JComboBox<String> styled() {
        JComboBox<String> combo = new JComboBox<>(new String[] {"IntelliJ", "Eclipse", "VS Code", "Classic"});
        combo.setUI(new AsciiStyle.Combo());
        return combo;
    }

    private static void fire(JComboBox<?> combo, String key) {
        KeyStroke stroke = KeyStroke.getKeyStroke(key);
        Object name = combo.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).get(stroke);
        Action action = combo.getActionMap().get(name);
        assertNotNull(action, key);
        KeyEvent event = new KeyEvent(combo, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), stroke.getModifiers(),
                stroke.getKeyCode(), KeyEvent.CHAR_UNDEFINED);
        assertTrue(SwingUtilities.notifyAction(action, stroke, event, combo, stroke.getModifiers()), key);
    }

    private static void edt(Runnable task) throws Exception {
        FutureTask<Void> future = new FutureTask<>(() -> { task.run(); return null; });
        SwingUtilities.invokeAndWait(future); future.get();
    }
}
