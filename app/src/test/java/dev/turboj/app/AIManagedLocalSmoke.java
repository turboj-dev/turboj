package dev.turboj.app;

import dev.turboj.ai.AIConfig;
import dev.turboj.ai.local.LocalModelManager;
import dev.turboj.config.Settings;
import java.awt.KeyboardFocusManager;
import java.awt.event.KeyEvent;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * Real-window acceptance of TurboJ-managed local AI, with no terminal command: enable Local, TurboJ starts
 * its own Ollama on a loopback port, loads and warms the model; a real suggestion is accepted with Tab and
 * undone; a multi-line one too; JDT still completes; disabling stops the server TurboJ started; a new
 * TurboJ starts it again from the saved preferences. An Ollama the user runs elsewhere is never touched.
 * Usage: AIManagedLocalSmoke [port] [model]; needs Ollama installed and the model in its store.
 */
public final class AIManagedLocalSmoke {
    private static final String CALCULATOR = """
            public class Calculator {
                public int multiply(int a, int b) {
                    return\s
                }

                public int sumOfSquares(int[] values) {
                    int total = 0;
                   \s
                    return total;
                }
            }
            """;
    private static final StringBuilder report = new StringBuilder();

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 11439;
        String model = args.length > 1 ? args[1] : "qwen2.5-coder:1.5b-base";
        Path work = Files.createTempDirectory("turboj-ai-managed-");
        System.setProperty("turboj.config", work.resolve("config").toString());
        System.setProperty("apple.laf.useScreenMenuBar", "false");
        Path project = Files.createDirectories(work.resolve("calc/src/main/java"));
        Path calculator = Files.writeString(project.resolve("Calculator.java"), CALCULATOR);
        String endpoint = "http://127.0.0.1:" + port;
        boolean externalBefore = answers("http://127.0.0.1:11434");
        check(!answers(endpoint), "nothing listens on " + endpoint + " before TurboJ");

        var frame = open(new Settings());
        AIConfig local = AIConfig.defaults().withMode(AIConfig.Mode.LOCAL)
                .withLocal(AIConfig.LocalBackend.OLLAMA, endpoint, model, AIConfig.LocalRuntime.MANAGED);
        try {
            edt(() -> { frame.openPath(calculator); return null; });
            await(() -> calculator.equals(frame.currentFile()) && frame.editor().isFocusOwner(), "Calculator open with focus", 30);
            long enabled = System.nanoTime();
            edt(() -> { frame.configureAI(local); return null; });
            await(() -> frame.localAIStatus().state() == LocalModelManager.State.READY, "managed runtime ready", 90);
            var status = edt(frame::localAIStatus);
            check(status.ownership() == LocalModelManager.Ownership.MANAGED, "TurboJ started and owns the runtime: " + status.runtime());
            note(String.format("Enable → Ready in %d ms (model load and warm-up %d ms)",
                    Duration.ofNanos(System.nanoTime() - enabled).toMillis(), status.loadMillis()));
            List<ProcessHandle> servers = ProcessHandle.current().descendants()
                    .filter(p -> p.info().command().orElse("").endsWith("ollama")).toList();
            note("Processes: " + servers.stream().map(p -> p.pid() + " " + p.info().commandLine().orElse("?")).toList());
            check(!servers.isEmpty(), "an ollama serve process runs as a child of TurboJ (pid " + servers.getFirst().pid() + ")");
            check(edt(() -> frame.statusBar().text(dev.turboj.ui.StatusBar.Segment.AI)).equals("AI · Local · Ready"),
                    "status segment reads 'AI · Local · Ready'");

            // A real one-line suggestion after "return ", accepted with Tab, undone.
            int caret = CALCULATOR.indexOf("return ") + "return ".length();
            String line = suggest(frame, caret, false);
            note("Suggestion: '" + line.replace("\n", "\\n") + "'");
            check(!line.isBlank() && !line.contains("\n"), "a single-line suggestion");
            key(frame, KeyEvent.VK_TAB, 0);
            String accepted = edt(() -> frame.editor().getText());
            check(accepted.equals(CALCULATOR.substring(0, caret) + line + CALCULATOR.substring(caret)), "Tab inserts exactly the suggestion");
            check(accepted.contains("return a * b;") || accepted.contains("return b * a;"), "the model completes multiply as a * b");
            edt(() -> { frame.editor().undo(); return null; });
            check(edt(() -> frame.editor().getText()).equals(CALCULATOR), "one Undo restores the buffer");

            // A multi-line suggestion inside sumOfSquares.
            int body = CALCULATOR.indexOf("       \n        return total;") + 7;
            String block = suggest(frame, body, true);
            note("Multi-line suggestion: '" + block.replace("\n", "\\n") + "'");
            check(!block.isBlank(), "a suggestion inside the loop body");
            key(frame, KeyEvent.VK_TAB, 0);
            check(edt(() -> frame.editor().getText()).contains(block), "Tab inserts the block");
            edt(() -> { frame.editor().undo(); return null; });
            check(edt(() -> frame.editor().getText()).equals(CALCULATOR), "one Undo removes the whole block");

            // Java completion stays independent of AI.
            int afterReturn = CALCULATOR.indexOf("return ") + "return ".length();
            edt(() -> {
                frame.aiCompletion().dismiss();
                frame.editor().gotoOffset(afterReturn);
                frame.editor().replaceSelection("Math.ab");
                frame.aiCompletion().dismiss();
                frame.runCommand("complete");
                return null;
            });
            await(() -> frame.editor().inlineCompletion() != null && !frame.editor().isAIInlineCompletion()
                    && frame.editor().inlineCompletion().insertion().contains("s"), "JDT proposes Math.abs", 30);
            check(true, "JDT completion works with AI on: '" + edt(() -> frame.editor().inlineCompletion().insertion()) + "'");
            edt(() -> { frame.runCommand("completion.dismiss"); frame.editor().undo(); return null; });

            // Disable: the server TurboJ started stops; nothing else does.
            edt(() -> { frame.configureAI(local.withMode(AIConfig.Mode.DISABLED)); return null; });
            await(() -> !answers(endpoint), "managed server stopped after Disable", 15);
            long gone = System.nanoTime() + Duration.ofSeconds(10).toNanos();
            while (servers.stream().anyMatch(ProcessHandle::isAlive) && System.nanoTime() < gone) Thread.sleep(100);
            check(servers.stream().noneMatch(ProcessHandle::isAlive), "no ollama process left: "
                    + servers.stream().filter(ProcessHandle::isAlive).map(p -> p.pid() + " " + p.info().commandLine().orElse("")).toList());
            edt(() -> { frame.configureAI(local); return null; });
            await(() -> frame.localAIStatus().state() == LocalModelManager.State.READY, "runtime ready again", 90);
        } finally {
            edt(() -> { frame.shutdown(); frame.dispose(); return null; });
        }
        await(() -> !answers(endpoint), "managed server stopped when TurboJ closed", 15);
        check(true, "quitting TurboJ stops the server it started");

