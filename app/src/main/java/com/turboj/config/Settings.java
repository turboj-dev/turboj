package com.turboj.config;

import com.google.gson.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;

public final class Settings {
    public String theme = "classic";
    public int fontSize = 18;
    public int width = 1060, height = 740;
    public int tabWidth = 4;
    public boolean isolated = false;
    public boolean autoClose = true;
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
            if (!Files.exists(p) || Files.size(p) > 65536) return new Settings();
            Settings s = new Gson().fromJson(Files.readString(p), Settings.class);
            if (s == null) return new Settings();
            s.fontSize = Math.clamp(s.fontSize, 10, 48);
            s.width = Math.clamp(s.width, 640, 4000);
            s.height = Math.clamp(s.height, 420, 2400);
            if (s.recentFiles == null) s.recentFiles = new ArrayList<>();
            if (s.theme == null || !List.of("classic", "amber", "modern", "light").contains(s.theme))
                s.theme = "classic";
            return s;
        } catch (IOException | RuntimeException e) {
            return new Settings();
        }
    }

    public void save() throws IOException {
        Files.createDirectories(directory().resolve("themes"));
        SafeFiles.atomicWrite(
                directory().resolve("config.json"),
                new GsonBuilder().setPrettyPrinting().create().toJson(this));
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
