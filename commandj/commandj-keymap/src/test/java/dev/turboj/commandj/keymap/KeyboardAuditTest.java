package dev.turboj.commandj.keymap;

import dev.turboj.commandj.core.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class KeyboardAuditTest {
    @Test void classicQuickFixUsesCmdOneWithoutChangingOtherProfileMeanings() {
        var maps = new KeymapManager();
        try (var registry = new CommandRegistry()) {
            registry.register(Command.action("refactor.context-actions", "Quick Fix", "Code", () -> {}));
            registry.register(Command.action("view.focusExplorer", "Explorer", "View", () -> {}));
            var resolver = new ShortcutResolver(maps,registry);
            for (String profile : List.of("turboj-macos","eclipse-macos")) {
                maps.select(profile);
                assertEquals("refactor.context-actions",resolver.accept(KeyStrokeSpec.parse("META+1"),CommandContext.of("editor"),1).command());
                assertEquals("refactor.context-actions",resolver.accept(KeyStrokeSpec.parse("META+1"),CommandContext.of("quick-fix-popup"),2).command());
                assertTrue(maps.conflicts().isEmpty());
            }
            maps.select("intellij-macos");
            assertEquals("view.focusExplorer",resolver.accept(KeyStrokeSpec.parse("META+1"),CommandContext.of("editor"),3).command());
        }
    }

    @Test void classicQuickFixMigrationPreservesCustomizedCmdOneAndExplicitUnbinding() {
        var maps = new KeymapManager(); maps.select("turboj-macos");
        var profile = maps.selected();
        var previous = new LinkedHashMap<>(profile.overrides());
        previous.remove("refactor.context-actions");
        previous.put("custom.action",List.of(KeyBinding.of("META+1")));
        maps.install(List.of(new KeymapProfile(profile.id(),profile.name(),null,previous,true)),profile.id());
        assertEquals(List.of(KeyBinding.of("META+1")),maps.bindings("custom.action"));
        assertTrue(maps.bindings("refactor.context-actions").isEmpty());
        assertTrue(maps.migrationNotices().stream().anyMatch(s -> s.contains("refactor.context-actions")));
        previous.remove("custom.action");
        maps.install(List.of(new KeymapProfile(profile.id(),profile.name(),null,previous,true)),profile.id());
        assertEquals(List.of(KeyBinding.of("ALT+ENTER"),KeyBinding.of("META+1")),maps.bindings("refactor.context-actions"));
        previous.put("refactor.context-actions",List.of());
        maps.install(List.of(new KeymapProfile(profile.id(),profile.name(),null,previous,true)),profile.id());
        assertTrue(maps.bindings("refactor.context-actions").isEmpty());
    }

    @Test void eclipseSaveAllUsesItsDocumentedGestureWithoutChangingOtherProfiles() {
        var maps = new KeymapManager();
        try (var registry = new CommandRegistry()) {
            registry.register(Command.action("saveAs", "Save As", "File", () -> {}));
            registry.register(Command.action("file.saveAll", "Save All", "File", () -> {}));
            var resolver = new ShortcutResolver(maps, registry);
            for (String profile : List.of("intellij-macos", "eclipse-macos", "vscode-macos", "turboj-macos")) {
                maps.select(profile);
                boolean eclipse = profile.equals("eclipse-macos");
                assertEquals(eclipse ? "file.saveAll" : "saveAs", resolver.accept(
                        KeyStrokeSpec.parse("META+SHIFT+S"), CommandContext.of("editor"), 1).command(), profile);
                if (profile.equals("turboj-macos")) assertEquals(List.of(KeyBinding.of("META+ALT+S")), maps.bindings("file.saveAll"),
                        "TurboJ follows the macOS Option-means-all convention");
                else assertTrue(maps.bindings(eclipse ? "saveAs" : "file.saveAll").isEmpty(), profile);
                assertTrue(maps.conflicts().isEmpty(), profile);
            }
        }
    }

    @Test void eclipseSaveAllMigrationPreservesStoredSaveAsOrCustomShortcutAndExplainsSuppression() {
        for (String existingCommand : List.of("saveAs", "custom.persistEverything")) {
            var maps = new KeymapManager();
            maps.select("eclipse-macos");
            var profile = maps.selected();
            var stored = new LinkedHashMap<>(profile.overrides());
            stored.remove("file.saveAll");
            stored.put("saveAs", List.of());
            stored.put(existingCommand, List.of(KeyBinding.of("META+SHIFT+S")));
            maps.install(List.of(new KeymapProfile(profile.id(), profile.name(), null, stored, true)), profile.id());
            assertEquals("eclipse-macos", maps.selectedId());
            assertEquals(stored.get(existingCommand), maps.bindings(existingCommand));
            assertTrue(maps.bindings("file.saveAll").isEmpty());
            assertTrue(maps.migrationNotices().stream().anyMatch(message -> message.contains("file.saveAll")
                    && message.contains("conflicts")), maps.migrationNotices().toString());
            assertTrue(maps.conflicts().isEmpty());
            var restored = new KeymapManager();
            KeymapJson.read(restored, KeymapJson.write(maps));
            assertEquals(maps.bindings(), restored.bindings());
            assertEquals("eclipse-macos", restored.selectedId());
            assertTrue(restored.migrationNotices().stream().anyMatch(message -> message.contains("file.saveAll")
                    && message.contains("conflicts")));
        }
    }

    @Test void eclipseSaveAllDefaultIsSuppressedByAnInheritedUserShortcut() {
        var maps = new KeymapManager();
        maps.select("eclipse-macos");
        var profile = maps.selected();
        var stored = new LinkedHashMap<>(profile.overrides());
        stored.remove("file.saveAll");
        var customBinding = List.of(KeyBinding.of("META+SHIFT+S"));
        maps.install(List.of(new KeymapProfile(profile.id(), profile.name(), null, stored, true),
                new KeymapProfile("personal-eclipse", "Personal Eclipse", profile.id(),
                        Map.of("custom.persistEverything", customBinding), false)), "personal-eclipse");
        assertEquals("personal-eclipse", maps.selectedId());
        assertEquals(customBinding, maps.bindings("custom.persistEverything"));
        assertTrue(maps.bindings("file.saveAll").isEmpty());
        assertTrue(maps.migrationNotices().stream().anyMatch(message -> message.contains("file.saveAll")
                && message.contains("conflicts")));
        assertTrue(maps.conflicts().isEmpty());
    }

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
        maps.select("turboj-macos");
        assertEquals(KeySequence.parse("SHIFT+F1"), maps.bindings("help").getFirst().sequence());
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
