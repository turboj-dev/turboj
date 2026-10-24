package dev.turboj.ui;

import dev.turboj.commandj.swing.AsciiPalette;
import dev.turboj.ui.themes.Theme;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PressedShortcutDisplayTest {
    @Test void allThemesRetainWebsiteAccentWithReadableTextAndFlatBottomRightGeometry() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var display = new PressedShortcutDisplay(() -> {});
            display.setEnabled(true);
            for (String id : Theme.bundledResources().keySet()) {
                Theme theme = Theme.bundled(id);
                display.setTheme(theme);
                assertEquals(theme.keyword(), display.background(), id);
                assertTrue(AsciiPalette.contrast(display.foreground(), display.background()) >= 4.5, id);
                display.show("Cmd+S  Save");
                var image = new BufferedImage(600, 300, BufferedImage.TYPE_INT_RGB);
                var g = image.createGraphics();
                g.setColor(Color.MAGENTA); g.fillRect(0, 0, 600, 300);
                var bounds = display.bounds(g, 600, 300);
                display.paint(g, 600, 300); g.dispose();
                assertEquals(584, bounds.x + bounds.width);
                assertEquals(288, bounds.y + bounds.height);
                assertEquals(display.background().getRGB(), image.getRGB(bounds.x + 1, bounds.y + 1));
                assertEquals(Color.MAGENTA.getRGB(), image.getRGB(bounds.x - 1, bounds.y));
            }
            display.clear();
        });
    }

    @Test void hintTimesOutAndDisablingClearsItImmediately() throws Exception {
        var expired = new CountDownLatch(1);
        var display = new AtomicReference<PressedShortcutDisplay>();
        SwingUtilities.invokeAndWait(() -> {
            display.set(new PressedShortcutDisplay(() -> {
                if (display.get() != null && display.get().text().isEmpty()) expired.countDown();
            }, 40));
            display.get().show("Cmd+S");
            assertEquals("", display.get().text(), "disabled by default");
            display.get().setEnabled(true);
            display.get().show("Cmd+S");
            assertEquals("Cmd+S", display.get().text());
        });
        assertTrue(expired.await(3, TimeUnit.SECONDS), "one-shot timer clears the hint");
        SwingUtilities.invokeAndWait(() -> {
            display.get().show("F9");
            display.get().setEnabled(false);
            assertEquals("", display.get().text());
            display.get().show("Cmd+R");
            assertEquals("", display.get().text());
        });
    }

    @Test void paintingHintKeepsSourceCaretUndoAndSmallViewportIntact() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            var editor = new EditorComponent();
            editor.setText("class Example { int n = 42; }\n");
            editor.gotoOffset(12);
            var before = editor.captureLocation();
            long version = editor.version();
            editor.setShowPressedShortcuts(true);
            editor.showPressedShortcut("Cmd+K, Cmd+S  Very long action title 😀".repeat(8));
            editor.setSize(120, 90);
            var image = new BufferedImage(120, 90, BufferedImage.TYPE_INT_RGB);
            var g = image.createGraphics(); editor.paint(g); g.dispose();
            assertEquals(before, editor.captureLocation());
            assertEquals(version, editor.version());
            assertEquals("class Example { int n = 42; }\n", editor.getText());
            editor.setShowPressedShortcuts(false);
            assertEquals("", editor.pressedShortcutText());
        });
    }
}
