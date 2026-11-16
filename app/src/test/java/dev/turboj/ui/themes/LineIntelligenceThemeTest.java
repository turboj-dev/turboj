package dev.turboj.ui.themes;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.*;
import java.awt.Color;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

class LineIntelligenceThemeTest {
    @Test void rowTintsRespectSyntaxContrastAcrossAll103Themes() {
        for (String id : Theme.bundledResources().keySet()) {
            Theme theme = Theme.bundled(id);
            var palette = dev.turboj.ui.intelligence.CoverageRowPalette.resolve(theme);
            assertNotEquals(theme.background(),palette.covered(),id);
            assertNotEquals(theme.background(),palette.uncovered(),id);
            assertNotEquals(palette.covered(),palette.uncovered(),id);
            assertTrue(contrast(palette.covered(),theme.background()) < 1.5,id + " subtle covered surface");
            assertTrue(contrast(palette.uncovered(),theme.background()) < 1.5,id + " subtle uncovered surface");
            float coveredHue = Color.RGBtoHSB(palette.covered().getRed(),palette.covered().getGreen(),palette.covered().getBlue(),null)[0] * 360;
            float uncoveredHue = Color.RGBtoHSB(palette.uncovered().getRed(),palette.uncovered().getGreen(),palette.uncovered().getBlue(),null)[0] * 360;
            assertTrue(coveredHue >= 160 && coveredHue <= 230,id + " cyan/blue background");
            assertTrue(uncoveredHue >= 38 && uncoveredHue <= 66,id + " yellow/amber background");
            for (var entry : theme.colors().entrySet()) {
                if (!entry.getKey().startsWith("syntax.") && !entry.getKey().equals("editor.foreground")) continue;
                double original = contrast(entry.getValue(),theme.background());
                double minimum = Math.min(4.5,original);
                assertTrue(contrast(palette.ink(entry.getValue(),false),palette.covered()) >= minimum,id + " covered " + entry.getKey());
                assertTrue(contrast(palette.ink(entry.getValue(),true),palette.uncovered()) >= minimum,id + " uncovered " + entry.getKey());
            }
        }
    }

    @Test void allNativeThemesResolveDistinctReadableSemanticFamilies() {
        assertEquals(103, Theme.bundledResources().size());
        for (String id : Theme.bundledResources().keySet()) {
            Theme theme = Theme.bundled(id);
            assertEquals(id, theme.id());
            for (String role : List.of("test.success", "test.failure", "test.pending", "test.unknown", "coverage.covered", "coverage.uncovered",
                    "coverage.partial", "git.added", "git.deleted", "status.unknown", "status.stale", "status.pending")) {
                String[] path = role.split("\\.");
                Color color = theme.color(path[0], path[1]);
                assertTrue(contrast(color, theme.background()) >= 4.5, id + " " + role);
                float hue = Color.RGBtoHSB(color.getRed(), color.getGreen(), color.getBlue(), null)[0] * 360;
                if (role.equals("test.success") || role.equals("git.added")) assertTrue(hue >= 85 && hue <= 155, id + " green " + hue);
                if (role.equals("test.failure") || role.equals("git.deleted")) assertTrue(hue <= 20 || hue >= 340, id + " red " + hue);
                if (role.equals("coverage.covered")) assertTrue(hue >= 160 && hue <= 230, id + " cyan/blue " + hue);
                if (role.equals("coverage.uncovered")) assertTrue(hue >= 38 && hue <= 66, id + " yellow " + hue);
            }
        }
    }

    @Test void optionalOverridesRetainReadableColorsAndRepairLowContrastWithoutChangingOtherTokens() throws Exception {
        String json;
        try (var input = Theme.class.getResourceAsStream("/themes/dark.json")) {
            json = new String(input.readAllBytes(), StandardCharsets.UTF_8);
        }
        JsonObject definition = JsonParser.parseString(json).getAsJsonObject();
        JsonObject tests = new JsonObject(); tests.addProperty("success", "#26A269"); tests.addProperty("failure", "#171A21");
        definition.add("test", tests);
        JsonObject coverage = new JsonObject(); coverage.addProperty("uncovered", "#171A21"); definition.add("coverage", coverage);
        Theme custom = Theme.parse(definition.toString());
        assertEquals(Color.decode("#26A269"), custom.color("test", "success"));
        assertTrue(contrast(custom.color("test", "failure"), custom.background()) >= 4.5);
        assertTrue(contrast(custom.color("coverage", "uncovered"), custom.background()) >= 4.5);
        assertEquals(Theme.parse(json).background(), custom.background());
        assertEquals(Theme.parse(json).keyword(), custom.keyword());
        coverage.addProperty("coveredBackground","#123448");
        coverage.addProperty("uncoveredBackground","#383010");
        var customRows = dev.turboj.ui.intelligence.CoverageRowPalette.resolve(Theme.parse(definition.toString()));
        assertEquals(Color.decode("#123448"),customRows.covered());
        assertEquals(Color.decode("#383010"),customRows.uncovered());
        coverage.addProperty("coveredBackground","invalid");
        assertThrows(IllegalArgumentException.class,() -> Theme.parse(definition.toString()));
        coverage.remove("coveredBackground");
        tests.addProperty("success", "#FF00FF");
        Color repaired = Theme.parse(definition.toString()).color("test", "success");
        float hue = Color.RGBtoHSB(repaired.getRed(), repaired.getGreen(), repaired.getBlue(), null)[0] * 360;
        assertTrue(hue >= 85 && hue <= 155, "A success override cannot silently mean magenta");
        coverage.addProperty("partial", "yellow");
        assertThrows(IllegalArgumentException.class, () -> Theme.parse(definition.toString()));
    }

    private static double contrast(Color a, Color b) {
        double first = luminance(a), second = luminance(b);
        return (Math.max(first, second) + .05) / (Math.min(first, second) + .05);
    }
    private static double luminance(Color color) {
        double[] rgb = {color.getRed() / 255d, color.getGreen() / 255d, color.getBlue() / 255d};
        for (int i = 0; i < 3; i++) rgb[i] = rgb[i] <= .04045 ? rgb[i] / 12.92 : Math.pow((rgb[i] + .055) / 1.055, 2.4);
        return rgb[0] * .2126 + rgb[1] * .7152 + rgb[2] * .0722;
    }
}
