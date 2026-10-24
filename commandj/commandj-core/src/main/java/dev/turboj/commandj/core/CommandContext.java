package dev.turboj.commandj.core;

import java.util.*;

/** Immutable context snapshot; attribute values are owned by the caller and should be immutable. */
public record CommandContext(Set<String> tags, Map<String, Object> attributes) {
    public CommandContext {
        tags = Set.copyOf(tags);
        attributes = Map.copyOf(attributes);
    }

    public static CommandContext of(String... tags) {
        return new CommandContext(Set.of(tags), Map.of());
    }

    public boolean has(String tag) {
        return tags.contains(tag);
    }

    public <T> Optional<T> value(String key, Class<T> type) {
        Object value = attributes.get(key);
        return type.isInstance(value) ? Optional.of(type.cast(value)) : Optional.empty();
    }
}
