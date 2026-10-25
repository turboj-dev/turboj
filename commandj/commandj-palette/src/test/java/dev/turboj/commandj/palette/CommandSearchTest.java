package dev.turboj.commandj.palette;

import dev.turboj.commandj.core.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CommandSearchTest {
    @Test void aliasesShortcutsAndRebindingAreSearchableWithoutRebuildingEveryQuery() {
        try (var registry = new CommandRegistry()) {
            registry.register(Command.action("deleteLine", "Delete Line", "Edit", () -> {})
                    .withAliases("remove row", "clear line").requiring("editor"));
            var revision = new java.util.concurrent.atomic.AtomicLong();
            var keys = new java.util.concurrent.atomic.AtomicReference<>(java.util.List.of("Cmd+BACK_SPACE"));
            var reads = new java.util.concurrent.atomic.AtomicInteger();
            var search = new CommandSearch(registry, revision::get, id -> { reads.incrementAndGet(); return keys.get(); });
            assertEquals("deleteLine", search.search("remove row", CommandContext.of("editor"), 20).getFirst().command().id());
            assertFalse(search.search("⌘BACK_SPACE", CommandContext.of(),20).getFirst().availability().enabled());
            assertEquals(1, reads.get());
            keys.set(java.util.List.of("Ctrl+ALT+F12")); revision.incrementAndGet();
            assertFalse(search.search("Ctrl+ALT+F12", CommandContext.of("editor"),20).isEmpty());
            assertEquals(2, reads.get());
        }
    }
    @Test
    void fuzzyAliasesCategoriesAndDisabledReasons() {
        try (var registry = new CommandRegistry()) {
            registry.register(Command.action("format", "Format Document", "Code", () -> {})
                    .withAliases("beautify", "reformat"));
            registry.register(Command.action("rename", "Rename Symbol", "Refactor", () -> {})
                    .when(c -> Availability.disabled("Select a Java symbol")));
            var search = new CommandSearch(registry);
            assertEquals("format", search.search("fmt doc", CommandContext.of(), 10).getFirst()
                    .command().id());
            assertEquals("format", search.search("beautify", CommandContext.of(), 10).getFirst()
                    .command().id());
            var disabled = search.search("refactor", CommandContext.of(), 10).getFirst();
            assertEquals("Select a Java symbol", disabled.availability().reason());
        }
    }

    @Test
    void recentCommandsAndRegistryInvalidation() {
        try (var registry = new CommandRegistry()) {
            registry.register(Command.action("a", "Alpha", "Test", () -> {}));
            registry.register(Command.action("b", "Beta", "Test", () -> {}));
            var search = new CommandSearch(registry);
            assertEquals("a", search.search("", CommandContext.of(), 10).getFirst().command().id());
            registry.execute("b", CommandContext.of());
            assertEquals("b", search.search("", CommandContext.of(), 10).getFirst().command().id());
            registry.register(Command.action("c", "Charlie", "Test", () -> {}));
            assertEquals("c", search.search("char", CommandContext.of(), 10).getFirst().command()
                    .id());
            registry.unregister("c");
            assertTrue(search.search("char", CommandContext.of(), 10).isEmpty());
        }
    }

    @Test
    void localeAndThreadSchedulingDoNotChangeOrder() {
        Locale previous = Locale.getDefault();

        try (var registry = new CommandRegistry()) {
            registry.register(Command.action("inspect", "Inspect Java", "Test", () -> {}));
            var search = new CommandSearch(registry);
            var baseline = search.search("INS", CommandContext.of(), 10);
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals(baseline, search.search("INS", CommandContext.of(), 10));
        } finally {
            Locale.setDefault(previous);
        }
    }
}
