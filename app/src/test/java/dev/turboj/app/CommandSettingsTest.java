package dev.turboj.app;

import static org.junit.jupiter.api.Assertions.*;
import dev.turboj.config.Settings;
import java.nio.file.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommandSettingsTest {
    @TempDir
    Path temp;

    @Test
    void migratesExistingSettingsAndRetainsExplicitProfile() throws Exception {
        String old = System.getProperty("turboj.config");
        System.setProperty("turboj.config", temp.toString());
        try {
            assertEquals("intellij-macos", Settings.load().keymapProfile);
            Files.writeString(temp.resolve("config.json"), "{\"theme\":\"light\"}");
            assertEquals("turboj-classic", Settings.load().keymapProfile);
            Files.writeString(temp.resolve("config.json"),
                    "{\"theme\":\"dark\",\"keymapProfile\":\"vscode-macos\"}");
            assertEquals("vscode-macos", Settings.load().keymapProfile);
            assertEquals("dark", Settings.load().theme);
            Files.writeString(temp.resolve("commandj-keymap.json"), "{\"schema\":1}");
            assertEquals("{\"schema\":1}", Settings.load().commandKeymapJson);
            assertFalse(Settings.load().snapshotJson().contains("commandKeymapJson"));
            Files.delete(temp.resolve("commandj-keymap.json"));
            Files.writeString(temp.resolve("config.json"), "{broken");
            assertEquals("turboj-classic", Settings.load().keymapProfile);
            Files.writeString(temp.resolve("config.json"), " ".repeat(65_537));
            assertEquals("turboj-classic", Settings.load().keymapProfile);
        } finally {
            if (old == null)
                System.clearProperty("turboj.config");
            else
                System.setProperty("turboj.config", old);
        }
    }
}
