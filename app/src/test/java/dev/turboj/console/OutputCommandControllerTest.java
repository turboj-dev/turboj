package dev.turboj.console;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboj.ui.ConsolePane;
import dev.turboj.ui.themes.Theme;
import java.awt.event.ActionEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.text.JTextComponent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(15)
class OutputCommandControllerTest {
    @TempDir Path directory;

    @Test void escapePreservesDraftAndDoesNotTouchScannerInputOrTranscriptSelection() throws Exception {
        var fixture = fixture();
        edt(() -> {
            fixture.console.inputComponent().select(0, "server ready".length());
            fixture.console.setInputText("pending Scanner input");
            fixture.controller.open();
            fixture.console.commandLine().setText("! curl http://localhost:8080/hello");
            press(fixture.console.commandLine(), "ESCAPE");
            assertFalse(fixture.console.isCommandLineVisible());
            assertEquals(1, fixture.returned.get());
            assertEquals("pending Scanner input", fixture.console.inputText());
            fixture.controller.open();
            assertEquals("! curl http://localhost:8080/hello", fixture.console.commandLine().getText());
            assertNull(fixture.controller.lastCompletion(), "Typing and Escape must never execute a command");
            fixture.controller.close();
            return null;
        });
    }

    @Test void enterAppendsActualOutputAfterCommandWithoutClearingServerLogOrSendingStdin() throws Exception {
        var fixture = fixture();
        var completion = edt(() -> {
            fixture.controller.open();
            fixture.console.commandLine().setText("! printf 'HTTP/1.1 200 OK\\n{\"status\":200,\"message\":\"OK\"}\\n'");
            fixture.console.commandLine().postActionEvent();
            return fixture.controller.lastCompletion();
        });
        assertTrue(completion.get(5, TimeUnit.SECONDS).succeeded());
        edt(() -> {
            String text = fixture.console.text();
            assertEquals("server ready\n$ printf 'HTTP/1.1 200 OK\\n{\"status\":200,\"message\":\"OK\"}\\n'\n"
                    + "HTTP/1.1 200 OK\n{\"status\":200,\"message\":\"OK\"}\n", text, "the command as a shell echoes it, then its output, nothing else");
            assertTrue(fixture.notices.getLast().text().startsWith("Command printf"), fixture.notices.toString());
            assertTrue(fixture.notices.getLast().text().contains(" done · exit 0"), fixture.notices.toString());
            assertFalse(fixture.controller.isRunning());
            assertEquals(completion.join(), fixture.controller.lastResult());
            assertEquals(List.of(), fixture.stdin);
            assertEquals("", fixture.console.commandLine().getText());
            fixture.controller.close();
            return null;
        });
    }

    @Test void commandsRequireExplicitBangAndEnterAndSupplierIsReadOnEdt() throws Exception {
        var fixture = fixture();
        edt(() -> {
            fixture.controller.open();
            fixture.console.commandLine().setText("printf must-not-run");
            fixture.console.commandLine().postActionEvent();
            assertNull(fixture.controller.lastCompletion());
            assertEquals("printf must-not-run", fixture.console.commandLine().getText());
            fixture.controller.close();
            return null;
        });
    }

    @Test void historyRestoresTheCurrentUnsubmittedDraft() throws Exception {
        var fixture = fixture();
        var completion = edt(() -> {
            fixture.controller.open();
            fixture.console.commandLine().setText("! printf previous");
            fixture.console.commandLine().postActionEvent();
            return fixture.controller.lastCompletion();
        });
        completion.get(5, TimeUnit.SECONDS);
        edt(() -> {
            fixture.console.commandLine().setText("! printf draft");
            press(fixture.console.commandLine(), "UP");
            assertEquals("! printf previous", fixture.console.commandLine().getText());
            press(fixture.console.commandLine(), "DOWN");
            assertEquals("! printf draft", fixture.console.commandLine().getText());
            fixture.controller.close();
            return null;
        });
    }

    @Test void controlCCancelsOnlyCommandAndCommandCCopyBindingRemainsUnmodified() throws Exception {
        var fixture = fixture();
        var completion = edt(() -> {
            fixture.controller.open();
            fixture.console.commandLine().setText("! sleep 30");
            fixture.console.commandLine().postActionEvent();
            press(fixture.console.commandLine(), "ctrl C");
            assertEquals(fixture.copyBinding, fixture.console.commandLine().getInputMap().get(KeyStroke.getKeyStroke("meta C")));
            return fixture.controller.lastCompletion();
        });
        assertTrue(completion.get(5, TimeUnit.SECONDS).cancelled());
        edt(() -> {
            assertTrue(fixture.console.inputComponent().isEditable(), "A separate Java program remains running");
            assertEquals(List.of(), fixture.stdin);
            assertTrue(fixture.notices.getLast().text().contains(" stopped · cancelled"), fixture.notices.toString());
            fixture.controller.close();
            return null;
        });
    }

    @Test void themeChangesKeepCommandDraftCaretAndSharedFont() throws Exception {
        var fixture = fixture();
        edt(() -> {
            fixture.controller.open();
            fixture.console.commandLine().setText("! printf caffè");
            fixture.console.commandLine().setCaretPosition(10);
            for (String theme : Theme.bundledResources().keySet()) {
                fixture.console.setTheme(Theme.bundled(theme));
                assertEquals("! printf caffè", fixture.console.commandLine().getText(), theme);
                assertEquals(10, fixture.console.commandLine().getCaretPosition(), theme);
                assertEquals(fixture.console.inputComponent().getFont(), fixture.console.commandLine().getFont(), theme);
            }
            fixture.controller.close();
            return null;
        });
    }

    private Fixture fixture() throws Exception {
        var fixture = edt(() -> {
            var stdin = new ArrayList<String>();
            var console = new ConsolePane(stdin::add);
            console.setInlineInput(true);
            console.setProgramRunning(true);
            console.append("server ready\n", false);
            var returned = new AtomicInteger();
            var binding = console.commandLine().getInputMap().get(KeyStroke.getKeyStroke("meta C"));
            var notices = new java.util.concurrent.CopyOnWriteArrayList<OutputLog.Notice>();
            var controller = new OutputCommandController(console, () -> {
                assertTrue(SwingUtilities.isEventDispatchThread());
                return directory;
            }, returned::incrementAndGet, notices::add);
            return new Fixture(console, controller, stdin, returned, binding, notices);
        });
        edt(() -> null);
        return fixture;
    }

    private static void press(JTextComponent component, String key) {
        var binding = component.getInputMap().get(KeyStroke.getKeyStroke(key));
        assertNotNull(binding, key);
        var action = component.getActionMap().get(binding);
        assertNotNull(action, key);
        action.actionPerformed(new ActionEvent(component, ActionEvent.ACTION_PERFORMED, key));
    }

    private static <T> T edt(Callable<T> action) throws Exception {
        var task = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }

    private record Fixture(ConsolePane console, OutputCommandController controller, List<String> stdin,
                           AtomicInteger returned, Object copyBinding, List<OutputLog.Notice> notices) {}
}
