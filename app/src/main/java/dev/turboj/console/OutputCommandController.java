package dev.turboj.console;

import dev.turboj.ui.ConsolePane;
import java.awt.event.ActionEvent;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;
import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;

/** Vim-style :! entry into the existing output pane, independent from program stdin and Maven jobs. */
public final class OutputCommandController implements AutoCloseable {
    private final ConsolePane console;
    private final OutputLog log;
    private OutputLog.Action action;
    private final Supplier<Path> workingDirectory;
    private final Runnable focusEditor;
    private final OutputCommandRunner runner;
    private final List<String> history = new ArrayList<>();
    private int historyPosition;
    private String historyDraft = "";
    private boolean running;
    private boolean closed;
    private OutputCommandRunner.Result lastResult;
    private CompletableFuture<OutputCommandRunner.Result> lastCompletion;

    public OutputCommandController(ConsolePane console, Supplier<Path> workingDirectory, Runnable focusEditor) {
        this(console, workingDirectory, focusEditor, new OutputCommandRunner());
    }

    OutputCommandController(ConsolePane console, Supplier<Path> workingDirectory,
                            Runnable focusEditor, OutputCommandRunner runner) {
        this.console = Objects.requireNonNull(console);
        this.log = new OutputLog(console);
        this.workingDirectory = Objects.requireNonNull(workingDirectory);
        this.focusEditor = Objects.requireNonNull(focusEditor);
        this.runner = Objects.requireNonNull(runner);
        var field = console.commandLine();
        field.addActionListener(event -> submit());
        bind(field, "ESCAPE", "dismiss-output-command", this::dismiss);
        bind(field, "UP", "previous-output-command", () -> history(-1));
        bind(field, "DOWN", "next-output-command", () -> history(1));
        // Cmd+C remains ordinary Copy. Only Control+C requests cancellation of our own process.
        bind(field, "ctrl C", "cancel-output-command", this::cancel);
        console.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT)
                .put(KeyStroke.getKeyStroke("ctrl C"), "cancel-output-command");
        console.getActionMap().put("cancel-output-command", action(this::cancel));
    }

    public void open() {
        requireEdt();
        if (!closed) console.showCommandLine();
    }

    public boolean dismiss() {
        requireEdt();
        if (!console.isCommandLineVisible()) return false;
        console.hideCommandLine();
        focusEditor.run();
        return true;
    }

    public boolean cancel() {
        requireEdt();
        return runner.cancel();
    }

    public boolean isRunning() { return running; }
    public OutputCommandRunner.Result lastResult() { return lastResult; }
    /** Null before the first command; completes after the final status has reached the output document. */
    public CompletableFuture<OutputCommandRunner.Result> lastCompletion() { return lastCompletion; }

    public void submit() {
        requireEdt();
        if (closed) return;
        String entry = console.commandLine().getText().strip();
        if (entry.isEmpty()) return;
        if (running) {
            console.append("\nA command is running. Control+C stops that command; Maven keeps running.\n", ConsolePane.Role.WARNING);
            return;
        }
        // The colon is the fixed prompt. Accept a pasted full :! command as well.
        if (entry.startsWith(":")) entry = entry.substring(1).stripLeading();
        if (!entry.startsWith("!") || entry.substring(1).isBlank()) {
            console.append("\nUse ! followed by a shell command, for example ! curl http://localhost:8080/hello\n", ConsolePane.Role.WARNING);
            return;
        }
        String command = entry.substring(1).stripLeading();
        Path directory;
        try {
            directory = Objects.requireNonNull(workingDirectory.get(), "No command working directory");
            // Runner validates text before scheduling; the shell is launched only after explicit Enter.
            var current = action = log.start("Command", ":! " + command);
            var completion = runner.start(command, directory, (text, stderr) -> current.program(text, stderr));
            lastCompletion = new CompletableFuture<>();
            var visibleCompletion = lastCompletion;
            running = true;
            lastResult = null;
            console.setCommandRunning(true);
            remember("! " + command);
            console.commandLine().setText("");
            completion.whenComplete((result, failure) -> SwingUtilities.invokeLater(() -> {
                if (failure != null) {
                    running = false;
                    console.setCommandRunning(false);
                    visibleCompletion.completeExceptionally(failure);
                    return;
                }
                if (!closed) {
                    if (result.error() != null) action.finish(OutputLog.Outcome.FAILURE, "error: " + result.error(), null);
                    else if (result.timedOut()) action.finish(OutputLog.Outcome.FAILURE, "timed out; process stopped", null);
                    else if (result.cancelled()) action.finish(OutputLog.Outcome.STOPPED, "cancelled", null);
                    else action.finish(result.succeeded() ? OutputLog.Outcome.SUCCESS : OutputLog.Outcome.FAILURE, "exit " + result.exitCode(), null);
                    console.setCommandRunning(false);
                }
                // append() schedules its EDT drain before this callback: expose a visible result.
                SwingUtilities.invokeLater(() -> {
                    running = false;
                    lastResult = result;
                    visibleCompletion.complete(result);
                });
            }));
        } catch (RuntimeException failure) {
            if (action != null && action.open()) action.finish(OutputLog.Outcome.FAILURE, failure.getMessage(), null);
            else log.start("Command", "").finish(OutputLog.Outcome.FAILURE, failure.getMessage(), null);
        }
    }

    @Override public void close() {
        requireEdt();
        closed = true;
        runner.close();
        console.hideCommandLine();
    }

    private void remember(String entry) {
        if (history.isEmpty() || !history.getLast().equals(entry)) history.add(entry);
        if (history.size() > 100) history.removeFirst();
        historyPosition = history.size();
        historyDraft = "";
    }

    private void history(int direction) {
        if (history.isEmpty()) return;
        if (historyPosition == history.size()) historyDraft = console.commandLine().getText();
        historyPosition = Math.clamp(historyPosition + direction, 0, history.size());
        console.commandLine().setText(historyPosition == history.size() ? historyDraft : history.get(historyPosition));
        console.commandLine().setCaretPosition(console.commandLine().getDocument().getLength());
    }

    private static void bind(JComponent component, String key, String name, Runnable callback) {
        component.getInputMap().put(KeyStroke.getKeyStroke(key), name);
        component.getActionMap().put(name, action(callback));
    }

    private static AbstractAction action(Runnable callback) {
        return new AbstractAction() {
            @Override public void actionPerformed(ActionEvent event) { callback.run(); }
        };
    }

    private static void requireEdt() {
        if (!SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Output commands require the EDT");
    }
}
