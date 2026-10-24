package dev.turboj.commandj.core;

import java.util.concurrent.*;

public final class CommandExecution {
    private final String commandId;
    private final CancellationToken token;
    private final CompletableFuture<CommandResult> result;
    private final boolean cancellable;

    CommandExecution(String id, CancellationToken token, CompletableFuture<CommandResult> result,
            boolean cancellable) {
        this.commandId = id;
        this.token = token;
        this.result = result;
        this.cancellable = cancellable;
    }

    public String commandId() {
        return commandId;
    }

    public CompletionStage<CommandResult> stage() {
        return result.minimalCompletionStage();
    }

    public boolean cancel() {
        if (!cancellable || result.isDone())
            return false;
        return token.cancel();
    }

    public boolean isDone() {
        return result.isDone();
    }
}
