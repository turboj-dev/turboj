package dev.turboj.commandj.keymap;

import dev.turboj.commandj.core.CommandRegistry;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/** Profile inheritance, persistence and conflict-safe modifications, independent from themes/UI. */
public final class KeymapManager {
    public record Conflict(String command, String other, KeySequence sequence, boolean prefix,
            String reason) {}

    public record Change(boolean applied, List<Conflict> conflicts) {
        public Change {
            conflicts = List.copyOf(conflicts);
        }
    }

    private final LinkedHashMap<String, KeymapProfile> profiles = new LinkedHashMap<> ();
    private final Map<String, KeymapProfile> defaults = new HashMap<> ();
    private final CopyOnWriteArrayList<Runnable> listeners = new CopyOnWriteArrayList<> ();
    private String selected;
    private long revision;
    private Map<String, List<KeyBinding>> cached = Map.of();
    private List<String> migrationNotices = List.of();

    public KeymapManager() {
        for (KeymapProfile p : BundledKeymaps.profiles()) {
            profiles.put(p.id(), p);
            defaults.put(p.id(), p);
        }
        select("intellij-macos");
    }

    public synchronized List<KeymapProfile> profiles() {
        return List.copyOf(profiles.values());
    }

    public synchronized String selectedId() {
        return selected;
    }

    public synchronized KeymapProfile selected() {
        return profiles.get(selected);
    }

    public synchronized long revision() {
        return revision;
    }

    public synchronized Map<String, List<KeyBinding>> bindings() {
        return cached;
    }

    /** Resolve a profile without changing the selected profile or firing listeners. */
    public synchronized Map<String, List<KeyBinding>> bindingsForProfile(String id) {
        return resolve(id, new HashSet<>());
    }

    public synchronized List<String> migrationNotices() { return migrationNotices; }

    public synchronized boolean hasStoredConflicts() {
        return profiles.keySet().stream().anyMatch(id -> !conflicts(resolve(id, new HashSet<>())).isEmpty());
    }

    /** Imports a complete legacy user map as one transaction. User/user conflicts remain visible
     * and unexecutable; only conflicting defaults are removed. Input iteration order is irrelevant. */
    public synchronized void migrateLegacyBindings(Map<String, List<KeyBinding>> imported) {
        var users = new TreeMap<String, List<KeyBinding>>();
        imported.forEach((id, values) -> users.put(id, List.copyOf(values)));
        var all = new TreeMap<>(cached);
        all.putAll(users);
        var notices = new ArrayList<String>();
        for (var entry : cached.entrySet()) {
            if (users.containsKey(entry.getKey())) continue;
            var retained = entry.getValue().stream().filter(existing -> users.values().stream().flatMap(List::stream)
                    .noneMatch(user -> overlap(existing, user))).toList();
            if (!retained.equals(entry.getValue())) {
                all.put(entry.getKey(), retained);
                notices.add("Legacy migration: retained user binding; removed conflicting default for " + entry.getKey());
            }
        }
        KeymapProfile next = selected();
        for (var entry : all.entrySet()) next = next.with(entry.getKey(), entry.getValue());
        profiles.put(selected, next);
        for (var conflict : conflicts(all)) notices.add("Legacy user bindings conflict: " + conflict.command()
                + " / " + conflict.other() + "; resolve explicitly in Keymap Settings.");
        migrationNotices = List.copyOf(notices); changed();
    }

    private static boolean overlap(KeyBinding a, KeyBinding b) {
        return a.context().overlaps(b.context()) && (a.sequence().startsWith(b.sequence().strokes())
                || b.sequence().startsWith(a.sequence().strokes()));
    }

    public synchronized List<KeyBinding> bindings(String command) {
        return cached.getOrDefault(command, List.of());
    }

    public synchronized List<KeyBinding> commandBindings(CommandRegistry registry, String command) {
        return ShortcutReference.bindings(registry, cached, registry.canonicalId(command));
    }

    /** An explicit edit replaces the action's canonical and historical alias bindings together. */
    public synchronized Change replaceCommandBindings(CommandRegistry registry, String command,
            List<KeyBinding> values, Set<String> authorizedCommands) {
        String canonical = registry.canonicalId(command);
        if (registry.find(canonical).isEmpty()) throw new IllegalArgumentException("Unknown command: " + command);
        var all = new TreeMap<>(cached);
        var actionIds = new TreeSet<String>();
        actionIds.add(canonical);
        all.keySet().stream().filter(id -> registry.canonicalId(id).equals(canonical)).forEach(actionIds::add);
        actionIds.forEach(id -> all.put(id, List.of()));
        all.put(canonical, List.copyOf(values));
        var collisions = conflicts(all).stream().filter(c -> c.command().equals(canonical) || c.other().equals(canonical)).toList();
        for (var collision : collisions) {
            String other = collision.command().equals(canonical) ? collision.other() : collision.command();
            if (other.equals(canonical) || !authorizedCommands.contains(other)) return new Change(false, collisions);
        }
        KeymapProfile next = selected();
        for (String id : actionIds) next = next.with(id, id.equals(canonical) ? values : List.of());
        for (String other : authorizedCommands) {
            if (actionIds.contains(other)) continue;
            var retained = all.getOrDefault(other, List.of()).stream().filter(existing -> values.stream()
                    .noneMatch(value -> value.context().overlaps(existing.context())
                            && (value.sequence().startsWith(existing.sequence().strokes())
                            || existing.sequence().startsWith(value.sequence().strokes())))).toList();
            next = next.with(other, retained);
        }
        profiles.put(selected, next); changed(); return new Change(true, List.of());
    }

