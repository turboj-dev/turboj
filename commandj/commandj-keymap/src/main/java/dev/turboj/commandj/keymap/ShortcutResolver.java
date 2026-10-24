package dev.turboj.commandj.keymap;

import dev.turboj.commandj.core.*;
import java.util.*;

/** One event-driven chord state machine, no polling or timer per key. Call from one event thread. */
public final class ShortcutResolver {
    public enum Kind {
        NONE,
        PENDING,
        MATCH,
        CONFLICT
    }

    public record Resolution(Kind kind, String command, List<String> candidates) {
        public Resolution {
            candidates = List.copyOf(candidates);
        }
    }

    private final KeymapManager maps;
    private final CommandRegistry registry;
    private final long timeoutNanos;
    private final ArrayList<KeyStrokeSpec> prefix = new ArrayList<> ();
    private long last, revision = -1;

    public ShortcutResolver(KeymapManager maps, CommandRegistry registry) {
        this(maps, registry, 1_500_000_000L);
    }

    public ShortcutResolver(KeymapManager maps, CommandRegistry registry, long timeoutNanos) {
        this.maps = Objects.requireNonNull(maps);
        this.registry = Objects.requireNonNull(registry);
        if (timeoutNanos <= 0)
            throw new IllegalArgumentException("Timeout must be positive");
        this.timeoutNanos = timeoutNanos;
    }

    public void reset() {
        prefix.clear();
    }

    public boolean pending() {
        return !prefix.isEmpty();
    }

    public Resolution accept(KeyStrokeSpec stroke, CommandContext context, long now) {
        if (revision != maps.revision() || now - last > timeoutNanos) {
            reset();
            revision = maps.revision();
        }
        if (stroke.key().equals("ESCAPE") && pending()) {
            reset();
            return new Resolution(Kind.NONE, null, List.of());
        }
        last = now;
        prefix.add(stroke);
        Resolution result = resolve(context);

        if (result.kind() == Kind.NONE && prefix.size() > 1) {
            reset();
            prefix.add(stroke);
            result = resolve(context);
        }
        if (result.kind() != Kind.PENDING)
            reset();
        return result;
    }

    private Resolution resolve(CommandContext context) {
        var matches = new TreeSet<String> ();
        var longer = new TreeSet<String> ();
        maps.bindings().forEach((id, list) -> {
            if (registry.find(id).isEmpty() || !registry.availability(id, context).enabled())
                return;
            for (var b : list)
                if (b.context().matches(context) && b.sequence().startsWith(prefix)) {
                    if (b.sequence().strokes().size() == prefix.size())
                        matches.add(id);
                    else
                        longer.add(id);
                }
        });
        if (matches.size() > 1 || !matches.isEmpty() && !longer.isEmpty()) {
            matches.addAll(longer);
            return new Resolution(Kind.CONFLICT, null, List.copyOf(matches));
        }
        if (matches.size() == 1)
            return new Resolution(Kind.MATCH, matches.first(), List.copyOf(matches));
        if (!longer.isEmpty())
            return new Resolution(Kind.PENDING, null, List.copyOf(longer));
        return new Resolution(Kind.NONE, null, List.of());
    }
}
