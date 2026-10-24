package com.turboj.ui;

import static org.junit.jupiter.api.Assertions.*;

import com.turboj.ui.themes.Theme;

import org.junit.jupiter.api.Test;

import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.text.AttributedString;
import java.util.List;

import javax.swing.SwingUtilities;

class EditorAICompletionTest {
    @Test
    void multilineProjectionKeepsTheDocumentImmutableAndAcceptsInOneUndo() throws Exception {
        SwingUtilities.invokeAndWait(
                () -> {
                    String source =
                            "class Square {\n    int square(int value) {\n        \n    }\n}\n";
                    EditorComponent editor = editor(source, source.indexOf("        \n") + 8);
                    String suggestion = "int result = value * value;\n        return result;";
                    long version = editor.version();
                    int lines = editor.document().lineCount(), caret = editor.caretOffset();
                    var location = editor.captureLocation();
                    assertTrue(editor.setAIInlineCompletion(ai(editor, suggestion)));
                    assertTrue(editor.isAIInlineCompletion());
                    paint(editor);
                    assertEquals(source, editor.getText());
                    assertEquals(version, editor.version());
                    assertEquals(lines, editor.document().lineCount());
                    assertEquals(location, editor.captureLocation());
                    assertFalse(editor.document().canUndo());
                    key(editor, KeyEvent.VK_TAB);
                    assertEquals(
                            source.substring(0, caret) + suggestion + source.substring(caret),
                            editor.getText());
                    assertFalse(editor.isAIInlineCompletion());
                    editor.undo();
                    assertEquals(source, editor.getText());
                    assertFalse(editor.document().canUndo());
                    editor.redo();
                    assertTrue(editor.getText().contains(suggestion));
                });
    }

    @Test
    void sameLineSuffixMovesAfterVirtualTextAndMouseMappingRemainsOnRealSource() throws Exception {
        SwingUtilities.invokeAndWait(
                () -> {
                    String source = "int value = ;\nreturn value;";
                    int caret = source.indexOf(';');
                    EditorComponent editor = editor(source, caret);
                    Rectangle followingBefore = editor.offsetBounds(source.indexOf("return"));
                    assertTrue(editor.setAIInlineCompletion(ai(editor, "square(2)\n        + 1")));
                    Rectangle following = editor.offsetBounds(source.indexOf("return"));
                    assertEquals(followingBefore.y + following.height, following.y);
                    assertEquals(
                            source.indexOf("return"), editor.offsetAt(following.getLocation()));
                    // The first inserted row begins at the true caret; virtual text does not
                    // acquire offsets.
                    Rectangle caretBounds = editor.caretBounds();
                    assertEquals(
                            caret,
                            editor.offsetAt(
                                    new Point(
                                            caretBounds.x + caretBounds.width * 2,
                                            caretBounds.y + 1)));
                    // The pre-existing semicolon follows the last ghost row instead of being
                    // painted under it.
                    int endOfInsertionColumn = 11; // Eight spaces + "+ 1".
                    Rectangle origin = editor.offsetBounds(0);
                    Point semicolon =
                            new Point(
                                    origin.x + endOfInsertionColumn * origin.width,
                                    origin.height + 1);
                    assertEquals(caret, editor.offsetAt(semicolon));
                    assertEquals(
                            caret + 1,
                            editor.offsetAt(new Point(semicolon.x + origin.width, semicolon.y)));
                    paint(editor);
                    editor.clearInlineCompletion();
                    assertEquals(followingBefore, editor.offsetBounds(source.indexOf("return")));
                    assertEquals(source, editor.getText());
                });
    }

