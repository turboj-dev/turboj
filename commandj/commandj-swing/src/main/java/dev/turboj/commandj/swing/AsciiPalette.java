package dev.turboj.commandj.swing;

import java.awt.Color;
import java.awt.Font;
import java.util.Objects;

/** Immutable editor-surface colors, independent of any application's theme implementation. */
public record AsciiPalette(Color background, Color foreground, Color selectionBackground,
        Color selectionForeground, Color muted, Color border, Font font) {
    public AsciiPalette {
        Objects.requireNonNull(background); Objects.requireNonNull(selectionBackground);
        foreground = readable(Objects.requireNonNull(foreground), background, 4.5);
        selectionForeground = readable(Objects.requireNonNull(selectionForeground), selectionBackground, 4.5);
        muted = readable(Objects.requireNonNull(muted), background, 3);
        border = readable(Objects.requireNonNull(border), background, 3);
        Objects.requireNonNull(font);
        font = new Font(Font.MONOSPACED, font.getStyle(), font.getSize());
    }

    public static AsciiPalette defaults() {
        return new AsciiPalette(new Color(0x171b24), new Color(0xd8dee9), new Color(0x35445e),
                Color.WHITE, new Color(0x96a2b5), new Color(0xb3c0d3), new Font(Font.MONOSPACED, Font.PLAIN, 14));
    }

    /** Adjust only as much as needed; retain the supplied theme color when already readable. */
    public static Color readable(Color candidate, Color background, double minimum) {
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

    private static double luminance(Color color) {
        return .2126 * linear(color.getRed()) + .7152 * linear(color.getGreen()) + .0722 * linear(color.getBlue());
    }
    private static double linear(int channel) {
        double value = channel / 255.0;
        return value <= .04045 ? value / 12.92 : Math.pow((value + .055) / 1.055, 2.4);
    }
}
