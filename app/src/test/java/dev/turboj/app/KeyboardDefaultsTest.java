package dev.turboj.app;

import dev.turboj.commandj.core.*;
import dev.turboj.commandj.keymap.*;
import dev.turboj.help.CheatSheet;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class KeyboardDefaultsTest {
    @Test void everySupplementResolvesAndTheFourPageSheetNeverDropsAnAction() {
        try (var registry = new CommandRegistry()) {
            for (String id : KeyboardDefaults.SUPPLEMENTS.keySet())
                registry.register(Command.action(id, "Action " + id, "Category", () -> fail("The routing audit must not execute actions")));
            var maps = new KeymapManager();
            maps.completeDefaults(registry, KeyboardDefaults.SUPPLEMENTS);
            assertTrue(registry.commands().size() >= 309);
            for (var profile : maps.profiles()) {
                maps.select(profile.id());
                assertTrue(maps.conflicts().isEmpty(), profile.id());
                var resolver = new ShortcutResolver(maps, registry);
                for (var command : registry.commands()) {
                    var bindings = maps.commandBindings(registry, command.id());
                    assertFalse(bindings.isEmpty(), profile.id() + ": " + command.id());
                    for (var binding : bindings) if (binding.sequence().strokes().getFirst().equals(KeyStrokeSpec.parse(KeyboardDefaults.PREFIX))) {
                        ShortcutResolver.Resolution result = null;
                        for (var stroke : binding.sequence().strokes()) result = resolver.accept(stroke, CommandContext.of("editor"), System.nanoTime());
                        assertEquals(command.id(), result.command(), profile.id());
                    }
                }
                String html = CheatSheet.html(registry, maps.bindings(), profile.id(), profile.name());
                assertEquals(4, html.split("<section class=\"page\">", -1).length - 1);
                for (var command : registry.commands())
                    assertEquals(1, html.split("data-command=\"" + java.util.regex.Pattern.quote(command.id()) + "\"", -1).length - 1, command.id());
            }
        }
    }
}