        // A new TurboJ reads the saved preferences and starts local AI by itself.
        var saved = Settings.load();
        check(saved.ai.mode() == AIConfig.Mode.LOCAL && saved.ai.localRuntime() == AIConfig.LocalRuntime.MANAGED
                && saved.ai.localEndpoint().equals(endpoint), "preferences persisted: " + saved.ai.localRuntime() + " " + saved.ai.localEndpoint());
        long reopened = System.nanoTime();
        var second = open(saved);
        try {
            await(() -> second.localAIStatus().state() == LocalModelManager.State.READY, "ready after reopening", 90);
            note("Reopen → Ready in " + Duration.ofNanos(System.nanoTime() - reopened).toMillis() + " ms, no user action");
        } finally {
            edt(() -> { second.shutdown(); second.dispose(); return null; });
        }
        await(() -> !answers(endpoint), "stopped again on quit", 15);
        check(answers("http://127.0.0.1:11434") == externalBefore, "the user's own Ollama on 11434 is untouched");
        Files.writeString(Path.of("app/target/ai-managed-local-smoke.txt"), report);
        System.exit(0);
    }

    private static String suggest(TurboFrame frame, int caret, boolean larger) throws Exception {
        edt(() -> {
            frame.aiCompletion().dismiss();
            frame.editor().setSelection(caret, caret);
            frame.aiCompletion().request(true, larger);
            return null;
        });
        await(() -> frame.editor().isAIInlineCompletion(), "ghost text", 60);
        // Streaming may extend the first statement; wait until it stops changing.
        String last = "";
        for (int stable = 0; stable < 10; ) {
            Thread.sleep(50);
            String now = edt(() -> frame.editor().isAIInlineCompletion() ? frame.editor().inlineCompletion().insertion() : "");
            stable = now.equals(last) ? stable + 1 : 0;
            last = now;
        }
        return last;
    }

    private static TurboFrame open(Settings settings) throws Exception {
        var frame = edt(() -> {
            UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
            var f = new TurboFrame(settings, Map.of());
            f.setVisible(true);
            f.toFront();
            f.editor().requestFocusInWindow();
            return f;
        });
        await(() -> KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner() != null, "keyboard focus", 30);
        return frame;
    }

    private static void key(TurboFrame frame, int code, int modifiers) throws Exception {
        edt(() -> {
            java.awt.Toolkit.getDefaultToolkit().getSystemEventQueue().postEvent(new KeyEvent(frame.editor(), KeyEvent.KEY_PRESSED,
                    System.currentTimeMillis(), modifiers, code, KeyEvent.CHAR_UNDEFINED));
            return null;
        });
        Thread.sleep(150);
        edt(() -> null);
    }

    private static boolean answers(String base) {
        try {
            var client = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).build();
            return client.send(HttpRequest.newBuilder(URI.create(base + "/api/version")).timeout(Duration.ofSeconds(1)).build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
        note("PASS " + message);
    }

    private static void note(String message) {
        report.append(message).append('\n');
        System.out.println(message);
    }

    private static void await(Callable<Boolean> condition, String label, int seconds) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
        while (System.nanoTime() < deadline) {
            if (edt(condition)) return;
            Thread.sleep(50);
        }
        throw new AssertionError("Timeout: " + label);
    }

    private static <T> T edt(Callable<T> action) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) return action.call();
        var task = new FutureTask<T>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }
}
