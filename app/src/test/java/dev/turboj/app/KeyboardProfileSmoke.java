package dev.turboj.app;

import dev.turboj.commandj.keymap.*;
import dev.turboj.commandj.swing.*;
import dev.turboj.config.Settings;
import java.awt.*;
import java.awt.event.KeyEvent;
import java.nio.file.*;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.*;
import javax.swing.*;

/** Real-window profile routes and persistence. Fixture/custom binding setup is programmatic;
 * profile changes and editing probes use Swing keyboard events only, with no mouse input. */
public final class KeyboardProfileSmoke {
    private static final StringBuilder report = new StringBuilder();
    private static final String CUSTOM_BINDING = "META+ALT+SHIFT+F12";
    private static TurboFrame frame;
    private static int checks;

    public static void main(String[] args) throws Exception {
        Path output = Path.of("app/target/keyboard-profile-smoke").toAbsolutePath();
        Files.createDirectories(output);
        Path artifact = Path.of(TurboFrame.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        report.append("Started UTC: ").append(java.time.Instant.now()).append('\n');
        report.append("Artifact: ").append(artifact).append('\n');
        if (Files.isRegularFile(artifact)) report.append("SHA-256: ").append(java.util.HexFormat.of().formatHex(
                java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(artifact)))).append('\n');
        report.append("Runtime: ").append(System.getProperty("java.version"))
                .append("; Swing key dispatch, no mouse; isolated settings; fixture and custom binding setup programmatic.\n");
        System.setProperty("turboj.config", Files.createTempDirectory(output, "settings-").toString());
        System.setProperty("apple.laf.useScreenMenuBar", "false");
        frame = open(new Settings());
        try {
            waitFor(() -> frame.isActive() && frame.editor().isFocusOwner(), "initial editor focus");
            check(edt(() -> frame.commandKeymaps().selectedId()).equals("intellij-macos"), "New settings default to IntelliJ macOS");
            check(edt(() -> frame.commandKeymaps().replaceCommandBindings(frame.commandRegistry(),
                    "editor.trimTrailingWhitespace", List.of(KeyBinding.editor(CUSTOM_BINDING)), Set.of()).applied()),
                    "Fixture installs one conflict-free IntelliJ custom binding");
            for (String profile : List.of("intellij-macos", "eclipse-macos", "vscode-macos", "turboj-classic")) {
                palette("keymap.profile." + profile);
                waitFor(() -> frame.commandKeymaps().selectedId().equals(profile) && frame.editor().isFocusOwner(), "profile " + profile);
                check(true, "Palette switches to " + profile + " using the previously active palette shortcut");
                selectedMarker(profile);
                if (!profile.equals("turboj-classic")) commandD(profile);
            }
            menuSwitch("vscode-macos");
            comboSwitch("eclipse-macos");
            palette("keymap.profile.intellij-macos");
            waitFor(() -> frame.commandKeymaps().selectedId().equals("intellij-macos") && frame.editor().isFocusOwner(), "return to customized IntelliJ profile");
            check(customBindingPresent(), "Switching profiles preserves the IntelliJ custom override");
            edt(() -> { frame.commandKeymaps().duplicate("keyboard-smoke-custom", "Smoke custom profile"); return null; });
            selectedMarker(null);
            check(true, "A user-created custom profile does not falsely mark a bundled profile selected");
            palette("keymap.profile.vscode-macos");
            waitFor(() -> frame.commandKeymaps().selectedId().equals("vscode-macos"), "select persisted profile");
            waitFor(() -> {
                Settings saved = Settings.load();
                if (!saved.keymapProfile.equals("vscode-macos") || saved.commandKeymapJson == null) return false;
                var restored = new KeymapManager(); KeymapJson.read(restored, saved.commandKeymapJson);
                return restored.selectedId().equals("vscode-macos")
                        && restored.profiles().stream().anyMatch(profile -> profile.id().equals("keyboard-smoke-custom"));
            }, "latest profile preferences persisted");
            edt(() -> { frame.shutdown(); frame.dispose(); return null; });
            frame = open(Settings.load());
            waitFor(() -> frame.isActive() && frame.editor().isFocusOwner(), "reopened editor focus");
            check(edt(() -> frame.commandKeymaps().selectedId()).equals("vscode-macos"), "A new frame reloads the selected VS Code profile from isolated disk settings");
            selectedMarker("vscode-macos");
            commandD("vscode-macos");
            palette("keymap.profile.intellij-macos");
            waitFor(() -> frame.commandKeymaps().selectedId().equals("intellij-macos") && frame.editor().isFocusOwner(), "reopen customized profile");
            check(customBindingPresent(), "Reloading preserves the custom IntelliJ binding");
            fixture("alpha   \n", 1);
            stroke(KeyStrokeSpec.parse(CUSTOM_BINDING));
            check(edt(() -> frame.editor().getText()).equals("alpha\n"), "Reloaded custom shortcut still executes its actual editor action");
            report.append("Completed UTC: ").append(java.time.Instant.now()).append('\n');
            report.append("PASS complete: ").append(checks).append(" assertions.\n");
        } catch (Throwable failure) {
            report.append("FAIL ").append(failure).append('\n'); throw failure;
        } finally {
            Files.writeString(output.resolve("report.txt"), report.toString());
            edt(() -> { frame.shutdown(); frame.dispose(); return null; });
        }
    }

