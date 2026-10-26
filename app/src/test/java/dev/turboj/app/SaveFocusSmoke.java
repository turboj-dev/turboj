package dev.turboj.app;

import dev.turboj.config.Settings;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.*;
import javax.swing.*;

/** Real window with the user's automation (Format, Run and Test on Save): Save keeps the caret in the editor, in place. */
public final class SaveFocusSmoke {
    private static TurboFrame frame;

    public static void main(String[] args) throws Exception {
        Path session = Files.createTempDirectory("turboj-save-focus-").toRealPath();
        System.setProperty("turboj.config", session.resolve("preferences").toString());
        Path project = Files.createDirectories(session.resolve("hello-world"));
        Path sources = Files.createDirectories(project.resolve("src/main/java"));
        Files.createDirectories(project.resolve(".turboj"));
        Files.writeString(project.resolve(".turboj/project.properties"), "kind=java\njava.release=25\nname=HelloWorld\n");
        Files.writeString(project.resolve(".turboj/junit.properties"), "runtime=bundled-jupiter-5.13.4\n");
        Path main = sources.resolve("HelloWorld.java");
        String source = "public class HelloWorld {\n    public static void main(String[] args) {\n        System.out.println(\"Hello World!\");\n    }\n}\n";
        Files.writeString(main, source);
        try {
            frame = edt(() -> {
                Settings settings = new Settings();
                settings.autoRunOnSave = true; settings.autoTestOnSave = true; settings.formatOnSave = true; settings.formatter = "aosp";
                TurboFrame result = new TurboFrame(settings, Map.of()); result.setVisible(true); result.toFront(); return result;
            });
            edt(() -> { frame.openPath(project); return null; });
            await("plain project", () -> frame.projectStatus().contains("hello-world"));
            edt(() -> { frame.openPath(main); return null; });
            await("open", () -> main.equals(frame.currentFile()));
            int caret = source.indexOf("Hello World") + 5;
            for (int round = 1; round <= 3; round++) {
                edt(() -> { frame.editor().gotoOffset(caret); frame.editor().requestFocusInWindow(); return null; });
                await("editor focus", () -> frame.editor().isFocusOwner());
                edt(() -> { frame.editor().replaceSelection("!"); return null; }); // a change to save, inside the string
                int before = edt(() -> frame.editor().caretOffset());
                int runs = edt(() -> count(frame.console().text(), "RUN SUCCESS"));
                key(KeyEvent.VK_S, KeyEvent.META_DOWN_MASK);
                await("saved and run", () -> count(frame.console().text(), "RUN SUCCESS") > runs && !frame.isRunning());
                Thread.sleep(600); // let any late focus request land
                int finalRound = round;
                check(edt(() -> frame.editor().isFocusOwner()), "round " + finalRound + ": the caret stays in the editor after Save, Run and Test on Save; focus "
                        + edt(() -> String.valueOf(KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner()).replaceAll(",.*", "")));
                int after = edt(() -> frame.editor().caretOffset());
                check(edt(() -> frame.editor().getText().substring(0, after)).endsWith("\"Hello!") && after == before, "round " + finalRound
                        + ": the caret keeps its place in the text (" + before + " → " + after + ")");
                System.out.println("PASS round " + round + ": Save keeps the caret in the editor at the same place");
            }
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }

    private static int count(String text, String part) { int n = 0; for (int i = text.indexOf(part); i >= 0; i = text.indexOf(part, i + 1)) n++; return n; }
    private static void key(int code, int modifiers) throws Exception {
        edt(() -> {
            Component focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
            if (focus == null) throw new AssertionError("No keyboard focus");
            for (int kind : new int[]{KeyEvent.KEY_PRESSED, KeyEvent.KEY_RELEASED})
                Toolkit.getDefaultToolkit().getSystemEventQueue().postEvent(new KeyEvent(focus, kind, System.currentTimeMillis(), modifiers, code, KeyEvent.CHAR_UNDEFINED));
            return null;
        });
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void await(String what, Callable<Boolean> check) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (System.nanoTime() < end) { if (edt(check)) return; Thread.sleep(25); }
        throw new AssertionError("Timed out waiting for " + what + "; messages " + edt(() -> frame.statusMessages()) + "; build " + edt(frame::buildStatus) + "; saved file:\n" + java.nio.file.Files.readString(frame.currentFile()) + "; output:\n" + edt(() -> frame.console().text()));
    }
    private static <T> T edt(Callable<T> task) throws Exception { var future = new FutureTask<>(task); SwingUtilities.invokeAndWait(future); return future.get(); }
}
