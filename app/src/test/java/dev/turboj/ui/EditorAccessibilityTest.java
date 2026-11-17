package dev.turboj.ui;

import java.awt.*;
import java.awt.event.InputMethodEvent;
import java.awt.event.KeyEvent;
import java.awt.font.TextHitInfo;
import java.text.AttributedString;
import java.util.ArrayList;
import javax.accessibility.*;
import javax.swing.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EditorAccessibilityTest {
    @Test void exposesSourceCaretSelectionAndBoundaries() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var editor = new EditorComponent(); editor.setText("class Café {\r\n  String value = \"😀\";\r\n}");
            assertInstanceOf(Accessible.class, editor);
            var context = editor.getAccessibleContext(); var text = context.getAccessibleText();
            assertNotNull(text); assertEquals(AccessibleRole.TEXT, context.getAccessibleRole());
            assertEquals(editor.getText().length(), text.getCharCount());
            assertEquals("Café", text.getAtIndex(AccessibleText.WORD, 7));
            assertEquals("c", text.getAtIndex(AccessibleText.CHARACTER, 0));
            assertNull(text.getBeforeIndex(AccessibleText.CHARACTER, 0));
            assertNull(text.getAfterIndex(AccessibleText.CHARACTER, text.getCharCount()-1));
            assertNull(text.getAtIndex(AccessibleText.WORD, -1));
            editor.setSelection(10, 6);
            assertEquals(6, text.getSelectionStart()); assertEquals(10, text.getSelectionEnd());
            assertEquals(editor.caretOffset(), text.getCaretPosition()); assertEquals("Café", text.getSelectedText());
            assertTrue(context.getAccessibleStateSet().contains(AccessibleState.MULTI_LINE));
        });
    }
    @Test void editsAndBufferSwitchesInvalidateWordCacheAndNotifyClients() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var editor = new EditorComponent(); editor.setText("alpha beta.");
            var context = editor.getAccessibleContext(); var text = context.getAccessibleText();
            var events = new ArrayList<String>(); context.addPropertyChangeListener(e -> events.add(e.getPropertyName()));
            assertEquals("alpha", text.getAtIndex(AccessibleText.WORD, 0));
            editor.setSelection(0, 5); editor.replaceSelection("gamma");
            assertEquals("gamma", text.getAtIndex(AccessibleText.WORD, 0));
            assertTrue(events.contains(AccessibleContext.ACCESSIBLE_TEXT_PROPERTY));
            assertTrue(events.contains(AccessibleContext.ACCESSIBLE_CARET_PROPERTY));
            assertTrue(events.contains(AccessibleContext.ACCESSIBLE_SELECTION_PROPERTY));
            editor.setText("other sentence.");
            assertEquals("other", text.getAtIndex(AccessibleText.WORD, 0));
            assertEquals("other sentence.", text.getAtIndex(AccessibleText.SENTENCE, 2));
        });
    }
    @Test void geometryTracksZoomTabsSurrogatesAndRejectsGutter() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var editor = new EditorComponent(); editor.setSize(1600,900); editor.setText("\tA😀B\n");
            var text = editor.getAccessibleContext().getAccessibleText();
            for (int size : new int[]{10,16,32,48}) {
                editor.setFont(new Font(Font.MONOSPACED,Font.PLAIN,size));
                var image=new java.awt.image.BufferedImage(1600,900,java.awt.image.BufferedImage.TYPE_INT_RGB);
                var g=image.createGraphics(); editor.paint(g); g.dispose();
                Rectangle a=text.getCharacterBounds(1);
                assertEquals(1,text.getIndexAtPoint(new Point(a.x+1,a.y+1)));
                assertEquals(text.getCharacterBounds(2),text.getCharacterBounds(3));
                assertTrue(text.getCharacterBounds(0).width>=a.width*4);
                assertEquals(-1,text.getIndexAtPoint(new Point(0,0)));
                assertNull(text.getCharacterBounds(-1)); assertNotNull(text.getCharacterAttribute(1));
            }
        });
    }
    @Test void preeditAndGhostAreNotCommittedAccessibleSource() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var editor = new EditorComponent(); editor.setText("abc"); editor.gotoOffset(3);
            var text=editor.getAccessibleContext().getAccessibleText();
            editor.setAIInlineCompletion(new EditorComponent.InlineCompletion(editor.version(),3,3,3," ghost",6));
            assertEquals(3,text.getCharCount());
            editor.dispatchEvent(new InputMethodEvent(editor,InputMethodEvent.INPUT_METHOD_TEXT_CHANGED,
                    new AttributedString("日本").getIterator(),0,TextHitInfo.leading(2),null));
            assertTrue(editor.hasInputComposition()); assertEquals(3,text.getCharCount());
            editor.dispatchEvent(new InputMethodEvent(editor,InputMethodEvent.INPUT_METHOD_TEXT_CHANGED,
                    new AttributedString("日本").getIterator(),2,null,null));
            assertFalse(editor.hasInputComposition()); assertEquals("abc日本",editor.getText());
            assertEquals(5,text.getCharCount()); assertEquals("日",text.getAtIndex(AccessibleText.CHARACTER,3));
        });
    }
    @Test void compositionOwnsNavigationDeletionAndCandidateConfirmation() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var editor = new EditorComponent(); editor.setText("abc"); editor.gotoOffset(2);
            editor.inputMethodTextChanged(new InputMethodEvent(editor,InputMethodEvent.INPUT_METHOD_TEXT_CHANGED,
                    new AttributedString("日本").getIterator(),0,TextHitInfo.leading(2),null));
            for (int key : new int[]{KeyEvent.VK_LEFT,KeyEvent.VK_RIGHT,KeyEvent.VK_BACK_SPACE,
                    KeyEvent.VK_DELETE,KeyEvent.VK_ENTER,KeyEvent.VK_TAB,KeyEvent.VK_ESCAPE}) {
                editor.processKeyEvent(new KeyEvent(editor,KeyEvent.KEY_PRESSED,0,0,key,KeyEvent.CHAR_UNDEFINED));
                assertEquals("abc",editor.getText()); assertEquals(2,editor.caretOffset());
                assertTrue(editor.hasInputComposition());
            }
            String committed="日本😀e\u0301";
            editor.inputMethodTextChanged(new InputMethodEvent(editor,InputMethodEvent.INPUT_METHOD_TEXT_CHANGED,
                    new AttributedString(committed).getIterator(),committed.length(),null,null));
            assertEquals("ab"+committed+"c",editor.getText());
            editor.undo(); assertEquals("abc",editor.getText());
        });
    }
    @Test void accessibleEditingUsesNormalUndoAndRespectsReadOnly() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var editor=new EditorComponent();editor.setText("alpha beta");
            var text=editor.getAccessibleContext().getAccessibleEditableText();
            assertNotNull(text);assertEquals("alpha beta",text.getTextRange(0,text.getCharCount()));
            text.replaceText(0,5,"gamma");assertEquals("gamma beta",editor.getText());
            editor.undo();assertEquals("alpha beta",editor.getText());
            text.selectText(6,10);assertEquals("beta",text.getSelectedText());
            text.insertTextAtIndex(10,"!");assertEquals("alpha beta!",editor.getText());
            editor.undo();assertEquals("alpha beta",editor.getText());
            text.replaceText(-1,3,"invalid");assertEquals("alpha beta",editor.getText());
            assertNull(text.getTextRange(0,100));
            editor.setReadOnly(true);text.setTextContents("forbidden");text.delete(0,5);
            assertEquals("alpha beta",editor.getText());
            assertFalse(editor.getAccessibleContext().getAccessibleStateSet().contains(AccessibleState.EDITABLE));
        });
    }
}
