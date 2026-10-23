package com.turboj.app;

import com.turboj.config.Settings;
import com.turboj.ui.themes.Theme;
import com.turboj.ui.themes.ThemeBrowserDialog;

import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;

import javax.imageio.ImageIO;
import javax.swing.*;

/** Explicit native UI acceptance run: one document, one worker and the three shared themes. */
public final class ThemeSmoke {
    private static final StringBuilder report = new StringBuilder();

    public static void main(String[] args) throws Exception {
        Path target = Path.of("app/target").toAbsolutePath();
        Files.createDirectories(target);
        Path settings = Files.createTempDirectory(target, "theme-smoke-settings-");
        System.setProperty("turboj.config", settings.toString());
        System.setProperty("apple.laf.useScreenMenuBar", "false");
        TurboFrame frame =
                edt(
                        () -> {
                            UIManager.setLookAndFeel(
                                    UIManager.getCrossPlatformLookAndFeelClassName());
                            TurboFrame f = new TurboFrame(new Settings(), Map.of());
                            f.setVisible(true);
                            f.toFront();
                            f.editor().requestFocusInWindow();
                            return f;
                        });
        try {
            waitFor(() -> frame.editor().isFocusOwner(), "editor focus");
            List<JRadioButtonMenuItem> buttons = edt(() -> themeRadioItems(frame));
            check(
                    buttons.stream()
                            .map(AbstractButton::getText)
                            .toList()
                            .equals(List.of("Turbo Classic", "Modern Dark", "Modern Light")),
                    "Three presets in Options → Themes");
            check(edt(() -> buttons.getFirst().isSelected()), "Classic default selected");
            check(
                    edt(() -> buttons(frame.getJMenuBar()).isEmpty()),
                    "No permanent header theme buttons");
            nativeInput(frame, buttons);
            ghost(frame, buttons, target);
            documentation(frame, buttons);
            browser(frame, settings, target);
            randomWheel(frame, settings, target);
            note("PASS native shared-theme acceptance completed");
        } finally {
            Files.writeString(target.resolve("theme-smoke.txt"), report.toString());
            edt(
                    () -> {
                        frame.shutdown();
                        frame.dispose();
                        return null;
                    });
        }
    }

    private static void nativeInput(TurboFrame frame, List<JRadioButtonMenuItem> buttons)
            throws Exception {
        String source =
                "import java.util.Scanner; public class ThemeInput { public static void"
                    + " main(String[] args) { System.out.print(\"Number: \"); String value = new"
                    + " Scanner(System.in).nextLine(); System.out.println(\"Read \" + value); }}";
        edt(
                () -> {
                    frame.editor().setText(source);
                    frame.compile(true, false);
                    return null;
                });
        waitFor(
                () -> frame.isRunning() && frame.console().text().contains("Number: "),
                "real program prompt");
        edt(
                () -> {
                    frame.console().setInputText("20");
                    return null;
                });
        String transcript = edt(() -> frame.console().text());
        long version = edt(() -> frame.editor().version());
        for (int i = 0; i < 3; i++) {
            final int index = i;
            edt(
                    () -> {
                        buttons.get(index).doClick();
                        return null;
                    });
            check(edt(() -> frame.isRunning()), "theme switch stopped program");
            check(
                    edt(() -> frame.console().text()).equals(transcript),
                    "theme switch changed console output");
            check(
                    edt(() -> frame.console().inputText()).equals("20"),
                    "theme switch lost unsent stdin");
            check(
                    edt(() -> frame.editor().version()) == version,
                    "theme switch changed document version");
            Theme theme = Theme.bundled(Theme.BUNDLED[i]);
            check(
                    edt(() -> frame.console().inputComponent().getBackground())
                            .equals(theme.messagesBackground()),
                    "console theme token mismatch");
        }
        edt(
                () -> {
                    frame.console().submitInput();
                    return null;
                });
        waitFor(
                () ->
                        !frame.isRunning()
                                && frame.console().text().contains("Read 20")
                                && frame.console().text().contains("[exit 0"),
                "program completes with preserved stdin");
        note(
                "PASS actual Scanner program keeps output, pending stdin and execution across"
                        + " Classic/Dark/Light");
    }

