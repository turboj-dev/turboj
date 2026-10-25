package dev.turboj.commandj.keymap;

import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Bounded, validated JSON. No executable expressions or arbitrary object deserialization. */
public final class KeymapJson {
    private static final int MAX_BYTES = 1_048_576;

    private KeymapJson() {}

    public static String write(KeymapManager manager) {
        JsonObject root = new JsonObject();
        root.addProperty("schemaVersion", 1);
        root.addProperty("selected", manager.selectedId());
        root.addProperty("preserveConflicts", manager.hasStoredConflicts());
        JsonArray profiles = new JsonArray();

        for (var profile : manager.profiles()) {
            JsonObject p = new JsonObject();
            p.addProperty("id", profile.id());
            p.addProperty("name", profile.name());
            if (profile.parentId() != null)
                p.addProperty("parent", profile.parentId());
            JsonObject bindings = new JsonObject();
            profile.overrides().forEach((id, values) -> {
                JsonArray b = new JsonArray();

                for (var binding : values) {
                    JsonObject v = new JsonObject();
                    v.addProperty("keys", binding.sequence().external());
                    JsonArray required = new JsonArray();
                    new TreeSet<> (binding.context().required()).forEach(required::add);
                    v.add("required", required);
                    JsonArray forbidden = new JsonArray();
                    new TreeSet<> (binding.context().forbidden()).forEach(forbidden::add);
                    v.add("forbidden", forbidden);
                    b.add(v);
                }
                bindings.add(id, b);
            });
            p.add("bindings", bindings);
            profiles.add(p);
        }
        root.add("profiles", profiles);
        return new GsonBuilder().setPrettyPrinting().create().toJson(root) + "\n";
    }

    public static void read(KeymapManager manager, String text) {
        if (text.length() > MAX_BYTES)
            throw new IllegalArgumentException("Keymap exceeds 1 MiB");
        try {
            JsonObject root = JsonParser.parseString(text).getAsJsonObject();

            if (root.get("schemaVersion").getAsInt() != 1)
                throw new IllegalArgumentException("Unsupported keymap schema");
            JsonArray array = root.getAsJsonArray("profiles");

            if (array.size() > 128)
                throw new IllegalArgumentException("Too many keymaps");
            var profiles = new ArrayList<KeymapProfile> ();
            var ids = new HashSet<String> ();

            for (JsonElement element : array) {
                JsonObject p = element.getAsJsonObject();
                String id = p.get("id").getAsString();

                if (!ids.add(id))
                    throw new IllegalArgumentException("Duplicate keymap identity");
                var bindings = new TreeMap<String, List<KeyBinding>> ();
                JsonObject values = p.getAsJsonObject("bindings");

                if (values.size() > 4096)
                    throw new IllegalArgumentException("Too many commands");
                for (var entry : values.entrySet()) {
                    JsonArray keys = entry.getValue().getAsJsonArray();

                    if (keys.size() > 16)
                        throw new IllegalArgumentException("Too many bindings");
                    var b = new ArrayList<KeyBinding> ();

                    for (var value : keys) {
                        JsonObject k = value.getAsJsonObject();
                        b.add(new KeyBinding(KeySequence.parse(k.get("keys").getAsString()),
                                new BindingContext(tags(k, "required"), tags(k, "forbidden"))));
                    }
                    bindings.put(entry.getKey(), b);
                }
                profiles.add(new KeymapProfile(id, p.get("name").getAsString(), p.has("parent") ? p
                        .get("parent").getAsString() : null, bindings, false));
            }
            manager.install(profiles, root.get("selected").getAsString(), root.has("preserveConflicts")
                    && root.get("preserveConflicts").getAsBoolean());
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException malformed) {
            throw new IllegalArgumentException("Malformed keymap JSON", malformed);
        }
    }

    private static Set<String> tags(JsonObject obj, String name) {
        var result = new TreeSet<String> ();

        if (!obj.has(name))
            return result;
        JsonArray array = obj.getAsJsonArray(name);

        if (array.size() > 32)
            throw new IllegalArgumentException("Too many context tags");
        for (var value : array) {
            String tag = value.getAsString();

            if (!tag.matches("[A-Za-z0-9_.-]{1,80}"))
                throw new IllegalArgumentException("Invalid context tag");
            result.add(tag);
        }
        return result;
    }

    public static void load(Path path, KeymapManager manager) throws IOException {
        if (Files.size(path) > MAX_BYTES)
            throw new IOException("Keymap exceeds 1 MiB");
        try {
            read(manager, Files.readString(path));
        } catch (IllegalArgumentException invalid) {
            throw new IOException(invalid.getMessage(), invalid);
        }
    }

    public static void save(Path path, KeymapManager manager) throws IOException {
        save(path, write(manager));
    }

    public static void save(Path path, String snapshot) throws IOException {
        Path absolute = path.toAbsolutePath();
        Files.createDirectories(absolute.getParent());
        Path temporary = Files.createTempFile(absolute.getParent(), ".commandj-", ".json");

        try {
            Files.writeString(temporary, snapshot);
            Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
