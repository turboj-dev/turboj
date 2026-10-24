package dev.turboj.ui.themes;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import dev.turboj.config.Settings;
import dev.turboj.ui.ConsolePane;
import dev.turboj.ui.EditorComponent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Color;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.*;
import javax.swing.text.*;

class ThemeContractTest {
    @TempDir Path directory;

    @Test
    void bundledPalettesHaveReadableTextAndVisibleCursors() {
        for (String id : Theme.BUNDLED) {
            Theme t = Theme.bundled(id);
            assertEquals(id, t.id());
            for (String foreground :
                    List.of("foreground", "keyword", "string", "comment", "ghost")) {
                Color color =
                        switch (foreground) {
                            case "foreground" -> t.foreground();
                            case "ghost" -> t.color("intelligence", "ghost");
                            default -> t.color("syntax", foreground);
                        };
                assertContrast(t.name() + " editor " + foreground, color, t.background(), 4.5);
            }
            assertContrast(
                    t.name() + " selected source",
                    t.color("editor", "selectionForeground"),
                    t.selection(),
                    4.5);
            assertContrast(
                    t.name() + " console", t.messagesForeground(), t.messagesBackground(), 4.5);
            assertContrast(
                    t.name() + " stderr",
                    t.color("messages", "errorForeground"),
                    t.messagesBackground(),
                    4.5);
            assertContrast(t.name() + " menu", t.menuForeground(), t.menuBackground(), 4.5);
            assertContrast(
                    t.name() + " active menu",
                    t.color("menu", "activeForeground"),
                    t.color("menu", "activeBackground"),
                    4.5);
            assertContrast(t.name() + " cursor", t.cursor(), t.background(), 3);
            assertContrast(
                    t.name() + " stdin cursor",
                    t.color("messages", "cursor"),
                    t.messagesBackground(),
                    3);
            for (String token : List.of("foreground", "muted", "heading", "link"))
                assertContrast(
                        t.name() + " page " + token,
                        t.color("ui", token),
                        t.color("ui", "background"),
                        4.5);
            assertContrast(
                    t.name() + " action",
                    t.color("ui", "accentForeground"),
                    t.color("ui", "accent"),
                    4.5);
            assertThrows(
                    UnsupportedOperationException.class,
                    () -> t.colors().put("ui.background", Color.PINK));
        }
    }

    @Test
    void incompleteOrInvalidSchemaTwoIsRejectedAndLegacyUserFilesStillLoad() throws Exception {
        String json =
                new String(
                        Theme.class.getResourceAsStream("/themes/dark.json").readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8);
        JsonObject object = JsonParser.parseString(json).getAsJsonObject();
        object.getAsJsonObject("intelligence").remove("ghost");
        assertThrows(IllegalArgumentException.class, () -> Theme.parse(object.toString()));
        object.getAsJsonObject("intelligence").addProperty("ghost", "red");
        assertThrows(IllegalArgumentException.class, () -> Theme.parse(object.toString()));
        JsonObject legacy = JsonParser.parseString(json).getAsJsonObject();
        legacy.addProperty("schemaVersion", 1);
        for (String section :
                List.of("id", "chrome", "ui", "documentation", "intelligence", "diagnostics"))
            legacy.remove(section);
        Theme custom = Theme.parse(legacy.toString());
        assertEquals("custom", custom.id());
        assertEquals(Theme.bundled("dark").background(), custom.background());
        assertEquals(Theme.bundled("dark").keyword(), custom.keyword());
    }

    @Test
    void savedLegacyThemePreferencesMigrateWithoutLosingOtherSettings() throws Exception {
        String previous = System.getProperty("turboj.config");
        try {
            System.setProperty("turboj.config", directory.toString());
            for (String legacy :
                    List.of("modern", "amber", "missing", "classic", "dark", "light")) {
                Files.writeString(
                        directory.resolve("config.json"),
                        "{\"theme\":\""
                                + legacy
                                + "\",\"fontSize\":22,\"recentFiles\":[\"Hello.java\"]}");
                Settings settings = Settings.load();
                assertEquals(Theme.normalizeId(legacy), settings.theme);
                assertEquals(22, settings.fontSize);
                assertEquals(List.of("Hello.java"), settings.recentFiles);
                settings.save();
                assertEquals(settings.theme, Settings.load().theme);
            }
        } finally {
            if (previous == null) System.clearProperty("turboj.config");
            else System.setProperty("turboj.config", previous);
        }
    }

