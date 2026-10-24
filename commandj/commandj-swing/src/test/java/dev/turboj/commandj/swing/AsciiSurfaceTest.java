package dev.turboj.commandj.swing;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.FutureTask;
import javax.swing.*;
import javax.swing.text.DefaultEditorKit;
import javax.swing.text.JTextComponent;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AsciiSurfaceTest {
    @Test void zoomScalesExplicitSurfacesOnceAndNeverDoubleScalesPackedSurfaces() throws Exception {
        edt(() -> {
            AsciiPalette original = AsciiStyle.palette(new JPanel());
            var base = AsciiPalette.defaults();
            var large = new AsciiPalette(base.background(), base.foreground(), base.selectionBackground(),
                    base.selectionForeground(), base.muted(), base.border(), base.font().deriveFont(28f));
            try {
                AsciiStyle.installDefaults(large);
                JRootPane root = new JRootPane(); root.setSize(1400, 1000); root.getLayeredPane().setSize(1400, 1000);
                OverlayHost host = OverlayHost.forOwner(root); host.setTheme(large);
                var packed = new EmbeddedDialog(root, "Packed", false);
                packed.add(new JTextField("query", 12)); packed.pack();
                Dimension measured = packed.getSize();
                packed.setVisible(true);
                assertEquals(measured, packed.getSize(), "pack already used the current font");
                packed.setVisible(false);
                host.setTheme(base); packed.setVisible(true);
                assertEquals(new Dimension(Math.max(220, Math.round(measured.width / 2f)),
                        Math.max(100, Math.round(measured.height / 2f))), packed.getSize(), "hidden surface follows a later font change once");
                packed.dispose();

                host.setTheme(large);
                var fixed = new EmbeddedDialog(root, "Explicit", false);
                fixed.add(new JTextField("value")); fixed.setSize(300, 180); fixed.setVisible(true);
                assertEquals(new Dimension(600, 360), fixed.getSize(), "explicit dimensions scale from the baseline font");
                host.setTheme(large); assertEquals(new Dimension(600, 360), fixed.getSize(), "same-size theme switch does not compound zoom");
                host.setTheme(base); assertEquals(new Dimension(300, 180), fixed.getSize());
                fixed.dispose();
            } finally { AsciiStyle.installDefaults(original); }
        });
    }

    @Test void asciiTextSurfacesRetainPlatformClipboardActions() throws Exception {
        edt(() -> {
            int mask = System.getProperty("os.name", "").startsWith("Mac")
                    ? InputEvent.META_DOWN_MASK : InputEvent.CTRL_DOWN_MASK;
            for (JTextComponent text : List.of(new JTextField("query"), new JTextPane(), new JTextArea())) {
                AsciiStyle.apply(text, AsciiPalette.defaults());
                assertEquals(DefaultEditorKit.copyAction, text.getInputMap().get(KeyStroke.getKeyStroke(KeyEvent.VK_C, mask)));
                assertEquals(DefaultEditorKit.cutAction, text.getInputMap().get(KeyStroke.getKeyStroke(KeyEvent.VK_X, mask)));
                assertEquals(DefaultEditorKit.pasteAction, text.getInputMap().get(KeyStroke.getKeyStroke(KeyEvent.VK_V, mask)));
                Object selectAll = text.getInputMap().get(KeyStroke.getKeyStroke(KeyEvent.VK_A, mask));
                assertEquals(DefaultEditorKit.selectAllAction, selectAll);
                assertNotNull(text.getActionMap().get(selectAll));
                assertNotNull(text.getActionMap().get(DefaultEditorKit.copyAction));
            }
        });
    }

    @Test void transparentHostDoesNotDarkenEditorAndPlainSurfaceRetainsCloseSemantics() throws Exception {
        edt(() -> {
            JRootPane root = new JRootPane(); root.setSize(640, 480); root.getLayeredPane().setSize(640, 480);
            OverlayHost host = OverlayHost.forOwner(root);
            var dialog = new EmbeddedDialog(root, "Find", false);
            var events = new ArrayList<String>();
            dialog.onOpened(() -> events.add("opened")); dialog.onClosing(() -> events.add("closing")); dialog.onClosed(() -> events.add("closed"));
            dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
            dialog.add(new JTextField("query")); dialog.setSize(300, 170); dialog.setVisible(true); dialog.doLayout();
            BufferedImage image = new BufferedImage(640, 480, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = image.createGraphics(); host.paint(graphics); graphics.dispose();
            assertEquals(0, image.getRGB(1, 1), "Editor pixels outside the surface stay untouched");
            dialog.closeButton().doClick(); assertTrue(dialog.isVisible());
            dialog.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE); dialog.closeButton().doClick();
            assertEquals(List.of("opened", "closing", "closing", "closed"), events);
            assertFalse(OverlayHost.active(root));
        });
    }

    @Test void lowContrastThemeControlsRemainReadableWithoutChangingCommandText() throws Exception {
        edt(() -> {
            for (Color background : List.of(Color.BLUE, Color.WHITE, Color.BLACK, new Color(0x777777))) {
                AsciiPalette p = new AsciiPalette(background, background, background, background, background, background, new Font(Font.SANS_SERIF, Font.PLAIN, 14));
                assertTrue(AsciiPalette.contrast(p.foreground(), p.background()) >= 4.5);
                assertTrue(AsciiPalette.contrast(p.selectionForeground(), p.selectionBackground()) >= 4.5);
                assertTrue(AsciiPalette.contrast(p.muted(), p.background()) >= 3);
                assertTrue(AsciiPalette.contrast(p.border(), p.background()) >= 3);
                JPanel panel = new JPanel(); JButton run = new JButton("Run"); run.setEnabled(false); panel.add(run);
                JTextField field = new JTextField("source"); panel.add(field); AsciiStyle.apply(panel, p);
                assertEquals("Run", run.getText()); assertEquals("Run", run.getAccessibleContext().getAccessibleName());
                assertEquals(p.background(), field.getBackground()); assertEquals(p.selectionForeground(), field.getSelectedTextColor());
                assertEquals(Font.MONOSPACED, run.getFont().getFamily());
            }
        });
    }

    private static void edt(Runnable task) throws Exception {
        FutureTask<Void> future = new FutureTask<>(() -> { task.run(); return null; });
        SwingUtilities.invokeAndWait(future); future.get();
    }
}
