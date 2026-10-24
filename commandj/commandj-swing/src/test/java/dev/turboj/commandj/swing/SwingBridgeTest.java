package dev.turboj.commandj.swing;

import dev.turboj.commandj.core.*;
import dev.turboj.commandj.keymap.*;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.event.*;
import java.util.concurrent.atomic.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SwingBridgeTest {
    @Test
    void logicalKeysUseAwtModifiersAndIgnoreInputMethodKeys() {
        JPanel c = new JPanel();
        KeyEvent key = new KeyEvent(c, KeyEvent.KEY_PRESSED, 1, InputEvent.META_DOWN_MASK
                | InputEvent.ALT_DOWN_MASK, KeyEvent.VK_L, 'l');
        assertEquals(KeyStrokeSpec.parse("META+ALT+L"), SwingKeys.fromEvent(key).orElseThrow());
        assertEquals(KeyStroke.getKeyStroke("meta alt L"), SwingKeys.toSwing(SwingKeys
                .fromEvent(key).orElseThrow()));
        assertTrue(SwingKeys.fromEvent(new KeyEvent(c, KeyEvent.KEY_PRESSED, 1, 0,
                KeyEvent.VK_DEAD_ACUTE, KeyEvent.CHAR_UNDEFINED)).isEmpty());
        assertTrue(SwingKeys.fromEvent(new KeyEvent(c, KeyEvent.KEY_PRESSED, 1,
                InputEvent.ALT_GRAPH_DOWN_MASK, KeyEvent.VK_Q, '@')).isEmpty());
    }

    @Test
    void legacyBindingsRetainMeaning() {
        assertEquals(KeySequence.parse("META+SHIFT+S"), SwingKeys.parseLegacy("meta shift S"));
    }

    @Test
    void menuDispatchUsesRegistryAndKeepsToggleMetadata() throws Exception {
        try (var registry = new CommandRegistry()) {
            var executed = new AtomicInteger();
            registry.register(Command.action("toggle", "Toggle", "Options",
                    executed::incrementAndGet));
            SwingUtilities.invokeAndWait(() -> {
                var action = new CommandAction(registry, "toggle", () -> CommandContext.of(),
                        r -> {});
                action.putValue(Action.SELECTED_KEY, true);
                new JCheckBoxMenuItem(action).doClick(0);
                assertEquals(1, executed.get());
                assertEquals(List.of("toggle"), registry.recentCommands());
                assertEquals(Boolean.FALSE, action.getValue(Action.SELECTED_KEY));
            });
        }
    }
}
