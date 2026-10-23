package com.turboj.ui;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.awt.event.ActionEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

import javax.swing.Action;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.text.AbstractDocument;
import javax.swing.text.DefaultEditorKit;
import javax.swing.text.JTextComponent;

class ConsolePaneTest {
    private static final String PROMPT = "Quanti voti vuoi inserire? ";

    @Test
    void typedValueSharesThePromptLineInTheSameTextDocument() throws Exception {
        Fixture fixture = fixture();
        edt(
                () -> {
                    fixture.pane.setInputText("20");
                    assertEquals(PROMPT + "20", fixture.pane.text());
                    assertEquals("20", fixture.pane.inputText());
                    assertEquals(PROMPT + "20", fixture.pane.inputComponent().getText());
                    assertTrue(fixture.sent.isEmpty(), "Typing alone must not send stdin");
                    return null;
                });
    }

    @Test
    void backspaceDeleteAndReplacementProtectProgramOutput() throws Exception {
        Fixture fixture = fixture();
        edt(
                () -> {
                    fixture.pane.setInputText("20");
                    JTextComponent text = fixture.pane.inputComponent();
                    text.setCaretPosition(PROMPT.length());
                    action(text, DefaultEditorKit.deletePrevCharAction);
                    assertEquals(PROMPT + "20", fixture.pane.text());

                    text.setCaretPosition(PROMPT.length() - 1);
                    action(text, DefaultEditorKit.deleteNextCharAction);
                    assertEquals(PROMPT + "20", fixture.pane.text());

                    text.select(0, PROMPT.length() + 1);
                    text.replaceSelection("3");
                    assertEquals(
                            PROMPT + "30",
                            fixture.pane.text(),
                            "A selection crossing history may replace only its editable suffix");
                    assertEquals("30", fixture.pane.inputText());

                    // Direct document operations (including cut/paste actions) obey the same
                    // boundary.
                    ((AbstractDocument) text.getDocument()).replace(0, PROMPT.length(), "", null);
                    assertEquals(PROMPT + "30", fixture.pane.text());
                    return null;
                });
    }

    @Test
    void enterSubmitsExactlyOneLineAndCommitsOnlyANewline() throws Exception {
        Fixture fixture = fixture();
        edt(
                () -> {
                    fixture.pane.setInputText("20");
                    pressEnter(fixture.pane.inputComponent());
                    assertEquals(List.of("20\n"), fixture.sent);
                    assertEquals(PROMPT + "20\n", fixture.pane.text());
                    assertEquals("", fixture.pane.inputText());

                    fixture.pane.inputComponent().setCaretPosition(fixture.pane.text().length());
                    action(fixture.pane.inputComponent(), DefaultEditorKit.deletePrevCharAction);
                    assertEquals(
                            PROMPT + "20\n", fixture.pane.text(), "Committed stdin is transcript");

                    fixture.pane.setInputText("4");
                    pressEnter(fixture.pane.inputComponent());
                    assertEquals(List.of("20\n", "4\n"), fixture.sent);
                    assertEquals(PROMPT + "20\n4\n", fixture.pane.text());
                    return null;
                });
    }

    @Test
    void asynchronousOutputArrivesBeforeUnsentInputWithoutErasingIt() throws Exception {
        Fixture fixture = fixture();
        edt(
                () -> {
                    fixture.pane.setInputText("20");
                    return null;
                });
        Thread producer = new Thread(() -> fixture.pane.append("[background output]\n", false));
        producer.start();
        producer.join();
        edt(
                () -> {
                    assertEquals(PROMPT + "[background output]\n20", fixture.pane.text());
                    assertEquals("20", fixture.pane.inputText());
                    pressEnter(fixture.pane.inputComponent());
                    assertEquals(List.of("20\n"), fixture.sent);
                    return null;
                });
    }

    @Test
    void outputTruncationPreservesPendingInputAndSubmissionBoundary() throws Exception {
        Fixture fixture = fixture();
        edt(
                () -> {
                    fixture.pane.setInputText("20");
                    fixture.pane.append("a".repeat(130000), false);
                    return null;
                });
        edt(
                () -> {
                    fixture.pane.append("b".repeat(150000), false);
                    return null;
                });
        edt(
                () -> {
                    assertTrue(fixture.pane.text().length() <= 200000);
                    assertTrue(fixture.pane.text().contains("[output truncated]"));
                    assertTrue(fixture.pane.text().endsWith("20"));
                    assertEquals("20", fixture.pane.inputText());
                    pressEnter(fixture.pane.inputComponent());
                    assertEquals(List.of("20\n"), fixture.sent);
                    assertTrue(fixture.pane.text().endsWith("20\n"));
                    return null;
                });
    }

    @Test
    void stoppingAndRestartingDiscardsUnsentInputAndDisablesIdleSubmission() throws Exception {
        Fixture fixture = fixture();
        edt(
                () -> {
                    fixture.pane.setInputText("never submitted");
                    fixture.pane.setProgramRunning(false);
                    assertEquals(PROMPT, fixture.pane.text());
                    assertEquals("", fixture.pane.inputText());
                    assertFalse(fixture.pane.inputComponent().isEditable());
                    fixture.pane.setInputText("must be ignored");
                    pressEnter(fixture.pane.inputComponent());
                    assertTrue(fixture.sent.isEmpty());
                    assertEquals(PROMPT, fixture.pane.text());

                    fixture.pane.clear();
                    fixture.pane.setProgramRunning(true);
                    fixture.pane.append("Next run: ", false);
                    return null;
                });
        edt(
                () -> {
                    fixture.pane.setInputText("7");
                    pressEnter(fixture.pane.inputComponent());
                    assertEquals("Next run: 7\n", fixture.pane.text());
                    assertEquals(List.of("7\n"), fixture.sent);
                    return null;
                });
    }

    private static Fixture fixture() throws Exception {
        Fixture fixture =
                edt(
                        () -> {
                            List<String> sent = new ArrayList<>();
                            ConsolePane pane = new ConsolePane(sent::add);
                            pane.setInlineInput(true);
                            pane.setProgramRunning(true);
                            pane.append(PROMPT, false);
                            return new Fixture(pane, sent);
                        });
        edt(() -> null); // Drain the asynchronous prompt before starting an edit.
        return fixture;
    }

    private static void pressEnter(JTextComponent text) {
        Object binding = text.getInputMap().get(KeyStroke.getKeyStroke("ENTER"));
        assertNotNull(binding, "Inline stdin must have an Enter binding");
        action(text, binding);
    }

    private static void action(JTextComponent text, Object name) {
        Action action = text.getActionMap().get(name);
        assertNotNull(action, "Missing text action: " + name);
        action.actionPerformed(
                new ActionEvent(text, ActionEvent.ACTION_PERFORMED, name.toString()));
    }

    private static <T> T edt(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }

    private record Fixture(ConsolePane pane, List<String> sent) {}
}
