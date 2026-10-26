package dev.turboj.commandj.swing;

import java.awt.*;
import javax.swing.border.AbstractBorder;

/**
 * Flat terminal-style rules with a title embedded in the top rule, without window chrome. A border with tabs shows
 * them centred in the top rule where a title would be, {@code ┌──── Output │ [Tests] │ JShell ──── hint ─┐}, so a
 * tabbed panel needs no second header; {@link #tabAt} finds the tab under the mouse.
 */
public final class AsciiBorder extends AbstractBorder {
    public enum Line { SINGLE, DOUBLE }
    private final String title;
    private final AsciiPalette palette;
    private final Line line;
    private final int rightReserve;
    private final java.util.List<String> tabs;
    private final int selected;

    public AsciiBorder(String title, AsciiPalette palette) { this(title, palette, Line.DOUBLE); }
    public AsciiBorder(String title, AsciiPalette palette, Line line) { this(title, palette, line, 0); }
    AsciiBorder(String title, AsciiPalette palette, Line line, int rightReserve) {
        this(java.util.List.of(), -1, title, palette, line, rightReserve);
    }
    /** Tabs at the left of the top rule, {@code selected} marked; {@code title} goes to the right. */
    public AsciiBorder(java.util.List<String> tabs, int selected, String title, AsciiPalette palette, Line line) {
        this(tabs, selected, title, palette, line, 0);
    }
    private AsciiBorder(java.util.List<String> tabs, int selected, String title, AsciiPalette palette, Line line, int rightReserve) {
        this.title = title == null ? "" : title;
        this.palette = java.util.Objects.requireNonNull(palette);
        this.line = java.util.Objects.requireNonNull(line);
        this.rightReserve = Math.max(0, rightReserve);
        this.tabs = java.util.List.copyOf(tabs);
        this.selected = selected;
    }
    public java.util.List<String> tabs() { return tabs; }
    public int selected() { return selected; }

    /** The tab whose caption is at {@code point} (component coordinates), or -1. */
    public int tabAt(Component component, Point point) {
        FontMetrics fm = component.getFontMetrics(palette.font());
        if (point.y < 0 || point.y >= fm.getHeight()) return -1;
        int[] edges = tabEdges(fm, 0, component.getWidth());
        for (int i = 0; i < tabs.size(); i++) if (point.x >= edges[2 * i] && point.x < edges[2 * i + 1]) return i;
        return -1;
    }

    /** Start and end x of each tab caption, separators excluded: the group is centred on the cell grid, as a title. */
    private int[] tabEdges(FontMetrics fm, int x, int width) {
        int cw = Math.max(1, fm.charWidth('M'));
        int[] edges = new int[tabs.size() * 2];
        int total = 0;
        for (int i = 0; i < tabs.size(); i++) total += (i > 0 ? fm.stringWidth(SEPARATOR) : 0) + fm.stringWidth(caption(i));
        int at = x + Math.max(2 * cw + fm.stringWidth(" "), ((width - total) / 2 / cw) * cw);
        for (int i = 0; i < tabs.size(); i++) {
            if (i > 0) at += fm.stringWidth(SEPARATOR);
            edges[2 * i] = at;
            at += fm.stringWidth(caption(i));
            edges[2 * i + 1] = at;
        }
        return edges;
    }
    private static final String SEPARATOR = " │ ";
    private String caption(int index) { return index == selected ? "[" + tabs.get(index) + "]" : tabs.get(index); }
    public String title() { return title; }
    public AsciiPalette palette() { return palette; }
    AsciiBorder withPalette(AsciiPalette replacement) {
        return new AsciiBorder(tabs, selected, title, replacement, line, rightReserve);
    }

    /** One text-cell rule, useful between output and its input row. */
    public static javax.swing.border.Border rule(AsciiPalette palette) {
        return new AbstractBorder() {
            @Override public Insets getBorderInsets(Component c, Insets insets) {
                insets.set(c.getFontMetrics(palette.font()).getHeight(), 0, 0, 0); return insets;
            }
            @Override public void paintBorder(Component c, Graphics graphics, int x, int y, int width, int height) {
                Graphics2D g = (Graphics2D) graphics.create();
                try {
                    g.setFont(palette.font()); g.setColor(palette.border());
                    FontMetrics fm = g.getFontMetrics();
                    horizontal(g, "─", x, y + fm.getAscent(), width, fm.charWidth('M'));
                } finally { g.dispose(); }
            }
        };
    }

