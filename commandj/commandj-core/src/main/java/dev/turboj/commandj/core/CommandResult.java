package dev.turboj.commandj.core;

import java.util.Objects;

public record CommandResult(Status status, String message) {
    public enum Status {
        SUCCEEDED,
        DISABLED,
        CANCELLED,
        FAILED
    }

    public CommandResult {
        Objects.requireNonNull(status);
        message = Objects.requireNonNullElse(message, "");
    }

    public static CommandResult success() {
        return new CommandResult(Status.SUCCEEDED, "");
    }

    public static CommandResult failure(String message) {
        return new CommandResult(Status.FAILED, message);
    }

    public static CommandResult cancelled() {
        return new CommandResult(Status.CANCELLED, "Cancelled");
    }
}
