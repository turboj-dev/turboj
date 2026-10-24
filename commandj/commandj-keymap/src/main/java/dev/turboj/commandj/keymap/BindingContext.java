package dev.turboj.commandj.keymap;

import dev.turboj.commandj.core.CommandContext;
import java.util.*;

/** Conjunction of required/forbidden tags; overlapping contexts can be detected exactly. */
public record BindingContext(Set<String> required, Set<String> forbidden) {
    public BindingContext {
        required = Set.copyOf(required);
        forbidden = Set.copyOf(forbidden);
        if (!Collections.disjoint(required, forbidden))
            throw new IllegalArgumentException("Contradictory binding context");
    }

    public static BindingContext any() {
        return new BindingContext(Set.of(), Set.of());
    }

    public static BindingContext requiring(String... tags) {
        return new BindingContext(Set.of(tags), Set.of());
    }

    public boolean matches(CommandContext context) {
        return context.tags().containsAll(required) && Collections.disjoint(context.tags(),
                forbidden);
    }

    public boolean overlaps(BindingContext other) {
        return Collections.disjoint(required, other.forbidden) && Collections.disjoint(forbidden,
                other.required);
    }
}
