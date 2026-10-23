package com.turboj.app;

import com.turboj.config.Settings;
import com.turboj.ui.themes.Theme;
import com.turboj.ui.themes.ThemeBrowserDialog;

import java.awt.*;
import java.awt.event.*;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;

import javax.swing.*;

/** Reports EDT action-to-ready latency with the real registry; no spin time is included. */
public final class ThemeBrowserLatency {
    public static void main(String[] args) throws Exception {
        Path target = Path.of("app/target").toAbsolutePath();
        Files.createDirectories(target);
        System.setProperty(
                "turboj.config", Files.createTempDirectory(target, "theme-latency-").toString());
        System.setProperty("apple.laf.useScreenMenuBar", "false");
        TurboFrame frame =
                edt(
                        () -> {
                            UIManager.setLookAndFeel(
                                    UIManager.getCrossPlatformLookAndFeelClassName());
                            var f = new TurboFrame(new Settings(), Map.of());
                            f.setVisible(true);
                            return f;
                        });
        var opens = new ArrayList<Double>();
        var searches = new ArrayList<Double>();
        var arrows = new ArrayList<Double>();
        try {
            for (int i = 0; i < 6; i++) {
                long started = System.nanoTime();
                SwingUtilities.invokeLater(frame::showThemeBrowser);
                waitFor(
                        () ->
                                dialog() != null
                                        && named(dialog(), "theme-apply", JButton.class)
                                                .isEnabled());
                opens.add((System.nanoTime() - started) / 1e6);
                ThemeBrowserDialog dialog = edt(ThemeBrowserLatency::dialog);
                if (dialog.getWidth() != 720 || dialog.getHeight() != 480)
                    throw new AssertionError("Expected compact720×480 dialog");
                for (String query : List.of("Modern Light", "Modern Dark", "")) {
                    long searchStart = System.nanoTime();
                    edt(
                            () -> {
                                named(dialog, "theme-search", JTextField.class).setText(query);
                                return null;
                            });
                    waitFor(() -> named(dialog, "theme-apply", JButton.class).isEnabled());
                    searches.add((System.nanoTime() - searchStart) / 1e6);
                }
                edt(
                        () -> {
                            named(dialog, "theme-preview-0", AbstractButton.class)
                                    .requestFocusInWindow();
                            return null;
                        });
                for (int n = 0; n < 8; n++) {
                    final int key = n % 2 == 0 ? KeyEvent.VK_RIGHT : KeyEvent.VK_LEFT;
                    long arrowStart = System.nanoTime();
                    edt(
                            () -> {
                                var component =
                                        (JComponent)
                                                KeyboardFocusManager
                                                        .getCurrentKeyboardFocusManager()
                                                        .getFocusOwner();
                                component.dispatchEvent(
                                        new KeyEvent(
                                                component,
                                                KeyEvent.KEY_PRESSED,
                                                System.currentTimeMillis(),
                                                0,
                                                key,
                                                KeyEvent.CHAR_UNDEFINED));
                                return null;
                            });
                    arrows.add((System.nanoTime() - arrowStart) / 1e6);
                }
                edt(
                        () -> {
                            named(dialog, "theme-cancel", JButton.class).doClick(0);
                            return null;
                        });
                waitFor(() -> dialog() == null);
            }
            String report =
                    "Native theme catalog: "
                            + Theme.bundledResources().size()
                            + " actual bundled palettes; window720×480\n"
                            + String.format(Locale.ROOT, "First open %.2f ms\n", opens.getFirst())
                            + summary("Warm dialog open", opens.subList(1, opens.size()))
                            + summary("Search", searches)
                            + summary("Cached same-page arrow", arrows)
                            + "Action-to-ready measurements on this Mac/JDK; not input-to-paint or"
                            + " cross-hardware guarantees.\n";
            Files.writeString(target.resolve("theme-latency.txt"), report);
            System.out.print(report);
        } finally {
            edt(
                    () -> {
                        for (Window w : Window.getWindows())
                            if (w instanceof ThemeBrowserDialog) dismiss(w);
                        frame.shutdown();
                        frame.dispose();
                        return null;
                    });
        }
    }

    private static void dismiss(Window window) {
        window.dispose();
    }

    private static String summary(String label, List<Double> values) {
        var sorted = new ArrayList<>(values);
        sorted.sort(Double::compare);
        return String.format(
                Locale.ROOT,
                "%s n=%d p50 %.2f ms p95 %.2f ms max %.2f ms%n",
                label,
                values.size(),
                sorted.get((sorted.size() - 1) / 2),
                sorted.get(Math.min(sorted.size() - 1, (int) Math.ceil(sorted.size() * .95) - 1)),
                sorted.getLast());
    }

    private static ThemeBrowserDialog dialog() {
        for (Window w : Window.getWindows())
            if (w instanceof ThemeBrowserDialog d && d.isShowing()) return d;
        return null;
    }

    private static <T extends Component> T named(Container parent, String name, Class<T> type) {
        for (Component c : parent.getComponents()) {
            if (name.equals(c.getName()) && type.isInstance(c)) return type.cast(c);
            if (c instanceof Container child) {
                T result = named(child, name, type);
                if (result != null) return result;
            }
        }
        return null;
    }

    private static void waitFor(Callable<Boolean> condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        while (System.nanoTime() < deadline) {
            if (edt(condition)) return;
            Thread.sleep(2);
        }
        throw new AssertionError("Timed out waiting for catalog UI");
    }

    private static <T> T edt(Callable<T> action) throws Exception {
        var result = new FutureTask<T>(action);
        SwingUtilities.invokeAndWait(result);
        return result.get();
    }
}
