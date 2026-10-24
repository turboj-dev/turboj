package dev.turboj.ui;

import dev.turboj.ui.themes.Theme;
import java.awt.*;
import java.util.List;
import javax.swing.*;
import javax.swing.border.AbstractBorder;
import javax.swing.plaf.ColorUIResource;
import javax.swing.plaf.BorderUIResource;
import javax.swing.plaf.FontUIResource;
import javax.swing.plaf.basic.*;

/** Theme-backed Swing chrome. No platform menu delegate may substitute its own text colors. */
public final class MenuStyler {
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
        Palette p = palette(theme);
        for (String type : List.of("Menu", "MenuItem", "CheckBoxMenuItem", "RadioButtonMenuItem")) {
            color(type + ".background", p.background()); color(type + ".foreground", p.foreground());
            color(type + ".selectionBackground", p.selectedBackground());
            color(type + ".selectionForeground", p.selectedForeground());
            color(type + ".disabledForeground", p.disabled());
            color(type + ".acceleratorForeground", p.foreground());
            color(type + ".acceleratorSelectionForeground", p.selectedForeground());
            UIManager.put(type + ".acceleratorFont", new FontUIResource(Font.DIALOG, Font.PLAIN, 16));
            UIManager.put(type + ".arrowIcon", type.equals("Menu") ? new MenuIcon(p, Glyph.ARROW) : new EmptyIcon());
            UIManager.put(type + ".borderPainted", false);
        }
        UIManager.put("CheckBoxMenuItem.checkIcon", new MenuIcon(p, Glyph.CHECK));
        UIManager.put("RadioButtonMenuItem.checkIcon", new MenuIcon(p, Glyph.RADIO));
        color("PopupMenu.background", p.background()); color("PopupMenu.foreground", p.foreground());
        color("MenuBar.background", p.background()); color("MenuBar.foreground", p.foreground());
        color("Separator.foreground", p.border()); color("Separator.background", p.background());
        UIManager.put("PopupMenu.border", BorderFactory.createLineBorder(p.border()));
        UIManager.put("MenuBar.border", BorderFactory.createEmptyBorder(3, 5, 3, 5));
        UIManager.put("MenuBar.gradient", null);
        UIManager.put("MenuBarUI", BasicMenuBarUI.class.getName());

        Color surface = theme.color("ui", "surface"), fg = readable(theme.color("ui", "foreground"), surface, 4.5);
        Color disabled = readable(theme.color("ui", "muted"), surface, 3);
        Color selection = theme.color("intelligence", "selectionBackground");
        Color selectedFg = readable(theme.color("intelligence", "selectionForeground"), selection, 4.5);
        Color border = readable(theme.color("ui", "border"), surface, 3);
        for (String type : List.of("Panel", "Viewport", "Label", "Button", "ToggleButton", "CheckBox",
                "RadioButton", "ComboBox", "List", "Table", "TableHeader", "Tree", "TabbedPane",
                "TextField", "FormattedTextField", "PasswordField", "TextArea", "TextPane", "EditorPane", "Spinner", "ScrollPane")) {
            color(type + ".background", surface); color(type + ".foreground", fg);
            color(type + ".disabledForeground", disabled); color(type + ".disabledText", disabled);
            color(type + ".inactiveForeground", disabled); color(type + ".inactiveBackground", surface);
            color(type + ".selectionBackground", selection); color(type + ".selectionForeground", selectedFg);
            color(type + ".focus", readable(theme.color("ui", "focus"), surface, 3));
        }
        color("TabbedPane.selected", selection); color("TabbedPane.selectHighlight", border);
        color("TabbedPane.contentAreaColor", surface); color("TabbedPane.highlight", border);
        color("TabbedPane.shadow", border); color("TabbedPane.darkShadow", border);
        UIManager.put("TabbedPaneUI", ThemeTabs.class.getName());
        color("Table.gridColor", border); color("TextField.caretForeground", fg);
        color("TextArea.caretForeground", fg); color("PasswordField.caretForeground", fg);
        color("ComboBox.buttonBackground", surface); color("ComboBox.buttonDarkShadow", border);
        color("ComboBox.buttonShadow", border); color("ComboBox.buttonHighlight", border);
        color("ComboBox.disabledBackground", surface); color("ComboBox.disabledForeground", disabled);
        color("ScrollBar.background", surface); color("ScrollBar.thumb", border);
        color("ScrollBar.thumbDarkShadow", border); color("ScrollBar.thumbHighlight", border);
        color("ScrollBar.track", surface); color("ScrollBar.trackHighlight", surface);
        UIManager.put("Button.gradient", null); UIManager.put("ToggleButton.gradient", null);
        UIManager.put("CheckBox.gradient", null); UIManager.put("RadioButton.gradient", null);
        // Aqua delegates/borders can paint a white native bezel while honoring a dark foreground.
        // Explicit headless-friendly delegates also make screenshots and the running app agree.
        UIManager.put("ButtonUI", ThemeButton.class.getName());
        UIManager.put("ToggleButtonUI", ThemeToggle.class.getName());
        UIManager.put("CheckBoxUI", ThemeCheckBox.class.getName());
        UIManager.put("RadioButtonUI", ThemeRadio.class.getName());
        UIManager.put("LabelUI", ThemeLabel.class.getName());
        for (String type : List.of("ComboBox", "Spinner", "TextField", "FormattedTextField", "PasswordField",
                "TextArea", "TextPane", "EditorPane", "List", "Table", "TableHeader", "Tree", "ScrollPane", "ScrollBar"))
            UIManager.put(type + "UI", "javax.swing.plaf.basic.Basic" + type + "UI");
        color("control", surface); color("controlText", fg); color("controlShadow", border);
        color("controlDkShadow", border); color("controlHighlight", border); color("controlLtHighlight", border);
        color("text", surface); color("textText", fg); color("textHighlight", selection);
        color("textHighlightText", selectedFg); color("textInactiveText", disabled);
        color("ToggleButton.select", selection); color("Button.select", selection);
        var controlBorder = new BorderUIResource(new ControlBorder());
        UIManager.put("Button.border", controlBorder); UIManager.put("ToggleButton.border", controlBorder);
        for (String type : List.of("ComboBox", "TextField", "FormattedTextField", "PasswordField", "Spinner", "ScrollPane"))
            UIManager.put(type + ".border", new BorderUIResource(BorderFactory.createLineBorder(border)));
        var choiceBorder = new BorderUIResource(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        UIManager.put("CheckBox.border", choiceBorder); UIManager.put("RadioButton.border", choiceBorder);
        Palette controls = new Palette(surface, fg, surface, fg, disabled,
                readable(theme.color("ui", "focus"), surface, 3), border);
        UIManager.put("CheckBox.icon", new MenuIcon(controls, Glyph.CHECK));
        UIManager.put("RadioButton.icon", new MenuIcon(controls, Glyph.RADIO));
    }

