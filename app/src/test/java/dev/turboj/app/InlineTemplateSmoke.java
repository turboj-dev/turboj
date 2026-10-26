package dev.turboj.app;

import dev.turboj.config.Settings;
import dev.turboj.refactorj.api.JavaQuickActions.Proposal;
import dev.turboj.ui.StatusBar;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import javax.swing.*;

/**
 * Real window, TurboJ keymap: missing method → ⌘1 → Create Method → fields edited in place → Enter → plain Java, the
 * call renamed in the other file; Esc takes a session back; Undo/Redo stay coherent; local Rename reuses the template.
 */
public final class InlineTemplateSmoke {
    private static TurboFrame frame;
    private static final int CMD = KeyEvent.META_DOWN_MASK, SHIFT = KeyEvent.SHIFT_DOWN_MASK, OPTION = KeyEvent.ALT_DOWN_MASK;
    private static final String TEST = """
            import org.junit.jupiter.api.Test;
            import static org.junit.jupiter.api.Assertions.assertEquals;

            public class CalculatorTest {
                Calculator calculator = new Calculator();

                @Test
                void multiplies() {
                    assertEquals(0, calculator.multiply(0, 0));
                    // multiply in a comment stays
                    String text = "multiply";
                    int total = 0;
                    total += 5;
                    assertEquals(5, total);
                }
            }
            """;

