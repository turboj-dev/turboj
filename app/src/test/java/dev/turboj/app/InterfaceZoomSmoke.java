package dev.turboj.app;

import dev.turboj.commandj.core.CommandContext;
import dev.turboj.commandj.core.CommandResult;
import dev.turboj.commandj.swing.AsciiBorder;
import dev.turboj.commandj.swing.EmbeddedDialog;
import dev.turboj.config.Settings;
import dev.turboj.ui.AsciiChrome;
import dev.turboj.ui.AsciiMenuBar;
import dev.turboj.ui.MenuStyler;
import dev.turboj.ui.themes.Theme;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.Map;
import java.util.concurrent.*;
import javax.swing.*;

/** Actual frame: one footer, current Maven state and font commands throughout the ASCII UI. */
public final class InterfaceZoomSmoke {
    private static final StringBuilder report = new StringBuilder();

    public static void main(String[] args) throws Exception {
        Path output = Path.of("app/target/interface-zoom-smoke").toAbsolutePath();
        Files.createDirectories(output);
        Path root = Files.createTempDirectory(output, "project-");
        System.setProperty("turboj.config", root.resolve("settings").toString());
        System.setProperty("apple.laf.useScreenMenuBar", "false");
        Files.writeString(root.resolve("pom.xml"), "<project><modelVersion>4.0.0</modelVersion>"
                + "<groupId>fixture.zoom</groupId><artifactId>example</artifactId><version>1</version>"
                + "<properties><maven.compiler.release>21</maven.compiler.release></properties></project>");
        TurboFrame frame = edt(() -> {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            var value = new TurboFrame(new Settings(), Map.of());
            value.setVisible(true); value.toFront(); value.editor().requestFocusInWindow();
            return value;
        });
        try {
            waitFor(() -> !frame.buildStatus().contains("CHECKING"));
            edt(() -> { frame.console().append("Retain this output across zoom and theme changes\n", false); return null; });
            waitFor(() -> frame.console().text().contains("Retain this output"));
            JLabel status = edt(() -> (JLabel) named(frame, "workspace-status"));
            JPanel footer = edt(() -> (JPanel) named(frame, "workspace-status-line"));
            edt(() -> {
                Container content = (Container) ((BorderLayout) frame.getContentPane().getLayout()).getLayoutComponent(BorderLayout.CENTER);
                check(((BorderLayout) content.getLayout()).getLayoutComponent(BorderLayout.NORTH) == null,
                        "no extra status strip above the editor");
                check(footer.getComponentCount() == 2 && status.getParent() == footer,
                        "one bottom line contains current state and file/caret position");
                check(!status.getText().contains(" Save") && status.getToolTipText().contains(" Save"),
                        "shortcut help is in the tooltip instead of the bottom text");
                frame.mavenProjects().open(root); return null;
            });
            waitFor(() -> frame.mavenProjects().workspace() != null && !frame.mavenProjects().busy());
            check(edt(() -> status.getText().contains("Maven") && status.getText().contains("Java 21")
                    && status.getToolTipText().contains("Modules 1")), "Maven import updates the same compact footer");
            JTextField input = new JTextField("Preserve dialog text", 24);
            JTable table = new JTable(2, 2);
            EmbeddedDialog dialog = edt(() -> {
                var value = new EmbeddedDialog(frame, "Zoom example", false);
                var body = new JPanel(new BorderLayout()); body.add(input, BorderLayout.NORTH);
                body.add(new JScrollPane(table), BorderLayout.CENTER); value.add(body);
                value.setSize(440, 240); value.setVisible(true); input.setCaretPosition(7); return value;
            });
            int baseWidth = edt(dialog::getWidth), baseRow = edt(table::getRowHeight);
            for (String id : Theme.BUNDLED) {
                edt(() -> { frame.applyTheme(Theme.bundled(id)); return null; });
                command(frame, "fontUp");
                edt(() -> {
                    int chrome = AsciiChrome.font().getSize();
                    JMenu menu = ((AsciiMenuBar) frame.getJMenuBar()).allMenus().getFirst();
                    check(frame.editor().getFont().getSize() == 20 && chrome == 16,
                            id + " font command scales editor and chrome together");
                    check(menu.getFont().getSize() == chrome && UIManager.getFont("MenuItem.acceleratorFont").getSize() == chrome
                            && frame.console().inputComponent().getFont().getSize() == chrome && status.getFont().getSize() == chrome,
                            id + " menu, shortcuts, output and footer use the scaled font");
                    check(input.getFont().getSize() == chrome && table.getRowHeight() > baseRow
                            && dialog.getWidth() > baseWidth && ((AsciiBorder) dialog.getBorder()).palette().font().getSize() == chrome,
                            id + " open dialog text, table and ASCII border scale within the frame");
                    check(MenuStyler.contrast(status.getForeground(), footer.getBackground()) >= 4.5,
                            id + " footer remains readable");
                    check(input.getText().equals("Preserve dialog text") && input.getCaretPosition() == 7,
                            id + " zoom retains dialog input and caret");
                    check(frame.console().text().contains("Retain this output"), id + " zoom retains output transcript");
                    return null;
                });
                screenshot(frame, output.resolve(id + ".png"));
                command(frame, "fontDown");
                check(edt(() -> frame.editor().getFont().getSize() == 18 && status.getFont().getSize() == 14
                        && table.getRowHeight() == baseRow), id + " smaller font restores the complete text grid");
            }
            edt(() -> { dialog.dispose(); frame.setSize(640, 480); return null; });
            command(frame, "fontUp");
            waitFor(() -> Settings.load().fontSize == 20);
            check(true, "interface font setting persists");
            edt(() -> {
                frame.validate();
                var bar = (AsciiMenuBar) frame.getJMenuBar(); bar.doLayout();
                check(bar.overflowMenu().getParent() == bar, "larger text retains access to menus through More");
                check(footer.getHeight() == status.getPreferredSize().height,
                        "compact frame keeps one text row at the bottom");
                String activity = TurboFrame.compactStatus(frame.buildStatus());
                check(status.getText().startsWith(" " + activity) && status.getFontMetrics(status.getFont()).stringWidth(" " + activity) < status.getWidth(),
                        "compact footer keeps the complete activity first and visible");
                check(!status.getText().contains(root.getFileName().toString()) && status.getToolTipText().contains(root.getFileName().toString()),
                        "project name and module details remain in the tooltip");
                check(frame.console().getParent() instanceof JComponent panel && panel.getBorder() instanceof AsciiBorder border
                        && border.title().equals("Output"), "output title does not duplicate the status line");
                return null;
            });
            screenshot(frame, output.resolve("compact.png"));
            Path longFile = root.resolve("A_very_long_source_filename_that_must_never_hide_the_current_build_activity_or_the_caret_position.java");
            Files.writeString(longFile, "class Example { int value = 42; }\n");
            edt(() -> { frame.openPath(longFile); return null; });
            waitFor(() -> longFile.equals(frame.currentFile()));
            edt(() -> {
                frame.editor().gotoOffset(12); frame.validate();
                JLabel caret = (JLabel) named(frame, "caret-status");
                check(caret.getText().contains("…") && caret.getText().stripTrailing().endsWith("1:13")
                        && caret.getToolTipText().equals(longFile.toString()), "long filename shortens while retaining caret and full path tooltip");
                check(caret.getPreferredSize().width <= footer.getWidth() / 2 && status.getWidth() >= footer.getWidth() / 2,
                        "long filename reserves at least half of footer for current activity");
                return null;
            });
            screenshot(frame, output.resolve("long-filename.png"));
            Files.writeString(output.resolve("report.txt"), report.toString());
        } finally {
            edt(() -> { frame.shutdown(); frame.dispose(); return null; });
        }
    }

