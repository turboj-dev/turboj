package dev.turboj.commandj.keymap;

import dev.turboj.commandj.core.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CompleteDefaultsTest {
    private static final Map<String, KeySequence> SUPPLEMENTS = Map.of(
            "save", KeySequence.parse("CTRL+META+K, F, S"),
            "new.action", KeySequence.parse("CTRL+META+K, N, A"));

    @Test void migrationDoesNotRebindAnExplicitlyUnboundHistoricalAlias() {
        try (var registry = new CommandRegistry()) {
            registry.register(Command.action("new.action", "New action", "File", () -> {}));
            registry.alias("old.action", "new.action");
            var old = new KeymapManager();
            old.replaceBindings("old.action", List.of());
            var maps = new KeymapManager();
            maps.completeDefaults(registry, SUPPLEMENTS);
            KeymapJson.read(maps, KeymapJson.write(old));
            assertTrue(maps.commandBindings(registry, "new.action").isEmpty());
            maps.assignMissing(registry, SUPPLEMENTS);
            assertFalse(maps.commandBindings(registry, "new.action").isEmpty());
        }
    }

    @Test void defaultsRespectAliasesAndProfilesAndAreResettable() {
        try (var registry = new CommandRegistry()) {
            registry.register(Command.action("save", "Save", "File", () -> {}));
            registry.register(Command.action("new.action", "New action", "File", () -> {}));
            var maps = new KeymapManager();
            var save = maps.bindings("save");
            maps.completeDefaults(registry, SUPPLEMENTS);
            for (var profile : maps.profiles()) {
                maps.select(profile.id());
                assertEquals(List.of(), maps.conflicts());
                assertEquals(SUPPLEMENTS.get("new.action"), maps.bindings("new.action").getFirst().sequence());
            }
            maps.select("intellij-macos");
            assertEquals(save, maps.bindings("save"));
            maps.replaceBindings("new.action", List.of());
            assertTrue(maps.resetCommand(registry, "new.action").applied());
            assertFalse(maps.bindings("new.action").isEmpty());
        }
    }

    @Test void migrationPreservesCustomBindingsAndExplicitUnbindingAndReportsPrefixConflicts() {
        try (var registry = new CommandRegistry()) {
            registry.register(Command.action("save", "Save", "File", () -> {}));
            registry.register(Command.action("new.action", "New action", "File", () -> {}));
            var old = new KeymapManager();
            old.replaceBindings("save", List.of(KeyBinding.of("CTRL+META+K")));
            var maps = new KeymapManager();
            maps.completeDefaults(registry, SUPPLEMENTS);
            KeymapJson.read(maps, KeymapJson.write(old));
            assertEquals(old.bindings("save"), maps.bindings("save"));
            assertTrue(maps.bindings("new.action").isEmpty());
            assertTrue(maps.migrationNotices().stream().anyMatch(s -> s.contains("new.action") && s.contains("conflicts")));
            assertFalse(maps.assignMissing(registry, SUPPLEMENTS).isEmpty());
            assertEquals(old.bindings("save"), maps.bindings("save"));
            old.replaceBindings("save", List.of());
            KeymapJson.read(maps, KeymapJson.write(old));
            assertTrue(maps.bindings("save").isEmpty(), "an explicit unbind is preserved on import");
            assertFalse(maps.bindings("new.action").isEmpty(), "new safe default is merged");
            assertTrue(maps.assignMissing(registry, SUPPLEMENTS).isEmpty());
            assertEquals(SUPPLEMENTS.get("save"), maps.bindings("save").getFirst().sequence());
        }
    }

    @Test void disabledChordIsConsumedAndExplainedAndBadSuffixNeverBecomesText() {
        try (var registry = new CommandRegistry()) {
            registry.register(Command.action("new.action", "New action", "File", () -> fail("disabled action ran")).requiring("editor"));
            var maps = new KeymapManager();
            maps.completeDefaults(registry, SUPPLEMENTS);
            var resolver = new ShortcutResolver(maps, registry, 100);
            var context = CommandContext.of("console");
            assertEquals(ShortcutResolver.Kind.PENDING, resolver.accept(KeyStrokeSpec.parse("CTRL+META+K"), context, 1).kind());
            assertEquals(ShortcutResolver.Kind.PENDING, resolver.accept(KeyStrokeSpec.parse("N"), context, 2).kind());
            var resolved = resolver.accept(KeyStrokeSpec.parse("A"), context, 3);
            assertEquals("new.action", resolved.command());
            assertFalse(registry.availability(resolved.command(), context).enabled());
            resolver.accept(KeyStrokeSpec.parse("CTRL+META+K"), context, 4);
            assertEquals(ShortcutResolver.Kind.CANCELLED, resolver.accept(KeyStrokeSpec.parse("Z"), context, 5).kind());
            resolver.accept(KeyStrokeSpec.parse("CTRL+META+K"), context, 6);
            assertEquals(ShortcutResolver.Kind.CANCELLED, resolver.accept(KeyStrokeSpec.parse("ESCAPE"), context, 7).kind());
            resolver.accept(KeyStrokeSpec.parse("CTRL+META+K"), context, 8);
            assertEquals(ShortcutResolver.Kind.CANCELLED, resolver.accept(KeyStrokeSpec.parse("N"), context, 200).kind());
            assertEquals(ShortcutResolver.Kind.NONE, resolver.accept(KeyStrokeSpec.parse("X"), context, 201).kind());
        }
    }
}
