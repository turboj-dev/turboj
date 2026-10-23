package com.turboj.ui;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.image.BufferedImage;

import javax.swing.SwingUtilities;

class EditorComponentTest {
    @Test
    void keyboardEditingPreservesUnicodeAndCrlf() throws Exception {
        SwingUtilities.invokeAndWait(
                () -> {
                    EditorComponent editor = new EditorComponent();
                    editor.setSize(900, 600);
                    editor.setText("a🙂\r\nb");
                    editor.gotoOffset(3);
                    key(editor, KeyEvent.VK_BACK_SPACE, 0);
                    assertEquals("a\r\nb", editor.getText());
                    editor.undo();
                    assertEquals("a🙂\r\nb", editor.getText());
                    editor.gotoLine(2);
                    key(editor, KeyEvent.VK_BACK_SPACE, 0);
                    assertEquals("a🙂b", editor.getText());
                    editor.undo();
                    editor.gotoOffset(3);
                    key(editor, KeyEvent.VK_DELETE, 0);
                    assertEquals("a🙂b", editor.getText());
                });
    }

    @Test
    void selectionIndentCommentsAndFindShareDocumentHistory() throws Exception {
        SwingUtilities.invokeAndWait(
                () -> {
                    EditorComponent editor = new EditorComponent();
                    editor.setSize(900, 600);
                    editor.setText("one\r\ntwo\r\n");
                    editor.selectAll();
                    editor.indent(false);
                    assertEquals("    one\r\n    two\r\n", editor.getText());
                    editor.selectAll();
                    editor.toggleComment();
                    assertEquals("    // one\r\n    // two\r\n", editor.getText());
                    editor.toggleComment();
                    assertEquals("    one\r\n    two\r\n", editor.getText());
                    assertTrue(editor.find("two", true));
                    assertEquals("two", editor.getSelectedText());
                    editor.replaceSelection("second");
                    assertTrue(editor.getText().contains("second"));
                    editor.undo();
                    assertTrue(editor.getText().contains("two"));
                });
    }

    @Test
    void paintingAndThemeChangesPreserveCaretAndSource() throws Exception {
        SwingUtilities.invokeAndWait(
                () -> {
                    EditorComponent editor = new EditorComponent();
                    editor.setSize(800, 500);
                    editor.setText("public class Hello {\n    String text = \"hello\";\n}\n");
                    editor.gotoLine(2);
                    String source = editor.getText();
                    int caret = editor.caretOffset();
                    BufferedImage image = new BufferedImage(800, 500, BufferedImage.TYPE_INT_RGB);
                    var graphics = image.createGraphics();
                    editor.paint(graphics);
                    graphics.dispose();
                    editor.setPalette(
                            Color.BLACK,
                            Color.ORANGE,
                            Color.YELLOW,
                            Color.WHITE,
                            Color.GRAY,
                            Color.BLUE,
                            Color.WHITE,
                            Color.RED,
                            false);
                    graphics = image.createGraphics();
                    editor.paint(graphics);
                    graphics.dispose();
                    assertEquals(source, editor.getText());
                    assertEquals(caret, editor.caretOffset());
                    assertTrue(editor.lastPaintNanos() > 0);
                    editor.replaceSelection("// inserted\n");
                    graphics = image.createGraphics();
                    editor.paint(graphics);
                    graphics.dispose();
                    assertTrue(editor.inputToPaintSamples().length > 0);
                });
    }

    @Test
    void autoIndentAndSelectionByKeyboardWork() throws Exception {
        SwingUtilities.invokeAndWait(
                () -> {
                    EditorComponent editor = new EditorComponent();
                    editor.setSize(800, 500);
                    editor.setText("class Hello {");
                    editor.gotoOffset(editor.getText().length());
                    key(editor, KeyEvent.VK_ENTER, 0);
                    assertEquals("class Hello {\n    ", editor.getText());
                    editor.replaceSelection("word");
                    key(
                            editor,
                            KeyEvent.VK_LEFT,
                            InputEvent.SHIFT_DOWN_MASK | InputEvent.ALT_DOWN_MASK);
                    assertEquals("word", editor.getSelectedText());
                    editor.replaceSelection("value");
                    assertTrue(editor.getText().endsWith("value"));
                });
    }

    private static void key(EditorComponent editor, int code, int modifiers) {
        editor.processKeyEvent(
                new KeyEvent(
                        editor,
                        KeyEvent.KEY_PRESSED,
                        System.currentTimeMillis(),
                        modifiers,
                        code,
                        KeyEvent.CHAR_UNDEFINED));
    }
}