    private static void ghost(TurboFrame frame, List<JRadioButtonMenuItem> buttons, Path target)
            throws Exception {
        String source = "class Main {\n    void demo() {\n        System.out.pr\n    }\n}\n";
        edt(
                () -> {
                    frame.editor().setText(source);
                    frame.editor().gotoOffset(source.indexOf(".pr") + 3);
                    frame.editor().requestFocusInWindow();
                    return null;
                });
        waitFor(() -> frame.editor().inlineCompletion() != null, "semantic ghost text");
        var proposal = edt(() -> frame.editor().inlineCompletion());
        var location = edt(() -> frame.editor().captureLocation());
        long version = edt(() -> frame.editor().version());
        for (int i = 0; i < 3; i++) {
            final int index = i;
            edt(
                    () -> {
                        buttons.get(index).doClick();
                        check(source.equals(frame.editor().getText()), "theme changed source");
                        check(frame.editor().version() == version, "theme changed version");
                        check(
                                location.equals(frame.editor().captureLocation()),
                                "theme changed caret or viewport");
                        check(
                                proposal.equals(frame.editor().inlineCompletion()),
                                "theme dropped live semantic proposal");
                        Theme theme = Theme.bundled(Theme.BUNDLED[index]);
                        check(
                                frame.editor().getBackground().equals(theme.background()),
                                "editor token mismatch");
                        BufferedImage image =
                                new BufferedImage(
                                        frame.getWidth(),
                                        frame.getHeight(),
                                        BufferedImage.TYPE_INT_RGB);
                        Graphics2D g = image.createGraphics();
                        frame.paintAll(g);
                        g.dispose();
                        ImageIO.write(
                                image,
                                "png",
                                target.resolve("theme-" + theme.id() + ".png").toFile());
                        return null;
                    });
        }
        edt(
                () -> {
                    check(
                            frame.editor().acceptInlineCompletion(),
                            "live suggestion no longer accepts");
                    frame.editor().undo();
                    check(
                            source.equals(frame.editor().getText()),
                            "undo lost after theme switching");
                    return null;
                });
        note(
                "PASS three live theme screenshots; source, cursor, viewport, ghost proposal and"
                        + " single-step undo preserved");
    }

    private static void documentation(TurboFrame frame, List<JRadioButtonMenuItem> buttons)
            throws Exception {
        String source = "class Main { String value = \"hello\".substring(1); }";
        edt(
                () -> {
                    frame.editor().setText(source);
                    frame.editor().gotoOffset(source.indexOf("substring") + 2);
                    frame.editor().requestFocusInWindow();
                    frame.intelligence().quickDocumentation();
                    return null;
                });
        waitFor(() -> frame.intelligence().documentationVisible(), "JDK documentation popup");
        var docs = edt(() -> frame.intelligence().lastDocumentation());
        for (int i = 0; i < 3; i++) {
            final int index = i;
            edt(
                    () -> {
                        buttons.get(index).doClick();
                        return null;
                    });
            check(
                    edt(() -> frame.intelligence().documentationVisible()),
                    "theme switch dismissed documentation");
            check(
                    edt(() -> frame.intelligence().lastDocumentation()).equals(docs),
                    "theme switch changed documentation data");
        }
        note(
                "PASS real JDK Quick Documentation stays visible and retains its resolved content"
                        + " across all themes");
    }

