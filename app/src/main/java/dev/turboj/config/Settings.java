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
    public int tabWidth = dev.turboj.formatting.StandardFormatters.DEFAULT.indent();
    /** The Java style Format Document applies: google, aosp, eclipse or javafmt (StandardFormatters). */
    /** Each action starts on an empty output; off keeps the history scrollable, as a terminal does. */
    public boolean clearOutputBeforeRun = false;
    public String formatter = dev.turboj.formatting.StandardFormatters.DEFAULT.id();
    public boolean isolated = false;
    public boolean autoClose = true;
    public boolean formatOnSave = false;
    public boolean autoTest = false;
    public boolean autoRunOnSave = false;
    public boolean autoTestOnSave = false;
    public String cherryTestExecutable = "";
    public boolean showPressedShortcuts = false;
    /** Shortcuts written the macOS way (⌃⌥⇧⌘O) rather than in words (Ctrl+Option+Shift+Cmd+O). */
    public boolean shortcutSymbols = System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("mac");
    public String keymapProfile = dev.turboj.commandj.keymap.BundledKeymaps.DEFAULT;
    public String workspaceDirectory;
    public boolean rememberWorkspace;
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
                s.keymapProfile = dev.turboj.commandj.keymap.BundledKeymaps.DEFAULT;
            // An installation that never chose a formatter gets TurboJ's default; the editor indents as that style does.
            if (!JsonParser.parseString(json).getAsJsonObject().has("formatter")) {
                s.formatter = dev.turboj.formatting.StandardFormatters.DEFAULT.id();
                s.tabWidth = dev.turboj.formatting.StandardFormatters.DEFAULT.indent();
            }
            s.formatter = dev.turboj.formatting.StandardFormatters.of(s.formatter).id();
            if (s.keymapProfile == null || s.keymapProfile.isBlank())
                s.keymapProfile = dev.turboj.commandj.keymap.BundledKeymaps.DEFAULT;
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
        settings.keymapProfile = dev.turboj.commandj.keymap.BundledKeymaps.DEFAULT;
        return settings;
    }

    private static Settings loadCommandKeymap(Settings settings) {
        try {
            Path file = directory().resolve("commandj-keymap.json");

            if (Files.isRegularFile(file) && Files.size(file) <= 1_048_576)
                settings.commandKeymapJson = Files.readString(file);
            else if (Files.exists(directory().resolve("keymap.json")))
                settings.keymapProfile = dev.turboj.commandj.keymap.BundledKeymaps.DEFAULT;
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
