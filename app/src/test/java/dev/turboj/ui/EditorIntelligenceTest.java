package dev.turboj.ui;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

class EditorIntelligenceTest {
    @Test
    void inlineAcceptAndDismissKeysCanBeRemapped() throws Exception {
        SwingUtilities.invokeAndWait(
                () -> {
                    EditorComponent editor = editor("System.out.pr");
                    editor.setCompletionKeys(
                            KeyStroke.getKeyStroke("ctrl ENTER"), KeyStroke.getKeyStroke("F4"));
                    assertTrue(
                            editor.setInlineCompletion(completion(editor, 11, 13, "println()", 9)));
                    key(editor, KeyEvent.VK_TAB, 0);
                    assertEquals("System.out.pr   ", editor.getText());
                    editor.undo();
                    editor.gotoOffset(13);
                    assertTrue(
                            editor.setInlineCompletion(completion(editor, 11, 13, "println()", 9)));
                    key(editor, KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK);
                    assertEquals("System.out.println()", editor.getText());
                    editor.undo();
                    editor.gotoOffset(13);
                    assertTrue(
                            editor.setInlineCompletion(completion(editor, 11, 13, "println()", 9)));
                    key(editor, KeyEvent.VK_F4, 0);
                    assertNull(editor.inlineCompletion());
                    assertEquals("System.out.pr", editor.getText());
                });
    }

    @Test
    void ghostDoesNotChangeSourceCaretVersionOrHistoryAndTabIsOneUndo() throws Exception {
        SwingUtilities.invokeAndWait(
                () -> {
                    EditorComponent editor = editor("System.out.pr");
                    long version = editor.version();
                    int caret = editor.caretOffset();
                    assertFalse(editor.document().canUndo());
                    assertTrue(
                            editor.setInlineCompletion(completion(editor, 11, 13, "println()", 9)));
                    assertEquals("System.out.pr", editor.getText());
                    assertEquals(caret, editor.caretOffset());
                    assertEquals(version, editor.version());
                    assertFalse(editor.document().canUndo());
                    paint(editor);
                    key(editor, KeyEvent.VK_TAB, 0);
                    assertEquals("System.out.println()", editor.getText());
                    assertNull(editor.inlineCompletion());
                    editor.undo();
                    assertEquals("System.out.pr", editor.getText());
                    assertFalse(editor.document().canUndo());
                    editor.redo();
                    assertEquals("System.out.println()", editor.getText());
                });
    }

    @Test
    void dismissalMotionSelectionTypingAndVersionChangesInvalidateGhost() throws Exception {
        SwingUtilities.invokeAndWait(
                () -> {
                    EditorComponent editor = editor("System.out.pr");
                    var proposal = completion(editor, 11, 13, "println()", 9);
                    assertTrue(editor.setInlineCompletion(proposal));
                    key(editor, KeyEvent.VK_ESCAPE, 0);
                    assertNull(editor.inlineCompletion());
                    assertEquals("System.out.pr", editor.getText());
                    assertTrue(editor.setInlineCompletion(proposal));
                    key(editor, KeyEvent.VK_LEFT, 0);
                    assertNull(editor.inlineCompletion());
                    assertFalse(editor.setInlineCompletion(proposal));
                    editor.gotoOffset(13);
                    assertTrue(editor.setInlineCompletion(proposal));
                    editor.selectAll();
                    assertNull(editor.inlineCompletion());
                    assertFalse(editor.setInlineCompletion(proposal));
                    editor.gotoOffset(13);
                    assertTrue(editor.setInlineCompletion(proposal));
                    type(editor, 'i');
                    assertEquals("System.out.pri", editor.getText());
                    assertNull(editor.inlineCompletion());
                    editor.gotoOffset(13);
                    assertFalse(editor.setInlineCompletion(proposal));
                });
    }

    @Test
    void existingSourceAfterCaretIsNotCoveredButPopupCanReplaceItsRange() throws Exception {
        SwingUtilities.invokeAndWait(
                () -> {
                    EditorComponent editor = editor("System.out.pr;");
                    editor.gotoOffset(13);
                    var proposal = completion(editor, 11, 13, "println()", 9);
                    assertFalse(editor.setInlineCompletion(proposal));
                    assertTrue(editor.applyCompletion(proposal));
                    assertEquals("System.out.println();", editor.getText());
                    editor.setText("System.out.print");
                    editor.gotoOffset(13);
                    proposal = completion(editor, 11, 16, "println()", 9);
                    assertFalse(editor.setInlineCompletion(proposal));
                    assertTrue(editor.applyCompletion(proposal));
                    assertEquals("System.out.println()", editor.getText());
                });
    }

