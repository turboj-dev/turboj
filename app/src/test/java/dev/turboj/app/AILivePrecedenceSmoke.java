package dev.turboj.app;

import dev.turboj.ai.AIConfig;
import dev.turboj.ai.local.LocalModelManager;
import dev.turboj.commandj.keymap.KeyStrokeSpec;
import dev.turboj.commandj.swing.OverlayHost;
import dev.turboj.config.Settings;
import dev.turboj.ui.EditorComponent.Selection;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import javax.swing.*;

/** Real local inference and real Swing command dispatch; never downloads or owns the external server. */
public final class AILivePrecedenceSmoke {
    private static final String SOURCE = "class Calculator {\n    int multiply(int a, int b) {\n        return \n    }\n}\n";
    private static final StringBuilder report = new StringBuilder();
    private static TurboFrame frame;
    private static int checks;
    public static void main(String[] args) throws Exception {
        if (args.length != 2 || !args[0].startsWith("http://127.0.0.1:"))
            throw new IllegalArgumentException("Usage: AILivePrecedenceSmoke <http://127.0.0.1:port> <installed FIM model>");
        Path output = Files.createDirectories(Path.of("app/target/ai-live-precedence"));
        Path work = Files.createTempDirectory("turboj-live-precedence-").toRealPath();
        System.setProperty("turboj.config", work.resolve("settings").toString());
        System.setProperty("apple.laf.useScreenMenuBar", "false");
        Path source = Files.writeString(work.resolve("Calculator.java"), SOURCE);
        report.append("Native Swing events; programmatic fixture; external real Ollama; ").append(args[0]).append("; ").append(args[1]).append('\n');
        var d = AIConfig.defaults();
        var config = new AIConfig(AIConfig.Mode.LOCAL, AIConfig.LocalBackend.OLLAMA, args[0], args[1],
                d.cloudEndpoint(), d.cloudModel(), false, false, false, 200, 60, 128, 12288, d.exclusions(), true,
                d.cloudProvider(), AIConfig.LocalRuntime.EXTERNAL, 30, "", false);
        frame = edt(() -> {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            var f = new TurboFrame(new Settings(), Map.of()); f.setVisible(true); f.toFront();
            Desktop.getDesktop().requestForeground(true); f.openPath(source); f.configureAI(config); return f;
        });
        try {
            await(() -> source.equals(frame.currentFile()) && frame.editor().isFocusOwner(), "source open");
            await(() -> frame.localAIStatus().state() == LocalModelManager.State.READY, "external model ready");
            check(edt(() -> frame.localAIStatus().ownership()) == LocalModelManager.Ownership.EXTERNAL, "external runtime remains externally owned");
            for (String profile : List.of("intellij-macos", "eclipse-macos", "vscode-macos", "turboj-macos")) {
                shortcut("keymap.profile." + profile);
                await(() -> frame.commandKeymaps().selectedId().equals(profile), "profile switch");
                fixture(SOURCE, SOURCE.indexOf("return ") + 7);
                shortcut("ai.another");
                await(() -> frame.editor().isAIInlineCompletion() && frame.aiCompletion().metrics().contains("Total generation"), "live suggestion");
                // Wait for generation to settle, so the captured proposal is precisely what Tab accepts.
                Thread.sleep(400);
                String suggestion = edt(() -> frame.editor().inlineCompletion().insertion());
                report.append(profile).append(" live suggestion: ").append(suggestion.replace("\n", "\\n")).append('\n');
                check(edt(() -> frame.editor().getText()).equals(SOURCE), profile + ": model output is ghost text only");
                int caret = edt(() -> frame.editor().caretOffset());
                key(KeyEvent.VK_TAB, 0);
                check(edt(() -> frame.editor().getText()).equals(SOURCE.substring(0, caret) + suggestion + SOURCE.substring(caret)),
                        profile + ": Tab accepts exactly the live proposal without extra indentation");
                shortcut("undo");
                check(edt(() -> frame.editor().getText()).equals(SOURCE), profile + ": one Undo restores exact source");
                shortcut("ai.another");
                await(() -> frame.editor().isAIInlineCompletion(), "live ghost for Escape");
                key(KeyEvent.VK_ESCAPE, 0);
                check(edt(() -> !frame.editor().isAIInlineCompletion() && frame.editor().getText().equals(SOURCE)), profile + ": Escape dismisses live AI without source edits");

                fixture(SOURCE.replace("return ", "return Math.ab"), SOURCE.indexOf("return ") + 7 + "Math.ab".length());
                shortcut("complete");
                await(() -> frame.intelligence().alternativesVisible() && frame.editor().inlineCompletion() != null, "real JDT proposals");
                shortcut("ai.request");
                Thread.sleep(250);
                check(edt(() -> frame.intelligence().alternativesVisible() && !frame.editor().isAIInlineCompletion()), profile + ": explicit JDT popup keeps precedence over AI request");
                String before = edt(() -> frame.editor().getText());
                key(KeyEvent.VK_TAB, 0);
                check(edt(() -> !frame.intelligence().alternativesVisible() && !frame.editor().getText().equals(before)), profile + ": Tab applies the JDT proposal with live AI enabled");

                fixture(SOURCE, SOURCE.indexOf("int a") + 4);
                edt(() -> {
                    int a = SOURCE.indexOf("int a") + 4, b = SOURCE.indexOf("int b") + 4;
                    frame.editor().startTemplate(List.of(List.of(new Selection(a, a + 1)), List.of(new Selection(b, b + 1)))); return null;
                });
                shortcut("ai.request"); key(KeyEvent.VK_TAB, 0);
                check(edt(() -> frame.editor().templateIndex() == 1 && !frame.editor().isAIInlineCompletion() && frame.editor().getText().equals(SOURCE)),
                        profile + ": template owns Tab; AI cannot replace selected placeholder");
                key(KeyEvent.VK_TAB, KeyEvent.SHIFT_DOWN_MASK);
                check(edt(() -> frame.editor().templateIndex()) == 0, profile + ": Shift+Tab reverses template navigation");
                key(KeyEvent.VK_ESCAPE, 0);

                fixture(SOURCE, SOURCE.indexOf("return ") + 7);
                shortcut("ai.another");
                shortcut("keymap");
                await(() -> OverlayHost.active(frame), "keymap overlay");
                Thread.sleep(600);
                key(KeyEvent.VK_TAB, 0); key(KeyEvent.VK_TAB, KeyEvent.SHIFT_DOWN_MASK);
                check(edt(() -> OverlayHost.active(frame) && !frame.editor().isFocusOwner() && frame.editor().getText().equals(SOURCE)),
                        profile + ": dialog Tab stays local while a live request is in flight or cancelled");
                key(KeyEvent.VK_ESCAPE, 0);
                await(() -> !OverlayHost.active(frame) && frame.editor().isFocusOwner(), "dialog restores source");
                check(edt(() -> !frame.editor().isAIInlineCompletion()), profile + ": no stale ghost returns after closing the dialog");
            }
            for (String[] pair : List.of(new String[]{"ai.request","completion.acceptWord"}, new String[]{"ai.larger","completion.acceptLine"})) {
                int caret=SOURCE.indexOf("return ")+7;fixture(SOURCE,caret);shortcut(pair[0]);
                await(() -> frame.editor().isAIInlineCompletion(),"live partial acceptance");Thread.sleep(400);
                String proposal=edt(() -> frame.editor().inlineCompletion().insertion());shortcut(pair[1]);
                String after=edt(() -> frame.editor().getText());int added=after.length()-SOURCE.length();
                check(added>0&&after.startsWith(SOURCE.substring(0,caret))&&after.endsWith(SOURCE.substring(caret))
                        &&proposal.startsWith(after.substring(caret,caret+added)),pair[0]+" → "+pair[1]+": accepts a nonempty prefix of actual model output and preserves surrounding source");
                shortcut("undo");check(edt(() -> frame.editor().getText()).equals(SOURCE),pair[1]+": one Undo restores source");
            }
            fixture(SOURCE,SOURCE.indexOf("return ")+7);var runtimeBefore=edt(() -> frame.localAIStatus());shortcut("ai.retry");
            await(() -> frame.localAIStatus()!=runtimeBefore&&frame.localAIStatus().ready(),"external runtime retry");
            check(edt(() -> frame.localAIStatus().ownership())==LocalModelManager.Ownership.EXTERNAL,"ai.retry reconnects to externally owned runtime without taking ownership");
            shortcut("ai.test");await(() -> frame.statusMessages().contains("first text after"),"real completion probe");
            check(edt(() -> frame.editor().getText()).equals(SOURCE),"ai.test validates an actual local response using its synthetic snippet without editing source");
            shortcut("ai.download");await(() -> OverlayHost.active(frame),"download consent");key(KeyEvent.VK_ESCAPE,0);
            await(() -> !OverlayHost.active(frame)&&frame.editor().isFocusOwner(),"download cancelled");
            check(edt(() -> frame.localAIStatus().ready()),"ai.download cancellation leaves installed model and external runtime ready; no download authorized");
            report.append(edt(() -> frame.aiCompletion().metrics())).append('\n');
            report.append("PASS ").append(checks).append(" assertions\n");
        } catch (Throwable failure) { report.append("FAIL ").append(failure).append('\n'); throw failure; }
        finally {
            Files.writeString(output.resolve("report.txt"), report);
            edt(() -> { frame.shutdown(); frame.dispose(); return null; });
        }
    }
    private static void fixture(String text, int offset) throws Exception {
        edt(() -> { frame.aiCompletion().dismiss(); frame.intelligence().dismiss(); frame.editor().finishTemplate();
            frame.editor().setText(text); frame.editor().gotoOffset(offset); frame.editor().requestFocusInWindow(); return null; });
        await(() -> frame.editor().isFocusOwner(), "fixture focus");
    }
    private static void shortcut(String command) throws Exception {
        var binding = edt(() -> frame.commandKeymaps().commandBindings(frame.commandRegistry(), command).getFirst());
        for (KeyStrokeSpec stroke : binding.sequence().strokes()) {
            int modifiers = 0;
            for (var mod : stroke.modifiers()) modifiers |= switch (mod) {
                case META -> KeyEvent.META_DOWN_MASK; case CTRL -> KeyEvent.CTRL_DOWN_MASK;
                case ALT -> KeyEvent.ALT_DOWN_MASK; case SHIFT -> KeyEvent.SHIFT_DOWN_MASK;
            };
            key(KeyEvent.class.getField("VK_" + stroke.key()).getInt(null), modifiers);
        }
    }
    private static void key(int code, int modifiers) throws Exception {
        if (!edt(frame::isActive)) edt(() -> { frame.toFront(); Desktop.getDesktop().requestForeground(true); return null; });
        await(() -> frame.isActive() && KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner() != null, "focus before key");
        Component focus = edt(() -> KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner());
        var queue = Toolkit.getDefaultToolkit().getSystemEventQueue();
        queue.postEvent(new KeyEvent(focus, KeyEvent.KEY_PRESSED, System.currentTimeMillis(), modifiers, code, KeyEvent.CHAR_UNDEFINED));
        queue.postEvent(new KeyEvent(focus, KeyEvent.KEY_RELEASED, System.currentTimeMillis(), modifiers, code, KeyEvent.CHAR_UNDEFINED));
        edt(() -> null); Thread.sleep(60);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); checks++; report.append("PASS ").append(message).append('\n'); System.out.println("PASS " + message); }
    private static void await(Callable<Boolean> ready, String label) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(75);
        while (System.nanoTime() < deadline) { if (edt(ready)) return; Thread.sleep(25); }
        throw new AssertionError("Timeout: " + label + "; " + edt(() -> frame.statusBar().lastMessage()));
    }
    private static <T> T edt(Callable<T> action) throws Exception { var task = new FutureTask<>(action); SwingUtilities.invokeLater(task); return task.get(90, TimeUnit.SECONDS); }
}
