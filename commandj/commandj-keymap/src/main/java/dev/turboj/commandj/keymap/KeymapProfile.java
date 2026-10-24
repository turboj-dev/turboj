package dev.turboj.commandj.keymap;

import java.util.*;

/** Overrides replace the parent's bindings for a command; an empty list explicitly unbinds it. */
public record KeymapProfile(String id, String name, String parentId, Map<String,
        List<KeyBinding>> overrides, boolean bundled) {
    public KeymapProfile {
        if (id == null || !id.matches("[A-Za-z0-9_.-]{1,100}") || name == null || name.isBlank()
                || name.length() > 120)
            throw new IllegalArgumentException("Invalid keymap identity");
        var copy = new TreeMap<String, List<KeyBinding>> ();
        overrides.forEach((k, v) -> {
            if (k == null || k.isBlank() || k.length() > 160 || v.size() > 16)
                throw new IllegalArgumentException("Invalid binding");
            copy.put(k, List.copyOf(v));
        });
        overrides = Collections.unmodifiableMap(copy);
    }

    public KeymapProfile with(String command, List<KeyBinding> bindings) {
        var next = new TreeMap<> (overrides);
        next.put(command, List.copyOf(bindings));
        return new KeymapProfile(id, name, parentId, next, bundled);
    }

    public KeymapProfile reset(String command) {
        var next = new TreeMap<> (overrides);
        next.remove(command);
        return new KeymapProfile(id, name, parentId, next, bundled);
    }
}
