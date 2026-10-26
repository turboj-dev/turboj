package dev.turboj.ui;

import dev.turboj.commandj.swing.KeyGlyphs;

import dev.turboj.commandj.swing.AsciiBorder;
import dev.turboj.commandj.swing.AsciiPalette;
import dev.turboj.commandj.swing.AsciiStyle;
import dev.turboj.ui.themes.Theme;
import java.awt.*;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.util.List;
import javax.swing.*;
import javax.swing.plaf.ColorUIResource;
import javax.swing.plaf.FontUIResource;
import javax.swing.plaf.basic.*;

/** Flat text-cell menus sharing the application's terminal-style controls and theme colors. */
public final class MenuStyler {
    private static final String PALETTE = "turboj.menu.palette";
    private MenuStyler() {}

    public record Palette(Color background, Color foreground, Color selectedBackground,
            Color selectedForeground, Color disabled, Color focus, Color border) {}

    public static Palette palette(Theme theme) {
        Color background = theme.menuBackground(), selected = theme.color("menu", "activeBackground");
        return new Palette(background, readable(theme.menuForeground(), background, 4.5), selected,
                readable(theme.color("menu", "activeForeground"), selected, 4.5),
                readable(theme.color("ui", "muted"), background, 3),
                readable(theme.color("ui", "focus"), selected, 3),
                readable(theme.color("ui", "border"), background, 3));
    }

    /** Install before creating controls, and again whenever the application changes its theme. */
    public static void install(Theme theme) {
        AsciiStyle.installDefaults(AsciiChrome.editor(theme));
        Palette p = palette(theme);
        UIManager.put(PALETTE, p);
        var font = new FontUIResource(AsciiChrome.font());
        for (String type : List.of("Menu", "MenuItem", "CheckBoxMenuItem", "RadioButtonMenuItem")) {
            color(type + ".background", p.background()); color(type + ".foreground", p.foreground());
            color(type + ".selectionBackground", p.selectedBackground());
            color(type + ".selectionForeground", p.selectedForeground());
            color(type + ".disabledForeground", p.disabled());
            color(type + ".acceleratorForeground", p.foreground());
            color(type + ".acceleratorSelectionForeground", p.selectedForeground());
            UIManager.put(type + ".font", font); UIManager.put(type + ".acceleratorFont", font);
            UIManager.put(type + ".border", BorderFactory.createEmptyBorder());
            UIManager.put(type + ".borderPainted", false);
            UIManager.put(type + ".arrowIcon", type.equals("Menu") ? new MenuIcon(p, Glyph.ARROW) : new EmptyIcon());
        }
        UIManager.put("MenuUI", AsciiMenuUI.class.getName());
        UIManager.put("MenuItemUI", AsciiMenuItemUI.class.getName());
        UIManager.put("CheckBoxMenuItemUI", AsciiCheckMenuUI.class.getName());
        UIManager.put("RadioButtonMenuItemUI", AsciiRadioMenuUI.class.getName());
        UIManager.put("PopupMenuUI", BasicPopupMenuUI.class.getName());
        UIManager.put("PopupMenuSeparatorUI", AsciiSeparatorUI.class.getName());
        UIManager.put("MenuBarUI", BasicMenuBarUI.class.getName());
        UIManager.put("CheckBoxMenuItem.checkIcon", new MenuIcon(p, Glyph.CHECK));
        UIManager.put("RadioButtonMenuItem.checkIcon", new MenuIcon(p, Glyph.RADIO));
        color("PopupMenu.background", p.background()); color("PopupMenu.foreground", p.foreground());
        color("MenuBar.background", p.background()); color("MenuBar.foreground", p.foreground());
        color("Separator.foreground", p.border()); color("Separator.background", p.background());
        UIManager.put("PopupMenu.font", font); UIManager.put("MenuBar.font", font);
        UIManager.put("PopupMenu.border", popupBorder(p));
        UIManager.put("MenuBar.border", BorderFactory.createEmptyBorder());
        UIManager.put("MenuBar.gradient", null);
    }

