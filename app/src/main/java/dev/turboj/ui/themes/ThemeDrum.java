package dev.turboj.ui.themes;

import java.awt.*;

import javax.swing.*;

/** Small DOS text drum. Animation repaints only this surface; source editing state is untouched. */
final class ThemeDrum extends JPanel {
    private Theme theme;
    private ThemeWheelPlan plan;
    private int step;
    private double progress;

    ThemeDrum() {
        setName("theme-random-drum");
        getAccessibleContext().setAccessibleName("Random theme text wheel");
    }

    void setTheme(Theme theme) {
        this.theme = theme;
        repaint();
    }

    void show(ThemeWheelPlan plan, int step, double progress) {
        this.plan = plan;
        this.step = step;
        this.progress = Math.clamp(progress, 0, 1);
        repaint();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        if (theme == null) return;
        Graphics2D g = (Graphics2D) graphics.create();
        try {
            g.setColor(theme.color("ui", "background"));
            g.fillRect(0, 0, getWidth(), getHeight());
            int size = getWidth() < 780 ? 15 : 18;
            g.setFont(new Font(Font.MONOSPACED, Font.PLAIN, size));
            FontMetrics metrics = g.getFontMetrics();
            int cw = metrics.charWidth('M'), lh = metrics.getHeight() + 3;
            int columns = Math.max(20, Math.min(66, (getWidth() - 30) / cw));
            int left = (getWidth() - columns * cw) / 2,
                    top = Math.max(lh, (getHeight() - lh * 8) / 2);
            g.setColor(theme.color("ui", "foreground"));
            String border = "+" + "-".repeat(columns - 2) + "+";
            g.drawString(border, left, top);
            g.drawString(border, left, top + lh * 6);
            String title = "[ RANDOM THEME ]";
            g.setColor(theme.color("ui", "background"));
            g.fillRect(left + 2 * cw, top - metrics.getAscent(), title.length() * cw, lh - 8);
            g.setColor(theme.color("ui", "foreground"));
            g.drawString(title, left + 2 * cw, top);
            for (int row = 1; row <= 5; row++) {
                g.drawString("|", left, top + lh * row);
                g.drawString("|", left + (columns - 1) * cw, top + lh * row);
            }
            g.setColor(theme.color("ui", "accent"));
            g.fillRect(left + cw, top + lh * 3 - metrics.getAscent(), (columns - 2) * cw, lh - 4);
            g.setColor(theme.color("ui", "accentForeground"));
            g.drawString(">", left + cw, top + lh * 3);
            g.drawString("<", left + (columns - 2) * cw, top + lh * 3);
            if (plan == null) {
                g.setColor(theme.color("ui", "foreground"));
                g.drawString("Preparing real themes…", left + 3 * cw, top + lh * 2);
                return;
            }
            Shape clip = g.getClip();
            g.clipRect(left + 3 * cw, top + lh - metrics.getAscent(), (columns - 6) * cw, lh * 5);
            var current = plan.frames().get(step);
            for (int row = 0; row < 6; row++) {
                String name =
                        row < 5
                                ? current.names().get(row)
                                : plan.frames()
                                        .get(Math.min(step + 1, plan.frames().size() - 1))
                                        .names()
                                        .getLast();
                int baseline = top + lh * (row + 1) - (int) Math.round(progress * lh);
                boolean atCenter = Math.abs(baseline - (top + lh * 3)) < lh / 2;
                g.setColor(theme.color("ui", atCenter ? "accentForeground" : "foreground"));
                int max = columns - 6;
                if (name.length() > max) name = name.substring(0, max - 1) + "…";
                g.drawString(name, left + (columns - name.length()) * cw / 2, baseline);
            }
            g.setClip(clip);
            g.setColor(theme.color("ui", "muted"));
            String caption = "Every center crossing previews a real theme";
            g.drawString(
                    caption,
                    Math.max(12, (getWidth() - metrics.stringWidth(caption)) / 2),
                    top + lh * 7);
        } finally {
            g.dispose();
        }
    }
}
