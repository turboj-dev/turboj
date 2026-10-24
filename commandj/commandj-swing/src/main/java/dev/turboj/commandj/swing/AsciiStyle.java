package dev.turboj.commandj.swing;

import java.awt.*;
import java.awt.event.*;
import java.util.List;
import javax.swing.*;
import javax.swing.border.*;
import javax.swing.plaf.*;
import javax.swing.plaf.basic.*;
import javax.swing.table.*;
import javax.swing.text.DefaultEditorKit;
import javax.swing.text.JTextComponent;
import javax.swing.tree.*;

/** Shared flat, monospaced controls for editor surfaces. No native or desktop-window delegates. */
public final class AsciiStyle {
    private static final String PALETTE = AsciiStyle.class.getName() + ".palette";
    private static AsciiPalette defaults = AsciiPalette.defaults();
    private AsciiStyle() {}

    public static AsciiPalette palette(Component component) {
        for (Component at = component; at != null; at = at.getParent())
            if (at instanceof JComponent c && c.getClientProperty(PALETTE) instanceof AsciiPalette p) return p;
        return defaults;
    }

    /** Install before constructing controls. Menu delegates remain the application's responsibility. */
    public static void installDefaults(AsciiPalette palette) {
        defaults = palette;
        for (String type : List.of("Panel", "Viewport", "Label", "Button", "ToggleButton", "CheckBox", "RadioButton",
                "ComboBox", "List", "Table", "TableHeader", "Tree", "TabbedPane", "TextField", "FormattedTextField",
                "PasswordField", "TextArea", "TextPane", "EditorPane", "Spinner", "ScrollPane", "ScrollBar", "OptionPane", "ToolTip")) {
            color(type + ".background", palette.background()); color(type + ".foreground", palette.foreground());
            color(type + ".disabledForeground", palette.muted()); color(type + ".disabledText", palette.muted());
            color(type + ".inactiveForeground", palette.muted()); color(type + ".inactiveBackground", palette.background());
            color(type + ".selectionBackground", palette.selectionBackground()); color(type + ".selectionForeground", palette.selectionForeground());
            color(type + ".focus", palette.border()); color(type + ".caretForeground", palette.foreground());
            UIManager.put(type + ".font", new FontUIResource(palette.font()));
        }
        color("control", palette.background()); color("controlText", palette.foreground());
        color("controlShadow", palette.border()); color("controlDkShadow", palette.border());
        color("controlHighlight", palette.border()); color("controlLtHighlight", palette.border());
        color("text", palette.background()); color("textText", palette.foreground());
        color("textHighlight", palette.selectionBackground()); color("textHighlightText", palette.selectionForeground());
        color("textInactiveText", palette.muted()); color("Table.gridColor", palette.border());
        color("ComboBox.buttonBackground", palette.background()); color("ComboBox.disabledBackground", palette.background());
        for (String type : List.of("TextField", "FormattedTextField", "PasswordField", "TextArea", "TextPane", "EditorPane", "List", "Table", "TableHeader", "Tree", "ScrollPane", "OptionPane"))
            UIManager.put(type + "UI", "javax.swing.plaf.basic.Basic" + type + "UI");
        UIManager.put("LabelUI", Label.class.getName()); UIManager.put("ButtonUI", Button.class.getName());
        UIManager.put("ToggleButtonUI", Toggle.class.getName()); UIManager.put("CheckBoxUI", CheckBox.class.getName());
        UIManager.put("RadioButtonUI", Radio.class.getName()); UIManager.put("ComboBoxUI", Combo.class.getName());
        UIManager.put("TabbedPaneUI", Tabs.class.getName()); UIManager.put("ScrollBarUI", ScrollBar.class.getName());
        UIManager.put("SpinnerUI", Spinner.class.getName());
        UIManager.put("ToolTipUI", BasicToolTipUI.class.getName());
        UIManager.put("ToolTip.border", new BorderUIResource(new AsciiBorder("", palette, AsciiBorder.Line.SINGLE)));
        for (String type : List.of("Button", "ToggleButton")) {
            UIManager.put(type + ".border", new BorderUIResource(new ButtonBorder()));
            UIManager.put(type + ".gradient", null); color(type + ".select", palette.selectionBackground());
        }
        for (String type : List.of("TextField", "FormattedTextField", "PasswordField", "ComboBox", "Spinner"))
            UIManager.put(type + ".border", new BorderUIResource(new InputBorder()));
        for (String type : List.of("ScrollPane", "TableHeader", "OptionPane"))
            UIManager.put(type + ".border", new BorderUIResource(BorderFactory.createEmptyBorder()));
        for (String type : List.of("CheckBox", "RadioButton"))
            UIManager.put(type + ".border", new BorderUIResource(BorderFactory.createEmptyBorder(3, 0, 3, 0)));
        UIManager.put("CheckBox.icon", new GlyphIcon("[ ]", "[x]"));
        UIManager.put("RadioButton.icon", new GlyphIcon("( )", "(*)"));
        UIManager.put("Tree.openIcon", new GlyphIcon("[-]", null)); UIManager.put("Tree.closedIcon", new GlyphIcon("[+]", null));
        UIManager.put("Tree.leafIcon", new GlyphIcon(" · ", null));
        UIManager.put("Tree.expandedIcon", new GlyphIcon("-", null)); UIManager.put("Tree.collapsedIcon", new GlyphIcon("+", null));
        for (String type : List.of("information", "question", "warning", "error"))
            UIManager.put("OptionPane." + type + "Icon", new GlyphIcon(type.equals("question") ? "[?]" : "[!]", null));
        UIManager.put("ScrollBar.width", palette.font().getSize() + 6);
        UIManager.put("TabbedPane.contentBorderInsets", new Insets(4, 0, 0, 0));
        UIManager.put("TabbedPane.tabAreaInsets", new Insets(0, 0, 0, 0));
        UIManager.put("TabbedPane.tabInsets", new Insets(4, 9, 4, 9));
    }

