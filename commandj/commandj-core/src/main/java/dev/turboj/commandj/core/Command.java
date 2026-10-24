package dev.turboj.commandj.core;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

/** Immutable executable descriptor. No UI classes or semantic-engine dependencies. */
public record Command(String id, String title, String description, String category,
        List<String> aliases, Set<String> requiredContexts, boolean cancellable, String undoLabel,
        Execution execution, Function<CommandContext, Availability> availability, Handler handler) {
    public enum Execution {
        CALLER,
        BACKGROUND
    }

    @FunctionalInterface
    public interface Handler {
        CompletionStage<CommandResult> execute(CommandContext context,
                CancellationToken cancellation) throws Exception;
    }

    public Command {
        if (id == null || id.isBlank() || title == null || title.isBlank())
            throw new IllegalArgumentException("Command ID/title required");
        description = Objects.requireNonNullElse(description, "");
        category = Objects.requireNonNullElse(category, "General");
        aliases = List.copyOf(aliases);
        requiredContexts = Set.copyOf(requiredContexts);
        undoLabel = Objects.requireNonNullElse(undoLabel, "");
        Objects.requireNonNull(execution);
        Objects.requireNonNull(availability);
        Objects.requireNonNull(handler);
    }

    public static Command of(String id, String title, String category, Handler handler) {
        return new Command(id, title, "", category, List.of(), Set.of(), false, "",
                Execution.CALLER, c -> Availability.available(), handler);
    }

    public static Command action(String id, String title, String category, Runnable handler) {
        return of(id, title, category, (c, t) -> {
            handler.run();
            return CompletableFuture.completedFuture(CommandResult.success());
        });
    }

    public Command withAliases(String... values) {
        return new Command(id, title, description, category, List.of(values), requiredContexts,
                cancellable, undoLabel, execution, availability, handler);
    }

    public Command when(Function<CommandContext, Availability> predicate) {
        return new Command(id, title, description, category, aliases, requiredContexts, cancellable,
                undoLabel, execution, predicate, handler);
    }

    public Command requiring(String... tags) {
        return new Command(id, title, description, category, aliases, Set.of(tags), cancellable,
                undoLabel, execution, availability, handler);
    }

    public Command inBackground(boolean cancel) {
        return new Command(id, title, description, category, aliases, requiredContexts, cancel,
                undoLabel, Execution.BACKGROUND, availability, handler);
    }

    public Availability available(CommandContext context) {
        for (String required : new TreeSet<> (requiredContexts))
            if (!context.has(required))
                return Availability.disabled("Requires " + required);
        try {
            return Objects.requireNonNull(availability.apply(context));
        } catch (RuntimeException failure) {
            return Availability.disabled("Availability check failed");
        }
    }
}