    @Override public Insets getBorderInsets(Component component, Insets insets) {
        FontMetrics fm = component.getFontMetrics(palette.font());
        insets.set(fm.getHeight(), fm.charWidth('M') + 3, fm.getHeight(), fm.charWidth('M') + 3);
        return insets;
    }
    @Override public boolean isBorderOpaque() { return false; }
    @Override public void paintBorder(Component component, Graphics graphics, int x, int y, int width, int height) {
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setFont(palette.font()); g.setColor(palette.border());
            FontMetrics fm = g.getFontMetrics();
            int cw = Math.max(1, fm.charWidth('M')), ch = fm.getHeight();
            if (width < cw * 2 || height < ch * 2) return;
            int right = x + width - cw, bottom = y + height - ch;
            String tl = line == Line.DOUBLE ? "╔" : "┌", tr = line == Line.DOUBLE ? "╗" : "┐";
            String bl = line == Line.DOUBLE ? "╚" : "└", br = line == Line.DOUBLE ? "╝" : "┘";
            String h = line == Line.DOUBLE ? "═" : "─", v = line == Line.DOUBLE ? "║" : "│";
            horizontal(g, h, x + cw, y + fm.getAscent(), width - 2 * cw, cw);
            horizontal(g, h, x + cw, bottom + fm.getAscent(), width - 2 * cw, cw);
            Shape clip = g.getClip();
            g.clipRect(x, y + ch, width, Math.max(0, height - 2 * ch));
            for (int row = y + ch; row < bottom; row += ch) {
                g.drawString(v, x, row + fm.getAscent()); g.drawString(v, right, row + fm.getAscent());
            }
            g.setClip(clip);
            g.drawString(tl, x, y + fm.getAscent()); g.drawString(tr, right, y + fm.getAscent());
            g.drawString(bl, x, bottom + fm.getAscent()); g.drawString(br, right, bottom + fm.getAscent());
            int tabsEnd = x;
            if (!tabs.isEmpty()) {
                int[] edges = tabEdges(fm, x, width);
                for (int i = 0; i < tabs.size(); i++) {
                    if (edges[2 * i + 1] > right - cw) break;
                    if (i > 0) {
                        g.setColor(palette.background()); g.fillRect(edges[2 * i - 1], y, edges[2 * i] - edges[2 * i - 1], ch);
                        g.setColor(palette.border()); g.drawString(SEPARATOR, edges[2 * i - 1], y + fm.getAscent());
                    }
                    boolean current = i == selected;
                    g.setColor(current ? palette.selectionBackground() : palette.background());
                    g.fillRect(edges[2 * i], y, edges[2 * i + 1] - edges[2 * i], ch);
                    g.setColor(current ? palette.selectionForeground() : palette.foreground());
                    g.drawString(caption(i), edges[2 * i], y + fm.getAscent());
                    tabsEnd = edges[2 * i + 1];
                }
                // A space of rule on either side of the tabs, as around a title.
                int space = fm.stringWidth(" ");
                g.setColor(palette.background());
                g.fillRect(edges[0] - space, y, space, ch);
                if (tabsEnd > x) g.fillRect(tabsEnd, y, space, ch);
                // With tabs, the title is a hint at the right of the rule.
                if (!title.isEmpty()) {
                    String caption = " " + title + " ";
                    int titleX = right - cw - fm.stringWidth(caption);
                    if (titleX > tabsEnd + 2 * cw) {
                        g.setColor(palette.background()); g.fillRect(titleX, y, fm.stringWidth(caption), ch);
                        g.setColor(palette.muted()); g.drawString(caption, titleX, y + fm.getAscent());
                    }
                }
            }
            if (tabs.isEmpty() && !title.isEmpty()) {
                int available = Math.max(0, width - 4 * cw - rightReserve);
                String caption = " " + title + " ";
                while (caption.length() > 3 && fm.stringWidth(caption) > available)
                    caption = caption.substring(0, caption.length() - 3) + "… ";
                if (fm.stringWidth(caption) <= available) {
                    int titleX = x + Math.max(2 * cw, ((width - rightReserve - fm.stringWidth(caption)) / 2 / cw) * cw);
                    g.setColor(palette.background()); g.fillRect(titleX, y, fm.stringWidth(caption), ch);
                    g.setColor(palette.foreground()); g.drawString(caption, titleX, y + fm.getAscent());
                }
            }
        } finally { g.dispose(); }
    }
    private static void horizontal(Graphics2D g, String glyph, int x, int baseline, int width, int cellWidth) {
        Shape clip = g.getClip();
        g.clipRect(x, baseline - g.getFontMetrics().getAscent(), Math.max(0, width), g.getFontMetrics().getHeight());
        g.drawString(glyph.repeat(Math.max(0, (width + cellWidth - 1) / cellWidth)), x, baseline);
        g.setClip(clip);
    }
}
