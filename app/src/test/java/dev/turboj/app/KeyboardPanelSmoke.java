package dev.turboj.app;

import dev.turboj.commandj.keymap.KeyStrokeSpec;
import dev.turboj.commandj.swing.CommandPaletteDialog;
import dev.turboj.commandj.swing.OverlayHost;
import dev.turboj.compiler.CompilerDiagnostic;
import dev.turboj.config.Settings;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.*;
import javax.swing.*;
import javax.swing.text.JTextComponent;

/** Supplementary real-window keyboard focus checks; fixture setup is programmatic.
 * Commands, popup navigation and JShell input use actual Swing key dispatch, never mouse events.
 * This does not validate a physical keyboard layout or substitute for a human audit. */
public final class KeyboardPanelSmoke {
    private static final StringBuilder report = new StringBuilder();
    private static TurboFrame frame;
    private static int checks;

    public static void main(String[] args) throws Exception {
        Path output = Path.of("app/target/keyboard-panel-smoke").toAbsolutePath();
        Files.createDirectories(output);
        Path artifact = Path.of(TurboFrame.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        report.append("Started UTC: ").append(java.time.Instant.now()).append('\n');
        report.append("Artifact: ").append(artifact).append('\n');
        if (Files.isRegularFile(artifact)) report.append("SHA-256: ").append(java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(artifact)))).append('\n');
        report.append("Runtime: ").append(System.getProperty("java.version"))
                .append("; actual Swing key dispatch, no mouse events; Classic profile.\n");
        System.setProperty("turboj.config", Files.createTempDirectory(output, "settings-").toString());
        System.setProperty("apple.laf.useScreenMenuBar", "false");
        frame = edt(() -> {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            Settings settings = new Settings(); settings.keymapProfile = "turboj-classic";
            var result = new TurboFrame(settings, Map.of());
            result.setVisible(true); result.toFront(); result.editor().requestFocusInWindow(); return result;
        });
        try {
            waitFor(() -> frame.isActive() && frame.editor().isFocusOwner(), "initial source focus");
            if (args.length == 0 || !args[0].equals("--explorer-only")) {
                jshell();
                consoleNavigation();
                menus();
                completion();
                diagnostics();
            }
            explorer(output);
            report.append("Completed UTC: ").append(java.time.Instant.now()).append('\n');
            report.append("PASS complete: ").append(checks).append(" assertions.\n");
        } catch (Throwable failure) {
            report.append("FAIL ").append(failure).append('\n'); throw failure;
        } finally {
            Files.writeString(output.resolve("report.txt"), report.toString());
            edt(() -> { frame.shutdown(); frame.dispose(); return null; });
        }
    }

    private static void jshell() throws Exception {
        String source = edt(() -> frame.editor().getText());
        palette("view.focusJShell");
        waitFor(() -> focus() instanceof JTextField field && field.isEnabled()
                && SwingUtilities.isDescendingFrom(field, frame.repl()), "JShell input focus");
        type("1+1"); key(KeyEvent.VK_ENTER, 0);
        waitFor(() -> frame.repl().text().contains("\n2\n") && focus() instanceof JTextField field
                && field.isEnabled(), "actual JShell result 2");
        check(edt(() -> frame.repl().text()).contains("> 1+1\n2\n"), "Palette → JShell → typed 1+1 → Enter produces actual 2");
        key(KeyEvent.VK_ESCAPE, 0); waitFor(() -> frame.editor().isFocusOwner(), "Escape from JShell");
        check(source.equals(edt(() -> frame.editor().getText())), "JShell input and Escape preserve source and restore editor");
    }

    private static void menus() throws Exception {
        String source = edt(() -> frame.editor().getText());
        key(KeyEvent.VK_F10, 0);
        waitFor(() -> MenuSelectionManager.defaultManager().getSelectedPath().length > 0, "F10 opens menu");
        check(true, "Classic F10 opens native application menu");
        key(KeyEvent.VK_DOWN, 0);
        MenuElement[] before = edt(() -> MenuSelectionManager.defaultManager().getSelectedPath());
        key(KeyEvent.VK_RIGHT, 0);
        waitFor(() -> !java.util.Arrays.equals(before, MenuSelectionManager.defaultManager().getSelectedPath()), "Right navigates menu");
        check(true, "Arrow keys navigate the open menu");
        for (int i = 0; i < 5 && edt(() -> MenuSelectionManager.defaultManager().getSelectedPath().length > 0); i++)
            key(KeyEvent.VK_ESCAPE, 0);
        waitFor(() -> MenuSelectionManager.defaultManager().getSelectedPath().length == 0
                && frame.editor().isFocusOwner(), "Escape closes menu and restores source");
        check(source.equals(edt(() -> frame.editor().getText())), "Menu Escape restores source focus without edits");
    }

    private static void consoleNavigation() throws Exception {
        String source = edt(() -> frame.editor().getText());
        String fixture = "KEYBOARD LINE ONE\nKEYBOARD LINE TWO\nKEYBOARD LINE THREE\n";
        edt(() -> { frame.console().append(fixture, false); return null; });
        waitFor(() -> frame.console().text().contains(fixture), "console fixture text");
        palette("view.focusConsole");
        waitFor(() -> focus() instanceof JTextComponent && !frame.editor().isFocusOwner(), "console caret focus");
        JTextComponent text = edt(() -> (JTextComponent) focus());
        String before = edt(text::getText);
        edt(() -> { text.setCaretPosition(text.getText().indexOf("KEYBOARD LINE TWO") + 5); return null; });
        int originalRow = edt(() -> text.getDocument().getDefaultRootElement().getElementIndex(text.getCaretPosition()));
        key(KeyEvent.VK_UP, 0);
        check(edt(() -> text.getDocument().getDefaultRootElement().getElementIndex(text.getCaretPosition())) == originalRow - 1,
                "Console Up moves the actual caret to the previous logical line");
        key(KeyEvent.VK_DOWN, 0);
        check(edt(() -> text.getDocument().getDefaultRootElement().getElementIndex(text.getCaretPosition())) == originalRow,
                "Console Down moves the actual caret to the following logical line");
        key(KeyEvent.VK_UP, KeyEvent.SHIFT_DOWN_MASK);
        check(edt(() -> text.getSelectedText() != null && !text.getSelectedText().isEmpty()),
                "Console Shift+Up selects output text with the keyboard");
        key(KeyEvent.VK_ESCAPE, 0); waitFor(() -> frame.editor().isFocusOwner(), "Escape from output caret navigation");
        check(edt(text::getText).startsWith(before) && source.equals(edt(() -> frame.editor().getText())),
                "Console caret navigation preserves output/source and Escape returns editor focus");
    }

    private static void completion() throws Exception {
        String source = "class Main {\n    void demo() {\n        System.out.pr\n    }\n}\n";
        edt(() -> { frame.editor().setText(source);
            frame.editor().gotoOffset(source.indexOf("System.out.pr") + "System.out.pr".length()); return null; });
        palette("complete");
        waitFor(() -> frame.intelligence().alternativesVisible(), "semantic completion popup");
        check(edt(() -> frame.intelligence().lastCompletion().proposals().stream()
                .anyMatch(proposal -> proposal.display().contains("println"))), "Completion popup contains real JDK println proposals");
        JList<?> list = edt(() -> visibleList(frame.getLayeredPane(), null));
        check(list != null && edt(() -> list.getModel().getSize()) > 1, "Completion choices are visible and navigable");
        int previous = edt(list::getSelectedIndex);
        key(KeyEvent.VK_DOWN, 0);
        check(edt(list::getSelectedIndex) == previous + 1, "Down selects next completion without applying it");
        key(KeyEvent.VK_UP, 0);
        check(edt(list::getSelectedIndex) == previous, "Up returns to previous completion");
        key(KeyEvent.VK_ESCAPE, 0);
        waitFor(() -> !frame.intelligence().alternativesVisible() && frame.editor().isFocusOwner(), "Escape dismisses completion");
        check(source.equals(edt(() -> frame.editor().getText())), "Completion dismissal preserves source and editor focus");
    }

    private static void diagnostics() throws Exception {
        var fixture = new StringBuilder("class Main {\n    int value() { return missingValue; }\n");
        for (int i = 0; i < 39; i++) fixture.append("    int other").append(i)
                .append("() { return missingOther").append(i).append("; }\n");
        String source = fixture.append("}\n").toString();
        edt(() -> { frame.editor().setText(source); frame.editor().gotoOffset(0); return null; });
        palette("compile");
        waitFor(() -> frame.currentResult() != null && frame.currentResult().version() == frame.editor().version()
                && !frame.currentResult().success(), "current real javac diagnostics");
        palette("view.focusConsole");
        waitFor(() -> !frame.editor().isFocusOwner() && focus() instanceof javax.swing.text.JTextComponent,
                "console focus before diagnostic navigation");
        palette("view.focusDiagnostics");
        waitFor(() -> OverlayHost.active(frame), "diagnostic dialog opens");
        Thread.sleep(150); // Initial dialog focus is delivered by the native event queue.
        for (int i = 0; i < 10 && !edt(() -> focus() instanceof JList<?> list
                && "compiler-diagnostics".equals(list.getName())); i++) {
            Component previousFocus = edt(KeyboardPanelSmoke::focus);
            key(KeyEvent.VK_TAB, 0);
            waitFor(() -> focus() != previousFocus, "Tab traverses diagnostic controls");
        }
        waitFor(() -> focus() instanceof JList<?> list && "compiler-diagnostics".equals(list.getName()), "diagnostic list focus");
        JList<?> diagnostics = edt(() -> (JList<?>) focus());
        check(edt(() -> diagnostics.getModel().getSize()) >= 30, "Diagnostic fixture contains enough real errors to require scrolling");
        int originalSelection = edt(diagnostics::getSelectedIndex);
        key(KeyEvent.VK_END, 0);
        waitFor(() -> diagnostics.getLastVisibleIndex() == diagnostics.getModel().getSize() - 1, "End reveals final diagnostic");
        check(edt(diagnostics::getSelectedIndex) == originalSelection,
                "Diagnostics End reveals the final error while preserving Aqua selection semantics");
        key(KeyEvent.VK_HOME, 0);
        waitFor(() -> diagnostics.getFirstVisibleIndex() == 0, "Home reveals first diagnostic");
        check(edt(diagnostics::getSelectedIndex) == originalSelection,
                "Diagnostics Home reveals the first error without changing selection");
        CompilerDiagnostic selected = edt(() -> (CompilerDiagnostic) ((JList<?>) focus()).getSelectedValue());
        check(selected != null && selected.isError() && selected.start() > 0, "Diagnostics list exposes current compilation error");
        key(KeyEvent.VK_ENTER, 0);
        waitFor(() -> !OverlayHost.active(frame) && frame.editor().isFocusOwner(), "Enter opens diagnostic source");
        Thread.sleep(150); // Allow deferred overlay restoration and native focus events to settle.
        check(edt(() -> frame.editor().isFocusOwner() && frame.editor().caretOffset() == selected.start()),
                "Diagnostics opened from console: Enter navigates to exact source offset and retains editor focus"
                        + "; focus=" + edt(() -> focus() == null ? "none" : focus().getClass().getSimpleName())
                        + "; caret=" + edt(() -> frame.editor().caretOffset()) + "; expected=" + selected.start());
        check(source.equals(edt(() -> frame.editor().getText())), "Diagnostic navigation preserves source text");
    }

    private static void explorer(Path output) throws Exception {
        Path project = Files.createTempDirectory(output, "reactor-");
        Files.createDirectories(project.resolve("src/main/java"));
        Files.writeString(project.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion><groupId>dev.turboj.keyboard</groupId><artifactId>tree</artifactId><version>1.0.0</version><properties><maven.compiler.release>21</maven.compiler.release></properties></project>");
        Files.writeString(project.resolve("src/main/java/Example.java"), "class Example {}\n");
        edt(() -> { frame.mavenProjects().open(project); return null; });
        waitFor(() -> !frame.mavenProjects().busy() && frame.mavenProjects().workspace() != null, "fixture Maven import");
        palette("view.focusExplorer");
        waitFor(() -> focus() instanceof JTree && frame.mavenProjects().hasExplorerFocus(), "actual explorer tree focus");
        JTree tree = edt(() -> (JTree) focus());
        // Set up the same root state for each independent arrow probe; actions themselves use keys.
        edt(() -> { tree.setSelectionRow(0); tree.expandRow(0); return null; });
        int expandedRows = edt(tree::getRowCount);
        edt(() -> {
            report.append("Tree UI: ").append(tree.getUI().getClass().getName()).append('\n');
            for (String shortcut : java.util.List.of("LEFT", "RIGHT")) {
                Object action = tree.getInputMap().get(KeyStroke.getKeyStroke(shortcut));
                report.append("Tree binding ").append(shortcut).append(" -> ").append(action)
                        .append("; action=").append(action == null ? null : tree.getActionMap().get(action)).append('\n');
            }
            return null;
        });
        key(KeyEvent.VK_LEFT, 0); Thread.sleep(150);
        boolean collapsed = edt(() -> !tree.isExpanded(0) && tree.getRowCount() < expandedRows);
        report.append("Left probe: initial expanded rows=").append(expandedRows).append("; after rows=")
                .append(edt(tree::getRowCount)).append("; root expanded=").append(edt(() -> tree.isExpanded(0))).append('\n');
        edt(() -> { tree.collapseRow(0); tree.setSelectionRow(0); return null; });
        int collapsedRows = edt(tree::getRowCount);
        key(KeyEvent.VK_RIGHT, 0); Thread.sleep(150);
        boolean expanded = edt(() -> tree.isExpanded(0) && tree.getRowCount() > collapsedRows);
        report.append("Right probe: initial collapsed rows=").append(collapsedRows).append("; after rows=")
                .append(edt(tree::getRowCount)).append("; root expanded=").append(edt(() -> tree.isExpanded(0))).append('\n');
        check(collapsed, "Explorer Left collapses the actual selected expanded root");
        check(expanded, "Explorer Right expands the actual selected collapsed root");
        key(KeyEvent.VK_ESCAPE, 0); waitFor(() -> frame.editor().isFocusOwner(), "Escape from explorer");
        check(true, "Explorer Escape returns source focus after arrow navigation");
    }

    private static JList<?> visibleList(Component value, String name) {
        if (value instanceof JList<?> list && list.isShowing() && (name == null || name.equals(list.getName()))) return list;
        if (value instanceof Container container) for (Component child : container.getComponents()) {
            JList<?> found = visibleList(child, name); if (found != null) return found;
        }
        return null;
    }
    private static void palette(String id) throws Exception {
        var binding = edt(() -> frame.commandKeymaps().commandBindings(frame.commandRegistry(), "palette").getFirst());
        for (var stroke : binding.sequence().strokes()) stroke(stroke);
        waitFor(() -> palette() != null && palette().searchField().isFocusOwner(), "palette focus");
        type(edt(() -> frame.commandRegistry().find(id).orElseThrow().title()));
        waitFor(() -> palette() != null && palette().results().getModel().getSize() > 0, "palette results");
        int index = edt(() -> {
            var list = palette().results(); String canonical = frame.commandRegistry().canonicalId(id);
            for (int i = 0; i < list.getModel().getSize(); i++)
                if (list.getModel().getElementAt(i).command().id().equals(canonical)) return i;
            throw new AssertionError("Missing palette command: " + id);
        });
        int selected = edt(() -> palette().results().getSelectedIndex());
        for (int i = selected; i < index; i++) key(KeyEvent.VK_DOWN, 0);
        for (int i = selected; i > index; i--) key(KeyEvent.VK_UP, 0);
        key(KeyEvent.VK_ENTER, 0); waitFor(() -> palette() == null, "palette closes");
    }
    private static CommandPaletteDialog palette() {
        for (var dialog : OverlayHost.dialogs(frame))
            if (dialog instanceof CommandPaletteDialog palette && palette.isVisible()) return palette;
        return null;
    }
    private static void stroke(KeyStrokeSpec stroke) throws Exception {
        int modifiers = 0;
        for (var modifier : stroke.modifiers()) modifiers |= switch (modifier) {
            case META -> KeyEvent.META_DOWN_MASK; case ALT -> KeyEvent.ALT_DOWN_MASK;
            case CTRL -> KeyEvent.CTRL_DOWN_MASK; case SHIFT -> KeyEvent.SHIFT_DOWN_MASK;
        };
        key(KeyEvent.class.getField("VK_" + stroke.key()).getInt(null), modifiers);
    }
    private static void type(String text) throws Exception {
        for (char character : text.toCharArray()) edt(() -> {
            int code = KeyEvent.getExtendedKeyCodeForChar(character);
            int modifiers = Character.isUpperCase(character) ? KeyEvent.SHIFT_DOWN_MASK : 0;
            dispatch(KeyEvent.KEY_PRESSED, code, character, modifiers);
            dispatch(KeyEvent.KEY_TYPED, KeyEvent.VK_UNDEFINED, character, modifiers);
            dispatch(KeyEvent.KEY_RELEASED, code, character, modifiers); return null;
        });
    }
    private static void key(int code, int modifiers) throws Exception {
        edt(() -> { dispatch(KeyEvent.KEY_PRESSED, code, KeyEvent.CHAR_UNDEFINED, modifiers);
            dispatch(KeyEvent.KEY_RELEASED, code, KeyEvent.CHAR_UNDEFINED, modifiers); return null; });
    }
    private static void dispatch(int kind, int code, char character, int modifiers) {
        Component target = focus(); if (target == null) throw new AssertionError("Keyboard focus lost");
        KeyboardFocusManager.getCurrentKeyboardFocusManager().dispatchEvent(
                new KeyEvent(target, kind, System.currentTimeMillis(), modifiers, code, character));
    }
    private static Component focus() { return KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner(); }
    private static void check(boolean value, String label) { if (!value) throw new AssertionError(label);
        checks++; report.append("PASS ").append(label).append('\n'); System.out.println("PASS " + label); }
    private static void waitFor(Callable<Boolean> test, String label) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < deadline) { if (edt(test)) return; Thread.sleep(15); }
        throw new AssertionError("Timeout: " + label + "; focus=" + edt(() -> String.valueOf(focus())));
    }
    private static <T> T edt(Callable<T> action) throws Exception {
        var task = new FutureTask<>(action); SwingUtilities.invokeAndWait(task); return task.get();
    }
}