    private static void browser(TurboFrame frame, Path settings, Path target) throws Exception {
        edt(
                () -> {
                    frame.intelligence().dismiss();
                    themeRadioItems(frame).getFirst().doClick();
                    return null;
                });
        waitFor(() -> "classic".equals(Settings.load().theme), "Classic before preview");
        Path localDirectory = settings.resolve("themes");
        Files.createDirectories(localDirectory);
        String json = Files.readString(Path.of("app/src/main/resources/themes/dark.json"));
        for (int i = 0; i < 12; i++)
            Files.writeString(
                    localDirectory.resolve("fixture-" + i + ".json"),
                    json.replace("Modern Dark", String.format("Native fixture %02d", i)));
        String saved = Files.readString(settings.resolve("config.json"));
        var location = edt(() -> frame.editor().captureLocation());
        String source = edt(() -> frame.editor().getText()),
                output = edt(() -> frame.console().text());
        long version = edt(() -> frame.editor().version());
        SwingUtilities.invokeLater(frame::showThemeBrowser);
        waitFor(
                () ->
                        dialog() != null
                                && named(dialog(), "theme-preview-0", AbstractButton.class)
                                        .isEnabled(),
                "theme browser loaded");
        ThemeBrowserDialog d = edt(ThemeSmoke::dialog);
        check(
                edt(() -> named(d, "theme-preview-5", AbstractButton.class).isEnabled()),
                "six live preview slots");
        edt(
                () -> {
                    named(d, "theme-preview-1", AbstractButton.class).doClick();
                    return null;
                });
        check(
                edt(() -> frame.editor().getBackground())
                        .equals(Theme.bundled("dark").background()),
                "Dark live preview");
        check(
                saved.equals(Files.readString(settings.resolve("config.json"))),
                "preview must not persist");
        check(edt(() -> frame.editor().getText()).equals(source), "preview source preserved");
        check(edt(() -> frame.editor().version()) == version, "preview version preserved");
        check(
                edt(() -> frame.editor().captureLocation()).equals(location),
                "preview caret/viewport preserved");
        check(edt(() -> frame.console().text()).equals(output), "preview output preserved");
        Color selectedColor = edt(() -> frame.editor().getBackground());
        edt(
                () -> {
                    named(d, "theme-scrollbar", JScrollBar.class).setValue(4);
                    return null;
                });
        waitFor(
                () -> named(d, "theme-page-status", JLabel.class).getText().contains("13–18"),
                "scrollbar advances virtual rows");
        check(
                edt(() -> frame.editor().getBackground()).equals(selectedColor),
                "scrolling must not change the selected theme");
        edt(
                () -> {
                    JPanel grid = named(d, "theme-grid", JPanel.class);
                    grid.dispatchEvent(
                            new MouseWheelEvent(
                                    grid,
                                    MouseEvent.MOUSE_WHEEL,
                                    System.currentTimeMillis(),
                                    0,
                                    20,
                                    20,
                                    0,
                                    false,
                                    MouseWheelEvent.WHEEL_UNIT_SCROLL,
                                    3,
                                    1));
                    return null;
                });
        waitFor(
                () -> named(d, "theme-page-status", JLabel.class).getText().contains("16–21"),
                "mouse wheel advances virtual row");
        check(
                edt(() -> frame.editor().getBackground()).equals(selectedColor),
                "mouse wheel must preserve live selection");
        edt(
                () -> {
                    named(d, "theme-next", JButton.class).doClick();
                    return null;
                });
        waitFor(
                () -> named(d, "theme-page-status", JLabel.class).getText().contains("22–27"),
                "next browses adjacent visible rows");
        edt(
                () -> {
                    named(d, "theme-previous", JButton.class).doClick();
                    return null;
                });
        waitFor(
                () -> named(d, "theme-page-status", JLabel.class).getText().contains("16–21"),
                "previous browses adjacent visible rows");
        check(
                edt(() -> frame.editor().getBackground()).equals(selectedColor),
                "page buttons preserve offscreen selection");
        edt(
                () -> {
                    named(d, "theme-scrollbar", JScrollBar.class).setValue(0);
                    return null;
                });
        waitFor(
                () ->
                        named(d, "theme-page-status", JLabel.class)
                                .getText()
                                .contains("themes · 1–6"),
                "scroll back to first row");
        note(
                "PASS vertical scrollbar and mouse-wheel rows retain exactly six previews and"
                        + " preserve selection");
        edt(
                () -> {
                    var image =
                            new BufferedImage(
                                    d.getWidth(), d.getHeight(), BufferedImage.TYPE_INT_RGB);
                    var g = image.createGraphics();
                    d.paintAll(g);
                    g.dispose();
                    ImageIO.write(image, "png", target.resolve("theme-browser-grid.png").toFile());
                    JTextField field = named(d, "theme-search", JTextField.class);
                    field.requestFocusInWindow();
                    field.setText("Native fixture");
                    return null;
                });
        waitFor(
                () -> named(d, "theme-page-status", JLabel.class).getText().startsWith("12 themes"),
                "filter local themes");
        waitFor(
                () -> named(d, "theme-search", JTextField.class).isFocusOwner(),
                "search focus retained");
        edt(
                () -> {
                    key(named(d, "theme-search", JTextField.class), KeyEvent.VK_PAGE_DOWN);
                    return null;
                });
        waitFor(
                () -> named(d, "theme-page-status", JLabel.class).getText().contains("7–12"),
                "keyboard page down");
        check(
                edt(() -> named(d, "theme-search", JTextField.class).isFocusOwner()),
                "paging preserves search focus");
        edt(
                () -> {
                    key(named(d, "theme-search", JTextField.class), KeyEvent.VK_ESCAPE);
                    return null;
                });
        waitFor(() -> dialog() == null, "Escape closes preview");
        check(
                edt(() -> frame.editor().getBackground())
                        .equals(Theme.bundled("classic").background()),
                "Escape restores original");
        check(
                saved.equals(Files.readString(settings.resolve("config.json"))),
                "Escape keeps persisted preference");
        note(
                "PASS six native previews, live theme continuity, local search, keyboard paging and"
                        + " Escape rollback without saving");
        SwingUtilities.invokeLater(frame::showThemeBrowser);
        waitFor(
                () ->
                        dialog() != null
                                && named(dialog(), "theme-preview-0", AbstractButton.class)
                                        .isEnabled(),
                "browser reopened");
        ThemeBrowserDialog second = edt(ThemeSmoke::dialog);
        edt(
                () -> {
                    named(second, "theme-search", JTextField.class).setText("Native fixture 09");
                    return null;
                });
        waitFor(
                () ->
                        named(second, "theme-page-status", JLabel.class)
                                .getText()
                                .startsWith("1 themes"),
                "single custom match");
        edt(
                () -> {
                    key(named(second, "theme-search", JTextField.class), KeyEvent.VK_ENTER);
                    return null;
                });
        waitFor(() -> dialog() == null, "Enter confirms");
        Path selected = localDirectory.resolve("fixture-9.json").toAbsolutePath();
        waitFor(
                () -> selected.toString().equals(Settings.load().externalThemePath),
                "persistent external palette path");
        TurboFrame restored =
                edt(
                        () -> {
                            var f = new TurboFrame(Settings.load(), Map.of());
                            f.setVisible(true);
                            return f;
                        });
        try {
            waitFor(
                    () ->
                            restored.editor()
                                    .getBackground()
                                    .equals(Theme.bundled("dark").background()),
                    "external palette restored on launch");
        } finally {
            edt(
                    () -> {
                        restored.shutdown();
                        restored.dispose();
                        return null;
                    });
        }
        note(
                "PASS Enter applies selected local JSON and a new native window restores the saved"
                        + " external file");
    }