    public static void style(Component component, Theme theme) {
        Palette p = palette(theme);
        if (component instanceof JComponent swing) swing.putClientProperty(PALETTE, p);
        if (component instanceof JMenuBar bar) bar.setUI(new BasicMenuBarUI());
        else if (component instanceof JMenu menu) menu.setUI(new AsciiMenuUI());
        else if (component instanceof JCheckBoxMenuItem item) item.setUI(new AsciiCheckMenuUI());
        else if (component instanceof JRadioButtonMenuItem item) item.setUI(new AsciiRadioMenuUI());
        else if (component instanceof JMenuItem item) item.setUI(new AsciiMenuItemUI());
        else if (component instanceof JPopupMenu popup) popup.setUI(new BasicPopupMenuUI());
        else if (component instanceof JSeparator separator) separator.setUI(new AsciiSeparatorUI());
        component.setBackground(p.background()); component.setForeground(p.foreground());
        component.setFont(AsciiChrome.font());
        if (component instanceof JComponent swing) swing.setOpaque(true);
        if (component instanceof JMenuItem item) {
            item.putClientProperty("html.disable", Boolean.TRUE);
            item.setMargin(new Insets(0, 0, 0, 0));
            item.setBorder(BorderFactory.createEmptyBorder()); item.setBorderPainted(false);
        }
        if (component instanceof JMenuBar bar) {
            bar.setBorder(BorderFactory.createEmptyBorder());
            for (Component child : bar.getComponents()) style(child, theme);
            if (bar instanceof AsciiMenuBar ascii && ascii.overflowMenu().getParent() != bar)
                style(ascii.overflowMenu(), theme);
        } else if (component instanceof JMenu menu) {
            menu.putClientProperty("turboj.menu.theme", theme);
            if (!Boolean.TRUE.equals(menu.getClientProperty("turboj.menu.listener"))) {
                menu.putClientProperty("turboj.menu.listener", Boolean.TRUE);
                menu.addMenuListener(new javax.swing.event.MenuListener() {
                    @Override public void menuSelected(javax.swing.event.MenuEvent event) {
                        style(menu.getPopupMenu(), (Theme) menu.getClientProperty("turboj.menu.theme"));
                        refreshActions(menu.getPopupMenu());
                    }
                    @Override public void menuDeselected(javax.swing.event.MenuEvent event) {}
                    @Override public void menuCanceled(javax.swing.event.MenuEvent event) {}
                });
            }
            style(menu.getPopupMenu(), theme);
        } else if (component instanceof JPopupMenu popup) {
            popup.setBorder(popupBorder(p));
            for (Component child : popup.getComponents()) style(child, theme);
        }
    }

    private static AsciiBorder popupBorder(Palette p) {
        return new AsciiBorder("", new AsciiPalette(p.background(), p.foreground(),
                p.selectedBackground(), p.selectedForeground(), p.disabled(), p.border(),
                AsciiChrome.font()), AsciiBorder.Line.SINGLE);
    }

    private static void refreshActions(JPopupMenu popup) {
        for (Component child : popup.getComponents()) {
            if (child instanceof JMenuItem item && item.getAction() != null)
                item.setEnabled(item.getAction().isEnabled());
            if (child instanceof JMenu menu) refreshActions(menu.getPopupMenu());
        }
    }

    private static void color(String key, Color value) { UIManager.put(key, new ColorUIResource(value)); }
    static Color readable(Color candidate, Color background, double minimum) {
        return AsciiPalette.readable(candidate, background, minimum);
    }
    public static double contrast(Color a, Color b) { return AsciiPalette.contrast(a, b); }

    private static Palette colors(JComponent component) {
        Object value = component.getClientProperty(PALETTE);
        return (Palette) (value instanceof Palette ? value : UIManager.get(PALETTE));
    }
    private static boolean active(JMenuItem item) {
        return item.isEnabled() && (item.isArmed() || item instanceof JMenu && item.isSelected());
    }
    private static boolean topLevel(JMenuItem item) {
        return item instanceof JMenu menu && menu.isTopLevelMenu();
    }