    @Test
    void importAndIdentifierReplacementAreAtomicAndCaretFollowsBothEdits() throws Exception {
        SwingUtilities.invokeAndWait(
                () -> {
                    String original = "class Demo { Arr }";
                    EditorComponent editor = editor(original);
                    editor.gotoOffset(16);
                    String importText = "import java.util.ArrayList;\n";
                    var proposal =
                            new EditorComponent.InlineCompletion(
                                    editor.version(),
                                    16,
                                    13,
                                    16,
                                    "ArrayList",
                                    9,
                                    List.of(new EditorComponent.TextEdit(0, 0, importText)));
                    assertTrue(editor.applyCompletion(proposal));
                    assertEquals(importText + "class Demo { ArrayList }", editor.getText());
                    assertEquals(importText.length() + 22, editor.caretOffset());
                    editor.undo();
                    assertEquals(original, editor.getText());
                    assertFalse(editor.document().canUndo());
                    editor.redo();
                    assertEquals(importText + "class Demo { ArrayList }", editor.getText());
                });
    }

    @Test
    void overlappingCompletionEditsAreRejectedWithoutChangingSource() throws Exception {
        SwingUtilities.invokeAndWait(
                () -> {
                    EditorComponent editor = editor("System.out.pr");
                    var proposal =
                            new EditorComponent.InlineCompletion(
                                    editor.version(),
                                    13,
                                    11,
                                    13,
                                    "println()",
                                    9,
                                    List.of(new EditorComponent.TextEdit(10, 12, "oops")));
                    assertFalse(editor.applyCompletion(proposal));
                    assertEquals("System.out.pr", editor.getText());
                    assertFalse(editor.document().canUndo());
                });
    }

    @Test
    void switchingBuffersPreservesUnsavedTextUndoSelectionAndViewport() throws Exception {
        SwingUtilities.invokeAndWait(
                () -> {
                    EditorComponent editor = editor("one\ntwo\nthree\nfour");
                    editor.replaceSelection("!");
                    editor.restoreLocation(new EditorComponent.Location(10, 8, 1, 2));
                    var first = editor.captureState();
                    editor.setText("second");
                    editor.gotoOffset(6);
                    editor.replaceSelection(" buffer");
                    var second = editor.captureState();
                    editor.restoreState(first);
                    assertEquals("one\ntwo\nthree\nfour!", editor.getText());
                    assertEquals(first.location(), editor.captureLocation());
                    assertEquals("th", editor.getSelectedText());
                    editor.undo();
                    assertEquals("one\ntwo\nthree\nfour", editor.getText());
                    editor.restoreState(second);
                    assertEquals("second buffer", editor.getText());
                    editor.undo();
                    assertEquals("second", editor.getText());
                });
    }

    @Test
    void readOnlySourceAllowsNavigationAndSelectionButNoEditing() throws Exception {
        SwingUtilities.invokeAndWait(
                () -> {
                    EditorComponent editor = editor("public class ReadOnly {}\n");
                    editor.replaceSelection("// undo exists");
                    String source = editor.getText();
                    editor.setReadOnly(true);
                    editor.undo();
                    editor.selectAll();
                    editor.replaceSelection("bad");
                    editor.indent(false);
                    editor.toggleComment();
                    key(editor, KeyEvent.VK_BACK_SPACE, 0);
                    type(editor, 'x');
                    assertEquals(source, editor.getText());
                    editor.gotoOffset(1);
                    assertEquals(1, editor.caretOffset());
                    assertFalse(editor.getSelectedText().length() > 0);
                    var state = editor.captureState();
                    editor.setText("editable");
                    assertFalse(editor.isReadOnly());
                    editor.restoreState(state);
                    assertTrue(editor.isReadOnly());
                    assertEquals(source, editor.getText());
                });
    }

