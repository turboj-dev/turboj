package com.turboj.ui.themes;

import com.google.gson.*;

import java.awt.Color;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Immutable theme tokens. Bundled JSON is authoritative for both Java2D and the website export. */
public record Theme(
        String id, String name, Map<String, Color> colors, int fontSize, boolean antialias) {
    private record Registry(List<String> primary, Map<String, String> files) {}

    private static final Registry REGISTRY = readRegistry();
    public static final String[] BUNDLED = REGISTRY.primary().toArray(String[]::new);
    private static final Map<String, Theme> PRIMARY_CACHE =
            new java.util.concurrent.ConcurrentHashMap<>();
    private static final Map<String, String> REQUIRED =
            Map.ofEntries(
                    Map.entry(
                            "editor",
                            "background foreground cursor selection selectionForeground error"),
                    Map.entry(
                            "syntax",
                            "keyword string comment number identifier operator annotation"),
                    Map.entry("menu", "background foreground activeBackground activeForeground"),
                    Map.entry(
                            "messages",
                            "background foreground errorForeground inputForeground cursor"),
                    Map.entry("diagnostics", "error warning success"),
                    Map.entry("chrome", "border statusBackground statusForeground"),
                    Map.entry(
                            "ui",
                            "background foreground surface muted heading border accent"
                                    + " accentForeground link focus"),
                    Map.entry("documentation", "background foreground border code link"),
                    Map.entry(
                            "intelligence",
                            "ghost aiGhost selectionBackground selectionForeground definition"
                                    + " activeParameter"));

    public Theme {
        colors = Map.copyOf(colors);
    }

    /**
     * The previous fourth Amber preset is retired; saved Dark preferences retain their appearance.
     */
    public static String normalizeId(String id) {
        if ("modern".equals(id)) return "dark";
        return id != null && REGISTRY.files().containsKey(id) ? id : "classic";
    }

    public static Map<String, String> bundledResources() {
        return REGISTRY.files();
    }

    private static Registry readRegistry() {
        try (var input = Theme.class.getResourceAsStream("/themes/catalog.json")) {
            if (input == null) throw new IOException("Missing theme catalog");
            byte[] bytes = input.readNBytes(1_048_577);
            if (bytes.length > 1_048_576) throw new IOException("Theme catalog exceeds 1 MiB");
            JsonObject catalog =
                    JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8))
                            .getAsJsonObject();
            if (catalog.get("schemaVersion").getAsInt() != 1)
                throw new IOException("Unsupported catalog schema");
            List<String> primary = new ArrayList<>();
            for (JsonElement id : catalog.getAsJsonArray("primary")) primary.add(id.getAsString());
            if (!primary.equals(List.of("classic", "dark", "light")))
                throw new IOException("Invalid primary themes");
            Map<String, String> files = new LinkedHashMap<>();
            for (JsonElement element : catalog.getAsJsonArray("themes")) {
                if (files.size() >= 10_000)
                    throw new IOException("Theme catalog exceeds 10000 entries");
                JsonObject entry = element.getAsJsonObject();
                String id = entry.get("id").getAsString(), file = entry.get("file").getAsString();
                if (!id.matches("[a-z][a-z0-9_-]{0,63}")
                        || !validResource(file)
                        || files.put(id, file) != null)
                    throw new IOException("Invalid theme catalog entry");
            }
            if (!files.keySet().containsAll(primary))
                throw new IOException("Missing primary theme");
            return new Registry(List.copyOf(primary), Collections.unmodifiableMap(files));
        } catch (IOException | RuntimeException e) {
            return new Registry(
                    List.of("classic", "dark", "light"),
                    Map.of("classic", "classic.json", "dark", "dark.json", "light", "light.json"));
        }
    }

    private static boolean validResource(String file) {
        return file != null
                && file.matches("[A-Za-z0-9_-]+\\.json")
                && !file.equals("catalog.json");
    }

    static String resourceJson(String file) throws IOException {
        if (!validResource(file)) throw new IOException("Invalid theme resource path");
        try (var input = Theme.class.getResourceAsStream("/themes/" + file)) {
            if (input == null) throw new IOException("Missing theme resource: " + file);
            return readDefinition(input);
        }
    }

    static String readDefinition(InputStream input) throws IOException {
        byte[] bytes = input.readNBytes(65_537);
        if (bytes.length > 65_536) throw new IOException("Theme exceeds 64 KiB");
        return new String(bytes, StandardCharsets.UTF_8);
    }

    public static Theme bundled(String requested) {
        String id = normalizeId(requested);
        Theme cached = PRIMARY_CACHE.get(id);
        if (cached != null) return cached;
        try {
            Theme theme = parse(resourceJson(REGISTRY.files().get(id)));
            if (!theme.id().equals(id)) throw new IOException("Theme identity mismatch");
            if (REGISTRY.primary().contains(id)) PRIMARY_CACHE.putIfAbsent(id, theme);
            return theme;
        } catch (IOException | RuntimeException e) {
            if (!id.equals("classic")) return bundled("classic");
            return emergencyFallback();
        }
    }

    public static Theme load(Path path) throws IOException {
        try (var input = Files.newInputStream(path)) {
            return parse(readDefinition(input));
        } catch (RuntimeException e) {
            throw new IOException("Invalid theme: " + e.getMessage(), e);
        }
    }

    public static Theme parse(String json) {
        JsonObject object = JsonParser.parseString(json).getAsJsonObject();
        int schema = object.get("schemaVersion").getAsInt();
        if (schema != 1 && schema != 2)
            throw new IllegalArgumentException("Unsupported schemaVersion");
        if (schema == 1) upgradeLegacy(object);
        String id = object.get("id").getAsString();
        if (!id.matches("[a-z][a-z0-9_-]{0,63}"))
            throw new IllegalArgumentException("Invalid theme id");
        String name = object.get("name").getAsString();
        if (name.isBlank() || name.length() > 120)
            throw new IllegalArgumentException("Invalid theme name");
        Map<String, Color> colors = new LinkedHashMap<>();
        REQUIRED.forEach(
                (section, fields) -> {
                    JsonObject group = object.getAsJsonObject(section);
                    if (group == null)
                        throw new IllegalArgumentException("Missing theme section: " + section);
                    for (String field : fields.split(" ")) {
                        if (!group.has(field))
                            throw new IllegalArgumentException(
                                    "Missing theme token: " + section + "." + field);
                        String value = group.get(field).getAsString();
                        if (!value.matches("#[0-9a-fA-F]{6}"))
                            throw new IllegalArgumentException(
                                    "Invalid color: " + section + "." + field);
                        colors.put(section + "." + field, Color.decode(value));
                    }
                });
        JsonObject font = object.getAsJsonObject("font");
        int size = font.get("size").getAsInt();
        if (size < 10 || size > 48) throw new IllegalArgumentException("Font size must be 10–48");
        return new Theme(id, name, colors, size, font.get("antialias").getAsBoolean());
    }

    /**
     * Expand historical user palettes from their own colors; bundled presets never use this path.
     */
    private static void upgradeLegacy(JsonObject object) {
        object.addProperty("id", "custom");
        String bg = value(object, "editor", "background"),
                fg = value(object, "editor", "foreground");
        String selection = value(object, "editor", "selection"),
                keyword = value(object, "syntax", "keyword");
        String comment = value(object, "syntax", "comment"),
                string = value(object, "syntax", "string");
        String error = value(object, "editor", "error"), cursor = value(object, "editor", "cursor");
        set(object, "editor", "selectionForeground", fg);
        set(object, "syntax", "number", string);
        set(object, "syntax", "identifier", fg);
        set(object, "syntax", "operator", fg);
        set(object, "syntax", "annotation", keyword);
        set(object, "menu", "activeBackground", bg);
        set(object, "menu", "activeForeground", fg);
        set(object, "messages", "errorForeground", error);
        set(object, "messages", "inputForeground", value(object, "messages", "foreground"));
        set(object, "messages", "cursor", cursor);
        set(object, "diagnostics", "error", error);
        set(object, "diagnostics", "warning", keyword);
        set(object, "diagnostics", "success", string);
        set(object, "chrome", "border", fg);
        set(object, "chrome", "statusBackground", value(object, "menu", "background"));
        set(object, "chrome", "statusForeground", value(object, "menu", "foreground"));
        for (String field : "background surface".split(" ")) set(object, "ui", field, bg);
        for (String field : "foreground heading border".split(" ")) set(object, "ui", field, fg);
        set(object, "ui", "muted", comment);
        set(object, "ui", "accent", selection);
        set(object, "ui", "accentForeground", fg);
        set(object, "ui", "link", keyword);
        set(object, "ui", "focus", cursor);
        set(object, "documentation", "background", bg);
        set(object, "documentation", "foreground", fg);
        set(object, "documentation", "border", fg);
        set(object, "documentation", "code", string);
        set(object, "documentation", "link", keyword);
        set(object, "intelligence", "ghost", comment);
        set(object, "intelligence", "aiGhost", comment);
        set(object, "intelligence", "selectionBackground", selection);
        set(object, "intelligence", "selectionForeground", fg);
        set(object, "intelligence", "definition", string);
        set(object, "intelligence", "activeParameter", keyword);
    }

    private static String value(JsonObject object, String section, String field) {
        return object.getAsJsonObject(section).get(field).getAsString();
    }

    private static void set(JsonObject object, String section, String field, String value) {
        if (!object.has(section)) object.add(section, new JsonObject());
        object.getAsJsonObject(section).addProperty(field, value);
    }

    private static Theme emergencyFallback() {
        // A damaged installation must still display an editable window without recursive loading.
        Map<String, Color> safe = new HashMap<>();
        REQUIRED.forEach(
                (section, fields) -> {
                    for (String field : fields.split(" "))
                        safe.put(
                                section + "." + field,
                                field.toLowerCase(Locale.ROOT).contains("background")
                                                || field.equals("surface")
                                        ? Color.BLACK
                                        : Color.WHITE);
                });
        return new Theme("classic", "Turbo Classic", safe, 18, false);
    }

    public Color color(String section, String field) {
        Color color = colors.get(section + "." + field);
        if (color == null)
            throw new IllegalArgumentException("Unknown theme token: " + section + "." + field);
        return color;
    }

    public Color background() {
        return color("editor", "background");
    }

    public Color foreground() {
        return color("editor", "foreground");
    }

    public Color keyword() {
        return color("syntax", "keyword");
    }

    public Color string() {
        return color("syntax", "string");
    }

    public Color comment() {
        return color("syntax", "comment");
    }

    public Color cursor() {
        return color("editor", "cursor");
    }

    public Color selection() {
        return color("editor", "selection");
    }

    public Color error() {
        return color("diagnostics", "error");
    }

    public Color menuBackground() {
        return color("menu", "background");
    }

    public Color menuForeground() {
        return color("menu", "foreground");
    }

    public Color messagesBackground() {
        return color("messages", "background");
    }

    public Color messagesForeground() {
        return color("messages", "foreground");
    }
}