    /** Readable text labels preserve the actual KeyStroke, including platform modifiers. */
    static String accelerator(KeyStroke shortcut) {
        if (shortcut == null) return "";
        var label = new StringBuilder();
        int modifiers = shortcut.getModifiers();
        if (dev.turboj.commandj.keymap.KeyStrokeSpec.usesSymbols()) {
            // Apple's menu notation: ⌃⌥⇧⌘ then the key, no plus signs.
            if ((modifiers & InputEvent.CTRL_DOWN_MASK) != 0) label.append('⌃');
            if ((modifiers & InputEvent.ALT_DOWN_MASK) != 0) label.append('⌥');
            if ((modifiers & InputEvent.SHIFT_DOWN_MASK) != 0) label.append('⇧');
            if ((modifiers & InputEvent.META_DOWN_MASK) != 0) label.append('⌘');
            return label.append(shortcut.getKeyCode() == KeyEvent.VK_UNDEFINED ? String.valueOf(shortcut.getKeyChar())
                    : dev.turboj.commandj.swing.SwingKeys.keyName(shortcut.getKeyCode())
                            .map(dev.turboj.commandj.keymap.KeyStrokeSpec::keySymbol)
                            .orElse(KeyEvent.getKeyText(shortcut.getKeyCode()))).toString();
        }
        if ((modifiers & InputEvent.META_DOWN_MASK) != 0) label.append("Cmd+");
        if ((modifiers & InputEvent.CTRL_DOWN_MASK) != 0) label.append("Ctrl+");
        if ((modifiers & InputEvent.ALT_DOWN_MASK) != 0) label.append("Option+");
        if ((modifiers & InputEvent.SHIFT_DOWN_MASK) != 0) label.append("Shift+");
        if ((modifiers & InputEvent.ALT_GRAPH_DOWN_MASK) != 0) label.append("AltGr+");
        String key = shortcut.getKeyCode() == KeyEvent.VK_UNDEFINED
                ? String.valueOf(shortcut.getKeyChar()) : KeyEvent.getKeyText(shortcut.getKeyCode());
        return label.append(key).toString();
    }
    private static String choice(JMenuItem item) {
        if (item instanceof JCheckBoxMenuItem) return item.isSelected() ? "[x]" : "[ ]";
        if (item instanceof JRadioButtonMenuItem) return item.isSelected() ? "(o)" : "( )";
        return "";
    }

    private static Dimension preferred(JMenuItem item) {
        FontMetrics fm = item.getFontMetrics(AsciiChrome.font());
        int cell = fm.charWidth('M'), width = fm.stringWidth(item.getText() == null ? "" : item.getText());
        if (topLevel(item)) return new Dimension(topLevelWidth((JMenu) item), fm.getHeight() + 4);
        int shortcuts = KeyGlyphs.width(item, AsciiChrome.font(), accelerator(item.getAccelerator()));
        Container parent = item.getParent();
        if (parent != null)
            for (Component sibling : parent.getComponents())
                if (sibling instanceof JMenuItem other)
                    shortcuts = Math.max(shortcuts, KeyGlyphs.width(item, AsciiChrome.font(), accelerator(other.getAccelerator())));
        return new Dimension(width + cell * 8 + (shortcuts == 0 ? 0 : shortcuts + cell * 2), fm.getHeight() + 2);
    }

    static int topLevelWidth(JMenu menu) {
        FontMetrics fm = menu.getFontMetrics(AsciiChrome.font());
        return fm.stringWidth(menu.getText() == null ? "" : menu.getText()) + fm.charWidth('M') * 2;
    }

