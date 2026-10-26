package dev.turboj.app;

import dev.turboj.commandj.swing.CommandPaletteDialog;
import dev.turboj.commandj.swing.OverlayHost;
import dev.turboj.config.Settings;
import dev.turboj.refactorj.api.JavaIntelligence.Completion;
import dev.turboj.refactorj.api.JavaQuickActions.Proposal;
import dev.turboj.ui.ProjectSearchDialog;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import javax.swing.*;

/** Real window, Eclipse macOS keymap, a plain default-package project: the basics a user reaches for first. */
public final class EclipseBasicsSmoke {
    private static TurboFrame frame;
    private static final String MISSPELLED = """
            import org.junit.jupiter.api.Test;

            public class CalculatorTest {
                @Test
                public void test() {
                    Calculator calculator = new Calculator1();
                }
            }
            """;

    public static void main(String[] args) throws Exception {
        Path session = Files.createTempDirectory("turboj-eclipse-basics-").toRealPath(); // Commits refuse symlinked roots (/var).
        System.setProperty("turboj.config", session.resolve("preferences").toString());
        Path project = Files.createDirectories(session.resolve("hello-world"));
        Path sources = Files.createDirectories(project.resolve("src/main/java"));
        Path calculator = sources.resolve("Calculator.java"), test = sources.resolve("CalculatorTest.java");
        Files.writeString(calculator, "public class Calculator {}\n");
        Files.writeString(test, MISSPELLED);
        try {
            frame = edt(() -> {
                Settings settings = new Settings(); settings.keymapProfile = "eclipse-macos";
                TurboFrame result = new TurboFrame(settings, Map.of()); result.setVisible(true); result.toFront(); return result;
            });
            edt(() -> { frame.openPath(project); return null; });
            await("plain project", () -> frame.projectStatus().contains("hello-world"));

            open(test, MISSPELLED.indexOf("();") + 3);
            key(KeyEvent.VK_1, KeyEvent.META_DOWN_MASK);
            await("Cmd+1 fixes", () -> titles().containsAll(List.of("Create class 'Calculator1'", "Change to 'Calculator'")));
            check(edt(() -> frame.editor().caretOffset()) == MISSPELLED.indexOf("Calculator1"), "Cmd+1 at the end of the line moved to the problem");
            System.out.println("PASS Cmd+1 at the end of 'new Calculator1();' offers Create class and Change to 'Calculator'");
            key(KeyEvent.VK_ESCAPE, 0);
            await("quick fix closed", () -> named("quick-actions-proposals") == null && frame.editor().isFocusOwner());

            key(KeyEvent.VK_O, KeyEvent.META_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK);
            await("organize notice", () -> frame.statusMessages().contains("Imports are already organized"));
            check(edt(EclipseBasicsSmoke::refactoringPreviews).isEmpty(), "No preview dialog when there is nothing to organize");
            check(!edt(() -> frame.console().text()).contains("is null"), "Organize Imports does not fail on an unresolved type");
            System.out.println("PASS Shift+Cmd+O with nothing to change reports it without a dialog or an error");

            key(KeyEvent.VK_T, KeyEvent.META_DOWN_MASK | KeyEvent.SHIFT_DOWN_MASK);
            await("Open Type", () -> searchDialog() != null);
            for (var tab : List.of(ProjectSearchDialog.Tab.FILES, ProjectSearchDialog.Tab.METHODS, ProjectSearchDialog.Tab.FIELDS,
                    ProjectSearchDialog.Tab.CLASSES)) {
                click("project-search-tab-" + tab.name().toLowerCase(Locale.ROOT));
                await(tab + " tab", () -> searchDialog().tab() == tab && searchDialog().queryField().isFocusOwner());
            }
            System.out.println("PASS the search tabs Files, Methods, Fields and Classes are selectable with the mouse");
            edt(() -> { searchDialog().queryField().setText("Calculator"); return null; });
            await("Calculator first", () -> searchDialog().results().getModel().getSize() > 0
                    && searchDialog().results().getModel().getElementAt(0).file().equals(calculator));
            key(KeyEvent.VK_ENTER, 0);
            await("Calculator opened", () -> calculator.equals(frame.currentFile()) && frame.editor().isFocusOwner());

            key(KeyEvent.VK_LEFT, KeyEvent.META_DOWN_MASK | KeyEvent.ALT_DOWN_MASK);
            await("back to the test", () -> test.equals(frame.currentFile()));
            key(KeyEvent.VK_RIGHT, KeyEvent.META_DOWN_MASK | KeyEvent.ALT_DOWN_MASK);
            await("forward to Calculator", () -> calculator.equals(frame.currentFile()));
            System.out.println("PASS Option+Cmd+← returns from a file opened with Open Type, Option+Cmd+→ goes forward");

            key(KeyEvent.VK_3, KeyEvent.META_DOWN_MASK);
            await("Find Action", () -> palette() != null);
            edt(() -> { palette().searchField().setText("Organize Imports"); return null; });
            await("Organize Imports row", () -> row(0).size() == 3 && row(0).get(1).startsWith("Organize Imports"));
            List<String> row = edt(() -> row(0));
            String menu = edt(() -> menuOf("organizeImports"));
            check(row.getFirst().equals("[" + menu + "]") && !row.get(2).isBlank(), "Find Action row is [Menu] Action Shortcut: " + row);
            System.out.println("PASS Find Action rows read like a menu: " + row);
            key(KeyEvent.VK_ESCAPE, 0);
            await("palette closed", () -> palette() == null);

            String typing = MISSPELLED.replace("new Calculator1();", "new Calcu");
            Path completing = sources.resolve("TypingTest.java");
            Files.writeString(completing, typing.replace("CalculatorTest", "TypingTest"));
            open(completing, typing.replace("CalculatorTest", "TypingTest").indexOf("new Calcu") + 9);
            key(KeyEvent.VK_SPACE, KeyEvent.CTRL_DOWN_MASK);
            await("Ctrl+Space proposes Calculator", () -> named("completion-choices") instanceof JList<?> list && list.getModel().getSize() > 0
                    && ((Completion) list.getModel().getElementAt(0)).text().startsWith("Calculator"));
            System.out.println("PASS Ctrl+Space after 'new Calcu' proposes Calculator (once macOS lets Ctrl+Space through)");
            System.exit(0);
        } catch (Throwable failure) {
            failure.printStackTrace();
            System.exit(1);
        }
    }

