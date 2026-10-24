package dev.turboj.ui;

import static org.junit.jupiter.api.Assertions.*;
import dev.turboj.ui.themes.Theme;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.image.BufferedImage;
import javax.swing.*;
import javax.swing.plaf.basic.BasicMenuItemUI;
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

    @Test void eachThemePaintsDisabledTextWithoutPlatformEmbossAndKeepsSelectedTabReadable() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (String id : Theme.BUNDLED) {
                Theme theme = Theme.bundled(id); var p = MenuStyler.palette(theme);
                MenuStyler.install(theme);
                var item = new JMenuItem("Disabled command");
                item.setAccelerator(KeyStroke.getKeyStroke("meta F6"));
                item.setEnabled(false); MenuStyler.style(item, theme);
                assertEquals(BasicMenuItemUI.class, item.getUI().getClass());
                item.setSize(item.getPreferredSize());
                var image = new BufferedImage(item.getWidth(), item.getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics2D graphics = image.createGraphics(); item.paint(graphics); graphics.dispose();
                boolean hasDisabledText = false;
                for (int y = 0; y < image.getHeight(); y++) for (int x = 0; x < image.getWidth(); x++)
                    if (image.getRGB(x, y) == p.disabled().getRGB()) hasDisabledText = true;
                assertTrue(hasDisabledText, id);
                assertEquals(p.foreground(), UIManager.getColor("MenuItem.acceleratorForeground"));
                assertEquals(p.selectedForeground(), UIManager.getColor("MenuItem.acceleratorSelectionForeground"));
                assertEquals(16, UIManager.getFont("MenuItem.acceleratorFont").getSize());
                assertEquals(0, UIManager.getIcon("MenuItem.arrowIcon").getIconWidth());
                assertTrue(UIManager.getIcon("Menu.arrowIcon").getIconWidth() > 0);
                var tabs = new JTabbedPane(); tabs.addTab("Local", new JPanel()); tabs.addTab("Cloud", new JPanel());
                assertInstanceOf(MenuStyler.ThemeTabs.class, tabs.getUI());
                assertTrue(MenuStyler.contrast(UIManager.getColor("TabbedPane.selectionForeground"), UIManager.getColor("TabbedPane.selected")) >= 4.5);
                assertInstanceOf(MenuStyler.ThemeButton.class, new JButton().getUI());
                assertInstanceOf(MenuStyler.ThemeToggle.class, new JToggleButton().getUI());
                assertInstanceOf(MenuStyler.ThemeCheckBox.class, new JCheckBox().getUI());
                assertInstanceOf(MenuStyler.ThemeRadio.class, new JRadioButton().getUI());
                assertEquals(javax.swing.plaf.basic.BasicComboBoxUI.class, new JComboBox<>().getUI().getClass());
                assertEquals(javax.swing.plaf.basic.BasicFormattedTextFieldUI.class, new JFormattedTextField().getUI().getClass());
                var bar = new JMenuBar(); bar.add(new JMenu("Options"));
                MenuStyler.style(bar, theme); bar.setSize(400, 32); bar.doLayout();
                var barImage = new BufferedImage(400, 32, BufferedImage.TYPE_INT_RGB);
                Graphics2D barGraphics = barImage.createGraphics(); bar.paint(barGraphics); barGraphics.dispose();
                assertEquals(theme.menuBackground().getRGB(), barImage.getRGB(390, 16), id + " empty menubar area");
                var button = new JButton("Disabled action"); button.setEnabled(false);
                button.setSize(button.getPreferredSize());
                var buttonImage = new BufferedImage(button.getWidth(), button.getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics2D buttonGraphics = buttonImage.createGraphics(); button.paint(buttonGraphics); buttonGraphics.dispose();
                assertEquals(theme.color("ui", "surface").getRGB(), buttonImage.getRGB(3, 3), id + " button bezel");
            }
        });
    }

    @Test void reopeningMenuRefreshesDynamicAvailability() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            final boolean[] availabilityState = {false};
            Action action = new AbstractAction("Compile") {
                @Override public boolean isEnabled() { return availabilityState[0]; }
                @Override public void actionPerformed(ActionEvent event) { }
            };
            var menu = new JMenu("Run"); var item = menu.add(action);
            MenuStyler.install(Theme.bundled("classic")); MenuStyler.style(menu, Theme.bundled("classic"));
            assertFalse(item.isEnabled()); availabilityState[0] = true; menu.setSelected(true);
            assertTrue(item.isEnabled()); menu.setSelected(false);
        });
    }
}
