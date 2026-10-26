package dev.turboj.ui;

import dev.turboj.commandj.swing.KeyGlyphs;

import dev.turboj.commandj.swing.AsciiPalette;
import dev.turboj.ui.themes.Theme;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import javax.swing.Timer;

/** A brief painted keyboard hint. It owns no focusable component and never captures input. */
final class PressedShortcutDisplay {
    private final Runnable repaint;
    private final Timer timeout;
    private boolean enabled;
    private String text = "";
    private Color background, foreground;

    PressedShortcutDisplay(Runnable repaint) { this(repaint, 1500); }

    PressedShortcutDisplay(Runnable repaint, int durationMillis) {
        this.repaint = repaint;
        timeout = new Timer(durationMillis, event -> clear());
        timeout.setRepeats(false);
        setTheme(Theme.bundled("classic"));
    }

    void setTheme(Theme theme) {
        // The website's editor demo uses syntax.keyword as the shortcut badge accent.
        background = theme.keyword();
        foreground = AsciiPalette.readable(theme.background(), background, 4.5);
        if (!text.isEmpty()) repaint.run();
    }

    void setEnabled(boolean value) {
        enabled = value;
        if (!value) clear();
    }

    boolean enabled() { return enabled; }
    String text() { return text; }
    Color background() { return background; }
    Color foreground() { return foreground; }

    void show(String shortcut) {
        if (!enabled || shortcut == null || shortcut.isBlank()) return;
        text = shortcut;
        timeout.restart();
        repaint.run();
    }

    void clear() {
        timeout.stop();
        if (text.isEmpty()) return;
        text = "";
        repaint.run();
    }

    Rectangle bounds(Graphics2D graphics, int width, int height) {
        Font font = AsciiChrome.font().deriveFont(Font.BOLD);
        var metrics = graphics.getFontMetrics(font);
        int badgeWidth = Math.min(Math.max(0, width - 32), KeyGlyphs.width(graphics, font, text) + 18);
        int badgeHeight = metrics.getHeight() + 6;
        return new Rectangle(Math.max(0, width - 16 - badgeWidth), Math.max(0, height - 12 - badgeHeight),
                badgeWidth, Math.min(height, badgeHeight));
    }

    void paint(Graphics2D graphics, int width, int height) {
        if (text.isEmpty() || width < 64 || height < 36) return;
        Rectangle badge = bounds(graphics, width, height);
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setColor(background);
            g.fillRect(badge.x, badge.y, badge.width, badge.height);
            g.clipRect(badge.x + 9, badge.y, Math.max(0, badge.width - 18), badge.height);
            g.setFont(AsciiChrome.font().deriveFont(Font.BOLD));
            g.setColor(foreground);
            var metrics = g.getFontMetrics();
            Font font = g.getFont();
            String label = text;
            if (KeyGlyphs.width(g, font, label) > badge.width - 18) {
                int end = text.length();
                do { end = text.offsetByCodePoints(end, -1); }
                while (end > 0 && KeyGlyphs.width(g, font, text.substring(0, end) + "…") > badge.width - 18);
                label = text.substring(0, end) + "…";
            }
            KeyGlyphs.draw(g, label, badge.x + 9, badge.y + 3 + metrics.getAscent());
        } finally { g.dispose(); }
    }
}
