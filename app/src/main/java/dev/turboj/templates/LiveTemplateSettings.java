package dev.turboj.templates;

import com.google.gson.*;
import java.util.*;

/** Dialog-local draft. Untouched entries and unknown JSON properties survive structured edits. */
final class LiveTemplateSettings {
    private static final Gson JSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private final Map<String, JsonObject> builtins = new LinkedHashMap<>();
    private JsonObject root;

    LiveTemplateSettings(List<LiveTemplateCatalog.Template> defaults, String json) {
        defaults.forEach(t -> builtins.put(t.id(), JSON.toJsonTree(t).getAsJsonObject()));
        load(json);
    }
    void load(String json) {
        LiveTemplateCatalog.parse(json); // Validate before replacing a working draft.
        root = JsonParser.parseString(json).getAsJsonObject();
    }
    String json() { return JSON.toJson(root) + "\n"; }
    boolean builtin(String id) { return builtins.containsKey(id); }
    boolean overridden(String id) { return userIndex(id) >= 0; }
    private JsonArray users() { return root.getAsJsonArray("templates"); }
    private int userIndex(String id) {
        for (int i = 0; i < users().size(); i++)
            if (text(users().get(i).getAsJsonObject(), "id").equals(id)) return i;
        return -1;
    }
    JsonObject definition(String id) {
        int index = userIndex(id);
        JsonObject value = index < 0 ? builtins.get(id) : users().get(index).getAsJsonObject();
        return value == null ? null : value.deepCopy();
    }
    void put(String id, JsonObject definition) {
        if (!id.equals(text(definition, "id"))) throw new IllegalArgumentException("Template ID cannot change");
        int index = userIndex(id);
        if (index < 0) users().add(definition.deepCopy()); else users().set(index, definition.deepCopy());
    }
    void remove(String id) { int index = userIndex(id); if (index >= 0) users().remove(index); }
    String add(JsonObject copy) {
        String id = "user/template"; int suffix = 2;
        while (definition(id) != null) id = "user/template-" + suffix++;
        JsonObject entry = copy == null ? JSON.toJsonTree(new LiveTemplateCatalog.Template(id, "snippet",
                "System.out.println($END$);", List.of("JAVA_STATEMENT"), List.of(), false, "New template", "")).getAsJsonObject() : copy.deepCopy();
        entry.addProperty("id", id);
        if (copy != null) entry.addProperty("name", displayName(copy) + " copy");
        put(id, entry); return id;
    }
    List<String> matching(String filter) {
        var merged = new LinkedHashMap<>(builtins);
        users().forEach(t -> merged.put(text(t.getAsJsonObject(), "id"), t.getAsJsonObject()));
        String[] words = filter.strip().toLowerCase(Locale.ROOT).split("\\s+");
        return merged.entrySet().stream().filter(e -> {
            var t = e.getValue(); String searchable = (e.getKey() + " " + displayName(t) + " " + text(t, "abbreviation")
                    + " " + text(t, "description") + " " + text(t, "body")).toLowerCase(Locale.ROOT);
            return Arrays.stream(words).allMatch(searchable::contains);
        }).map(Map.Entry::getKey).sorted(Comparator.comparing((String id) -> text(merged.get(id), "abbreviation"))
                .thenComparing(id -> id)).toList();
    }
    static String text(JsonObject object, String key) { return object.has(key) ? object.get(key).getAsString() : ""; }
    static String displayName(JsonObject object) { return object.has("name") ? text(object, "name") : text(object, "abbreviation"); }
    static LiveTemplateCatalog.Template template(JsonObject definition) {
        var root = new JsonObject(); root.addProperty("schemaVersion", 1);
        var entries = new JsonArray(); entries.add(definition); root.add("templates", entries);
        return LiveTemplateCatalog.parse(root.toString()).getFirst();
    }
}