    public static void main(String[] args) throws Exception {
        Path session = Files.createTempDirectory("turboj-template-").toRealPath();
        System.setProperty("turboj.config", session.resolve("preferences").toString());
        Path project = Files.createDirectories(session.resolve("calc"));
        Path sources = Files.createDirectories(project.resolve("src/main/java"));
        Files.createDirectories(project.resolve(".turboj"));
        Files.writeString(project.resolve(".turboj/project.properties"), "kind=java\njava.release=25\nname=Calc\n");
        Files.writeString(project.resolve(".turboj/junit.properties"), "runtime=bundled-jupiter-5.13.4\n");
        Path calculator = sources.resolve("Calculator.java"), test = sources.resolve("CalculatorTest.java");
        Files.writeString(calculator, "public class Calculator {\n}\n");
        Files.writeString(test, TEST);
        try {
            frame = edt(() -> { TurboFrame value = new TurboFrame(new Settings(), Map.of()); value.setVisible(true); value.toFront(); return value; });
            edt(() -> { frame.openPath(project); return null; });
            await("project", () -> frame.projectStatus().contains("calc"));
            edt(() -> { frame.openPath(calculator); return null; });
            await("Calculator open", () -> calculator.equals(frame.currentFile()));
            open(test, TEST.indexOf("multiply(0") + 3);

            createMethod("Create method 'multiply(int, int)' in Calculator");
            await("template in Calculator", () -> calculator.equals(frame.currentFile()) && frame.editor().templateActive());
            check(edt(() -> frame.editor().getSelectedText()).equals("int"), "the return type is selected first");
            check(edt(() -> frame.statusBar().text(StatusBar.Segment.MESSAGE)).contains("Tab: next"), "the keys are shown while the template lasts: "
                    + edt(() -> frame.statusBar().text(StatusBar.Segment.MESSAGE)) + " / history " + edt(() -> frame.statusBar().history()));
            var visited = new ArrayList<String>();
            for (int i = 0; i < 5; i++) { visited.add(edt(() -> frame.editor().getSelectedText())); key(KeyEvent.VK_TAB, 0); }
            String afterTabs = edt(() -> frame.editor().getText());
            check(visited.size() == 5 && visited.get(0).equals("int") && visited.get(1).equals("multiply") && visited.get(4).equals("0"),
                    "Tab visits return type, name, both parameters, returned value: " + visited);
            check(edt(() -> frame.editor().getSelectedText()).equals("int"), "Tab on the last field wraps to the first");
            check(afterTabs.contains("public int multiply(int " + visited.get(2) + ", int " + visited.get(3) + ") {"), "Tab inserted nothing:\n" + afterTabs);
            System.out.println("PASS fields in order " + visited + "; Tab wraps; nothing inserted");

            key(KeyEvent.VK_TAB, 0);                    // the method name
            type("product");
            key(KeyEvent.VK_ENTER, 0);                  // Enter from the name field confirms the whole template
            await("confirmed", () -> !frame.editor().templateActive());
            String declared = edt(() -> frame.editor().getText());
            check(declared.contains("public int product(int " + visited.get(2) + ", int " + visited.get(3) + ") {\n        return 0;\n    }"),
                    "the method stays as normal Java with the kept defaults:\n" + declared);
            check(edt(() -> frame.editor().getText().substring(0, frame.editor().caretOffset())).endsWith("return 0"), "the caret ends after the returned value");
            check(!edt(() -> frame.statusBar().text(StatusBar.Segment.MESSAGE)).contains("Tab: next"), "the key hint goes away");
            String caller = edt(() -> buffer(test));
            check(caller.contains("calculator.product(0, 0)") && caller.contains("// multiply in a comment stays") && caller.contains("\"multiply\""),
                    "the call in the other file follows the name; the comment and the string do not:\n" + caller);
            System.out.println("PASS Enter confirmed: product(...) declared in Calculator and called in CalculatorTest; homonyms untouched");

            // Undo takes the session back in coherent steps; Redo restores it.
            key(KeyEvent.VK_Z, CMD);
            await("undo rename", () -> buffer(calculator).contains("int multiply(") && buffer(test).contains("calculator.multiply(0, 0)"));
            key(KeyEvent.VK_Z, CMD);
            await("undo generation", () -> !buffer(calculator).contains("multiply("));
            key(KeyEvent.VK_Z, CMD | SHIFT);
            key(KeyEvent.VK_Z, CMD | SHIFT);
            await("redo", () -> buffer(calculator).contains("int product(") && buffer(test).contains("calculator.product(0, 0)"));
            System.out.println("PASS ⌘Z: rename (declaration and call together), then generation; ⇧⌘Z restores both");

            // Esc takes back only the session's edits and returns to the call.
            String testBefore = edt(() -> buffer(test)).replace("    }\n}", "    }\n\n    void divides() { long q = calculator.divide(4, 2); }\n}");
            edt(() -> { frame.openPath(test); return null; });
            await("test open", () -> test.equals(frame.currentFile()));
            edt(() -> { frame.editor().setText(testBefore); frame.editor().gotoOffset(testBefore.indexOf("divide(") + 2); frame.editor().requestFocusInWindow(); return null; });
            await("first check", () -> !frame.buildStatus().contains("CHECKING"));
            String calculatorBefore = edt(() -> buffer(calculator));
            createMethod("Create method 'divide(int, int)' in Calculator");
            await("divide template", () -> calculator.equals(frame.currentFile()) && frame.editor().templateActive());
            check(edt(() -> frame.editor().getSelectedText()).equals("long"), "the return type follows the declared variable: long");
            type("double");
            key(KeyEvent.VK_ESCAPE, 0);
            await("cancelled", () -> !frame.editor().templateActive() && test.equals(frame.currentFile()));
            await("generation taken back", () -> buffer(calculator).equals(calculatorBefore));
            check(edt(() -> buffer(test)).equals(testBefore), "the caller is unchanged");
            System.out.println("PASS Esc removed the generated divide(...) and its edits only, and returned to the call");

            // Local rename reuses the template: one linked field, every occurrence, one Undo step.
            int total = testBefore.indexOf("int total") + 4;
            edt(() -> { frame.editor().gotoOffset(total + 2); frame.editor().requestFocusInWindow(); return null; });
            await("editor focus", () -> frame.editor().isFocusOwner());
            key(KeyEvent.VK_R, CMD | OPTION);           // TurboJ Rename
            await("rename template", () -> frame.editor().templateActive() && frame.editor().caretCount() == 3);
            type("sum");
            check(edt(() -> frame.editor().getText()).contains("int sum = 0;\n        sum += 5;\n        assertEquals(5, sum);"), "every occurrence changes together");
            key(KeyEvent.VK_ENTER, 0);
            await("rename confirmed", () -> !frame.editor().templateActive());
            key(KeyEvent.VK_Z, CMD);
            await("one undo", () -> frame.editor().getText().equals(testBefore));
            System.out.println("PASS local Rename edits all occurrences in place; Enter checks it semantically; one ⌘Z restores");
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }

    private static void createMethod(String title) throws Exception {
        key(KeyEvent.VK_1, CMD);
        await("quick fixes", () -> named("quick-actions-proposals") instanceof JList<?> list && list.getModel().getSize() > 0);
        edt(() -> {
            JList<?> list = (JList<?>) named("quick-actions-proposals");
            for (int i = 0; i < list.getModel().getSize(); i++)
                if (((Proposal) list.getModel().getElementAt(i)).title().equals(title)) { list.setSelectedIndex(i); return null; }
            var titles = new ArrayList<String>();
            for (int i = 0; i < list.getModel().getSize(); i++) titles.add(((Proposal) list.getModel().getElementAt(i)).title());
            throw new AssertionError("Missing " + title + " in " + titles);
        });
        key(KeyEvent.VK_ENTER, 0);
    }

    private static String buffer(Path file) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) return file.equals(frame.currentFile()) ? frame.editor().getText() : frame.bufferText(file);
        return edt(() -> buffer(file));
    }

    private static void open(Path file, int caret) throws Exception {
        edt(() -> { frame.openPath(file); return null; });
        await("open " + file.getFileName(), () -> file.equals(frame.currentFile()));
        await("first check", () -> !frame.buildStatus().contains("CHECKING") && frame.currentResult() != null);
        edt(() -> { frame.editor().gotoOffset(caret); frame.editor().requestFocusInWindow(); return null; });
        await("editor focus", () -> frame.editor().isFocusOwner());
    }

    private static void type(String text) throws Exception {
        edt(() -> {
            Component focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
            for (char c : text.toCharArray())
                Toolkit.getDefaultToolkit().getSystemEventQueue().postEvent(new KeyEvent(focus, KeyEvent.KEY_TYPED, System.currentTimeMillis(), 0, KeyEvent.VK_UNDEFINED, c));
            return null;
        });
        Thread.sleep(150);
    }

    private static Component named(String name) { return named(frame, name); }
    private static Component named(Component c, String name) {
        if (name.equals(c.getName()) && c.isShowing()) return c;
        if (c instanceof Container container) for (Component child : container.getComponents()) {
            Component match = named(child, name); if (match != null) return match;
        }
        if (c instanceof Window window) for (Window owned : window.getOwnedWindows()) {
            Component match = named(owned, name); if (match != null) return match;
        }
        return null;
    }
    private static void key(int code, int modifiers) throws Exception {
        await("keyboard focus", () -> KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner() != null);
        edt(() -> {
            Component focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
            for (int kind : new int[]{KeyEvent.KEY_PRESSED, KeyEvent.KEY_RELEASED})
                Toolkit.getDefaultToolkit().getSystemEventQueue().postEvent(new KeyEvent(focus, kind, System.currentTimeMillis(), modifiers, code, KeyEvent.CHAR_UNDEFINED));
            return null;
        });
        Thread.sleep(120);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void await(String what, Callable<Boolean> check) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (System.nanoTime() < end) { if (edt(check)) return; Thread.sleep(25); }
        throw new AssertionError("Timed out waiting for " + what + "; file " + edt(frame::currentFile) + "; template "
                + edt(() -> frame.editor().templateActive()) + "; message " + edt(() -> frame.statusBar().lastMessage())
                + "; dialogs " + edt(() -> dev.turboj.commandj.swing.OverlayHost.dialogs(frame).stream().map(d -> d.getTitle()).toList())
                + "; editor:\n" + edt(() -> frame.editor().getText()));
    }
    private static <T> T edt(Callable<T> task) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) return task.call();
        var future = new FutureTask<>(task); SwingUtilities.invokeAndWait(future); return future.get();
    }
}