    private static void randomWheel(TurboFrame frame, Path settings, Path target) throws Exception {
        for (String id : Theme.BUNDLED) {
            String source =
                    Files.readString(Path.of("app/src/main/resources/themes/" + id + ".json"));
            Files.writeString(
                    settings.resolve("themes/wheel-" + id + ".json"),
                    source.replace(Theme.bundled(id).name(), "Wheel fixture " + id));
        }
        edt(
                () -> {
                    themeRadioItems(frame).getFirst().doClick();
                    frame.toFront();
                    return null;
                });
        waitFor(
                () ->
                        "classic".equals(Settings.load().theme)
                                && Settings.load().externalThemePath == null,
                "reset before random preview");
        String saved = Files.readString(settings.resolve("config.json"));
        String source = edt(() -> frame.editor().getText()),
                output = edt(() -> frame.console().text());
        long version = edt(() -> frame.editor().version());
        var location = edt(() -> frame.editor().captureLocation());
        SwingUtilities.invokeLater(frame::showThemeBrowser);
        waitFor(
                () ->
                        dialog() != null
                                && named(dialog(), "theme-preview-0", AbstractButton.class)
                                        .isEnabled(),
                "random dialog ready");
        ThemeBrowserDialog d = edt(ThemeSmoke::dialog);
        edt(
                () -> {
                    named(d, "theme-search", JTextField.class).setText("");
                    d.toFront();
                    return null;
                });
        waitFor(
                () -> named(d, "theme-random", JButton.class).isEnabled(),
                "full random pool ready");
        var crossings = new ArrayList<Long>();
        java.beans.PropertyChangeListener changes = e -> crossings.add(System.nanoTime());
        edt(
                () -> {
                    frame.editor().addPropertyChangeListener("background", changes);
                    named(d, "theme-random", JButton.class).doClick();
                    return null;
                });
        long started = System.nanoTime();
        waitFor(
                () -> named(d, "theme-skip-spin", JButton.class).isEnabled(),
                "random prepared final palette");
        waitFor(() -> crossings.size() >= 3, "live whole-editor palette crossings");
        check(
                saved.equals(Files.readString(settings.resolve("config.json"))),
                "spinning must not save preferences");
        check(
                source.equals(edt(() -> frame.editor().getText()))
                        && version == edt(() -> frame.editor().version()),
                "random source/version continuity");
        check(
                location.equals(edt(() -> frame.editor().captureLocation())),
                "random caret/viewport continuity");
        check(output.equals(edt(() -> frame.console().text())), "random output continuity");
        edt(
                () -> {
                    var image =
                            new BufferedImage(
                                    d.getWidth(), d.getHeight(), BufferedImage.TYPE_INT_RGB);
                    var g = image.createGraphics();
                    d.paintAll(g);
                    g.dispose();
                    ImageIO.write(image, "png", target.resolve("theme-random-drum.png").toFile());
                    return null;
                });
        waitFor(
                () ->
                        !named(d, "theme-skip-spin", JButton.class).isVisible()
                                && named(d, "theme-apply", JButton.class).isEnabled(),
                "natural random wheel landing");
        double seconds = (System.nanoTime() - started) / 1e9;
        check(seconds >= 3 && seconds < 6.5, "random wheel elapsed " + seconds);
        edt(
                () -> {
                    frame.editor().removePropertyChangeListener("background", changes);
                    named(d, "theme-cancel", JButton.class).doClick();
                    return null;
                });
        waitFor(() -> dialog() == null, "cancel natural wheel result");
        check(
                edt(() -> frame.editor().getBackground())
                        .equals(Theme.bundled("classic").background()),
                "random Cancel restores Classic");
        check(
                saved.equals(Files.readString(settings.resolve("config.json"))),
                "random landing is not persisted");
        note(
                String.format(
                        Locale.ROOT,
                        "PASS DOS random wheel completed in %.2f s with %d native palette"
                            + " crossings, fast continuous cubic slowdown, source/output continuity"
                            + " and Cancel rollback",
                        seconds,
                        crossings.size()));
        SwingUtilities.invokeLater(frame::showThemeBrowser);
        waitFor(
                () ->
                        dialog() != null
                                && named(dialog(), "theme-preview-0", AbstractButton.class)
                                        .isEnabled(),
                "skip dialog");
        ThemeBrowserDialog skipDialog = edt(ThemeSmoke::dialog);
        edt(
                () -> {
                    named(skipDialog, "theme-random", JButton.class).doClick();
                    return null;
                });
        waitFor(
                () -> named(skipDialog, "theme-skip-spin", JButton.class).isEnabled(),
                "skip ready");
        edt(
                () -> {
                    named(skipDialog, "theme-skip-spin", JButton.class).doClick();
                    return null;
                });
        waitFor(
                () ->
                        !named(skipDialog, "theme-skip-spin", JButton.class).isVisible()
                                && named(skipDialog, "theme-apply", JButton.class).isEnabled(),
                "Skip lands");
        edt(
                () -> {
                    named(skipDialog, "theme-reduced-motion", JCheckBox.class).doClick();
                    named(skipDialog, "theme-random", JButton.class).doClick();
                    return null;
                });
        waitFor(
                () ->
                        !named(skipDialog, "theme-skip-spin", JButton.class).isVisible()
                                && named(skipDialog, "theme-apply", JButton.class).isEnabled(),
                "reduced motion lands");
        edt(
                () -> {
                    named(skipDialog, "theme-reduced-motion", JCheckBox.class).doClick();
                    named(skipDialog, "theme-random", JButton.class).doClick();
                    return null;
                });
        waitFor(
                () -> named(skipDialog, "theme-skip-spin", JButton.class).isEnabled(),
                "cancellation during active spin");
        edt(
                () -> {
                    named(skipDialog, "theme-cancel", JButton.class).doClick();
                    return null;
                });
        waitFor(() -> dialog() == null, "active spin cancelled");
        Thread.sleep(800);
        check(
                edt(() -> frame.editor().getBackground())
                        .equals(Theme.bundled("classic").background()),
                "cancelled timer must not keep changing palette");
        check(
                saved.equals(Files.readString(settings.resolve("config.json"))),
                "Skip/reduced-motion/cancel never commits");
        note(
                "PASS Skip lands on a real theme, reduced motion completes immediately, and"
                        + " cancelling an active spin stops later palette changes");
    }

