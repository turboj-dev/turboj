package dev.turboj.commandj.keymap;

import dev.turboj.commandj.core.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class KeymapTest {
    @TempDir
    Path temporary;

    @Test
    void verifiedProfilesAreConflictFreeAndDistinct() {
        var maps = new KeymapManager();
        assertEquals("intellij-macos", maps.selectedId());
        assertEquals(4, maps.profiles().size());
        for (var profile : maps.profiles()) {
            maps.select(profile.id());
            assertEquals(List.of(), maps.conflicts(), profile.id());
        }
        maps.select("intellij-macos");
        assertEquals("SHIFT+F6", maps.bindings("refactor.rename").getFirst().sequence().external());
        maps.select("turboj-classic");
        assertTrue(maps.bindings("save").stream().anyMatch(b -> b.sequence().external()
                .equals("F2")));
        maps.select("vscode-macos");
        assertEquals(2, maps.bindings("format.selection").getFirst().sequence().strokes().size());
    }

    @Test
    void inheritanceDuplicationOverridesAndReset() {
        var maps = new KeymapManager();
        maps.duplicate("custom", "Custom");
        assertEquals("intellij-macos", maps.selected().parentId());
        var inherited = maps.bindings("save");
        assertTrue(maps.replaceBindings("save", List.of(KeyBinding.of("CTRL+ALT+S"))).applied());
        assertNotEquals(inherited, maps.bindings("save"));
        assertTrue(maps.resetCommand("save").applied());
        assertEquals(inherited, maps.bindings("save"));
        maps.replaceBindings("save", List.of());
        maps.resetProfile();
        assertEquals(inherited, maps.bindings("save"));
    }

    @Test
    void conflictsNeverSilentlyReplaceBindings() {
        var maps = new KeymapManager();
        var previous = maps.bindings("open");
        var result = maps.replaceBindings("open", List.of(KeyBinding.of("META+S")));
        assertFalse(result.applied());
        assertEquals(previous, maps.bindings("open"));
        assertEquals("META+S", maps.bindings("save").getFirst().sequence().external());
        assertFalse(maps.replaceBindingsResolving("open", List.of(KeyBinding.of("META+S")), Set
                .of()).applied());
        assertTrue(maps.replaceBindingsResolving("open", List.of(KeyBinding.of("META+S")), Set
                .of("save")).applied());
        assertTrue(maps.bindings("save").isEmpty());
        assertFalse(maps.resetCommand("save").applied());
    }

    @Test
    void exclusiveContextsCanShareKeysAndPrefixConflictsAreDetected() {
        var editor = new KeyBinding(KeySequence.parse("CTRL+K"), new BindingContext(Set
                .of("editor"), Set.of("console")));
        var console = new KeyBinding(KeySequence.parse("CTRL+K"), new BindingContext(Set
                .of("console"), Set.of("editor")));
        assertTrue(KeymapManager.conflicts(Map.of("a", List.of(editor), "b", List.of(console)))
                .isEmpty());
        var collision = KeymapManager.conflicts(Map.of("a", List.of(KeyBinding.of("META+K")), "b",
                List.of(KeyBinding.of("META+K, META+S"))));
        assertEquals(1, collision.size());
        assertTrue(collision.getFirst().prefix());
    }

    @Test
    void jsonRoundTripAndAtomicSavePreserveAllBindings() throws Exception {
        var maps = new KeymapManager();
        maps.duplicate("custom", "Custom");
        maps.replaceBindings("save", List.of(KeyBinding.of("CTRL+ALT+S"), KeyBinding
                .editor("CTRL+ALT+K, S")));
        String json = KeymapJson.write(maps);
        var loaded = new KeymapManager();
        KeymapJson.read(loaded, json);
        assertEquals(json, KeymapJson.write(loaded));
        Path file = temporary.resolve("settings/keymap.json");
        KeymapJson.save(file, maps);
        KeymapJson.load(file, loaded);
        assertEquals(maps.bindings(), loaded.bindings());
    }

    @Test
    void malformedCyclicAndConflictingImportsLeaveStateIntact() {
        var maps = new KeymapManager();
        String before = KeymapJson.write(maps);
        assertThrows(IllegalArgumentException.class, () -> KeymapJson.read(maps, "{}"));
        assertThrows(IllegalArgumentException.class, () -> maps
                .addProfile(new KeymapProfile("cycle", "Cycle", "cycle", Map.of(), false)));
        String bad = before.replace("META+O", "META+S");
        assertThrows(IllegalArgumentException.class, () -> KeymapJson.read(maps, bad));
        assertEquals(before, KeymapJson.write(maps));
    }

    @Test
    void chordsHandleCompletionWrongStrokeEscapeTimeoutAndProfileChanges() {
        var maps = new KeymapManager();
        maps.select("vscode-macos");
        try (var registry = new CommandRegistry()) {
            for (String id : List.of("keymap", "format.selection", "save", "refactor.rename"))
                registry.register(Command.action(id, id, "Test", () -> {}));
            var resolver = new ShortcutResolver(maps, registry, 100);
            var context = CommandContext.of("editor");
            assertEquals(ShortcutResolver.Kind.PENDING, resolver.accept(KeyStrokeSpec
                    .parse("META+K"), context, 1000).kind());
            assertEquals("keymap", resolver.accept(KeyStrokeSpec.parse("META+S"), context, 1001)
                    .command());
            resolver.accept(KeyStrokeSpec.parse("META+K"), context, 1100);
            assertEquals("save", resolver.accept(KeyStrokeSpec.parse("META+S"), context, 1300)
                    .command());
            resolver.accept(KeyStrokeSpec.parse("META+K"), context, 1400);
            resolver.accept(KeyStrokeSpec.parse("ESCAPE"), context, 1401);
            assertFalse(resolver.pending());
            resolver.accept(KeyStrokeSpec.parse("META+K"), context, 1500);
            maps.select("intellij-macos");
            assertEquals("refactor.rename", resolver.accept(KeyStrokeSpec.parse("SHIFT+F6"),
                    context, 1501).command());
        }
    }

    @Test
    void disabledAndUnregisteredCommandsNeverConsumeShortcut() {
        var maps = new KeymapManager();

        try (var registry = new CommandRegistry()) {
            registry.register(Command.action("save", "Save", "File", () -> {}).requiring("editor"));
            var resolver = new ShortcutResolver(maps, registry);
            assertEquals(ShortcutResolver.Kind.NONE, resolver.accept(KeyStrokeSpec.parse("META+S"),
                    CommandContext.of(), 1).kind());
            assertEquals(ShortcutResolver.Kind.NONE, resolver.accept(KeyStrokeSpec
                    .parse("SHIFT+F6"), CommandContext.of("editor"), 2).kind());
        }
    }

    @Test
    void logicalStrokeParsingAndReservedWarnings() {
        assertEquals(KeyStrokeSpec.parse("META+ALT+L"), KeyStrokeSpec.parse("cmd option L"));
        assertEquals("DELETE", KeyStrokeSpec.parse("Cmd+Forward_Delete").key());
        assertTrue(ReservedShortcuts.warning(KeySequence.parse("META+SPACE")).isPresent());
        assertTrue(ReservedShortcuts.warning(KeySequence.parse("CTRL+SPACE")).isPresent());
        assertThrows(IllegalArgumentException.class, () -> KeySequence.parse("CTRL"));
    }

    @Test
    void switchingProfilesDoesNotModifyExternalContext() {
        var maps = new KeymapManager();
        var context = CommandContext.of("editor", "dark-theme");

        for (var p : maps.profiles())
            maps.select(p.id());
        assertTrue(context.has("dark-theme"));
    }
}
