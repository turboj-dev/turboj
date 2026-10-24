package dev.turboj.commandj.swing;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.FutureTask;
import javax.swing.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AsciiSurfaceTest {
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
