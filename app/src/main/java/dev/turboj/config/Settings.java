package dev.turboj.config;

import com.google.gson.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public final class Settings {
    public String theme = "classic";
    public dev.turboj.ai.AIConfig ai = dev.turboj.ai.AIConfig.defaults();
    public String externalThemePath;
    public int fontSize = 18;
    public int width = 1060, height = 740;
    public int tabWidth = 4;
    public boolean isolated = false;
    public boolean autoClose = true;
    public boolean formatOnSave = false;
    public boolean autoTest = false;
    public boolean autoRunOnSave = false;
    public boolean autoTestOnSave = false;
    public boolean showPressedShortcuts = false;
    public String keymapProfile = "intellij-macos";
    public transient String commandKeymapJson;
    public List<String> recentFiles = new ArrayList<>();

    public static Path directory() {
        return Path.of(
                System.getProperty(
                        "turboj.config",
                        Path.of(System.getProperty("user.home"), ".config", "turboj").toString()));
    }

    public static Settings load() {
        try {
            Path p = directory().resolve("config.json");
            if (!Files.exists(p)) return loadCommandKeymap(new Settings());
            if (Files.size(p) > 65536) return loadCommandKeymap(legacyDefaults());
            String json = Files.readString(p);
            Settings s = new Gson().fromJson(json, Settings.class);
            if (s == null) return loadCommandKeymap(legacyDefaults());
            if (!JsonParser.parseString(json).getAsJsonObject().has("keymapProfile"))
                s.keymapProfile = "turboj-classic";
            if (s.keymapProfile == null || s.keymapProfile.isBlank())
                s.keymapProfile = "turboj-classic";
            s.fontSize = Math.clamp(s.fontSize, 10, 48);
            s.width = Math.clamp(s.width, 640, 4000);
            s.height = Math.clamp(s.height, 420, 2400);
            if (s.recentFiles == null) s.recentFiles = new ArrayList<>();
            s.theme = dev.turboj.ui.themes.Theme.normalizeId(s.theme);
            try {
                if (s.ai == null) s.ai = dev.turboj.ai.AIConfig.defaults();
                s.ai.validate();
            } catch (RuntimeException invalidAI) {
                s.ai = dev.turboj.ai.AIConfig.defaults();
            }
            return loadCommandKeymap(s);
        } catch (IOException | RuntimeException e) {
            return loadCommandKeymap(legacyDefaults());
        }
    }

    private static Settings legacyDefaults() {
        Settings settings = new Settings();
        settings.keymapProfile = "turboj-classic";
        return settings;
    }

    private static Settings loadCommandKeymap(Settings settings) {
        try {
            Path file = directory().resolve("commandj-keymap.json");

            if (Files.isRegularFile(file) && Files.size(file) <= 1_048_576)
                settings.commandKeymapJson = Files.readString(file);
            else if (Files.exists(directory().resolve("keymap.json")))
                settings.keymapProfile = "turboj-classic";
        } catch (IOException ignored) {
            // Preserve the previous/default profile if preferences are inaccessible.
        }
        return settings;
    }

    public void save() throws IOException {
        saveSnapshot(snapshotJson());
    }

    /** Capture on the EDT before queuing a preference write; do not share mutable recentFiles. */
    public String snapshotJson() {
        return new GsonBuilder().setPrettyPrinting().create().toJson(this);
    }

    public static void saveSnapshot(String json) throws IOException {
        Files.createDirectories(directory().resolve("themes"));
        SafeFiles.atomicWrite(directory().resolve("config.json"), json);
    }

    public static Map<String, String> keymap() {
        try {
            Path p = directory().resolve("keymap.json");
            if (!Files.exists(p) || Files.size(p) > 65536) return Map.of();
            var o = JsonParser.parseString(Files.readString(p)).getAsJsonObject();
            var m = new LinkedHashMap<String, String>();
            o.entrySet().forEach(e -> m.put(e.getKey(), e.getValue().getAsString()));
            return m;
        } catch (IOException | RuntimeException e) {
            return Map.of();
        }
    }
}