    @Test
    void allPrimaryThemesUseAIGhostColorAndDoNotChangeProjectionState() throws Exception {
        SwingUtilities.invokeAndWait(
                () -> {
                    EditorComponent editor = editor("\tint value = ;\nreturn value;", 13);
                    editor.setTabWidth(4);
                    var proposal = ai(editor, "42\n\t+ otherValue");
                    assertTrue(editor.setAIInlineCompletion(proposal));
                    var location = editor.captureLocation();
                    for (String id : List.of("classic", "dark", "light")) {
                        Theme theme = Theme.bundled(id);
                        editor.setTheme(theme);
                        BufferedImage image = paint(editor);
                        int color = theme.color("intelligence", "aiGhost").getRGB();
                        assertTrue(
                                countColor(
                                                image,
                                                color,
                                                editor.caretBounds().height,
                                                editor.caretBounds().height * 2)
                                        > 20,
                                id);
                        assertEquals(proposal, editor.inlineCompletion());
                        assertEquals(location, editor.captureLocation());
                    }
                    editor.setFontSize(22);
                    Rectangle afterTab = editor.offsetBounds(1), origin = editor.offsetBounds(0);
                    assertEquals(4 * origin.width, afterTab.x - origin.x);
                    assertEquals(
                            editor.caretOffset(),
                            editor.offsetAt(editor.caretBounds().getLocation()));
                    paint(editor);
                });
    }

    @Test
    void partialAcceptancePreservesUnicodeAndHasASeparateCoherentUndoStep() throws Exception {
        SwingUtilities.invokeAndWait(
                () -> {
                    EditorComponent editor = editor("int value = ", 12);
                    String insertion = "  calculate(🙂) + 1;\n";
                    assertTrue(editor.setAIInlineCompletion(ai(editor, insertion)));
                    assertTrue(editor.acceptNextAIWord());
                    assertEquals("int value =   calculate", editor.getText());
                    assertEquals("(🙂) + 1;\n", editor.inlineCompletion().insertion());
                    assertTrue(editor.acceptNextAIWord());
                    assertEquals("int value =   calculate(", editor.getText());
                    assertTrue(editor.acceptNextAIWord());
                    assertEquals("int value =   calculate(🙂", editor.getText());
                    editor.undo();
                    assertEquals("int value =   calculate(", editor.getText());
                    assertNull(editor.inlineCompletion());
                    editor.undo();
                    editor.undo();
                    assertEquals("int value = ", editor.getText());
                });
    }

    @Test
    void scrollsThroughVirtualRowsWithoutChangingDocumentCoordinatesOrUndo() throws Exception {
        SwingUtilities.invokeAndWait(
                () -> {
                    EditorComponent editor = editor("int value = ;\nreturn value;", 12);
                    editor.setSize(900, 140);
                    var location = editor.captureLocation();
                    assertTrue(editor.setAIInlineCompletion(ai(editor, "1\n".repeat(20))));
                    editor.dispatchEvent(
                            new MouseWheelEvent(
                                    editor,
                                    MouseEvent.MOUSE_WHEEL,
                                    System.currentTimeMillis(),
                                    0,
                                    200,
                                    80,
                                    0,
                                    false,
                                    MouseWheelEvent.WHEEL_UNIT_SCROLL,
                                    3,
                                    4));
                    assertTrue(editor.caretBounds().y < 0);
                    assertEquals(location, editor.captureLocation());
                    assertEquals(12, editor.offsetAt(new Point(200, 20)));
                    paint(editor);
                    key(editor, KeyEvent.VK_ESCAPE);
                    assertFalse(editor.isAIInlineCompletion());
                    assertEquals(location, editor.captureLocation());
                    assertEquals("int value = ;\nreturn value;", editor.getText());
                    assertFalse(editor.document().canUndo());
                });
    }