    private static List<String> titles() {
        return named("quick-actions-proposals") instanceof JList<?> list ? java.util.stream.IntStream.range(0, list.getModel().getSize())
                .mapToObj(i -> list.getModel().getElementAt(i)).filter(Proposal.class::isInstance)
                .map(value -> ((Proposal) value).title()).toList() : List.of();
    }

    private static List<Dialog> refactoringPreviews() {
        return Arrays.stream(Window.getOwnerlessWindows()).flatMap(w -> Arrays.stream(w.getOwnedWindows()))
                .filter(Dialog.class::isInstance).map(Dialog.class::cast)
                .filter(d -> d.isShowing() && d.getTitle().startsWith("RefactorJ")).toList();
    }

    private static ProjectSearchDialog searchDialog() {
        return OverlayHost.dialogs(frame).stream().filter(ProjectSearchDialog.class::isInstance).map(ProjectSearchDialog.class::cast)
                .filter(Component::isShowing).findFirst().orElse(null);
    }

    private static CommandPaletteDialog palette() {
        return OverlayHost.dialogs(frame).stream().filter(CommandPaletteDialog.class::isInstance).map(CommandPaletteDialog.class::cast)
                .filter(Component::isShowing).findFirst().orElse(null);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static List<String> row(int index) {
        JList list = palette().results();
        if (list.getModel().getSize() <= index) return List.of();
        var rendered = list.getCellRenderer().getListCellRendererComponent(list, list.getModel().getElementAt(index), index, false, false);
        if (!(rendered instanceof Container container)) return List.of();
        return Arrays.stream(container.getComponents()).filter(JLabel.class::isInstance).map(c -> ((JLabel) c).getText()).toList();
    }

    private static String menuOf(String command) {
        JMenuBar bar = frame.getJMenuBar();
        for (int i = 0; i < bar.getMenuCount(); i++) if (contains(bar.getMenu(i).getPopupMenu(), command)) return bar.getMenu(i).getText();
        return "";
    }

    private static boolean contains(Container container, String command) {
        for (Component child : container.getComponents()) {
            if (child instanceof JMenu menu && contains(menu.getPopupMenu(), command)) return true;
            if (child instanceof JMenuItem item && command.equals(item.getActionCommand())) return true;
        }
        return false;
    }

    private static void open(Path file, int caret) throws Exception {
        edt(() -> { frame.openPath(file); return null; });
        await("open " + file.getFileName(), () -> file.equals(frame.currentFile()));
        // The first check must settle first: its diagnostics would otherwise race the explicit request.
        await("first check of " + file.getFileName(), () -> !frame.buildStatus().contains("CHECKING")
                && !frame.buildStatus().contains("COMPILING") && frame.currentResult() != null);
        edt(() -> { frame.editor().gotoOffset(caret); frame.editor().requestFocusInWindow(); return null; });
        await("editor focus", () -> frame.editor().isFocusOwner());
    }

    private static void click(String name) throws Exception {
        edt(() -> {
            Component target = named(name);
            if (target == null) throw new AssertionError("Missing " + name);
            for (int kind : new int[]{MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED})
                Toolkit.getDefaultToolkit().getSystemEventQueue().postEvent(new MouseEvent(target, kind, System.currentTimeMillis(), 0,
                        target.getWidth() / 2, target.getHeight() / 2, 1, false, MouseEvent.BUTTON1));
            return null;
        });
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

    /** Posted, not dispatched: a dialog the key opens must not block the event that opened it. */
    private static void key(int code, int modifiers) throws Exception {
        edt(() -> {
            Component focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();
            if (focus == null) throw new AssertionError("No keyboard focus");
            for (int kind : new int[]{KeyEvent.KEY_PRESSED, KeyEvent.KEY_RELEASED})
                Toolkit.getDefaultToolkit().getSystemEventQueue().postEvent(
                        new KeyEvent(focus, kind, System.currentTimeMillis(), modifiers, code, KeyEvent.CHAR_UNDEFINED));
            return null;
        });
        Thread.sleep(50);
    }
    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
    private static void await(String what, Callable<Boolean> check) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (System.nanoTime() < end) { if (edt(check)) return; Thread.sleep(25); }
        throw new AssertionError("Timed out waiting for " + what + "; status " + edt(frame::buildStatus) + "; focus "
                + edt(() -> String.valueOf(KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner()).replaceAll(",.*", ""))
                + "; editor:\n" + edt(() -> frame.editor().getText()) + "; console:\n" + edt(() -> frame.console().text()));
    }
    private static <T> T edt(Callable<T> task) throws Exception {
        var future = new FutureTask<>(task); SwingUtilities.invokeAndWait(future); return future.get();
    }
}
