package dev.turboj.commandj.swing;

import dev.turboj.commandj.core.*;
import java.awt.event.ActionEvent;
import java.util.Objects;
import java.util.function.*;
import javax.swing.*;

/** All menu activation goes through the same registry as shortcut/palette activation. */
public final class CommandAction extends AbstractAction {
    private final CommandRegistry registry;
    private final String id;
    private final Supplier<CommandContext> context;
    private final Consumer<CommandResult> finished;

    public CommandAction(CommandRegistry registry, String id, Supplier<CommandContext> context,
            Consumer<CommandResult> finished) {
        super(registry.find(id).orElseThrow().title());
        this.registry = registry;
        this.id = id;
        this.context = context;
        this.finished = Objects.requireNonNull(finished);
        putValue(ACTION_COMMAND_KEY, id);
    }

    @Override
    public boolean isEnabled() {
        return super.isEnabled() && registry.availability(id, context.get()).enabled();
    }

    public void refresh() {
        var availability = registry.availability(id, context.get());
        setEnabled(availability.enabled());
        putValue(SHORT_DESCRIPTION, availability.enabled() ? registry.find(id).orElseThrow()
                .description() : availability.reason());
    }

    @Override
    public void actionPerformed(ActionEvent event) {
        if (!SwingUtilities.isEventDispatchThread())
            throw new IllegalStateException("Swing commands require EDT");
        registry.execute(id, context.get()).stage().whenComplete((result, error) -> SwingUtilities
                .invokeLater(() -> finished.accept(error == null ? result : CommandResult
                .failure(error.getMessage()))));
    }
}
