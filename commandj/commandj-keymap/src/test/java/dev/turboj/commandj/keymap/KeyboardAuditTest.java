package dev.turboj.commandj.keymap;

import dev.turboj.commandj.core.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class KeyboardAuditTest {
    @Test void profileSpecificLineOperationsNeverMixIdeMeanings() {
        var maps = new KeymapManager();
        assertEquals(KeySequence.parse("META+D"), maps.bindings("duplicateLine").getFirst().sequence());
        assertEquals(KeySequence.parse("ALT+SHIFT+UP"), maps.bindings("moveLineUp").getFirst().sequence());
        assertTrue(maps.bindings("open").isEmpty(), "IntelliJ Cmd+O belongs to Go to Type, file open stays in palette");
        assertEquals(KeySequence.parse("META+O"), maps.bindings("goType").getFirst().sequence());
        assertEquals(KeySequence.parse("META+SHIFT+SPACE"), maps.bindings("completion.smart").getFirst().sequence());
        maps.select("eclipse-macos");
        assertEquals(KeySequence.parse("META+D"), maps.bindings("deleteLine").getFirst().sequence());
        assertEquals(KeySequence.parse("META+ALT+UP"), maps.bindings("editor.copyLineUp").getFirst().sequence());
        maps.select("vscode-macos");
        assertEquals(KeySequence.parse("META+D"), maps.bindings("editor.selectNextOccurrence").getFirst().sequence());
        assertEquals(KeySequence.parse("ALT+SHIFT+DOWN"), maps.bindings("editor.copyLineDown").getFirst().sequence());
        assertTrue(maps.bindings("duplicateLine").isEmpty());
        maps.select("turboj-classic");
        assertEquals(KeySequence.parse("F1"), maps.bindings("help").getFirst().sequence());
        assertTrue(maps.bindings("run").stream().anyMatch(b -> b.sequence().equals(KeySequence.parse("CTRL+F9"))));
    }

    @Test void oldSnapshotMergesOnlyNewNonconflictingDefaultsAndPreservesUnboundCommands() {
        var maps = new KeymapManager();
        var original = maps.selected();
        var old = new LinkedHashMap<>(original.overrides());
        old.remove("editor.joinLines");
        old.remove("editor.insertLineAbove");
        old.put("custom.action", List.of(KeyBinding.editor("CTRL+SHIFT+J")));
        old.put("editor.insertLineBelow", List.of());
        maps.install(List.of(new KeymapProfile(original.id(), original.name(), null, old, true)), original.id());
        assertTrue(maps.bindings("editor.joinLines").isEmpty());
        assertEquals(KeySequence.parse("CTRL+SHIFT+J"), maps.bindings("custom.action").getFirst().sequence());
        assertTrue(maps.bindings("editor.insertLineBelow").isEmpty());
        assertFalse(maps.bindings("editor.insertLineAbove").isEmpty());
        assertTrue(maps.migrationNotices().stream().anyMatch(s -> s.contains("editor.joinLines") && s.contains("conflicts")));
        assertTrue(maps.conflicts().isEmpty());
    }

    @Test void inheritedCustomBindingWinsOverNewParentDefault() {
        var maps = new KeymapManager();
        var base = maps.selected(); var old = new HashMap<>(base.overrides());
        old.remove("editor.joinLines");
        maps.install(List.of(new KeymapProfile(base.id(), base.name(), null, old, true),
                new KeymapProfile("custom", "Custom", base.id(), Map.of("custom.action",
                        List.of(KeyBinding.of("CTRL+SHIFT+J"))), false)), "custom");
        assertEquals("custom", maps.selectedId());
        assertTrue(maps.bindings("editor.joinLines").isEmpty());
        assertFalse(maps.bindings("custom.action").isEmpty());
    }

    @Test void prefixIndexDoesNotRunEveryAvailabilityPredicateForOrdinaryTyping() {
        var maps = new KeymapManager();
        try (var registry = new CommandRegistry()) {
            var checks = new AtomicInteger();
            for (String id : maps.bindings().keySet()) registry.register(Command.action(id,id,"Test",()->{})
                    .when(c -> { checks.incrementAndGet(); return Availability.available(); }));
            var resolver = new ShortcutResolver(maps, registry);
            assertEquals(ShortcutResolver.Kind.NONE, resolver.accept(KeyStrokeSpec.parse("A"), CommandContext.of("editor"), 1).kind());
            assertEquals(0, checks.get());
            assertEquals("save", resolver.accept(KeyStrokeSpec.parse("META+S"), CommandContext.of("editor"), 2).command());
            assertEquals(1, checks.get());
            assertEquals(ShortcutResolver.Kind.NONE, resolver.accept(KeyStrokeSpec.parse("META+S"),
                    CommandContext.of("editor", "input-method"), 3).kind());
        }
    }

    @Test void aliasBindingsExecuteOneCanonicalCommandAndReferencesIncludeUnassignedActions() {
        var maps = new KeymapManager();
        try (var registry = new CommandRegistry()) {
            registry.register(Command.action("save", "Save", "File", () -> {}));
            registry.register(Command.action("newAction", "New action", "Editor", () -> {}).requiring("editor"));
            registry.alias("legacy.save", "save");
            assertTrue(maps.replaceBindings("legacy.save", List.of(KeyBinding.of("CTRL+ALT+F12"))).applied());
            var resolver = new ShortcutResolver(maps, registry);
            assertEquals("save", resolver.accept(KeyStrokeSpec.parse("CTRL+ALT+F12"),CommandContext.of("editor"),1).command());
            var rows = ShortcutReference.rows(registry,maps,CommandContext.of());
            assertEquals(2, rows.size());
            assertTrue(ShortcutReference.markdown(registry,maps,CommandContext.of()).contains("Requires editor"));
            String comparison = ShortcutReference.comparisonMarkdown(registry,maps);
            assertTrue(comparison.contains("Unassigned"));
            assertTrue(comparison.contains("IntelliJ IDEA macOS"));
            assertEquals("intellij-macos", maps.selectedId());
        }
    }

    @Test void editingAndResettingCanonicalActionIncludesPersistedAliasBindings() {
        var maps = new KeymapManager();
        try (var registry = new CommandRegistry()) {
            registry.register(Command.action("file.save", "Save", "File", () -> {}));
            registry.alias("save", "file.save");
            assertEquals(KeySequence.parse("META+S"), maps.commandBindings(registry,"file.save").getFirst().sequence());
            assertTrue(maps.replaceCommandBindings(registry,"file.save",
                    List.of(KeyBinding.of("CTRL+ALT+F12")),Set.of()).applied());
            assertTrue(maps.bindings("save").isEmpty(), "old binding must not remain active but invisible");
            assertEquals(KeySequence.parse("CTRL+ALT+F12"), maps.commandBindings(registry,"file.save").getFirst().sequence());
            assertTrue(maps.resetCommand(registry,"file.save").applied());
            assertEquals(KeySequence.parse("META+S"), maps.commandBindings(registry,"file.save").getFirst().sequence());
            assertTrue(maps.bindings("file.save").isEmpty());
        }
    }

    @Test void legacyUserCollisionsArePreservedDeterministicallyAndSurvivePersistence() {
        var first = new KeymapManager(); var second = new KeymapManager();
        var users = new LinkedHashMap<String,List<KeyBinding>>();
        users.put("first", List.of(KeyBinding.of("META+S")));
        users.put("second", List.of(KeyBinding.of("META+S")));
        first.migrateLegacyBindings(users);
        var reverse = new LinkedHashMap<String,List<KeyBinding>>();
        reverse.put("second", users.get("second")); reverse.put("first", users.get("first"));
        second.migrateLegacyBindings(reverse);
        assertEquals(first.bindings(),second.bindings());
        assertTrue(first.bindings("save").isEmpty(), "only the conflicting default is removed");
        assertEquals(users.get("first"), first.bindings("first"));
        assertEquals(users.get("second"), first.bindings("second"));
        assertTrue(first.hasStoredConflicts());
        var restored = new KeymapManager(); KeymapJson.read(restored,KeymapJson.write(first));
        assertEquals(first.bindings(),restored.bindings());
        assertFalse(restored.migrationNotices().isEmpty());
        try (var registry = new CommandRegistry()) {
            registry.register(Command.action("first","First","Test",()->{}));
            registry.register(Command.action("second","Second","Test",()->{}));
            assertEquals(ShortcutResolver.Kind.CONFLICT, new ShortcutResolver(restored,registry)
                    .accept(KeyStrokeSpec.parse("META+S"),CommandContext.of(),1).kind());
        }
    }
}
