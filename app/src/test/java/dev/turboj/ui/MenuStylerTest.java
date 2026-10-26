package dev.turboj.ui;

import static org.junit.jupiter.api.Assertions.*;
import dev.turboj.commandj.swing.AsciiBorder;
import dev.turboj.ui.themes.Theme;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.image.BufferedImage;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.*;
import org.junit.jupiter.api.Test;

class MenuStylerTest {
    @Test void allCatalogMenuRolesKeepRequiredContrast() {
        for (String id : Theme.bundledResources().keySet()) {
            var p = MenuStyler.palette(Theme.bundled(id));
            assertTrue(MenuStyler.contrast(p.foreground(), p.background()) >= 4.5, id + " text/accelerators");
            assertTrue(MenuStyler.contrast(p.selectedForeground(), p.selectedBackground()) >= 4.5, id + " selection");
            assertTrue(MenuStyler.contrast(p.disabled(), p.background()) >= 3, id + " disabled");
            assertTrue(MenuStyler.contrast(p.focus(), p.selectedBackground()) >= 3, id + " focus");
            assertTrue(MenuStyler.contrast(p.border(), p.background()) >= 3, id + " separators");
        }
    }

    @Test void everyCatalogThemePaintsTextAndAcceleratorsWithConsistentContrastWithoutItemFrames() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (String id : Theme.bundledResources().keySet()) {
                Theme theme = Theme.bundled(id); var p = MenuStyler.palette(theme);
                MenuStyler.install(theme);
                var item = new JMenuItem("Compile project");
                KeyStroke shortcut = KeyStroke.getKeyStroke("meta shift F6");
                item.setAccelerator(shortcut); MenuStyler.style(item, theme);
                assertInstanceOf(MenuStyler.AsciiMenuItemUI.class, item.getUI());
                assertEquals(AsciiChrome.font(), item.getFont());
                assertEquals(AsciiChrome.font(), UIManager.getFont("MenuItem.acceleratorFont"));
                assertEquals(Font.MONOSPACED, item.getFont().getFamily());
                assertFalse(item.isBorderPainted());
                assertEquals(new Insets(0, 0, 0, 0), item.getInsets());
                item.setEnabled(false);
                BufferedImage disabled = paint(item);
                assertTextColor(disabled, p.disabled(), id + " disabled");
                int cell = item.getFontMetrics(item.getFont()).charWidth('M');
                int shortcutWidth = item.getFontMetrics(item.getFont()).stringWidth(MenuStyler.accelerator(shortcut));
                assertTrue(hasColor(disabled, p.disabled(), disabled.getWidth() - cell * 3 - shortcutWidth,
                        disabled.getWidth() - cell * 3), id + " disabled shortcut");
                assertEquals(p.background().getRGB(), disabled.getRGB(0, 0));

                item.setEnabled(true); item.setArmed(true);
                BufferedImage selected = paint(item);
                assertTextColor(selected, p.selectedForeground(), id + " selected");
                assertTrue(hasColor(selected, p.selectedForeground(), selected.getWidth() - cell * 3 - shortcutWidth,
                        selected.getWidth() - cell * 3), id + " selected shortcut");
                for (int x = 0; x < selected.getWidth(); x++) {
                    assertEquals(p.selectedBackground().getRGB(), selected.getRGB(x, 0), id + " no item outline");
                    assertEquals(p.selectedBackground().getRGB(), selected.getRGB(x, selected.getHeight() - 1));
                }
                assertEquals(shortcut, item.getAccelerator());
                dev.turboj.commandj.keymap.KeyStrokeSpec.useSymbols(false);
                assertEquals("Cmd+Shift+F6", MenuStyler.accelerator(shortcut));
                dev.turboj.commandj.keymap.KeyStrokeSpec.useSymbols(true);
                assertEquals("⇧⌘F6", MenuStyler.accelerator(shortcut), "macOS notation: ⌃⌥⇧⌘ then the key");
                dev.turboj.commandj.keymap.KeyStrokeSpec.useSymbols(false);
                assertEquals(0, UIManager.getIcon("MenuItem.arrowIcon").getIconWidth());
                var popup = new JPopupMenu(); popup.add(item); popup.addSeparator();
                MenuStyler.style(popup, theme);
                assertInstanceOf(AsciiBorder.class, popup.getBorder());
                assertInstanceOf(MenuStyler.AsciiSeparatorUI.class, ((JSeparator) popup.getComponent(1)).getUI());
                assertEquals(theme.background(), new JPanel().getBackground(), id + " shared control surface");
                var bar = new JMenuBar(); bar.add(new JMenu("Options"));
                MenuStyler.style(bar, theme); bar.setSize(400, 24); bar.doLayout();
                var image = new BufferedImage(400, 24, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = image.createGraphics(); bar.paint(g); g.dispose();
                assertEquals(theme.menuBackground().getRGB(), image.getRGB(390, 12), id + " menubar background");
            }
        });
    }

    @Test void checkboxRadioAndSubmenuIndicatorsAreTextAndKeepTheirActions() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Theme theme = Theme.bundled("classic");
            MenuStyler.install(theme); var p = MenuStyler.palette(theme);
            AtomicInteger calls = new AtomicInteger();
            var checkbox = new JCheckBoxMenuItem("Auto test", true);
            checkbox.addActionListener(event -> calls.incrementAndGet());
            MenuStyler.style(checkbox, theme);
            assertMarker(checkbox, "[x]", p.foreground(), p.background());
            checkbox.doClick(0);
            assertEquals(1, calls.get()); assertFalse(checkbox.isSelected());
            assertMarker(checkbox, "[ ]", p.foreground(), p.background());

            var radio = new JRadioButtonMenuItem("IntelliJ keymap", true);
            MenuStyler.style(radio, theme); assertMarker(radio, "(o)", p.foreground(), p.background());
            radio.setSelected(false); assertMarker(radio, "( )", p.foreground(), p.background());
            var popup = new JPopupMenu(); var submenu = new JMenu("Themes"); popup.add(submenu);
            MenuStyler.style(popup, theme);
            BufferedImage image = paint(submenu);
            int cell = submenu.getFontMetrics(submenu.getFont()).charWidth('M');
            assertTrue(hasColor(image, p.foreground(), image.getWidth() - cell * 2, image.getWidth() - cell));
            assertFalse(submenu.isBorderPainted());
        });
    }

    @Test void reopeningMenuRefreshesDynamicAvailabilityAndStylesNewItemsWithoutDuplicatingActions() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final boolean[] available = {false};
            AtomicInteger calls = new AtomicInteger();
            Action action = new AbstractAction("Compile") {
                @Override public boolean isEnabled() { return available[0]; }
                @Override public void actionPerformed(ActionEvent event) { calls.incrementAndGet(); }
            };
            var menu = new JMenu("Run"); var item = menu.add(action);
            Theme theme = Theme.bundled("classic");
            MenuStyler.install(theme); MenuStyler.style(menu, theme);
            assertFalse(item.isEnabled()); available[0] = true;
            JMenuItem added = menu.add("Added later");
            menu.setSelected(true);
            assertTrue(item.isEnabled());
            assertInstanceOf(MenuStyler.AsciiMenuItemUI.class, added.getUI());
            assertEquals(AsciiChrome.font(), added.getFont());
            menu.setSelected(false);
            MenuStyler.style(menu, theme); menu.setSelected(true); menu.setSelected(false);
            item.doClick(0); assertEquals(1, calls.get());
            assertEquals(action, item.getAction());
        });
    }

    private static BufferedImage paint(JMenuItem item) {
        item.setSize(item.getPreferredSize());
        var image = new BufferedImage(item.getWidth(), item.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics(); item.paint(graphics); graphics.dispose();
        return image;
    }

    private static boolean hasColor(BufferedImage image, Color color, int left, int right) {
        for (int y = 0; y < image.getHeight(); y++)
            for (int x = Math.max(0, left); x < Math.min(image.getWidth(), right); x++)
                if (image.getRGB(x, y) == color.getRGB()) return true;
        return false;
    }

    private static void assertTextColor(BufferedImage image, Color color, String message) {
        assertTrue(hasColor(image, color, 0, image.getWidth()), message);
    }

    private static void assertMarker(JMenuItem item, String marker, Color foreground, Color background) {
        BufferedImage actual = paint(item);
        int cell = item.getFontMetrics(item.getFont()).charWidth('M');
        var expected = new BufferedImage(actual.getWidth(), actual.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = expected.createGraphics();
        g.setColor(background); g.fillRect(0, 0, expected.getWidth(), expected.getHeight());
        g.setFont(AsciiChrome.font()); g.setColor(foreground);
        FontMetrics fm = g.getFontMetrics();
        g.drawString(marker, cell, (expected.getHeight() - fm.getHeight()) / 2 + fm.getAscent());
        g.dispose();
        for (int y = 0; y < actual.getHeight(); y++)
            for (int x = cell; x < cell * 4; x++)
                assertEquals(expected.getRGB(x, y), actual.getRGB(x, y), marker + " pixel " + x + "," + y);
    }
}
