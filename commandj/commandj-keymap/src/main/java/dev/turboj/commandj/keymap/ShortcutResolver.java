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

    public record Resolution(Kind kind, String command, List<String> candidates, KeySequence sequence) {
        public Resolution {
            candidates = List.copyOf(candidates);
        }

        public Resolution(Kind kind, String command, List<String> candidates) {
            this(kind, command, candidates, null);
        }
    }

    private final KeymapManager maps;
    private final CommandRegistry registry;
    private final long timeoutNanos;
    private final ArrayList<KeyStrokeSpec> prefix = new ArrayList<> ();
    private long last, revision = -1;
    private long registryRevision = -1;
    private Node root = new Node();
    private record Candidate(String command, KeyBinding binding) {}
    private static final class Node {
        final Map<KeyStrokeSpec, Node> children = new HashMap<>();
        final List<Candidate> candidates = new ArrayList<>();
    }

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
        if (revision != maps.revision() || registryRevision != registry.revision()) {
            rebuild();
            reset();
        }
        if (now - last > timeoutNanos || context.has("input-method")) {
            reset();
        }
        if (context.has("input-method")) return new Resolution(Kind.NONE, null, List.of());
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
        if (result.kind() != Kind.NONE)
            result = new Resolution(result.kind(), result.command(), result.candidates(), new KeySequence(prefix));
        if (result.kind() != Kind.PENDING)
            reset();
        return result;
    }

    private void rebuild() {
        root = new Node();
        maps.bindings().forEach((id, bindings) -> {
            if (registry.find(id).isEmpty()) return;
            for (KeyBinding binding : bindings) {
                Node node = root;
                Candidate candidate = new Candidate(registry.canonicalId(id), binding);
                for (KeyStrokeSpec stroke : binding.sequence().strokes()) {
                    node = node.children.computeIfAbsent(stroke, ignored -> new Node());
                    node.candidates.add(candidate);
                }
            }
        });
        revision = maps.revision();
        registryRevision = registry.revision();
    }

    private Resolution resolve(CommandContext context) {
        var matches = new TreeSet<String> ();
        var longer = new TreeSet<String> ();
        Node node = root;
        for (KeyStrokeSpec key : prefix) {
            node = node.children.get(key);
            if (node == null) return new Resolution(Kind.NONE, null, List.of());
        }
        for (Candidate candidate : node.candidates) {
            var binding = candidate.binding();
            if (!binding.context().matches(context)
                    || !registry.availability(candidate.command(), context).enabled()) continue;
            if (binding.sequence().strokes().size() == prefix.size()) matches.add(candidate.command());
            else longer.add(candidate.command());
        }
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
