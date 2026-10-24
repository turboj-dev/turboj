package dev.turboj.ui;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboj.ui.themes.Theme;

import dev.javafmt.core.TextEdit;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.FutureTask;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.SwingUtilities;

class EditorFormattingTest {
    @Test
    void sparseEditsMapSelectionAndRestoreExactUndoStateInEveryTheme() throws Exception {
        edt(
                () -> {
                    for (String theme : Theme.BUNDLED) {
                        var editor = new EditorComponent();
                        editor.setSize(900, 500);
                        editor.setTheme(Theme.bundled(theme));
                        editor.setText("class X{\nint a=1;\nint b=2;\n}\n");
                        editor.gotoOffset(editor.getText().length());
                        editor.replaceSelection("// typed\n");
                        String before = editor.getText();
                        int b = before.indexOf("b=2");
                        editor.restoreLocation(new EditorComponent.Location(b + 1, b, 1, 2));
                        var location = editor.captureLocation();
                        var document = editor.document();
                        var snapshot = editor.snapshot();
                        List<TextEdit> edits =
                                List.of(
                                        new TextEdit(before.indexOf('{'), before.indexOf('{'), " "),
                                        new TextEdit(
                                                before.indexOf("int a"),
                                                before.indexOf("int a"),
                                                "    "),
                                        new TextEdit(
                                                before.indexOf("int b"),
                                                before.indexOf("int b"),
                                                "    "));
                        String expected = TextEdit.apply(before, edits);
                        AtomicInteger changes = new AtomicInteger();
                        editor.setOnChange(changes::incrementAndGet);
                        assertTrue(editor.applyFormatting(editor.version(), edits));
                        assertEquals(1, changes.get());
                        assertSame(document, editor.document());
                        assertEquals(before, snapshot.toString());
                        assertEquals(expected, editor.getText());
                        assertEquals("b", editor.getSelectedText());
                        assertEquals(expected.indexOf("b=2") + 1, editor.caretOffset());
                        var after = editor.captureLocation();
                        assertEquals(location.topLine(), after.topLine());
                        assertEquals(location.leftColumn(), after.leftColumn());
                        editor.undo();
                        assertEquals(before, editor.getText());
                        assertEquals(location, editor.captureLocation());
                        editor.redo();
                        assertEquals(expected, editor.getText());
                        assertEquals(after, editor.captureLocation());
                        editor.undo();
                        editor.undo();
                        assertFalse(
                                editor.getText().contains("// typed"),
                                "format undo must not absorb earlier typing");
                    }
                });
    }

    @Test
    void staleReadonlyAndMalformedEditsCannotMutateSource() throws Exception {
        edt(
                () -> {
                    var editor = new EditorComponent();
                    editor.setText("class X { String s = \"🙂\"; }");
                    String source = editor.getText();
                    long version = editor.version();
                    assertFalse(
                            editor.applyFormatting(version - 1, List.of(new TextEdit(0, 0, " "))));
                    assertFalse(
                            editor.applyFormatting(
                                    version,
                                    List.of(new TextEdit(0, 10, " "), new TextEdit(3, 4, " "))));
                    int emoji = source.indexOf("🙂");
                    assertFalse(
                            editor.applyFormatting(
                                    version, List.of(new TextEdit(emoji + 1, emoji + 1, " "))));
                    editor.setReadOnly(true);
                    assertFalse(editor.applyFormatting(version, List.of(new TextEdit(0, 0, " "))));
                    assertEquals(source, editor.getText());
                    assertEquals(version, editor.version());
                    assertFalse(editor.document().canUndo());
                });
    }

    @Test
    void noopHasNoUndoEntryAndHistorySurvivesBufferNavigation() throws Exception {
        edt(
                () -> {
                    var editor = new EditorComponent();
                    editor.setText("class X{}");
                    long version = editor.version();
                    assertTrue(editor.applyFormatting(version, List.of()));
                    assertEquals(version, editor.version());
                    assertFalse(editor.document().canUndo());
                    assertTrue(editor.applyFormatting(version, List.of(new TextEdit(7, 7, " "))));
                    var original = editor.captureState();
                    editor.setText("class Y {}");
                    editor.restoreState(original);
                    editor.undo();
                    assertEquals("class X{}", editor.getText());
                    editor.redo();
                    assertEquals("class X {}", editor.getText());
                });
    }

    private static void edt(Runnable action) throws Exception {
        var task =
                new FutureTask<Void>(
                        () -> {
                            action.run();
                            return null;
                        });
        SwingUtilities.invokeAndWait(task);
        task.get();
    }
}