    public static void apply(Component component, AsciiPalette palette) {
        component.setBackground(palette.background()); component.setForeground(palette.foreground()); component.setFont(palette.font());
        if (component instanceof JComponent swing) swing.putClientProperty(PALETTE, palette);
        if (component instanceof JButton button) {
            button.setUI(new Button());
            button.setBorder(Boolean.TRUE.equals(button.getClientProperty("commandj.ascii.glyph")) ? BorderFactory.createEmptyBorder() : new ButtonBorder());
            button.setContentAreaFilled(true);
        }
        else if (component instanceof JCheckBox button) { button.setUI(new CheckBox()); button.setIcon(new GlyphIcon("[ ]", "[x]")); }
        else if (component instanceof JRadioButton button) { button.setUI(new Radio()); button.setIcon(new GlyphIcon("( )", "(*)")); }
        else if (component instanceof JToggleButton button) { button.setUI(new Toggle()); button.setBorder(new ButtonBorder()); }
        else if (component instanceof JLabel label) label.setUI(new Label());
        else if (component instanceof JComboBox<?> combo) { combo.setUI(new Combo()); combo.setBorder(new InputBorder()); }
        else if (component instanceof JSpinner spinner) { spinner.setUI(new Spinner()); spinner.setBorder(new InputBorder()); }
        else if (component instanceof JTabbedPane tabs) tabs.setUI(new Tabs());
        else if (component instanceof JScrollBar bar) bar.setUI(new ScrollBar());
        else if (component instanceof JSplitPane split) splitPane(split, palette);
        else if (component instanceof JToolTip tip) {
            UIManager.put("ToolTipUI", BasicToolTipUI.class.getName()); tip.updateUI();
            tip.setBorder(new AsciiBorder("", palette, AsciiBorder.Line.SINGLE));
        }
        if (component instanceof JTextComponent text) {
            javax.swing.plaf.TextUI ui = text instanceof JPasswordField ? new BasicPasswordFieldUI()
                    : text instanceof JFormattedTextField ? new BasicFormattedTextFieldUI()
                    : text instanceof JTextField ? new BasicTextFieldUI()
                    : text instanceof JTextArea ? new BasicTextAreaUI()
                    : text instanceof JTextPane ? new BasicTextPaneUI()
                    : text instanceof JEditorPane ? new BasicEditorPaneUI() : null;
            if (ui != null && (text.getUI() == null || text.getUI().getClass() != ui.getClass())) {
                int dot = text.getCaretPosition(), mark = text.getCaret().getMark();
                text.setUI(ui);
                text.setCaretPosition(Math.min(mark, text.getDocument().getLength()));
                text.moveCaretPosition(Math.min(dot, text.getDocument().getLength()));
            }
            text.setCaretColor(palette.foreground()); text.setSelectionColor(palette.selectionBackground());
            text.setSelectedTextColor(palette.selectionForeground()); text.setDisabledTextColor(palette.muted());
            // Basic text delegates need their own standard actions even when the host LAF is
            // Aqua. Dialogs deliberately retain local editing rather than global IDE shortcuts.
            int menuMask = System.getProperty("os.name", "").startsWith("Mac")
                    ? InputEvent.META_DOWN_MASK : InputEvent.CTRL_DOWN_MASK;
            text.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_C, menuMask), DefaultEditorKit.copyAction);
            text.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_X, menuMask), DefaultEditorKit.cutAction);
            text.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_V, menuMask), DefaultEditorKit.pasteAction);
            text.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_A, menuMask), DefaultEditorKit.selectAllAction);
            if (text instanceof JTextField) text.setBorder(new InputBorder());
        }
        if (component instanceof JScrollPane scroll) {
            if (scroll.getUI().getClass() != BasicScrollPaneUI.class) scroll.setUI(new BasicScrollPaneUI());
            scroll.setBorder(BorderFactory.createEmptyBorder()); scroll.setViewportBorder(null);
        }
        if (component instanceof JList<?> list) {
            if (list.getUI().getClass() != BasicListUI.class) list.setUI(new BasicListUI());
            list.setSelectionBackground(palette.selectionBackground()); list.setSelectionForeground(palette.selectionForeground());
        }
        if (component instanceof JTable table) {
            if (table.getUI().getClass() != BasicTableUI.class) table.setUI(new BasicTableUI());
            table.setSelectionBackground(palette.selectionBackground()); table.setSelectionForeground(palette.selectionForeground());
            table.setGridColor(palette.border()); table.setShowGrid(false); table.setIntercellSpacing(new Dimension(0, 0));
            table.setRowHeight(table.getFontMetrics(palette.font()).getHeight() + 4);
            if (table.getTableHeader() != null) table.getTableHeader().setDefaultRenderer(new HeaderRenderer());
        }
        if (component instanceof JTableHeader header && header.getUI().getClass() != BasicTableHeaderUI.class)
            header.setUI(new BasicTableHeaderUI());
        if (component instanceof JTree tree) {
            tree.setUI(new BasicTreeUI());
            tree.putClientProperty("JTree.lineStyle", "None");
            if (tree.getCellRenderer() instanceof DefaultTreeCellRenderer renderer) {
                renderer.setBackgroundNonSelectionColor(palette.background()); renderer.setTextNonSelectionColor(palette.foreground());
                renderer.setBackgroundSelectionColor(palette.selectionBackground()); renderer.setTextSelectionColor(palette.selectionForeground());
                renderer.setBorderSelectionColor(palette.border()); renderer.setOpenIcon(new GlyphIcon("[-]", null));
                renderer.setClosedIcon(new GlyphIcon("[+]", null)); renderer.setLeafIcon(new GlyphIcon(" · ", null)); renderer.setFont(palette.font());
            }
            tree.setRowHeight(tree.getFontMetrics(palette.font()).getHeight() + 3);
        }
        if (component instanceof JComponent swing && swing.getBorder() instanceof TitledBorder titled)
            swing.setBorder(new AsciiBorder(titled.getTitle(), palette, AsciiBorder.Line.SINGLE));
        else if (component instanceof JComponent swing && swing.getBorder() instanceof AsciiBorder ascii)
            swing.setBorder(ascii.withPalette(palette));
        if (component instanceof Container container)
            for (Component child : container.getComponents()) apply(child, palette);
    }

    private static void color(String key, Color value) { UIManager.put(key, new ColorUIResource(value)); }

    /** Restyle only the divider, preserving child state and the host's resize geometry. */
    public static void splitPane(JSplitPane split, AsciiPalette palette) {
        int location = split.getDividerLocation(), size = split.getDividerSize();
        double weight = split.getResizeWeight();
        split.setUI(new BasicSplitPaneUI() {
            @Override public BasicSplitPaneDivider createDefaultDivider() {
                return new BasicSplitPaneDivider(this) {
                    @Override public void paint(Graphics graphics) {
                        graphics.setColor(palette.background()); graphics.fillRect(0, 0, getWidth(), getHeight());
                    }
                };
            }
        });
        split.setBorder(null); split.setDividerSize(size); split.setResizeWeight(weight);
        split.setBackground(palette.background()); split.setContinuousLayout(true);
        if (location >= 0) split.setDividerLocation(location);
    }
    private static boolean selected(AbstractButton b) {
        return b.getModel().isPressed() && b.getModel().isArmed() || b instanceof JToggleButton && b.isSelected();
    }
    private static void buttonText(Graphics g, AbstractButton b, Rectangle text, String title, boolean highlight) {
        AsciiPalette p = palette(b); boolean selected = highlight && selected(b);
        g.setColor(!b.isEnabled() ? p.muted() : selected ? p.selectionForeground() : p.foreground());
        BasicGraphicsUtils.drawStringUnderlineCharAt(g, title, b.getDisplayedMnemonicIndex(), text.x, text.y + g.getFontMetrics().getAscent());
    }
    public static final class Button extends BasicButtonUI {
        public static ComponentUI createUI(JComponent c) { return new Button(); }
        @Override protected void paintText(Graphics g, AbstractButton b, Rectangle r, String title) { buttonText(g, b, r, title, true); }
        @Override protected void paintButtonPressed(Graphics g, AbstractButton b) { g.setColor(palette(b).selectionBackground()); g.fillRect(0, 0, b.getWidth(), b.getHeight()); }
    }
    public static final class Toggle extends BasicToggleButtonUI {
        public static ComponentUI createUI(JComponent c) { return new Toggle(); }
        @Override protected void paintText(Graphics g, AbstractButton b, Rectangle r, String title) { buttonText(g, b, r, title, true); }
        @Override protected void paintButtonPressed(Graphics g, AbstractButton b) { g.setColor(palette(b).selectionBackground()); g.fillRect(0, 0, b.getWidth(), b.getHeight()); }
    }
    public static final class CheckBox extends BasicCheckBoxUI {
        public static ComponentUI createUI(JComponent c) { return new CheckBox(); }
        @Override protected void paintText(Graphics g, AbstractButton b, Rectangle r, String title) { buttonText(g, b, r, title, false); }
        @Override protected void paintFocus(Graphics g, Rectangle r, Dimension d) { }
    }
    public static final class Radio extends BasicRadioButtonUI {
        public static ComponentUI createUI(JComponent c) { return new Radio(); }
        @Override protected void paintText(Graphics g, AbstractButton b, Rectangle r, String title) { buttonText(g, b, r, title, false); }
        @Override protected void paintFocus(Graphics g, Rectangle r, Dimension d) { }
    }
    public static final class Label extends BasicLabelUI {
        public static ComponentUI createUI(JComponent c) { return new Label(); }
        @Override protected void paintDisabledText(JLabel label, Graphics g, String text, int x, int y) {
            g.setColor(palette(label).muted()); BasicGraphicsUtils.drawStringUnderlineCharAt(g, text, label.getDisplayedMnemonicIndex(), x, y);
        }
    }
    public static final class Combo extends BasicComboBoxUI {
        public static ComponentUI createUI(JComponent c) { return new Combo(); }
        @Override protected JButton createArrowButton() { return glyphButton("v"); }
        @Override public void paintCurrentValueBackground(Graphics g, Rectangle bounds, boolean focus) {
            g.setColor(palette(comboBox).background()); g.fillRect(bounds.x, bounds.y, bounds.width, bounds.height);
        }
    }
    public static final class Spinner extends BasicSpinnerUI {
        public static ComponentUI createUI(JComponent c) { return new Spinner(); }
        @Override protected Component createNextButton() { JButton b = glyphButton("+"); installNextButtonListeners(b); return b; }
        @Override protected Component createPreviousButton() { JButton b = glyphButton("-"); installPreviousButtonListeners(b); return b; }
    }
    public static final class Tabs extends BasicTabbedPaneUI {
        public static ComponentUI createUI(JComponent c) { return new Tabs(); }
        @Override protected void paintTabBorder(Graphics g, int placement, int index, int x, int y, int w, int h, boolean selected) { }
        @Override protected void paintContentBorder(Graphics g, int placement, int selected) { }
        @Override protected void paintFocusIndicator(Graphics g, int placement, Rectangle[] rects, int index, Rectangle icon, Rectangle text, boolean selected) { }
        @Override protected void paintTabBackground(Graphics g, int placement, int index, int x, int y, int w, int h, boolean selected) {
            g.setColor(selected ? palette(tabPane).selectionBackground() : palette(tabPane).background()); g.fillRect(x, y, w, h);
        }
        @Override protected void paintText(Graphics g, int placement, Font font, FontMetrics fm, int index, String title, Rectangle text, boolean selected) {
            AsciiPalette p = palette(tabPane); g.setFont(font);
            g.setColor(!tabPane.isEnabled() || !tabPane.isEnabledAt(index) ? AsciiPalette.readable(p.muted(), selected ? p.selectionBackground() : p.background(), 3)
                    : selected ? p.selectionForeground() : p.foreground());
            g.drawString(title, text.x, text.y + fm.getAscent());
            if (selected) { g.drawString("[", text.x - fm.charWidth('['), text.y + fm.getAscent()); g.drawString("]", text.x + text.width, text.y + fm.getAscent()); }
        }
    }
    public static final class ScrollBar extends BasicScrollBarUI {
        public static ComponentUI createUI(JComponent c) { return new ScrollBar(); }
        @Override protected JButton createDecreaseButton(int orientation) { return glyphButton(orientation == NORTH ? "^" : "<"); }
        @Override protected JButton createIncreaseButton(int orientation) { return glyphButton(orientation == SOUTH ? "v" : ">"); }
        @Override protected void paintTrack(Graphics g, JComponent c, Rectangle r) {
            AsciiPalette p = palette(c); g.setColor(p.background()); g.fillRect(r.x, r.y, r.width, r.height);
            scrollGlyphs(g, r, p, scrollbar.getOrientation() == JScrollBar.VERTICAL ? "│" : "─", p.muted());
        }
        @Override protected void paintThumb(Graphics g, JComponent c, Rectangle r) {
            if (!c.isEnabled() || r.isEmpty()) return;
            scrollGlyphs(g, r, palette(c), "█", palette(c).border());
        }
        private void scrollGlyphs(Graphics graphics, Rectangle r, AsciiPalette p, String glyph, Color color) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.clip(r); g.setFont(p.font()); g.setColor(color); FontMetrics fm = g.getFontMetrics();
                if (scrollbar.getOrientation() == JScrollBar.VERTICAL)
                    for (int y = r.y; y < r.y + r.height; y += fm.getHeight())
                        g.drawString(glyph, r.x + (r.width - fm.charWidth('M')) / 2, y + fm.getAscent());
                else
                    for (int x = r.x; x < r.x + r.width; x += fm.charWidth('M'))
                        g.drawString(glyph, x, r.y + (r.height - fm.getHeight()) / 2 + fm.getAscent());
            } finally { g.dispose(); }
        }
    }
    private static JButton glyphButton(String glyph) {
        JButton button = new JButton(glyph); button.setUI(new Button()); button.setFocusable(false);
        button.putClientProperty("commandj.ascii.glyph", Boolean.TRUE);
        button.setBorder(BorderFactory.createEmptyBorder()); button.setMargin(new Insets(0, 2, 0, 2));
        button.setPreferredSize(new Dimension(defaults.font().getSize() + 6, defaults.font().getSize() + 6)); return button;
    }
    private static final class ButtonBorder extends AbstractBorder {
        @Override public Insets getBorderInsets(Component c, Insets i) { int cell = c.getFontMetrics(c.getFont()).charWidth('['); i.set(3, cell * 2, 3, cell * 2); return i; }
        @Override public void paintBorder(Component c, Graphics g, int x, int y, int w, int h) {
            AsciiPalette p = palette(c); boolean selected = c instanceof AbstractButton b && selected(b);
            g.setFont(c.getFont()); g.setColor(!c.isEnabled() ? p.muted() : selected ? p.selectionForeground() : p.foreground());
            FontMetrics fm = g.getFontMetrics(); int baseline = y + (h - fm.getHeight()) / 2 + fm.getAscent();
            String left = c.hasFocus() ? ">[" : " [", right = c.hasFocus() ? "]<" : "] ";
            g.drawString(left, x, baseline); g.drawString(right, x + w - fm.stringWidth(right), baseline);
        }
    }
    private static final class InputBorder extends AbstractBorder {
        @Override public Insets getBorderInsets(Component c, Insets i) { int cell = c.getFontMetrics(c.getFont()).charWidth('M'); i.set(4, cell + 3, 5, cell + 3); return i; }
        @Override public void paintBorder(Component c, Graphics g, int x, int y, int w, int h) {
            AsciiPalette p = palette(c); g.setColor(c.isEnabled() ? p.border() : p.muted());
            g.setFont(p.font()); FontMetrics fm = g.getFontMetrics(); int cw = fm.charWidth('M');
            int baseline = y + (h - fm.getHeight()) / 2 + fm.getAscent();
            g.drawString("[", x, baseline); g.drawString("]", x + w - cw, baseline);
            if (c.hasFocus()) {
                Shape clip = g.getClip(); g.clipRect(x + cw, y + h - 4, Math.max(0, w - 2 * cw), 4);
                g.drawString("_".repeat(Math.max(0, w / cw)), x + cw, y + h - fm.getDescent()); g.setClip(clip);
            }
        }
    }
    private static final class GlyphIcon implements Icon, UIResource {
        private final String normal, selected;
        GlyphIcon(String normal, String selected) { this.normal = normal; this.selected = selected; }
        public int getIconWidth() { return defaults.font().getSize() * normal.length() * 3 / 5 + 3; }
        public int getIconHeight() { return defaults.font().getSize() + 3; }
        public void paintIcon(Component c, Graphics g, int x, int y) {
            AsciiPalette p = palette(c); g.setFont(p.font()); g.setColor(c.isEnabled() ? p.foreground() : p.muted());
            String glyph = selected != null && c instanceof AbstractButton b && b.isSelected() ? selected : normal;
            if (c.hasFocus() && c instanceof AbstractButton) { g.setColor(p.selectionBackground()); g.fillRect(x, y, getIconWidth(), getIconHeight()); g.setColor(p.selectionForeground()); }
            g.drawString(glyph, x, y + g.getFontMetrics().getAscent());
        }
    }
    private static final class HeaderRenderer extends DefaultTableCellRenderer {
        @Override public Component getTableCellRendererComponent(JTable table, Object value, boolean selected, boolean focus, int row, int column) {
            super.getTableCellRendererComponent(table, value, selected, focus, row, column);
            AsciiPalette p = palette(table); setBackground(p.background()); setForeground(p.foreground()); setFont(p.font());
            setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, p.border())); return this;
        }
    }
}