    private static void paintItem(Graphics graphics, JMenuItem item) {
        Palette p = colors(item);
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            boolean selected = active(item), top = topLevel(item);
            g.setColor(selected ? p.selectedBackground() : p.background());
            g.fillRect(0, 0, item.getWidth(), item.getHeight());
            g.setFont(AsciiChrome.font());
            FontMetrics fm = g.getFontMetrics();
            int cell = fm.charWidth('M'), baseline = (item.getHeight() - fm.getHeight()) / 2 + fm.getAscent();
            g.setColor(!item.isEnabled() ? p.disabled() : selected ? p.selectedForeground() : p.foreground());
            boolean leftToRight = item.getComponentOrientation().isLeftToRight();
            int labelX = leftToRight ? cell * (top ? 1 : 5) : cell * (top ? 1 : 3);
            String label = item.getText() == null ? "" : item.getText();
            if (!top) {
                String shortcut = accelerator(item.getAccelerator());
                int shortcutWidth = KeyGlyphs.width(item, AsciiChrome.font(), shortcut);
                int shortcutX = leftToRight ? item.getWidth() - cell * 3 - shortcutWidth : cell * 3;
                if (!shortcut.isEmpty()) KeyGlyphs.draw((Graphics2D) g, shortcut, shortcutX, baseline);
                String marker = choice(item);
                if (!marker.isEmpty()) g.drawString(marker, leftToRight ? cell : item.getWidth() - cell * 4, baseline);
                if (item instanceof JMenu) g.drawString(leftToRight ? ">" : "<",
                        leftToRight ? item.getWidth() - cell * 2 : cell, baseline);
                int labelRight = leftToRight ? (shortcut.isEmpty() ? item.getWidth() - cell * 3 : shortcutX - cell * 2)
                        : item.getWidth() - cell * 5;
                if (!leftToRight) labelX = shortcut.isEmpty() ? cell * 3 : shortcutX + shortcutWidth + cell * 2;
                Shape clip = g.getClip();
                g.clipRect(labelX, 0, Math.max(0, labelRight - labelX), item.getHeight());
                int textX = leftToRight ? labelX : labelRight - fm.stringWidth(label);
                BasicGraphicsUtils.drawStringUnderlineCharAt(g, label, item.getDisplayedMnemonicIndex(), textX, baseline);
                g.setClip(clip);
            } else {
                BasicGraphicsUtils.drawStringUnderlineCharAt(g, label, item.getDisplayedMnemonicIndex(), labelX, baseline);
            }
        } finally { g.dispose(); }
    }

    public static final class AsciiMenuUI extends BasicMenuUI {
        public static javax.swing.plaf.ComponentUI createUI(JComponent c) { return new AsciiMenuUI(); }
        @Override public Dimension getPreferredSize(JComponent c) { return preferred((JMenuItem) c); }
        @Override public void paint(Graphics g, JComponent c) { paintItem(g, (JMenuItem) c); }
    }
    public static final class AsciiMenuItemUI extends BasicMenuItemUI {
        public static javax.swing.plaf.ComponentUI createUI(JComponent c) { return new AsciiMenuItemUI(); }
        @Override public Dimension getPreferredSize(JComponent c) { return preferred((JMenuItem) c); }
        @Override public void paint(Graphics g, JComponent c) { paintItem(g, (JMenuItem) c); }
    }
    public static final class AsciiCheckMenuUI extends BasicCheckBoxMenuItemUI {
        public static javax.swing.plaf.ComponentUI createUI(JComponent c) { return new AsciiCheckMenuUI(); }
        @Override public Dimension getPreferredSize(JComponent c) { return preferred((JMenuItem) c); }
        @Override public void paint(Graphics g, JComponent c) { paintItem(g, (JMenuItem) c); }
    }
    public static final class AsciiRadioMenuUI extends BasicRadioButtonMenuItemUI {
        public static javax.swing.plaf.ComponentUI createUI(JComponent c) { return new AsciiRadioMenuUI(); }
        @Override public Dimension getPreferredSize(JComponent c) { return preferred((JMenuItem) c); }
        @Override public void paint(Graphics g, JComponent c) { paintItem(g, (JMenuItem) c); }
    }
    public static final class AsciiSeparatorUI extends BasicPopupMenuSeparatorUI {
        public static javax.swing.plaf.ComponentUI createUI(JComponent c) { return new AsciiSeparatorUI(); }
        @Override public Dimension getPreferredSize(JComponent c) {
            return new Dimension(0, c.getFontMetrics(AsciiChrome.font()).getHeight());
        }
        @Override public void paint(Graphics graphics, JComponent c) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                Palette p = colors(c);
                g.setColor(p.background()); g.fillRect(0, 0, c.getWidth(), c.getHeight());
                g.setFont(AsciiChrome.font()); g.setColor(p.border());
                FontMetrics fm = g.getFontMetrics();
                int cell = Math.max(1, fm.charWidth('-'));
                g.drawString("-".repeat(Math.max(0, c.getWidth() / cell - 2)), cell,
                        (c.getHeight() - fm.getHeight()) / 2 + fm.getAscent());
            } finally { g.dispose(); }
        }
    }

    private enum Glyph { CHECK, RADIO, ARROW }
    private record EmptyIcon() implements Icon {
        @Override public int getIconWidth() { return 0; }
        @Override public int getIconHeight() { return 0; }
        @Override public void paintIcon(Component c, Graphics g, int x, int y) {}
    }
    private record MenuIcon(Palette palette, Glyph glyph) implements Icon {
        @Override public int getIconWidth() { return glyph == Glyph.ARROW ? 9 : 27; }
        @Override public int getIconHeight() { return 18; }
        @Override public void paintIcon(Component c, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                boolean selected = c instanceof AbstractButton b && b.isSelected();
                String marker = switch (glyph) {
                    case CHECK -> selected ? "[x]" : "[ ]";
                    case RADIO -> selected ? "(o)" : "( )";
                    case ARROW -> c.getComponentOrientation().isLeftToRight() ? ">" : "<";
                };
                g.setFont(AsciiChrome.font());
                g.setColor(!c.isEnabled() ? palette.disabled() : c instanceof JMenuItem item && active(item)
                        ? palette.selectedForeground() : palette.foreground());
                g.drawString(marker, x, y + g.getFontMetrics().getAscent());
            } finally { g.dispose(); }
        }
    }
}
