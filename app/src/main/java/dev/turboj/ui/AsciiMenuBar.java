package dev.turboj.ui;

import java.awt.Component;
import java.awt.Dimension;
import java.awt.Insets;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JMenu;
import javax.swing.JMenuBar;
import javax.swing.MenuSelectionManager;

/** Keeps every original application menu reachable when the frame becomes narrow. */
public final class AsciiMenuBar extends JMenuBar {
    private final JMenu overflow = new JMenu("More");
    private List<JMenu> menus = List.of();
    private boolean sealed;
    private boolean arranging;
    private int visibleCount = -1;

    public AsciiMenuBar() {
        overflow.getAccessibleContext().setAccessibleDescription("Additional application menus");
    }

    /** Call after constructing the menu bar; later menu items can change normally. */
    public void sealMenus() {
        if (sealed) return;
        var original = new ArrayList<JMenu>();
        for (Component component : getComponents())
            if (component instanceof JMenu menu) original.add(menu);
        menus = List.copyOf(original);
        sealed = true;
        revalidate();
    }

    public List<JMenu> allMenus() { return menus; }
    public JMenu overflowMenu() { return overflow; }

    @Override public void doLayout() {
        if (sealed && !arranging) {
            arranging = true;
            try {
                Insets insets = getInsets();
                int available = Math.max(0, getWidth() - insets.left - insets.right);
                int total = menus.stream().mapToInt(MenuStyler::topLevelWidth).sum();
                int count = menus.size();
                if (total > available) {
                    int remaining = available - MenuStyler.topLevelWidth(overflow);
                    count = 0;
                    for (JMenu menu : menus) {
                        int width = MenuStyler.topLevelWidth(menu);
                        if (width > remaining) break;
                        remaining -= width;
                        count++;
                    }
                }
                if (count != visibleCount) {
                    // Reparenting an open submenu invalidates its selection path; never leave a detached popup.
                    var selection = MenuSelectionManager.defaultManager();
                    for (var selected : selection.getSelectedPath())
                        if (selected == this) {
                            selection.clearSelectedPath();
                            break;
                        }
                    super.removeAll();
                    overflow.removeAll();
                    for (int i = 0; i < count; i++) super.add(menus.get(i));
                    if (count < menus.size()) {
                        for (int i = count; i < menus.size(); i++) overflow.add(menus.get(i));
                        super.add(overflow);
                    }
                    visibleCount = count;
                }
            } finally { arranging = false; }
        }
        super.doLayout();
    }

    @Override public Dimension getMinimumSize() {
        return sealed ? new Dimension(MenuStyler.topLevelWidth(overflow),
                getFontMetrics(AsciiChrome.font()).getHeight() + 4) : super.getMinimumSize();
    }
}
