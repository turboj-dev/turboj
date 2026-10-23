package com.turboj.ui.themes;

import com.google.gson.*;

import java.awt.Color;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;

public record Theme(
        String name,
        Color background,
        Color foreground,
        Color keyword,
        Color string,
        Color comment,
        Color cursor,
        Color selection,
        Color error,
        Color menuBackground,
        Color menuForeground,
        Color messagesBackground,
        Color messagesForeground,
        int fontSize,
        boolean antialias) {
    public static final String[] BUNDLED = {"classic", "amber", "modern"};

    public static Theme bundled(String id) {
        try (var in = Theme.class.getResourceAsStream("/themes/" + id + ".json")) {
            if (in == null) throw new IOException("Unknown theme: " + id);
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException | RuntimeException e) {
            return fallback();
        }
    }

    public static Theme load(Path path) throws IOException {
        if (Files.size(path) > 65536) throw new IOException("Theme exceeds 64 KiB");
        try {
            return parse(Files.readString(path));
        } catch (RuntimeException e) {
            throw new IOException("Invalid theme: " + e.getMessage(), e);
        }
    }

    public static Theme parse(String json) {
        var o = JsonParser.parseString(json).getAsJsonObject();
        if (o.get("schemaVersion").getAsInt() != 1)
            throw new IllegalArgumentException("Unsupported schemaVersion");
        var ed = o.getAsJsonObject("editor");
        var menu = o.getAsJsonObject("menu");
        var msg = o.getAsJsonObject("messages");
        var syntax = o.getAsJsonObject("syntax");
        var font = o.getAsJsonObject("font");
        int size = font.get("size").getAsInt();
        if (size < 10 || size > 48) throw new IllegalArgumentException("Font size must be 10–48");
        return new Theme(
                o.get("name").getAsString(),
                color(ed, "background"),
                color(ed, "foreground"),
                color(syntax, "keyword"),
                color(syntax, "string"),
                color(syntax, "comment"),
                color(ed, "cursor"),
                color(ed, "selection"),
                color(ed, "error"),
                color(menu, "background"),
                color(menu, "foreground"),
                color(msg, "background"),
                color(msg, "foreground"),
                size,
                font.get("antialias").getAsBoolean());
    }

    private static Color color(JsonObject o, String key) {
        String s = o.get(key).getAsString();
        if (!s.matches("#[0-9a-fA-F]{6}"))
            throw new IllegalArgumentException("Invalid color: " + key);
        return Color.decode(s);
    }

    private static Theme fallback() {
        return new Theme(
                "Turbo C Classic",
                new Color(0x0000aa),
                Color.WHITE,
                new Color(0xffff55),
                new Color(0x55ffff),
                new Color(0xaaaaaa),
                new Color(0xffff55),
                new Color(0x00aaaa),
                new Color(0xff5555),
                new Color(0xaaaaaa),
                Color.BLACK,
                new Color(0x00aaaa),
                Color.BLACK,
                18,
                false);
    }
}