    public synchronized Change resetCommand(CommandRegistry registry, String command) {
        String canonical = registry.canonicalId(command);
        KeymapProfile before = selected();
        KeymapProfile candidate = before;
        for (String id : before.overrides().keySet())
            if (registry.canonicalId(id).equals(canonical)) candidate = candidate.reset(id);
        KeymapProfile base = defaults.get(before.id());
        if (base != null) for (var entry : base.overrides().entrySet())
            if (registry.canonicalId(entry.getKey()).equals(canonical)) candidate = candidate.with(entry.getKey(), entry.getValue());
        profiles.put(selected, candidate);
        var collisions = conflicts(resolve(selected, new HashSet<>()));
        if (!collisions.isEmpty()) { profiles.put(selected, before); return new Change(false, collisions); }
        changed(); return new Change(true, List.of());
    }

    public synchronized String shortcutText(String command) {
        return String.join(" / ", bindings(command).stream().map(b -> b.sequence().display())
                .toList());
    }

    public AutoCloseable onChange(Runnable r) {
        listeners.add(r);
        return () -> listeners.remove(r);
    }

    private void changed() {
        cached = resolve(selected, new HashSet<> ());
        revision++;
        listeners.forEach(Runnable::run);
    }

    public synchronized void select(String id) {
        if (!profiles.containsKey(id))
            throw new IllegalArgumentException("Unknown keymap: " + id);
        selected = id;
        changed();
    }

    public synchronized KeymapProfile duplicate(String id, String name) {
        if (profiles.containsKey(id))
            throw new IllegalArgumentException("Duplicate keymap");
        var profile = new KeymapProfile(id, name, selected, Map.of(), false);
        profiles.put(id, profile);
        selected = id;
        changed();
        return profile;
    }

    public synchronized void addProfile(KeymapProfile profile) {
        if (profiles.containsKey(profile.id()))
            throw new IllegalArgumentException("Duplicate keymap");
        profiles.put(profile.id(), profile);
        try {
            if (!conflicts(resolve(profile.id(), new HashSet<> ())).isEmpty())
                throw new IllegalArgumentException("Profile has conflicting bindings");
        } catch (RuntimeException invalid) {
            profiles.remove(profile.id());
            throw invalid;
        }
        changed();
    }

    public synchronized Change replaceBindings(String command, List<KeyBinding> values) {
        var all = new TreeMap<> (cached);
        all.put(command, List.copyOf(values));
        List<Conflict> conflicts = conflicts(all).stream().filter(c -> c.command().equals(command)
                || c.other().equals(command)).toList();

        if (!conflicts.isEmpty())
            return new Change(false, conflicts);
        profiles.put(selected, selected().with(command, values));
        changed();
        return new Change(true, List.of());
    }

    /** Explicitly unbind conflicting commands before retrying; never overwrite them implicitly. */
    public synchronized Change resetCommand(String command) {
        KeymapProfile profile = selected();
        KeymapProfile base = defaults.get(profile.id());
        KeymapProfile candidate = base == null ? profile.reset(command) : profile.with(command, base
                .overrides().getOrDefault(command, List.of()));
        profiles.put(selected, candidate);
        Map<String, List<KeyBinding>> resolved = resolve(selected, new HashSet<> ());
        List<Conflict> collisions = conflicts(resolved);

        if (!collisions.isEmpty()) {
            profiles.put(selected, profile);
            return new Change(false, collisions);
        }
        changed();
        return new Change(true, List.of());
    }

    public synchronized Change replaceBindingsResolving(String command, List<KeyBinding> values,
            Set<String> authorizedCommands) {
        var all = new TreeMap<> (cached);
        all.put(command, List.copyOf(values));
        var collisions = conflicts(all).stream().filter(c -> c.command().equals(command) || c
                .other().equals(command)).toList();

        for (var c : collisions) {
            String other = c.command().equals(command) ? c.other() : c.command();

            if (other.equals(command) || !authorizedCommands.contains(other))
                return new Change(false, collisions);
        }
        KeymapProfile next = selected();

        for (String other : authorizedCommands) {
            if (other.equals(command))
                continue;
            var retained = all.getOrDefault(other, List.of()).stream().filter(existing -> values
                    .stream().noneMatch(v -> v.context().overlaps(existing.context()) && (v
                    .sequence().startsWith(existing.sequence().strokes()) || existing.sequence()
                    .startsWith(v.sequence().strokes())))).toList();
            next = next.with(other, retained);
        }
        profiles.put(selected, next.with(command, values));
        changed();
        return new Change(true, List.of());
    }