    private static ThemeBrowserDialog dialog() {
        for (Window window : Window.getWindows())
            if (window instanceof ThemeBrowserDialog dialog && dialog.isShowing()) return dialog;
        return null;
    }

    private static <T extends Component> T named(Container container, String name, Class<T> type) {
        for (Component component : container.getComponents()) {
            if (name.equals(component.getName()) && type.isInstance(component))
                return type.cast(component);
            if (component instanceof Container child) {
                T found = named(child, name, type);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void key(JComponent target, int code) {
        target.dispatchEvent(
                new KeyEvent(
                        target,
                        KeyEvent.KEY_PRESSED,
                        System.currentTimeMillis(),
                        0,
                        code,
                        KeyEvent.CHAR_UNDEFINED));
        target.dispatchEvent(
                new KeyEvent(
                        target,
                        KeyEvent.KEY_RELEASED,
                        System.currentTimeMillis(),
                        0,
                        code,
                        KeyEvent.CHAR_UNDEFINED));
    }

    private static List<JToggleButton> buttons(Container container) {
        List<JToggleButton> result = new ArrayList<>();
        for (Component child : container.getComponents()) {
            if (child instanceof JToggleButton button) result.add(button);
            else if (child instanceof Container nested) result.addAll(buttons(nested));
        }
        return result;
    }

    private static List<JRadioButtonMenuItem> themeRadioItems(TurboFrame frame) {
        List<JRadioButtonMenuItem> result = new ArrayList<>();
        JMenuBar bar = frame.getJMenuBar();
        for (int i = 0; i < bar.getMenuCount(); i++) {
            JMenu menu = bar.getMenu(i);
            if (menu == null) continue;
            if (!menu.getText().equals("Options")) continue;
            for (Component item : menu.getMenuComponents())
                if (item instanceof JMenu themes && themes.getText().equals("Themes"))
                    for (Component child : themes.getMenuComponents())
                        if (child instanceof JRadioButtonMenuItem radio) result.add(radio);
        }
        return result;
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void note(String text) {
        report.append(text).append('\n');
        System.out.println(text);
    }

    private static void waitFor(Callable<Boolean> predicate, String description) throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(30).toNanos();
        while (System.nanoTime() < end) {
            if (edt(predicate)) return;
            Thread.sleep(20);
        }
        throw new AssertionError("Timed out: " + description);
    }

    private static <T> T edt(Callable<T> operation) throws Exception {
        FutureTask<T> future = new FutureTask<>(operation);
        SwingUtilities.invokeAndWait(future);
        return future.get();
    }
}