    @Test
    void staleRangesReadOnlyCompositionAndSelectionRejectUnsafeAIInsertion() throws Exception {
        SwingUtilities.invokeAndWait(
                () -> {
                    EditorComponent editor = editor("int value = ;", 12);
                    var proposal = ai(editor, "42");
                    assertTrue(editor.setAIInlineCompletion(proposal));
                    key(editor, KeyEvent.VK_LEFT);
                    assertNull(editor.inlineCompletion());
                    assertFalse(editor.setAIInlineCompletion(proposal));
                    editor.gotoOffset(12);
                    editor.replaceSelection("1");
                    editor.gotoOffset(12);
                    assertFalse(editor.setAIInlineCompletion(proposal));
                    assertFalse(
                            editor.setAIInlineCompletion(
                                    new EditorComponent.InlineCompletion(
                                            editor.version(), 12, 11, 12, "42", 2)));
                    assertFalse(editor.setAIInlineCompletion(ai(editor, "bad\u0000text")));
                    assertFalse(editor.setAIInlineCompletion(ai(editor, "bad\rtext")));
                    assertFalse(editor.setAIInlineCompletion(ai(editor, "\ud83d")));
                    assertFalse(editor.setAIInlineCompletion(ai(editor, "x\n".repeat(129))));
                    editor.selectAll();
                    assertFalse(editor.setAIInlineCompletion(ai(editor, "42")));
                    editor.gotoOffset(12);
                    editor.setReadOnly(true);
                    assertFalse(editor.setAIInlineCompletion(ai(editor, "42")));
                    editor.setReadOnly(false);
                    assertTrue(editor.setAIInlineCompletion(ai(editor, "42")));
                    editor.inputMethodTextChanged(
                            new InputMethodEvent(
                                    editor,
                                    InputMethodEvent.INPUT_METHOD_TEXT_CHANGED,
                                    new AttributedString("あ").getIterator(),
                                    0,
                                    null,
                                    null));
                    assertNull(editor.inlineCompletion());
                    assertFalse(editor.setAIInlineCompletion(ai(editor, "42")));
                });
    }

    @Test
    void crlfSuggestionPreservesExistingEndingsAndSemanticCompletionCompatibility()
            throws Exception {
        SwingUtilities.invokeAndWait(
                () -> {
                    EditorComponent editor = editor("class X {\r\n\t\r\n}\r\n", 12);
                    String source = editor.getText();
                    assertTrue(
                            editor.setAIInlineCompletion(ai(editor, "int x = 1;\r\n\tint y = 2;")));
                    paint(editor);
                    assertTrue(editor.acceptInlineCompletion());
                    assertTrue(editor.getText().contains("1;\r\n\tint y"));
                    editor.undo();
                    assertEquals(source, editor.getText());
                    editor.setText("System.out.pr");
                    editor.gotoOffset(13);
                    var semantic =
                            new EditorComponent.InlineCompletion(
                                    editor.version(), 13, 11, 13, "println()", 9);
                    assertTrue(editor.setInlineCompletion(semantic));
                    assertFalse(editor.isAIInlineCompletion());
                    assertTrue(editor.acceptInlineCompletion());
                    assertEquals("System.out.println()", editor.getText());
                });
    }

    private static EditorComponent editor(String source, int caret) {
        EditorComponent editor = new EditorComponent();
        editor.setSize(900, 500);
        editor.setText(source);
        editor.gotoOffset(caret);
        return editor;
    }

    private static EditorComponent.InlineCompletion ai(EditorComponent editor, String value) {
        return new EditorComponent.InlineCompletion(
                editor.version(),
                editor.caretOffset(),
                editor.caretOffset(),
                editor.caretOffset(),
                value,
                value.length());
    }

    private static BufferedImage paint(EditorComponent editor) {
        BufferedImage image =
                new BufferedImage(
                        editor.getWidth(), editor.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        editor.paint(graphics);
        graphics.dispose();
        return image;
    }

    private static int countColor(BufferedImage image, int color, int firstY, int lastY) {
        int result = 0;
        for (int y = firstY; y < Math.min(lastY, image.getHeight()); y++)
            for (int x = 60; x < image.getWidth(); x++) if (image.getRGB(x, y) == color) result++;
        return result;
    }

    private static void key(EditorComponent editor, int code) {
        editor.processKeyEvent(
                new KeyEvent(
                        editor,
                        KeyEvent.KEY_PRESSED,
                        System.currentTimeMillis(),
                        0,
                        code,
                        KeyEvent.CHAR_UNDEFINED));
    }
}
