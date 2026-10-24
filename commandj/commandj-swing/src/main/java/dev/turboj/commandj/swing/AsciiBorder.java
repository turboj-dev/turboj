package dev.turboj.commandj.swing;

import java.awt.*;
import javax.swing.border.AbstractBorder;

/** Flat terminal-style rules with a title embedded in the top rule, without window chrome. */
public final class AsciiBorder extends AbstractBorder {
    public enum Line { SINGLE, DOUBLE }
    private final String title;
    private final AsciiPalette palette;
    private final Line line;
    private final int rightReserve;

    public AsciiBorder(String title, AsciiPalette palette) { this(title, palette, Line.DOUBLE); }
    public AsciiBorder(String title, AsciiPalette palette, Line line) { this(title, palette, line, 0); }
    AsciiBorder(String title, AsciiPalette palette, Line line, int rightReserve) {
        this.title = title == null ? "" : title;
        this.palette = java.util.Objects.requireNonNull(palette);
        this.line = java.util.Objects.requireNonNull(line);
        this.rightReserve = Math.max(0, rightReserve);
    }
    public String title() { return title; }
    public AsciiPalette palette() { return palette; }
    AsciiBorder withPalette(AsciiPalette replacement) {
        return new AsciiBorder(title, replacement, line, rightReserve);
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
            if (!title.isEmpty()) {
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
