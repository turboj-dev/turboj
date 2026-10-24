package com.turboj.intelligence;

import static org.junit.jupiter.api.Assertions.*;

import com.turboj.ui.EditorComponent;

import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;

class CompletionCoordinatorTest {
    @Test
    void semanticCompletionWinsOverAIAndExplicitChoiceWinsOverAutomaticJava() throws Exception {
        SwingUtilities.invokeAndWait(
                () -> {
                    EditorComponent editor = editor("System.out.pr");
                    CompletionCoordinator coordinator = new CompletionCoordinator(editor);
                    long ticket = coordinator.generation();
                    assertTrue(coordinator.ai(ticket, ai(editor, "intln();")));
                    var semantic =
                            new EditorComponent.InlineCompletion(
                                    editor.version(), 13, 11, 13, "print()", 7);
                    assertTrue(coordinator.semantic(semantic, true));
                    assertFalse(editor.isAIInlineCompletion());
                    assertFalse(coordinator.ai(ticket, ai(editor, "ignored();")));
                    var automatic =
                            new EditorComponent.InlineCompletion(
                                    editor.version(), 13, 11, 13, "println()", 9);
                    assertFalse(coordinator.semantic(automatic, false));
                    assertEquals(semantic, editor.inlineCompletion());
                    coordinator.clear();
                    assertFalse(coordinator.hasSemanticChoice());
                    assertFalse(coordinator.ai(ticket, ai(editor, "stale();")));
                    assertTrue(coordinator.ai(coordinator.generation(), ai(editor, "fresh();")));
                });
    }

    @Test
    void failedUnselectedSemanticOverlayRestoresAIWithoutTouchingSource() throws Exception {
        SwingUtilities.invokeAndWait(
                () -> {
                    EditorComponent editor = editor("int value = ;");
                    editor.gotoOffset(12);
                    CompletionCoordinator coordinator = new CompletionCoordinator(editor);
                    var suggestion = ai(editor, "42");
                    assertTrue(coordinator.ai(coordinator.generation(), suggestion));
                    var semantic =
                            new EditorComponent.InlineCompletion(
                                    editor.version(), 12, 12, 12, "Integer.valueOf()", 16);
                    assertFalse(coordinator.semantic(semantic, false)); // source follows the caret
                    assertTrue(editor.isAIInlineCompletion());
                    assertEquals(suggestion, editor.inlineCompletion());
                    assertEquals("int value = ;", editor.getText());
                    assertFalse(editor.document().canUndo());
                    assertFalse(coordinator.semantic(semantic, true));
                    assertTrue(coordinator.hasSemanticChoice());
                    assertNull(editor.inlineCompletion());
                    assertFalse(coordinator.ai(coordinator.generation(), suggestion));
                });
    }

    @Test
    void clearingAILeavesAnActiveJavaSuggestionAlone() throws Exception {
        SwingUtilities.invokeAndWait(
                () -> {
                    EditorComponent editor = editor("System.out.pr");
                    CompletionCoordinator coordinator = new CompletionCoordinator(editor);
                    var semantic =
                            new EditorComponent.InlineCompletion(
                                    editor.version(), 13, 11, 13, "println()", 9);
                    coordinator.semantic(semantic, false);
                    coordinator.clearAI();
                    assertEquals(semantic, editor.inlineCompletion());
                    coordinator.clear();
                    var ai = ai(editor, "intln();");
                    coordinator.ai(coordinator.generation(), ai);
                    coordinator.clearAI();
                    assertNull(editor.inlineCompletion());
                });
    }

    private static EditorComponent editor(String text) {
        EditorComponent result = new EditorComponent();
        result.setSize(900, 500);
        result.setText(text);
        result.gotoOffset(text.length());
        return result;
    }

    private static EditorComponent.InlineCompletion ai(EditorComponent editor, String text) {
        return new EditorComponent.InlineCompletion(
                editor.version(),
                editor.caretOffset(),
                editor.caretOffset(),
                editor.caretOffset(),
                text,
                text.length());
    }
}
