package dev.turboj.help;

import static org.junit.jupiter.api.Assertions.*;
import dev.turboj.commandj.core.Command;
import dev.turboj.commandj.core.CommandRegistry;
import dev.turboj.commandj.keymap.KeyBinding;
import dev.turboj.commandj.keymap.KeymapManager;
import java.util.List;
import org.junit.jupiter.api.Test;

class CheatSheetTest {
    @Test void showsTheUsersOwnBindingsAndLeavesOutCommandsWithoutOne() {
        try (var registry = new CommandRegistry()) {
            for (String id : List.of("smart.action", "run", "refactor.rename", "palette", "tests.stop"))
                registry.register(Command.action(id, "Title of " + id, "Test", () -> {}));
            var maps = new KeymapManager();
            maps.select("turboj-macos");
            var bindings = new java.util.HashMap<>(maps.bindings());
            bindings.put("refactor.rename", List.of(KeyBinding.of("SHIFT+F6")));
            String svg = CheatSheet.html(registry, bindings, "turboj-macos", "TurboJ macOS");
            assertTrue(svg.contains("aria-label=\"⌘\"><path d="), "key symbols print as outlines, independent of the font");
            String html = svg.replaceAll("<svg class=\"k\"[^>]*aria-label=\"(.)\"><path d=\"[^\"]*\"/></svg>", "$1");

            assertTrue(html.contains("Title of smart.action</span><span class=\"keys\"><kbd>⌘↩</kbd>"));
            assertTrue(html.contains("<kbd>⌘R</kbd><i>or</i><kbd>⌃F9</kbd>"), "both the Mac and the Turbo C binding");
            assertTrue(html.contains("Title of refactor.rename</span><span class=\"keys\"><kbd>⇧F6</kbd>"), "the user's override, not the default");
            assertFalse(html.contains("Title of tests.stop"), "a command without a shortcut stays in Find Action");
            assertTrue(html.contains("Everything else: Find Action <kbd>⇧⌘A</kbd>"));
            assertFalse(html.contains("How the keys work") || html.contains("On the Mac"), "only shortcuts, no explanation boxes");
            assertTrue(html.contains("Document start</span><span class=\"keys\"><kbd>⌘↑</kbd>"), "keys the editor handles itself");
            assertTrue(html.contains("Next field</span><span class=\"keys\"><kbd>⇥</kbd>"), "inline template keys");
            assertTrue(html.contains("@font-face{font-family:'JetBrains Mono'"), "fonts are embedded for printing");
            assertEquals(2, html.split("<section class=\"page\">", -1).length - 1, "two A4 pages");
            assertTrue(html.contains("size:A4 landscape"));

            var eclipseBindings = new java.util.HashMap<>(maps.bindingsForProfile("eclipse-macos"));
            eclipseBindings.put("run", List.of(KeyBinding.of("META+UP")));
            String eclipse = CheatSheet.html(registry, eclipseBindings, "eclipse-macos", "Eclipse macOS")
                    .replaceAll("<svg class=\"k\"[^>]*aria-label=\"(.)\"><path d=\"[^\"]*\"/></svg>", "$1");
            assertFalse(eclipse.contains("Document start</span>"), "a native key the profile gives to a command is not promised");
        }
    }
}
