package dev.turboj.commandj.swing;

import dev.turboj.commandj.core.*;
import dev.turboj.commandj.keymap.*;
import java.awt.*;
import java.awt.event.*;
import java.util.function.*;
import javax.swing.*;

/** Window-scoped logical-key adapter. Dialogs and input-method composition retain their events. */
public final class CommandKeyDispatcher implements KeyEventDispatcher, AutoCloseable {
    private final Window window;
    private final CommandRegistry registry;
    private final ShortcutResolver resolver;
    private final Supplier<CommandContext> context;
    private final Consumer<String> notice;
    private final BiConsumer<String, KeySequence> shortcut;
    private boolean suppressedTyped;

    public CommandKeyDispatcher(Window window, CommandRegistry registry, KeymapManager maps,
            Supplier<CommandContext> context, Consumer<String> notice) {
        this(window, registry, maps, context, notice, (command, sequence) -> {});
    }

    /** Observes resolved keyboard commands only; ordinary typing and dialog input are excluded. */
    public CommandKeyDispatcher(Window window, CommandRegistry registry, KeymapManager maps,
            Supplier<CommandContext> context, Consumer<String> notice,
            BiConsumer<String, KeySequence> shortcut) {
        this.window = window;
        this.registry = registry;
        this.resolver = new ShortcutResolver(maps, registry);
        this.context = context;
        this.notice = notice;
        this.shortcut = java.util.Objects.requireNonNull(shortcut);
        KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(this);
    }

    public void reset() {
        resolver.reset();
        suppressedTyped = false;
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        Component focus = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner();

        if (OverlayHost.active(window) || focus == null || focus instanceof JPasswordField || SwingUtilities.getWindowAncestor(focus) != window || !window
                .isActive()) {
            reset();
            return false;
        }
        if (event.getID() == KeyEvent.KEY_RELEASED) {
            suppressedTyped = false;
            return false;
        }
        if (event.getID() == KeyEvent.KEY_TYPED) {
            if (suppressedTyped) {
                suppressedTyped = false;
                event.consume();
                return true;
            }
            return false;
        }
        if (event.getID() != KeyEvent.KEY_PRESSED || event.isConsumed())
            return false;
        // Suppression belongs to this press, not to another key still being held down.
        suppressedTyped = false;
        var stroke = SwingKeys.fromEvent(event);

        if (stroke.isEmpty())
            return false;
        var outcome = resolver.accept(stroke.get(), context.get(), System.nanoTime());

        switch (outcome.kind()) {
            case NONE -> {
                return false;
            }
            case PENDING -> {
                notice.accept(stroke.get().display() + " …");
            }
            case CONFLICT -> {
                notice.accept("Conflicting shortcuts: " + String.join(", ", outcome.candidates()));
            }
            case MATCH -> {
                shortcut.accept(outcome.command(), outcome.sequence());
                registry.execute(outcome.command(), context.get()).stage().thenAccept(r -> {
                    if (r.status() != CommandResult.Status.SUCCEEDED)
                        SwingUtilities.invokeLater(() -> notice.accept(r.message()));
                });
            }
        }
        suppressedTyped = true;
        event.consume();
        return true;
    }

    @Override
    public void close() {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(this);
        reset();
    }
}