    @Test
    void themeSwitchDoesNotChangeSourceViewportGhostOrUndo() throws Exception {
        SwingUtilities.invokeAndWait(
                () -> {
                    EditorComponent editor = new EditorComponent();
                    editor.setText(
                            "class Main {\n    void demo() {\n        System.out.pr\n    }\n}\n");
                    int offset = editor.getText().indexOf(".pr") + 3;
                    editor.gotoOffset(offset);
                    var location = editor.captureLocation();
                    String source = editor.getText();
                    long version = editor.version();
                    var proposal =
                            new EditorComponent.InlineCompletion(
                                    version, offset, offset - 2, offset, "println()", 9);
                    assertTrue(editor.setInlineCompletion(proposal));
                    AtomicInteger changes = new AtomicInteger();
                    editor.setOnChange(changes::incrementAndGet);
                    for (String id : Theme.BUNDLED) {
                        editor.setTheme(Theme.bundled(id));
                        assertEquals(source, editor.getText());
                        assertEquals(version, editor.version());
                        assertEquals(location, editor.captureLocation());
                        assertEquals(proposal, editor.inlineCompletion());
                    }
                    assertEquals(0, changes.get());
                    assertTrue(editor.acceptInlineCompletion());
                    editor.undo();
                    assertEquals(source, editor.getText());
                });
    }

    @Test
    void queuedPreferenceSnapshotDoesNotReadLaterMutableSettings() throws Exception {
        String previous = System.getProperty("turboj.config");
        try {
            System.setProperty("turboj.config", directory.toString());
            Settings settings = new Settings();
            settings.theme = "dark";
            settings.recentFiles.add("Before.java");
            String snapshot = settings.snapshotJson();
            settings.theme = "light";
            settings.recentFiles.add("After.java");
            Settings.saveSnapshot(snapshot);
            Settings loaded = Settings.load();
            assertEquals("dark", loaded.theme);
            assertEquals(List.of("Before.java"), loaded.recentFiles);
        } finally {
            if (previous == null) System.clearProperty("turboj.config");
            else System.setProperty("turboj.config", previous);
        }
    }

    @Test
    void themeSwitchPreservesUnsentStdinTranscriptAndStderrDistinction() throws Exception {
        ConsolePane[] value = new ConsolePane[1];
        SwingUtilities.invokeAndWait(
                () -> {
                    ConsolePane pane =
                            new ConsolePane(s -> fail("Theme switching must never send stdin"));
                    value[0] = pane;
                    pane.setTheme(Theme.bundled("classic"));
                    pane.setInlineInput(true);
                    pane.setProgramRunning(true);
                    pane.append("error\n", true);
                    pane.append("Number: ", false);
                });
        SwingUtilities.invokeAndWait(
                () -> {
                    ConsolePane pane = value[0];
                    pane.setInputText("20");
                    String text = pane.text();
                    JTextPane output = (JTextPane) pane.inputComponent();
                    output.select(
                            output.getDocument().getLength() - 2, output.getDocument().getLength());
                    int dot = output.getCaret().getDot(), mark = output.getCaret().getMark();
                    for (String id : Theme.BUNDLED) {
                        Theme t = Theme.bundled(id);
                        pane.setTheme(t);
                        assertEquals(text, pane.text());
                        assertEquals("20", pane.inputText());
                        assertEquals(dot, output.getCaret().getDot());
                        assertEquals(mark, output.getCaret().getMark());
                        assertEquals(
                                t.color("messages", "errorForeground"),
                                StyleConstants.getForeground(
                                        output.getStyledDocument()
                                                .getCharacterElement(0)
                                                .getAttributes()));
                        assertEquals(
                                t.messagesForeground(),
                                StyleConstants.getForeground(
                                        output.getStyledDocument()
                                                .getCharacterElement(7)
                                                .getAttributes()));
                    }
                });
    }

    private static void assertContrast(String name, Color fg, Color bg, double minimum) {
        double a = luminance(fg),
                b = luminance(bg),
                ratio = (Math.max(a, b) + .05) / (Math.min(a, b) + .05);
        assertTrue(ratio >= minimum, name + " contrast " + ratio + " < " + minimum);
    }

    private static double luminance(Color c) {
        double[] values = {c.getRed() / 255.0, c.getGreen() / 255.0, c.getBlue() / 255.0};
        for (int i = 0; i < 3; i++)
            values[i] =
                    values[i] <= .04045
                            ? values[i] / 12.92
                            : Math.pow((values[i] + .055) / 1.055, 2.4);
        return values[0] * .2126 + values[1] * .7152 + values[2] * .0722;
    }
}
