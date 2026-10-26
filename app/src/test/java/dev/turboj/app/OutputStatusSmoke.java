package dev.turboj.app;

import dev.turboj.config.Settings;
import dev.turboj.ui.StatusBar;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.*;
import javax.swing.*;

/** Real window, TurboJ keymap, plain project with JUnit: the output reads like Maven and the status line holds the state. */
public final class OutputStatusSmoke {
    private static TurboFrame frame;
    private static final int CMD = KeyEvent.META_DOWN_MASK;

    public static void main(String[] args) throws Exception {
        Path session = Files.createTempDirectory("turboj-output-").toRealPath();
        System.setProperty("turboj.config", session.resolve("preferences").toString());
        Path project = Files.createDirectories(session.resolve("hello-world"));
        Path sources = Files.createDirectories(project.resolve("src/main/java"));
        Files.createDirectories(project.resolve(".turboj"));
        Files.writeString(project.resolve(".turboj/project.properties"), "kind=java\njava.release=25\nname=HelloWorld\n");
        Files.writeString(project.resolve(".turboj/junit.properties"), "runtime=bundled-jupiter-5.13.4\n");
        Path main = sources.resolve("HelloWorld.java"), test = sources.resolve("CalculatorTest.java");
        Files.writeString(sources.resolve("Calculator.java"), "public class Calculator {\n    int add(int a, int b) { return a + b; }\n}\n");
        Files.writeString(main, "public class HelloWorld {\n    public static void main(String[] args) {\n        System.out.println(\"Hello World!\");\n    }\n}\n");
        Files.writeString(test, "import org.junit.jupiter.api.Test;\nimport static org.junit.jupiter.api.Assertions.assertEquals;\n\n"
                + "public class CalculatorTest {\n    @Test\n    void adds() { assertEquals(4, new Calculator().add(2, 2)); }\n"
                + "    @Test\n    void fails() { assertEquals(5, new Calculator().add(2, 2)); }\n}\n");
        try {
            frame = edt(() -> { TurboFrame result = new TurboFrame(new Settings(), Map.of()); result.setVisible(true); result.toFront(); return result; });
            edt(() -> { frame.openPath(project); return null; });
            await("plain project", () -> frame.projectStatus().contains("hello-world"));
            open(main);
            await("background check", () -> segment(StatusBar.Segment.STATE).startsWith("● BUILD OK"));
            check(edt(() -> frame.console().text()).isEmpty() || !edt(() -> frame.console().text()).contains("BUILD OK"),
                    "background checks never write to the output");
            check(segment(StatusBar.Segment.FORMATTER).equals("IntelliJ IDEA") && segment(StatusBar.Segment.KEYMAP).equals("TurboJ")
                    && segment(StatusBar.Segment.PROJECT).contains("hello-world") && segment(StatusBar.Segment.CARET).startsWith("Ln "),
                    "status line: state, caret, formatter, keymap and project segments");
            System.out.println("PASS status line: " + edt(() -> java.util.Arrays.stream(StatusBar.Segment.values())
                    .map(OutputStatusSmoke::segmentNow).filter(text -> !text.isEmpty()).toList()));

            key(KeyEvent.VK_R, CMD);
            await("run summary", () -> frame.console().text().contains("[INFO] RUN SUCCESS · exit 0"));
            String run = edt(() -> frame.console().text());
            check(run.contains("[INFO] Run HelloWorld.java · hello-world · Java ") && run.contains("[INFO] --- compile ---")
                    && run.contains("[INFO] --- run HelloWorld.main ---\nHello World!\n[INFO] ---") && run.contains("[INFO] Total time: "),
                    "Run reads like Maven: header, phases, the program's own output unprefixed, summary\n" + run);
            System.out.println("PASS Run output:\n" + run.strip());

            edt(() -> { frame.editor().setText(frame.editor().getText().replace("Hello World!\");", "Hello World!\")")); return null; });
            key(KeyEvent.VK_S, CMD);
            await("saved message", () -> frame.statusBar().lastMessage().startsWith("Saved HelloWorld.java"));
            check(!edt(() -> frame.console().text()).contains("Saved HelloWorld.java"), "Saved goes to the status line, not the output");
            System.out.println("PASS Saved HelloWorld.java appears in the status line only");
            key(KeyEvent.VK_R, CMD);
            await("compile failure", () -> frame.console().text().contains("[ERROR] RUN FAILURE · 1 error"));
            String failed = edt(() -> frame.console().text());
            check(failed.contains("[ERROR] src/main/java/HelloWorld.java:[3,"), "compile errors point at path:[line,col]\n" + failed);
            await("failed state", () -> segment(StatusBar.Segment.STATE).startsWith("✖"));
            System.out.println("PASS compile error: " + failed.lines().filter(line -> line.startsWith("[ERROR]")).toList() + " · state " + segment(StatusBar.Segment.STATE));
            edt(() -> { frame.editor().setText(frame.editor().getText().replace("Hello World!\")", "Hello World!\");")); return null; });
            key(KeyEvent.VK_S, CMD);

            open(test);
            edt(() -> { frame.console().clear(); return null; });
            key(KeyEvent.VK_U, CMD | KeyEvent.SHIFT_DOWN_MASK);
            await("test summary", () -> frame.console().text().contains("[ERROR] TEST FAILURE · 1 of 2 passed"));
            String tests = edt(() -> frame.console().text());
            check(tests.contains("[INFO] Test all tests · hello-world") && tests.contains("[INFO] --- test ---")
                    && tests.contains("Tests run: 2, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: ") && tests.contains("[ERROR] Failures:"),
                    "tests read like Surefire\n" + tests);
            await("tests segment", () -> segment(StatusBar.Segment.TESTS).equals("✖ 1 of 2 tests failed"));
            System.out.println("PASS Test output:\n" + tests.strip() + "\nPASS tests segment: " + segment(StatusBar.Segment.TESTS));
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }

    private static String segment(StatusBar.Segment segment) throws Exception {
        return SwingUtilities.isEventDispatchThread() ? segmentNow(segment) : edt(() -> segmentNow(segment));
    }
    private static String segmentNow(StatusBar.Segment segment) { return frame.statusBar().text(segment); }

    private static void open(Path file) throws Exception {
        edt(() -> { frame.openPath(file); return null; });
        await("open " + file.getFileName(), () -> file.equals(frame.currentFile()));
        await("editor focus", () -> { if (!frame.editor().isFocusOwner()) frame.editor().requestFocusInWindow(); return frame.editor().isFocusOwner(); });
    }

    private static void key(int code, int modifiers) throws Exception {
        await("keyboard focus", () -> KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner() != null
                || frame.editor().requestFocusInWindow() && false);
        edt(() -> {
            Component focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
            for (int kind : new int[]{KeyEvent.KEY_PRESSED, KeyEvent.KEY_RELEASED})
                Toolkit.getDefaultToolkit().getSystemEventQueue().postEvent(
                        new KeyEvent(focus, kind, System.currentTimeMillis(), modifiers, code, KeyEvent.CHAR_UNDEFINED));
            return null;
        });
        Thread.sleep(80);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void await(String what, Callable<Boolean> check) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (System.nanoTime() < end) { if (edt(check)) return; Thread.sleep(25); }
        throw new AssertionError("Timed out waiting for " + what + "; state " + segment(StatusBar.Segment.STATE) + "; message "
                + edt(() -> frame.statusBar().lastMessage()) + "; dialogs " + edt(() -> dev.turboj.commandj.swing.OverlayHost.dialogs(frame).stream()
                .map(d -> d.getTitle()).toList()) + "; output:\n" + edt(() -> frame.console().text()));
    }
    private static <T> T edt(Callable<T> task) throws Exception {
        var future = new FutureTask<>(task); SwingUtilities.invokeAndWait(future); return future.get();
    }
}