    public static void style(Component component, Theme theme) {
        Palette p = palette(theme);
        if (component instanceof JMenuBar bar) bar.setUI(new BasicMenuBarUI());
        else if (component instanceof JMenu menu) menu.setUI(new BasicMenuUI());
        else if (component instanceof JCheckBoxMenuItem item) item.setUI(new BasicCheckBoxMenuItemUI());
        else if (component instanceof JRadioButtonMenuItem item) item.setUI(new BasicRadioButtonMenuItemUI());
        else if (component instanceof JMenuItem item) item.setUI(new BasicMenuItemUI());
        else if (component instanceof JPopupMenu popup) popup.setUI(new BasicPopupMenuUI());
        else if (component instanceof JSeparator separator) {
            separator.setUI(new BasicPopupMenuSeparatorUI());
            separator.setForeground(p.border()); separator.setBackground(p.background()); return;
        }
        component.setBackground(p.background()); component.setForeground(p.foreground());
        component.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 16));
        if (component instanceof JComponent swing) swing.setOpaque(true);
        if (component instanceof JMenuItem item) {
            item.putClientProperty("html.disable", Boolean.TRUE);
            item.setBorder(new SelectionBorder(p.focus()));
            item.setBorderPainted(true);
        }
        if (component instanceof JMenuBar bar) {
            bar.setBorder(BorderFactory.createEmptyBorder(3, 5, 3, 5));
            for (Component child : bar.getComponents()) style(child, theme);
        } else if (component instanceof JMenu menu) {
            menu.putClientProperty("turboj.menu.theme", theme);
            if (!Boolean.TRUE.equals(menu.getClientProperty("turboj.menu.listener"))) {
                menu.putClientProperty("turboj.menu.listener", Boolean.TRUE);
                menu.addMenuListener(new javax.swing.event.MenuListener() {
                    @Override public void menuSelected(javax.swing.event.MenuEvent event) {
                        refreshActions(menu.getPopupMenu());
                        SwingUtilities.invokeLater(() -> {
                            if (menu.isSelected()) {
                                style(menu.getPopupMenu(), (Theme) menu.getClientProperty("turboj.menu.theme"));
                                refreshActions(menu.getPopupMenu());
                            }
                        });
                    }
                    @Override public void menuDeselected(javax.swing.event.MenuEvent event) { }
                    @Override public void menuCanceled(javax.swing.event.MenuEvent event) { }
                });
            }
            style(menu.getPopupMenu(), theme);
        } else if (component instanceof JPopupMenu popup) {
            popup.setBorder(BorderFactory.createLineBorder(p.border()));
            for (Component child : popup.getComponents()) style(child, theme);
        }
    }

    private static void refreshActions(JPopupMenu popup) {
        for (Component child : popup.getComponents()) {
            if (child instanceof JMenuItem item && item.getAction() != null)
                item.setEnabled(item.getAction().isEnabled());
            if (child instanceof JMenu menu) refreshActions(menu.getPopupMenu());
        }
    }

    private static void color(String key, Color value) { UIManager.put(key, new ColorUIResource(value)); }

    /** Retain the supplied color whenever readable; adjust only as much as a custom palette needs. */
    static Color readable(Color candidate, Color background, double minimum) {
        if (contrast(candidate, background) >= minimum) return candidate;
        Color end = contrast(Color.BLACK, background) > contrast(Color.WHITE, background) ? Color.BLACK : Color.WHITE;
        for (int amount = 1; amount <= 255; amount++) {
            Color result = new Color((candidate.getRed() * (255 - amount) + end.getRed() * amount) / 255,
                    (candidate.getGreen() * (255 - amount) + end.getGreen() * amount) / 255,
                    (candidate.getBlue() * (255 - amount) + end.getBlue() * amount) / 255);
            if (contrast(result, background) >= minimum) return result;
        }
        return end;
    }

    public static double contrast(Color a, Color b) {
        double first = luminance(a), second = luminance(b);
        return (Math.max(first, second) + .05) / (Math.min(first, second) + .05);
    }

    private static double luminance(Color c) {
        double[] channels = {c.getRed() / 255.0, c.getGreen() / 255.0, c.getBlue() / 255.0};
        for (int i = 0; i < channels.length; i++) channels[i] = channels[i] <= .04045 ? channels[i] / 12.92 : Math.pow((channels[i] + .055) / 1.055, 2.4);
        return .2126 * channels[0] + .7152 * channels[1] + .0722 * channels[2];
    }

    private static boolean active(Component component) {
        return component instanceof JMenuItem item && (item.isArmed() || item instanceof JMenu && item.isSelected());
    }

    private enum Glyph { CHECK, RADIO, ARROW }
    private record EmptyIcon() implements Icon {
        @Override public int getIconWidth() { return 0; }
        @Override public int getIconHeight() { return 0; }
        @Override public void paintIcon(Component c, Graphics g, int x, int y) { }
    }
    private record MenuIcon(Palette palette, Glyph glyph) implements Icon {
        @Override public int getIconWidth() { return 14; }
        @Override public int getIconHeight() { return 14; }
        @Override public void paintIcon(Component c, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.setColor(!c.isEnabled() ? palette.disabled() : active(c) ? palette.selectedForeground() : palette.foreground());
                g.setStroke(new BasicStroke(1.5f));
                if (glyph == Glyph.ARROW) {
                    int direction = c.getComponentOrientation().isLeftToRight() ? 1 : -1;
                    int start = direction == 1 ? x + 5 : x + 9;
                    g.drawLine(start, y + 3, start + direction * 4, y + 7);
                    g.drawLine(start + direction * 4, y + 7, start, y + 11);
                } else if (glyph == Glyph.RADIO) {
                    g.drawOval(x + 2, y + 2, 10, 10);
                    if (c instanceof AbstractButton button && button.isSelected()) g.fillOval(x + 5, y + 5, 5, 5);
                } else {
                    g.drawRect(x + 2, y + 2, 10, 10);
                    if (c instanceof AbstractButton button && button.isSelected()) {
                        g.drawLine(x + 4, y + 7, x + 6, y + 10); g.drawLine(x + 6, y + 10, x + 11, y + 4);
                    }
                }
            } finally { g.dispose(); }
        }
    }

    private static final class SelectionBorder extends AbstractBorder {
        private final Color color;
        SelectionBorder(Color color) { this.color = color; }
        @Override public Insets getBorderInsets(Component c, Insets insets) {
            insets.set(4, 6, 4, 6); return insets;
        }
        @Override public void paintBorder(Component c, Graphics g, int x, int y, int width, int height) {
            if (active(c)) { g.setColor(color); g.fillRect(x, y + 2, 2, Math.max(0, height - 4)); }
        }
    }

    /** Metal's disabled emboss and selected-tab foreground are not theme-aware. */
    public static final class ThemeTabs extends BasicTabbedPaneUI {
        public static javax.swing.plaf.ComponentUI createUI(JComponent component) { return new ThemeTabs(); }
        @Override protected void paintText(Graphics g, int placement, Font font, FontMetrics metrics,
                int index, String title, Rectangle text, boolean selected) {
            g.setFont(font);
            g.setColor(!tabPane.isEnabled() || !tabPane.isEnabledAt(index)
                    ? UIManager.getColor("TabbedPane.disabledForeground")
                    : selected ? UIManager.getColor("TabbedPane.selectionForeground") : tabPane.getForegroundAt(index));
            g.drawString(title, text.x, text.y + metrics.getAscent());
        }
    }

    private static void buttonText(Graphics graphics, AbstractButton button, Rectangle text, String title, boolean canSelect) {
        boolean selected = canSelect && (button.getModel().isArmed() && button.getModel().isPressed()
                || button instanceof JToggleButton && button.isSelected());
        Color background = selected ? UIManager.getColor("Button.selectionBackground") : button.getBackground();
        Color foreground = !button.isEnabled() ? UIManager.getColor("Button.disabledText")
                : selected ? UIManager.getColor("Button.selectionForeground") : button.getForeground();
        graphics.setColor(readable(foreground, background, button.isEnabled() ? 4.5 : 3));
        BasicGraphicsUtils.drawStringUnderlineCharAt(graphics, title, button.getDisplayedMnemonicIndex(),
                text.x, text.y + graphics.getFontMetrics().getAscent());
    }

    private static void choiceFocus(Graphics graphics, Rectangle text) {
        graphics.setColor(UIManager.getColor("CheckBox.focus"));
        graphics.drawRect(text.x - 2, text.y - 2, text.width + 3, text.height + 3);
    }

    public static final class ThemeButton extends BasicButtonUI {
        public static javax.swing.plaf.ComponentUI createUI(JComponent component) { return new ThemeButton(); }
        @Override protected void paintText(Graphics g, AbstractButton b, Rectangle r, String text) { buttonText(g, b, r, text, true); }
        @Override protected void paintButtonPressed(Graphics g, AbstractButton b) {
            g.setColor(UIManager.getColor("Button.selectionBackground")); g.fillRect(0, 0, b.getWidth(), b.getHeight());
        }
    }

    public static final class ThemeToggle extends BasicToggleButtonUI {
        public static javax.swing.plaf.ComponentUI createUI(JComponent component) { return new ThemeToggle(); }
        @Override protected void paintText(Graphics g, AbstractButton b, Rectangle r, String text) { buttonText(g, b, r, text, true); }
        @Override protected void paintButtonPressed(Graphics g, AbstractButton b) {
            g.setColor(UIManager.getColor("Button.selectionBackground")); g.fillRect(0, 0, b.getWidth(), b.getHeight());
        }
    }

    public static final class ThemeCheckBox extends BasicCheckBoxUI {
        public static javax.swing.plaf.ComponentUI createUI(JComponent component) { return new ThemeCheckBox(); }
        @Override protected void paintText(Graphics g, AbstractButton b, Rectangle r, String text) { buttonText(g, b, r, text, false); }
        @Override protected void paintFocus(Graphics g, Rectangle text, Dimension size) { choiceFocus(g, text); }
    }

    public static final class ThemeRadio extends BasicRadioButtonUI {
        public static javax.swing.plaf.ComponentUI createUI(JComponent component) { return new ThemeRadio(); }
        @Override protected void paintText(Graphics g, AbstractButton b, Rectangle r, String text) { buttonText(g, b, r, text, false); }
        @Override protected void paintFocus(Graphics g, Rectangle text, Dimension size) { choiceFocus(g, text); }
    }

    public static final class ThemeLabel extends BasicLabelUI {
        public static javax.swing.plaf.ComponentUI createUI(JComponent component) { return new ThemeLabel(); }
        @Override protected void paintDisabledText(JLabel label, Graphics graphics, String text, int x, int y) {
            graphics.setColor(readable(UIManager.getColor("Label.disabledForeground"), label.getBackground(), 3));
            BasicGraphicsUtils.drawStringUnderlineCharAt(graphics, text, label.getDisplayedMnemonicIndex(), x, y);
        }
    }

    private static final class ControlBorder extends AbstractBorder {
        @Override public Insets getBorderInsets(Component c, Insets insets) { insets.set(7, 10, 7, 10); return insets; }
        @Override public void paintBorder(Component c, Graphics graphics, int x, int y, int width, int height) {
            graphics.setColor(UIManager.getColor(c.hasFocus() ? "Button.focus" : "controlShadow"));
            graphics.drawRect(x, y, width - 1, height - 1);
            if (c.hasFocus()) graphics.drawRect(x + 1, y + 1, width - 3, height - 3);
        }
    }
}
