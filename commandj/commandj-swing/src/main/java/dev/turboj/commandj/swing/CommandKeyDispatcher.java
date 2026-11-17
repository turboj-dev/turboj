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
    private Component lastFocus;

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
        if (lastFocus != focus) { resolver.reset(); lastFocus = focus; }

        // A handled press may open an overlay, menu or move focus before its typed
        // event arrives. Consume that event in our window before applying the new
        // context's guards. A fresh press always belongs to the new focus owner.
        if (event.getID() == KeyEvent.KEY_TYPED) {
            boolean suppress = suppressedTyped;
            suppressedTyped = false;
            if (suppress && focus != null && SwingUtilities.getWindowAncestor(focus) == window && window.isActive()) {
                event.consume();
                return true;
            }
            return false;
        }
        if (event.getID() == KeyEvent.KEY_RELEASED || event.getID() == KeyEvent.KEY_PRESSED)
            suppressedTyped = false;

        if (OverlayHost.active(window) || MenuSelectionManager.defaultManager().getSelectedPath().length != 0
                || focus == null || focus instanceof JPasswordField || SwingUtilities.getWindowAncestor(focus) != window || !window
                .isActive()) {
            reset();
            return false;
        }
        if (event.getID() != KeyEvent.KEY_PRESSED || event.isConsumed())
            return false;
        var stroke = SwingKeys.fromEvent(event);

        if (stroke.isEmpty())
            return false;
        CommandContext snapshot = context.get();
        if (snapshot.has("input-method")) { reset(); return false; }
        var outcome = resolver.accept(stroke.get(), snapshot, System.nanoTime());

        if (outcome.kind() == ShortcutResolver.Kind.NONE) return false;
        // Set before callbacks: a modal action can enter a nested AWT event loop.
        // Do not re-arm after it returns, or the next ordinary character is lost.
        suppressedTyped = true;
        event.consume();

        switch (outcome.kind()) {
            case NONE -> {
                return false;
            }
            case PENDING -> {
                notice.accept(outcome.sequence().display() + " …  (Escape cancels; 10 seconds per key)");
            }
            case CANCELLED -> notice.accept("Shortcut cancelled.");
            case CONFLICT -> {
                notice.accept("Conflicting shortcuts: " + String.join(", ", outcome.candidates()));
            }
            case MATCH -> {
                shortcut.accept(outcome.command(), outcome.sequence());
                registry.execute(outcome.command(), snapshot).stage().thenAccept(r -> {
                    if (r.status() != CommandResult.Status.SUCCEEDED)
                        SwingUtilities.invokeLater(() -> notice.accept(r.message()));
                });
            }
        }
        return true;
    }

    @Override
    public void close() {
        KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(this);
        reset();
    }
}
