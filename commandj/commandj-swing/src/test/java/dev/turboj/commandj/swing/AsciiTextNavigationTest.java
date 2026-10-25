package dev.turboj.commandj.swing;

import java.awt.Dimension;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;
import java.util.concurrent.FutureTask;
import javax.swing.*;
import javax.swing.plaf.InputMapUIResource;
import javax.swing.text.JTextComponent;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AsciiTextNavigationTest {
    @Test void basicTextNavigationMovesCaretAndRetainsTypedInputWithAquaMap() throws Exception {
        edt(() -> {
            Object previous = UIManager.get("TextArea.focusInputMap");
            InputMap aqua = new InputMapUIResource();
            aqua.put(KeyStroke.getKeyStroke("UP"), "aqua-move-up");
            aqua.put(KeyStroke.getKeyStroke("DOWN"), "aqua-move-down");
            aqua.put(KeyStroke.getKeyStroke("PAGE_UP"), "aqua-page-up");
            aqua.put(KeyStroke.getKeyStroke("PAGE_DOWN"), "aqua-page-down");
            UIManager.put("TextArea.focusInputMap", aqua);
            try {
                JTextArea text = new JTextArea("first\nsecond\nthird\n".repeat(30));
                AsciiStyle.apply(text, AsciiPalette.defaults());
                text.setSize(220, 80);
                var image = new BufferedImage(220, 80, BufferedImage.TYPE_INT_RGB);
                var graphics = image.createGraphics(); text.paint(graphics); graphics.dispose();
                text.setCaretPosition(8);
                fire(text, KeyStroke.getKeyStroke("UP")); assertTrue(text.getCaretPosition() < 6);
                fire(text, KeyStroke.getKeyStroke("DOWN")); assertTrue(text.getCaretPosition() >= 6);
                int beforePage = text.getCaretPosition();
                fire(text, KeyStroke.getKeyStroke("PAGE_DOWN")); assertTrue(text.getCaretPosition() > beforePage);
                int afterPage = text.getCaretPosition();
                fire(text, KeyStroke.getKeyStroke("PAGE_UP")); assertTrue(text.getCaretPosition() < afterPage);
                int beforeTyping = text.getDocument().getLength();
                fire(text, KeyStroke.getKeyStroke('x'));
                assertEquals(beforeTyping + 1, text.getDocument().getLength(), "Typed text still uses the original keymap default action");
                assertSame(aqua, UIManager.get("TextArea.focusInputMap"));
            } finally { UIManager.put("TextArea.focusInputMap", previous); }
        });
    }

    @Test void aquaListHomeEndScrollWithoutChangingSelectionAndPreserveLocalKeys() throws Exception {
        edt(() -> {
            Object previous = UIManager.get("List.focusInputMap");
            InputMap aqua = new InputMapUIResource();
            aqua.put(KeyStroke.getKeyStroke("HOME"), "aquaHome");
            aqua.put(KeyStroke.getKeyStroke("END"), "aquaEnd");
            aqua.put(KeyStroke.getKeyStroke("DOWN"), "selectNextRow");
            UIManager.put("List.focusInputMap", aqua);
            try {
                String[] rows = new String[30];
                for (int i = 0; i < rows.length; i++) rows[i] = "result " + i;
                JList<String> list = new JList<>(rows);
                list.setFixedCellHeight(20); list.setFixedCellWidth(200);
                list.setSelectedIndex(12);
                list.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "openResult");
                AsciiStyle.apply(list, AsciiPalette.defaults());
                JViewport viewport = new JViewport();
                viewport.setExtentSize(new Dimension(200, 100)); viewport.setSize(200, 100);
                viewport.setView(list); list.setSize(200, 600);
                fire(list, KeyStroke.getKeyStroke("END"));
                assertTrue(viewport.getViewPosition().y > 0);
                assertEquals(29, list.getLastVisibleIndex());
                assertEquals(12, list.getSelectedIndex());
                fire(list, KeyStroke.getKeyStroke("HOME"));
                assertEquals(0, viewport.getViewPosition().y); assertEquals(12, list.getSelectedIndex());
                fire(list, KeyStroke.getKeyStroke("DOWN")); assertEquals(13, list.getSelectedIndex());
                assertEquals("openResult", list.getInputMap().get(KeyStroke.getKeyStroke("ENTER")));
                assertSame(aqua, UIManager.get("List.focusInputMap"));
            } finally { UIManager.put("List.focusInputMap", previous); }
        });
    }

    private static void fire(JComponent component, KeyStroke stroke) {
        Object name = component.getInputMap().get(stroke);
        Action action = component.getActionMap().get(name);
        assertNotNull(action, stroke.toString());
        boolean typed = stroke.getKeyCode() == KeyEvent.VK_UNDEFINED;
        KeyEvent event = new KeyEvent(component, typed ? KeyEvent.KEY_TYPED : KeyEvent.KEY_PRESSED,
                System.currentTimeMillis(), stroke.getModifiers(), stroke.getKeyCode(),
                typed ? stroke.getKeyChar() : KeyEvent.CHAR_UNDEFINED);
        assertTrue(SwingUtilities.notifyAction(action, stroke, event, component, stroke.getModifiers()));
    }

    private static void edt(Runnable task) throws Exception {
        FutureTask<Void> future = new FutureTask<>(() -> { task.run(); return null; });
        SwingUtilities.invokeAndWait(future); future.get();
    }
}
