package com.turboj.app;

import com.turboj.config.Settings;
import com.turboj.ui.EditorComponent;
import com.turboj.ui.themes.Theme;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.FutureTask;

import javax.imageio.ImageIO;
import javax.swing.JCheckBoxMenuItem;
import javax.swing.JMenuItem;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/** Explicit native window check against the real in-process javafmt library. */
public final class FormattingSmoke {
    private static final String SOURCE =
            "public class FormatMe{public static void main(String[]args){int"
                + " a=10;System.out.println(a);}}";
    private static final String EXPECTED =
            "public class FormatMe {\n"
                    + "    public static void main(String[] args) {\n"
                    + "        int a = 10;\n"
                    + "        System.out.println(a);\n"
                    + "    }\n}\n";
    private static final StringBuilder report = new StringBuilder();

    public static void main(String[] args) throws Exception {
        Path output = Path.of("app/target/javafmt-smoke").toAbsolutePath();
        Files.createDirectories(output);
        Path temporary = Files.createTempDirectory(output, "session-");
        System.setProperty("turboj.config", temporary.resolve("config").toString());
        System.setProperty("apple.laf.useScreenMenuBar", "false");
        TurboFrame frame =
                edt(
                        () -> {
                            UIManager.setLookAndFeel(
                                    UIManager.getCrossPlatformLookAndFeelClassName());
                            var window = new TurboFrame(new Settings(), Map.of());
                            window.setVisible(true);
                            window.toFront();
                            window.editor().requestFocusInWindow();
                            return window;
                        });
        try {
            waitFor(() -> frame.editor().isFocusOwner(), "editor focus");
            check(
                    edt(
                            () ->
                                    !((JCheckBoxMenuItem) item(frame, "Code", "Format on Save"))
                                            .isSelected()),
                    "Format on Save defaults off");
            for (String theme : Theme.BUNDLED) {
                var before =
                        edt(
                                () -> {
                                    frame.applyTheme(Theme.bundled(theme));
                                    frame.editor().setText(SOURCE);
                                    int position = SOURCE.indexOf("println") + 3;
                                    frame.editor()
                                            .restoreLocation(
                                                    new EditorComponent.Location(
                                                            position, position, 0, 0));
                                    var location = frame.editor().captureLocation();
                                    item(frame, "Code", "Format Document").doClick(0);
                                    return location;
                                });
                waitFor(() -> !frame.formatting().isPending(), "document format " + theme);
                check(
                        EXPECTED.equals(edt(() -> frame.editor().getText())),
                        "Canonical Java layout in " + theme);
                check(
                        edt(() -> frame.editor().caretOffset()) == EXPECTED.indexOf("println") + 3,
                        "Caret follows the original token in " + theme);
                check(!edt(frame::isRunning), "Formatting never starts execution");
                edt(
                        () -> {
                            var image =
                                    new BufferedImage(
                                            frame.getWidth(),
                                            frame.getHeight(),
                                            BufferedImage.TYPE_INT_RGB);
                            var graphics = image.createGraphics();
                            frame.paint(graphics);
                            graphics.dispose();
                            ImageIO.write(image, "png", output.resolve(theme + ".png").toFile());
                            frame.editor().undo();
                            return null;
                        });
                check(
                        SOURCE.equals(edt(() -> frame.editor().getText())),
                        "One Undo restores source in " + theme);
                check(
                        before.equals(edt(() -> frame.editor().captureLocation())),
                        "One Undo restores caret/viewport in " + theme);
                edt(
                        () -> {
                            frame.editor().redo();
                            return null;
                        });
                check(
                        EXPECTED.equals(edt(() -> frame.editor().getText())),
                        "Redo reapplies formatting in " + theme);
            }
            edt(
                    () -> {
                        frame.compile(true, false);
                        return null;
                    });
            waitFor(
                    () ->
                            !frame.isRunning()
                                    && frame.console()
                                            .text()
                                            .lines()
                                            .anyMatch(line -> line.strip().equals("10")),
                    "explicit compile/run output");
            note("PASS formatted Java compiles and explicit Run prints 10");

            String selection = "class Section{void first(){int x=1;}void second(){int y=2;}}";
            edt(
                    () -> {
                        frame.editor().setText(selection);
                        int start = selection.indexOf("int x");
                        frame.editor()
                                .restoreLocation(
                                        new EditorComponent.Location(start + 8, start, 0, 0));
                        item(frame, "Code", "Format Selection").doClick(0);
                        return null;
                    });
            waitFor(() -> !frame.formatting().isPending(), "selection format");
            String selected = edt(() -> frame.editor().getText());
            check(selected.contains("int x = 1;"), "Selection formats its enclosing syntax region");
            check(
                    selected.contains("void second(){int y=2;}"),
                    "Selection leaves unrelated method unchanged");
            edt(
                    () -> {
                        frame.editor().undo();
                        return null;
                    });
            check(
                    selection.equals(edt(() -> frame.editor().getText())),
                    "Selection formatting has one Undo");

            Path file = temporary.resolve("FormatMe.java");
            Files.writeString(file, SOURCE);
            edt(
                    () -> {
                        frame.openPath(file);
                        return null;
                    });
            waitFor(() -> file.equals(frame.currentFile()), "open save fixture");
            edt(
                    () -> {
                        item(frame, "Code", "Format on Save").doClick(0);
                        item(frame, "File", "Save").doClick(0);
                        return null;
                    });
            waitFor(
                    () -> frame.console().text().contains("Saved FormatMe.java"),
                    "format-on-save acknowledgment");
            check(
                    EXPECTED.equals(Files.readString(file)),
                    "Format on Save writes canonical source");
            check(
                    EXPECTED.equals(edt(() -> frame.editor().getText())),
                    "Saved buffer matches formatted disk source");
            edt(
                    () -> {
                        frame.editor().undo();
                        return null;
                    });
            check(
                    SOURCE.equals(edt(() -> frame.editor().getText())),
                    "Format on Save remains one undo transaction");

            String invalid = "public class FormatMe { void unfinished( }";
            edt(
                    () -> {
                        frame.editor().selectAll();
                        frame.editor().replaceSelection(invalid);
                        frame.console().clear();
                        item(frame, "File", "Save").doClick(0);
                        return null;
                    });
            waitFor(
                    () -> frame.console().text().contains("Saved FormatMe.java"),
                    "malformed-source save");
            check(
                    invalid.equals(Files.readString(file)),
                    "Invalid Java is safely saved unchanged when formatting is skipped");
            note(
                    "PASS native javafmt integration, three themes, range, undo, compile/run and"
                        + " save workflows");
        } finally {
            Files.writeString(output.resolve("report.txt"), report);
            edt(
                    () -> {
                        frame.shutdown();
                        frame.dispose();
                        return null;
                    });
        }
    }

    private static JMenuItem item(TurboFrame frame, String menuName, String itemName) {
        for (int i = 0; i < frame.getJMenuBar().getMenuCount(); i++) {
            var menu = frame.getJMenuBar().getMenu(i);
            if (menu != null && menuName.equals(menu.getText()))
                for (int j = 0; j < menu.getItemCount(); j++) {
                    var item = menu.getItem(j);
                    if (item != null && itemName.equals(item.getText())) return item;
                }
        }
        throw new AssertionError("Missing menu action " + menuName + " → " + itemName);
    }

    private static void check(boolean condition, String text) {
        if (!condition) throw new AssertionError(text);
        note("PASS " + text);
    }

    private static void note(String text) {
        report.append(text).append('\n');
        System.out.println(text);
    }

    private static void waitFor(Callable<Boolean> condition, String label) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(25).toNanos();
        while (System.nanoTime() < deadline) {
            if (edt(condition)) return;
            Thread.sleep(15);
        }
        throw new AssertionError("Timeout: " + label);
    }

    private static <T> T edt(Callable<T> action) throws Exception {
        var task = new FutureTask<T>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }
}
