package dev.turboj.commandj.swing;

import dev.turboj.commandj.keymap.KeyStrokeSpec;
import java.awt.*;

/**
 * macOS key symbols (⌘ ⌥ ⇧ ⌃ ↩ ⌫ arrows) drawn with the platform UI font. Monospaced fonts lack them, so Java falls
 * back glyph by glyph and ⇧ shrinks while ⌃ rises above the line; the UI font draws them as the system menus do.
 */
public final class KeyGlyphs {
    private static final String SYMBOLS = "⌘⌥⇧⌃↩⌫⌦⇥←→↑↓↖↘⇞⇟";

    private KeyGlyphs() {}

    public static boolean isSymbol(char c) { return SYMBOLS.indexOf(c) >= 0; }

    /** The font for a run of key symbols next to text set in {@code base}. */
    public static Font symbolFont(Font base) {
        return new Font(Font.DIALOG, base.getStyle(), base.getSize()).deriveFont(base.getSize2D());
    }

    /** The font for a field or cell that shows only shortcuts: the UI font while shortcuts are written as symbols. */
    public static Font shortcutFont(Font base) {
        return base == null || !KeyStrokeSpec.usesSymbols() ? base : symbolFont(base);
    }

    /** Draws text in the current font, with its key symbols in the UI font; returns the x after the text. */
    public static int draw(Graphics2D g, String text, int x, int baseline) {
        Font base = g.getFont(), symbols = symbolFont(base);
        for (String run : runs(text)) {
            g.setFont(isSymbol(run.charAt(0)) ? symbols : base);
            g.drawString(run, x, baseline);
            x += g.getFontMetrics().stringWidth(run);
        }
        g.setFont(base);
        return x;
    }

    /** The width {@link #draw} takes for this text in {@code base}. */
    public static int width(Component c, Font base, String text) {
        return width(c.getFontMetrics(base), c.getFontMetrics(symbolFont(base)), text);
    }

    public static int width(Graphics g, Font base, String text) {
        return width(g.getFontMetrics(base), g.getFontMetrics(symbolFont(base)), text);
    }

    private static int width(FontMetrics plain, FontMetrics symbols, String text) {
        int width = 0;
        for (String run : runs(text)) width += (isSymbol(run.charAt(0)) ? symbols : plain).stringWidth(run);
        return width;
    }

    /** A table cell renderer that sets cells showing key symbols in the UI font. */
    public static javax.swing.table.TableCellRenderer tableRenderer() {
        return new javax.swing.table.DefaultTableCellRenderer() {
            @Override public Component getTableCellRendererComponent(javax.swing.JTable table, Object value, boolean selected,
                    boolean focus, int row, int column) {
                var cell = super.getTableCellRendererComponent(table, value, selected, focus, row, column);
                boolean symbols = value instanceof String text && text.chars().anyMatch(c -> isSymbol((char) c));
                cell.setFont(symbols ? symbolFont(table.getFont()) : table.getFont());
                return cell;
            }
        };
    }

    /** Alternating runs of key symbols and other text. */
    static java.util.List<String> runs(String text) {
        var runs = new java.util.ArrayList<String>();
        int start = 0;
        for (int i = 1; i <= text.length(); i++)
            if (i == text.length() || isSymbol(text.charAt(i)) != isSymbol(text.charAt(start))) {
                runs.add(text.substring(start, i));
                start = i;
            }
        return runs;
    }
}
