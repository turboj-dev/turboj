package dev.turboj.ui.intelligence;

import dev.turboj.commandj.swing.AsciiPalette;
import dev.turboj.ui.themes.Theme;
import java.awt.Color;
import java.util.LinkedHashMap;
import java.util.Map;

/** Theme-relative surfaces and cached readable syntax inks, resolved only on theme changes. */
public record CoverageRowPalette(Color covered, Color uncovered,
        Map<Color,Color> coveredInks, Map<Color,Color> uncoveredInks) {
    public CoverageRowPalette {
        coveredInks = Map.copyOf(coveredInks); uncoveredInks = Map.copyOf(uncoveredInks);
    }

    public static CoverageRowPalette resolve(Theme theme) {
        Color covered = theme.colors().getOrDefault("coverage.coveredBackground",
                surface(theme.background(),theme.color("coverage","covered")));
        Color uncovered = theme.colors().getOrDefault("coverage.uncoveredBackground",
                surface(theme.background(),theme.color("coverage","uncovered")));
        return new CoverageRowPalette(covered,uncovered,inks(theme,covered),inks(theme,uncovered));
    }

    public Color ink(Color original,boolean uncoveredLine) {
        return (uncoveredLine ? uncoveredInks : coveredInks).getOrDefault(original,original);
    }

    private static Map<Color,Color> inks(Theme theme,Color surface) {
        Map<Color,Color> result = new LinkedHashMap<>();
        theme.colors().values().forEach(color -> result.put(color,AsciiPalette.readable(color,surface,
                Math.min(4.5,AsciiPalette.contrast(color,theme.background())))));
        return result;
    }

    private static Color surface(Color background,Color semantic) {
        float[] base = Color.RGBtoHSB(background.getRed(),background.getGreen(),background.getBlue(),null);
        float hue = Color.RGBtoHSB(semantic.getRed(),semantic.getGreen(),semantic.getBlue(),null)[0];
        double luminance = (AsciiPalette.contrast(Color.BLACK,background) - 1) * .05;
        boolean dark = AsciiPalette.contrast(Color.WHITE,background) > AsciiPalette.contrast(Color.BLACK,background);
        double target = dark ? luminance + .015 : luminance - .035;
        float saturation = dark ? .32f + base[1] * .15f : .08f + base[1] * .06f;
        float low = 0, high = 1;
        for (int i = 0; i < 16; i++) {
            float value = (low + high) / 2;
            Color candidate = Color.getHSBColor(hue,saturation,value);
            double actual = (AsciiPalette.contrast(Color.BLACK,candidate) - 1) * .05;
            if (actual < target) low = value; else high = value;
        }
        return Color.getHSBColor(hue,saturation,(low + high) / 2);
    }
}
