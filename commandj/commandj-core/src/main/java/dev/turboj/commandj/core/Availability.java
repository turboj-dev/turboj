package dev.turboj.commandj.core;

import java.util.Objects;

public record Availability(boolean enabled, String reason) {
    public Availability {
        reason = Objects.requireNonNullElse(reason, "");
        if (!enabled && reason.isBlank())
            throw new IllegalArgumentException("Disabled commands require an explanation");
    }

    public static Availability available() {
        return new Availability(true, "");
    }

    public static Availability disabled(String reason) {
        return new Availability(false, reason);
    }
}