    public synchronized void resetProfile() {
        KeymapProfile profile = selected();
        profiles.put(selected, defaults.getOrDefault(selected, new KeymapProfile(profile.id(),
                profile.name(), profile.parentId(), Map.of(), false)));
        changed();
    }

    public synchronized List<Conflict> conflicts() {
        return conflicts(cached);
    }

    public synchronized void install(List<KeymapProfile> values, String selected) {
        install(values, selected, false);
    }

    public synchronized void install(List<KeymapProfile> values, String selected, boolean preserveConflicts) {
        var previous = new LinkedHashMap<> (profiles);
        String old = this.selected;
        List<String> oldNotices = migrationNotices;

        try {
            for (KeymapProfile p : values) {
                if (defaults.containsKey(p.id()) && p.parentId() != null)
                    throw new IllegalArgumentException("Bundled parent cannot change");
                profiles.put(p.id(), new KeymapProfile(p.id(), p.name(), p.parentId(), p
                        .overrides(), defaults.containsKey(p.id())));
            }
            for (String id : profiles.keySet())
                resolve(id, new HashSet<> ());
            if (!profiles.containsKey(selected))
                throw new IllegalArgumentException("Unknown selected keymap");
            for (String id : profiles.keySet())
                if (!preserveConflicts && !conflicts(resolve(id, new HashSet<> ())).isEmpty())
                    throw new IllegalArgumentException("Imported keymap has conflicting bindings: "
                            + id);
            // Older full-snapshot exports have no entries for newly added commands. Preserve
            // every existing value (including an explicit empty list) and add safe defaults only.
            var notices = new ArrayList<String>();
            var previousConflicts = new HashSet<Conflict>();
            for (String id : profiles.keySet()) previousConflicts.addAll(conflicts(resolve(id, new HashSet<>())));
            if (!previousConflicts.isEmpty()) notices.add("Stored user shortcut conflicts were preserved; resolve them explicitly in Keymap Settings.");
            for (KeymapProfile imported : values) {
                KeymapProfile bundled = defaults.get(imported.id());
                if (bundled == null) continue;
                for (var entry : bundled.overrides().entrySet()) {
                    KeymapProfile current = profiles.get(imported.id());
                    if (current.overrides().containsKey(entry.getKey())) continue;
                    profiles.put(current.id(), current.with(entry.getKey(), entry.getValue()));
                    boolean collision = profiles.keySet().stream()
                            .flatMap(id -> conflicts(resolve(id, new HashSet<>())).stream())
                            .anyMatch(conflict -> !previousConflicts.contains(conflict));
                    if (collision) {
                        profiles.put(current.id(), current);
                        notices.add(current.name() + ": kept custom bindings; new default for "
                                + entry.getKey() + " was not assigned because it conflicts.");
                    } else notices.add(current.name() + ": added new default for " + entry.getKey());
                }
            }
            migrationNotices = List.copyOf(notices);
            this.selected = selected;
            changed();
        } catch (RuntimeException invalid) {
            profiles.clear();
            profiles.putAll(previous);
            this.selected = old;
            migrationNotices = oldNotices;
            cached = resolve(old, new HashSet<> ());
            throw invalid;
        }
    }

    private Map<String, List<KeyBinding>> resolve(String id, Set<String> visiting) {
        if (!visiting.add(id) || visiting.size() > 32)
            throw new IllegalArgumentException("Cyclic/deep keymap inheritance");
        KeymapProfile p = profiles.get(id);

        if (p == null)
            throw new IllegalArgumentException("Missing parent: " + id);
        var result = new TreeMap<String, List<KeyBinding>> ();

        if (p.parentId() != null)
            result.putAll(resolve(p.parentId(), visiting));
        result.putAll(p.overrides());
        return Collections.unmodifiableMap(result);
    }

    public static List<Conflict> conflicts(Map<String, List<KeyBinding>> bindings) {
        record Entry(String id, KeyBinding value) {}
        var entries = new ArrayList<Entry> ();
        bindings.forEach((id, values) -> values.forEach(v -> entries.add(new Entry(id, v))));
        var result = new ArrayList<Conflict> ();

        for (int i = 0; i < entries.size(); i++)
            for (int j = i + 1; j < entries.size(); j++) {
                var a = entries.get(i);
                var b = entries.get(j);

                if (!a.value.context().overlaps(b.value.context()))
                    continue;
                var x = a.value.sequence();
                var y = b.value.sequence();
                boolean prefix = x.startsWith(y.strokes()) || y.startsWith(x.strokes());

                if (prefix)
                    result.add(new Conflict(a.id, b.id, x, !x.equals(y), a.id
                            .equals(b.id) ? "Duplicate/prefix binding" : "Shortcut overlaps another command"));
            }
        return List.copyOf(result);
    }
}