    @Test
    void boundsFollowTabsScrollingFontAndThemesWithoutChangingCompletion() throws Exception {
        SwingUtilities.invokeAndWait(
                () -> {
                    EditorComponent editor = editor("\tSystem.out.pr");
                    editor.setTabWidth(4);
                    Rectangle zero = editor.offsetBounds(0), afterTab = editor.offsetBounds(1);
                    assertEquals(4 * zero.width, afterTab.x - zero.x);
                    var proposal = completion(editor, 12, 14, "println()", 9);
                    assertTrue(editor.setInlineCompletion(proposal));
                    editor.setPalette(
                            Color.WHITE,
                            Color.BLACK,
                            Color.BLUE,
                            Color.BLUE,
                            Color.GRAY,
                            Color.LIGHT_GRAY,
                            Color.BLACK,
                            Color.RED,
                            true);
                    editor.setFontSize(22);
                    paint(editor);
                    assertEquals(proposal, editor.inlineCompletion());
                    assertEquals("\tSystem.out.pr", editor.getText());
                    assertEquals(
                            editor.caretOffset(),
                            editor.offsetAt(editor.caretBounds().getLocation()));
                    editor.restoreLocation(new EditorComponent.Location(14, 14, 0, 3));
                    Rectangle scrolled = editor.offsetBounds(1);
                    assertEquals(editor.offsetBounds(0).x + 4 * scrolled.width, scrolled.x);
                });
    }

    @Test
    void commandClickResolvesPointerWithoutMovingCaretAndKeyHookCanRouteHistory() throws Exception {
        SwingUtilities.invokeAndWait(
                () -> {
                    EditorComponent editor = editor("Calculator.add(2, 3)");
                    int caret = editor.caretOffset();
                    AtomicInteger clicked = new AtomicInteger(-1);
                    editor.setOnGoDeclaration(clicked::set);
                    Rectangle bounds = editor.offsetBounds(11);
                    Point point = bounds.getLocation();
                    editor.dispatchEvent(
                            new MouseEvent(
                                    editor,
                                    MouseEvent.MOUSE_MOVED,
                                    System.currentTimeMillis(),
                                    InputEvent.META_DOWN_MASK,
                                    point.x,
                                    point.y + 2,
                                    0,
                                    false));
                    editor.setNavigableRange(editor.version(), 11, 14);
                    assertEquals(Cursor.HAND_CURSOR, editor.getCursor().getType());
                    editor.dispatchEvent(
                            new MouseEvent(
                                    editor,
                                    MouseEvent.MOUSE_PRESSED,
                                    System.currentTimeMillis(),
                                    InputEvent.META_DOWN_MASK,
                                    point.x,
                                    point.y + 2,
                                    1,
                                    false,
                                    MouseEvent.BUTTON1));
                    assertEquals(11, clicked.get());
                    assertEquals(caret, editor.caretOffset());
                    AtomicInteger back = new AtomicInteger();
                    editor.setKeyHandler(
                            event -> {
                                if (event.getID() == KeyEvent.KEY_PRESSED
                                        && event.isAltDown()
                                        && event.getKeyCode() == KeyEvent.VK_LEFT) {
                                    back.incrementAndGet();
                                    return true;
                                }
                                return false;
                            });
                    key(editor, KeyEvent.VK_LEFT, InputEvent.ALT_DOWN_MASK);
                    assertEquals(1, back.get());
                    assertEquals(caret, editor.caretOffset());
                });
    }

    private static EditorComponent editor(String source) {
        EditorComponent editor = new EditorComponent();
        editor.setSize(800, 500);
        editor.setText(source);
        editor.gotoOffset(source.length());
        return editor;
    }

    private static EditorComponent.InlineCompletion completion(
            EditorComponent editor, int start, int end, String text, int caret) {
        return new EditorComponent.InlineCompletion(
                editor.version(), editor.caretOffset(), start, end, text, caret);
    }

    private static void paint(EditorComponent editor) {
        BufferedImage image = new BufferedImage(800, 500, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        editor.paint(graphics);
        graphics.dispose();
    }

    private static void type(EditorComponent editor, char value) {
        editor.processKeyEvent(
                new KeyEvent(
                        editor,
                        KeyEvent.KEY_TYPED,
                        System.currentTimeMillis(),
                        0,
                        KeyEvent.VK_UNDEFINED,
                        value));
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