    private static TurboFrame open(Settings settings) throws Exception {
        return edt(() -> {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            var result = new TurboFrame(settings, Map.of());
            result.setVisible(true); result.toFront(); result.editor().requestFocusInWindow(); return result;
        });
    }
    private static boolean customBindingPresent() throws Exception {
        return edt(() -> frame.commandKeymaps().commandBindings(frame.commandRegistry(), "editor.trimTrailingWhitespace")
                .stream().anyMatch(binding -> binding.sequence().equals(KeySequence.parse(CUSTOM_BINDING))));
    }
    private static void fixture(String text, int offset) throws Exception {
        edt(() -> { frame.editor().setText(text); frame.editor().gotoOffset(offset); return null; });
    }
    private static void commandD(String profile) throws Exception {
        String source = "alpha\nbeta\nalpha";
        fixture(source, 1);
        key(KeyEvent.VK_D, KeyEvent.META_DOWN_MASK);
        switch (profile) {
            case "intellij-macos" -> check(edt(() -> frame.editor().getText()).equals("alpha\nalpha\nbeta\nalpha"), "IntelliJ Cmd+D duplicates the current line");
            case "eclipse-macos" -> check(edt(() -> frame.editor().getText()).equals("beta\nalpha"), "Eclipse Cmd+D deletes the current line");
            case "vscode-macos" -> {
                check(edt(() -> frame.editor().getSelectedText()).equals("alpha"), "VS Code Cmd+D selects the current occurrence");
                key(KeyEvent.VK_D, KeyEvent.META_DOWN_MASK);
                check(edt(() -> frame.editor().caretCount()) == 2 && edt(() -> frame.editor().getText()).equals(source), "VS Code repeated Cmd+D adds the next occurrence without modifying source");
                key(KeyEvent.VK_ESCAPE, 0);
            }
            default -> throw new AssertionError(profile);
        }
    }
    private static JMenu profilesMenu() {
        return find(frame.getJMenuBar(), JMenu.class, menu -> "Keyboard Profile".equals(menu.getText()));
    }
    private static void selectedMarker(String expected) throws Exception {
        int count = edt(() -> {
            int selected = 0;
            for (Component component : profilesMenu().getMenuComponents()) if (component instanceof JRadioButtonMenuItem item && item.isSelected()) {
                selected++;
                String id = expected == null ? "" : "keymap.profile." + expected;
                if (!id.equals(item.getActionCommand())) throw new AssertionError("Wrong selected profile marker: " + item.getActionCommand());
            }
            return selected;
        });
        check(count == (expected == null ? 0 : 1), "Keyboard Profile menu marker matches " + (expected == null ? "custom profile" : expected));
    }
    private static void menuSwitch(String profile) throws Exception {
        key(KeyEvent.VK_F10, 0); // This route deliberately starts in the Classic profile.
        waitFor(() -> MenuSelectionManager.defaultManager().getSelectedPath().length > 0, "menu opens");
        for (int i = 0; i < 20 && !edt(() -> topMenu().equals("Options")); i++) key(KeyEvent.VK_RIGHT, 0);
        check(edt(KeyboardProfileSmoke::topMenu).equals("Options"), "Keyboard arrows reach Options");
        for (int i = 0; i < 20 && !edt(() -> selectedMenuItem() instanceof JMenu menu && "Keyboard Profile".equals(menu.getText())); i++) key(KeyEvent.VK_DOWN, 0);
        check(edt(() -> selectedMenuItem() instanceof JMenu menu && "Keyboard Profile".equals(menu.getText())), "Keyboard arrows select the Keyboard Profile submenu");
        key(KeyEvent.VK_RIGHT, 0);
        for (int i = 0; i < 10 && !edt(() -> selectedMenuItem() != null
                && ("keymap.profile." + profile).equals(selectedMenuItem().getActionCommand())); i++) key(KeyEvent.VK_DOWN, 0);
        check(edt(() -> selectedMenuItem() != null && ("keymap.profile." + profile).equals(selectedMenuItem().getActionCommand())), "Keyboard arrows reach the requested profile menu item");
        key(KeyEvent.VK_ENTER, 0);
        waitFor(() -> frame.commandKeymaps().selectedId().equals(profile) && frame.editor().isFocusOwner(), "menu profile applies");
        check(true, "Enter changes the profile through Options → Keyboard Profile");
        selectedMarker(profile);
    }
    private static String topMenu() {
        for (var item : MenuSelectionManager.defaultManager().getSelectedPath())
            if (item instanceof JMenu menu) return menu.getText();
        return "";
    }
    private static JMenuItem selectedMenuItem() {
        var path = MenuSelectionManager.defaultManager().getSelectedPath();
        return path.length > 0 && path[path.length - 1] instanceof JMenuItem item ? item : null;
    }
    private static void comboSwitch(String profile) throws Exception {
        palette("keymap");
        waitFor(() -> keymap() != null, "Keymap Editor opens");
        Thread.sleep(150);
        for (int i = 0; i < 30 && !edt(() -> focus() instanceof JComboBox<?>); i++) {
            Component before = edt(KeyboardProfileSmoke::focus); key(KeyEvent.VK_TAB, 0);
            waitFor(() -> focus() != before, "Tab traverses keymap controls");
        }
        check(edt(() -> focus() instanceof JComboBox<?>), "Tab reaches the existing Keyboard Profile combo");
        JComboBox<?> combo = edt(() -> (JComboBox<?>) focus());
        edt(() -> {
            report.append("Combo UI: ").append(combo.getUI().getClass().getName()).append('\n');
            for (String shortcut : List.of("alt DOWN", "SPACE", "DOWN", "UP", "HOME", "ENTER", "ESCAPE")) {
                KeyStroke stroke = KeyStroke.getKeyStroke(shortcut);
                Object action = combo.getInputMap(JComponent.WHEN_FOCUSED).get(stroke);
                if (action == null) action = combo.getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT).get(stroke);
                String diagnostic = "Combo binding " + shortcut + " -> " + action
                        + "; action=" + (action == null ? "none" : combo.getActionMap().get(action));
                report.append(diagnostic).append('\n'); System.out.println(diagnostic);
            }
            return null;
        });
        int target = edt(() -> {
            for (int i = 0; i < combo.getItemCount(); i++)
                if (combo.getItemAt(i) instanceof KeymapProfile value && value.id().equals(profile)) return i;
            throw new AssertionError("Missing profile in combo: " + profile);
        });
        String previousProfile = edt(() -> frame.commandKeymaps().selectedId());
        key(KeyEvent.VK_DOWN, KeyEvent.ALT_DOWN_MASK);
        waitFor(combo::isPopupVisible, "combo opens with keyboard");
        key(KeyEvent.VK_ESCAPE, 0);
        waitFor(() -> !combo.isPopupVisible() && keymap() != null && focus() == combo,
                "Escape cancels only the profile dropdown");
        check(edt(() -> frame.commandKeymaps().selectedId()).equals(previousProfile),
                "Escape closes the profile dropdown without closing Settings or changing profile");
        key(KeyEvent.VK_SPACE, 0);
        waitFor(combo::isPopupVisible, "Space reopens the profile dropdown");
        key(KeyEvent.VK_HOME, 0);
        for (int i = 0; i < target; i++) key(KeyEvent.VK_DOWN, 0);
        key(KeyEvent.VK_ENTER, 0);
        waitFor(() -> frame.commandKeymaps().selectedId().equals(profile), "combo profile applies");
        check(true, "Existing Keymap Editor combo changes profile entirely by keyboard");
        key(KeyEvent.VK_ESCAPE, 0);
        waitFor(() -> keymap() == null && frame.editor().isFocusOwner(), "Keymap Editor closes to source");
        selectedMarker(profile);
    }
    private static KeymapEditorDialog keymap() {
        for (var dialog : OverlayHost.dialogs(frame)) if (dialog instanceof KeymapEditorDialog value && value.isVisible()) return value;
        return null;
    }
    private static <T extends Component> T find(Component parent, Class<T> type, java.util.function.Predicate<T> matches) {
        if (type.isInstance(parent) && matches.test(type.cast(parent))) return type.cast(parent);
        Component[] children = parent instanceof JMenu menu ? menu.getMenuComponents()
                : parent instanceof Container container ? container.getComponents() : new Component[0];
        for (Component child : children) { T found = find(child, type, matches); if (found != null) return found; }
        return null;
    }
    private static void palette(String id) throws Exception {
        var binding = edt(() -> frame.commandKeymaps().commandBindings(frame.commandRegistry(), "palette").getFirst());
        for (var stroke : binding.sequence().strokes()) stroke(stroke);
        waitFor(() -> palette() != null && palette().searchField().isFocusOwner(), "palette focus using active profile");
        type(edt(() -> frame.commandRegistry().find(id).orElseThrow().title()));
        waitFor(() -> palette() != null && palette().results().getModel().getSize() > 0, "palette matches");
        int target = edt(() -> {
            var list = palette().results(); String canonical = frame.commandRegistry().canonicalId(id);
            for (int i = 0; i < list.getModel().getSize(); i++) if (list.getModel().getElementAt(i).command().id().equals(canonical)) return i;
            throw new AssertionError("Missing command: " + id);
        });
        int selected = edt(() -> palette().results().getSelectedIndex());
        for (int i = selected; i < target; i++) key(KeyEvent.VK_DOWN, 0);
        for (int i = selected; i > target; i--) key(KeyEvent.VK_UP, 0);
        key(KeyEvent.VK_ENTER, 0); waitFor(() -> palette() == null, "palette closes");
    }
    private static CommandPaletteDialog palette() {
        for (var dialog : OverlayHost.dialogs(frame)) if (dialog instanceof CommandPaletteDialog value && value.isVisible()) return value;
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
        throw new AssertionError("Timeout: " + label + "; focus="
                + edt(() -> focus() == null ? "none" : focus().getClass().getSimpleName()));
    }
    private static <T> T edt(Callable<T> action) throws Exception {
        var task = new FutureTask<>(action); SwingUtilities.invokeAndWait(task); return task.get();
    }
}