    private static Component named(Container parent, String name) {
        if (name.equals(parent.getName())) return parent;
        for (Component child : parent.getComponents()) {
            if (name.equals(child.getName())) return child;
            if (child instanceof Container container) {
                Component found = named(container, name); if (found != null) return found;
            }
        }
        return null;
    }
    private static void command(TurboFrame frame, String id) throws Exception {
        CommandResult result = edt(() -> frame.commandRegistry().execute(id, CommandContext.of("editor", "editable")))
                .stage().toCompletableFuture().get(10, TimeUnit.SECONDS);
        if (result.status() != CommandResult.Status.SUCCEEDED) throw new AssertionError(result);
    }
    private static void screenshot(TurboFrame frame, Path file) throws Exception {
        edt(() -> {
            var image = new BufferedImage(frame.getWidth(), frame.getHeight(), BufferedImage.TYPE_INT_RGB);
            var graphics = image.createGraphics(); frame.paint(graphics); graphics.dispose();
            javax.imageio.ImageIO.write(image, "png", file.toFile()); return null;
        });
    }
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
        report.append("PASS ").append(message).append('\n'); System.out.println("PASS " + message);
    }
    private static void waitFor(Callable<Boolean> condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
        while (System.nanoTime() < until) { if (edt(condition)) return; Thread.sleep(20); }
        throw new AssertionError("Timed out waiting for interface state");
    }
    private static <T> T edt(Callable<T> action) throws Exception {
        var task = new FutureTask<T>(action); SwingUtilities.invokeAndWait(task); return task.get();
    }
}
