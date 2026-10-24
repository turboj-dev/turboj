package dev.turboj.ui;

import static org.junit.jupiter.api.Assertions.*;
import dev.turboj.ui.themes.Theme;
import java.awt.Component;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.*;
import org.junit.jupiter.api.Test;

class AsciiMenuBarTest {
    @Test void narrowFrameMovesExistingMenusToMoreAndWideningRestoresTheirOrderAndActions() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            Theme theme = Theme.bundled("classic");
            MenuStyler.install(theme);
            var bar = new AsciiMenuBar();
            var original = new ArrayList<JMenu>();
            AtomicInteger calls = new AtomicInteger();
            for (String title : List.of("File", "Edit", "Search", "Refactor", "Project", "Build",
                    "Run", "Test", "Debug", "Tools", "Window", "Options", "Help")) {
                var menu = new JMenu(title);
                var action = new JMenuItem(title + " action");
                action.addActionListener(event -> calls.incrementAndGet());
                menu.add(action); bar.add(menu); original.add(menu);
            }
            bar.sealMenus(); MenuStyler.style(bar, theme);
            assertEquals(original, bar.allMenus());
            assertThrows(UnsupportedOperationException.class, () -> bar.allMenus().clear());

            bar.setSize(640, 24); bar.doLayout();
            assertSame(bar, bar.overflowMenu().getParent());
            assertTrue(bar.overflowMenu().getMenuComponentCount() > 0);
            assertEquals(original, reachable(bar));
            for (Component component : bar.getComponents())
                assertTrue(component.getX() + component.getWidth() <= bar.getWidth(), component.toString());
            JMenu moved = (JMenu) bar.overflowMenu().getMenuComponent(0);
            moved.getItem(0).doClick(0);
            assertEquals(1, calls.get());
            MenuStyler.style(bar, Theme.bundled("dark"));
            assertInstanceOf(MenuStyler.AsciiMenuUI.class, moved.getUI());

            bar.setSize(1200, 24); bar.doLayout();
            assertNull(bar.overflowMenu().getParent());
            assertEquals(0, bar.overflowMenu().getMenuComponentCount());
            assertEquals(original, reachable(bar));
            for (int i = 0; i < original.size(); i++) assertSame(original.get(i), bar.getMenu(i));
            moved.getItem(0).doClick(0);
            assertEquals(2, calls.get());

            bar.setSize(100, 24); bar.doLayout();
            assertEquals(original, reachable(bar));
            bar.setSize(640, 24); bar.doLayout();
            assertEquals(original, reachable(bar));
        });
    }

    @Test void resizeClosesOnlyItsOwnOpenMenuPath() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            MenuStyler.install(Theme.bundled("classic"));
            var bar = new AsciiMenuBar();
            for (int i = 0; i < 8; i++) bar.add(new JMenu("Menu number " + i));
            bar.sealMenus(); MenuStyler.style(bar, Theme.bundled("classic"));
            bar.setSize(1400, 24); bar.doLayout();
            JMenu open = bar.getMenu(6);
            MenuSelectionManager.defaultManager().setSelectedPath(new MenuElement[] {bar, open, open.getPopupMenu()});
            bar.setSize(640, 24); bar.doLayout();
            assertEquals(0, MenuSelectionManager.defaultManager().getSelectedPath().length);
        });
    }

    private static List<JMenu> reachable(AsciiMenuBar bar) {
        var result = new ArrayList<JMenu>();
        for (Component component : bar.getComponents())
            if (component == bar.overflowMenu())
                for (Component child : bar.overflowMenu().getMenuComponents()) result.add((JMenu) child);
            else result.add((JMenu) component);
        return result;
    }
}
