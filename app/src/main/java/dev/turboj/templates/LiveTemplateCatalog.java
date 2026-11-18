package dev.turboj.templates;

import com.google.gson.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Pattern;

/** Versioned, declarative data. User entries override stable IDs; disabled entries are retained. */
public final class LiveTemplateCatalog {
    public record Variable(String name, String expression, String defaultValue, boolean alwaysStop) {}
    public record Template(String id, String abbreviation, String body, List<String> contexts,
                           List<Variable> variables, boolean disabled, String name, String description, boolean reformat, boolean shortenNames) {
        public Template(String id, String abbreviation, String body, List<String> contexts,
                        List<Variable> variables, boolean disabled, String name, String description) {
            this(id, abbreviation, body, contexts, variables, disabled, name, description, false, false);
        }
        public Template(String id, String abbreviation, String body, List<String> contexts,
                        List<Variable> variables, boolean disabled) {
            this(id, abbreviation, body, contexts, variables, disabled, abbreviation, "");
        }
        @Override public String toString() { return abbreviation + " — " + id; }
    }
    public static final String EMPTY = "{\n  \"schemaVersion\": 1,\n  \"templates\": []\n}\n";
    private static final Pattern NAME = Pattern.compile("[A-Za-z_][A-Za-z_0-9]*");
    public static final Set<String> CONTEXTS = Set.of("JAVA_STATEMENT", "JAVA_DECLARATION", "JAVA_EXPRESSION", "JAVA_CODE",
            "JAVA_ELSE_PLACE", "JAVA_CONSUMER", "JAVA_LANG_IO_STATEMENT", "JAVA_IMPLICIT_CLASS_DECLARATION",
            "JAVA_NORMAL_CLASS_DECLARATION_WITH_INSTANCE_MAIN", "JAVA_NORMAL_CLASS_DECLARATION_WITHOUT_INSTANCE_MAIN",
            "JAVA_STATEMENT_WITH_STRUCTURED_CONCURRENCY_STATIC_FACTORY_METHODS_WITH_EXECUTION_EXCEPTION", "JAVA_STATEMENT_WITH_STRUCTURED_CONCURRENCY");
    private final List<Template> templates;
    private final Map<String, List<Template>> abbreviations;
    public LiveTemplateCatalog(List<Template> builtins, List<Template> users) {
        var merged = new LinkedHashMap<String, Template>();
        builtins.forEach(t -> merged.put(t.id(), t)); users.forEach(t -> merged.put(t.id(), t));
        templates = List.copyOf(merged.values());
        var index = new HashMap<String, List<Template>>();
        templates.stream().filter(t -> !t.disabled()).forEach(t -> index.computeIfAbsent(t.abbreviation(), k -> new ArrayList<>()).add(t));
        var immutable = new HashMap<String, List<Template>>(); index.forEach((k, v) -> immutable.put(k, List.copyOf(v)));
        abbreviations = Map.copyOf(immutable);
    }
    public List<Template> templates() { return templates; }
    public List<Template> matching(String abbreviation) { return abbreviations.getOrDefault(abbreviation, List.of()); }
    public List<Template> startingWith(String prefix) {
        if (prefix.isEmpty()) return List.of();
        return templates.stream().filter(t -> !t.disabled() && t.abbreviation().startsWith(prefix))
                .sorted(Comparator.comparing((Template t) -> !t.abbreviation().equals(prefix))
                        .thenComparing(Template::abbreviation).thenComparing(Template::id)).toList();
    }
    public static List<Template> builtins() throws IOException {
        try (var stream = LiveTemplateCatalog.class.getResourceAsStream("/live-templates/catalog.json")) {
            if (stream == null) throw new IOException("Missing live-template catalog");
            return parse(new String(stream.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
    public static List<Template> parse(String json) {
        if (json.length() > 1_000_000) throw new IllegalArgumentException("Template catalog exceeds 1 MB");
        try {
            var root = JsonParser.parseString(json).getAsJsonObject();
            if (root.get("schemaVersion").getAsInt() != 1) throw new IllegalArgumentException("Unsupported template schemaVersion");
            var result = new ArrayList<Template>(); var ids = new HashSet<String>();
            for (var entry : root.getAsJsonArray("templates")) {
                var o = entry.getAsJsonObject(); String id = required(o, "id"), abbreviation = required(o, "abbreviation"), body = required(o, "body");
                if (!ids.add(id)) throw new IllegalArgumentException("Duplicate template ID: " + id);
                if (!abbreviation.matches("[\\p{L}\\p{N}_.-]{1,64}")) throw new IllegalArgumentException("Invalid abbreviation: " + abbreviation);
                if (body.length() > 32_768) throw new IllegalArgumentException("Template is too large: " + id);
                List<String> contexts = new ArrayList<>();
                for (var c : o.getAsJsonArray("contexts")) {
                    if (!CONTEXTS.contains(c.getAsString())) throw new IllegalArgumentException("Unknown context: " + c.getAsString());
                    contexts.add(c.getAsString());
                }
                if (contexts.isEmpty()) throw new IllegalArgumentException("Choose a context for " + id);
                var variables = new ArrayList<Variable>(); var names = new HashSet<String>();
                if (o.has("variables")) for (var item : o.getAsJsonArray("variables")) {
                    var v = item.getAsJsonObject(); String name = required(v, "name");
                    if (!NAME.matcher(name).matches() || Set.of("END", "SELECTION").contains(name) || !names.add(name))
                        throw new IllegalArgumentException("Invalid/duplicate variable: " + name);
                    variables.add(new Variable(name, optional(v, "expression"), optional(v, "defaultValue"), !v.has("alwaysStop") || v.get("alwaysStop").getAsBoolean()));
                }
                placeholders(body); // Reject broken syntax before modifying a document.
                String name = o.has("name") ? required(o, "name").strip() : abbreviation;
                if (name.isEmpty()) throw new IllegalArgumentException("Missing name");
                result.add(new Template(id, abbreviation, body, List.copyOf(contexts), List.copyOf(variables),
                        o.has("disabled") && o.get("disabled").getAsBoolean(), name, optional(o, "description"),
                        o.has("reformat") && o.get("reformat").getAsBoolean(), o.has("shortenNames") && o.get("shortenNames").getAsBoolean()));
                if (result.size() > 2000) throw new IllegalArgumentException("Too many templates");
            }
            return List.copyOf(result);
        } catch (IllegalArgumentException ex) { throw ex; }
        catch (RuntimeException ex) { throw new IllegalArgumentException("Expected schemaVersion: 1 and a templates array with id, abbreviation, body and contexts", ex); }
    }
    public record Token(int start, int end, String name) {}
    public static List<Token> placeholders(String body) {
        var tokens = new ArrayList<Token>(); int exits = 0;
        for (int i = 0; i < body.length(); i++) if (body.charAt(i) == '$') {
            int end = body.indexOf('$', i + 1);
            if (end < 0) throw new IllegalArgumentException("Unclosed $variable$; use $$ for a dollar sign");
            String name = body.substring(i + 1, end);
            if (!name.isEmpty() && !NAME.matcher(name).matches()) throw new IllegalArgumentException("Invalid placeholder: " + name);
            if (name.equals("END") && ++exits > 1) throw new IllegalArgumentException("Only one $END$ is allowed");
            tokens.add(new Token(i, end + 1, name)); i = end;
        }
        return List.copyOf(tokens);
    }
    private static String required(JsonObject o, String key) {
        String value = optional(o, key);
        if (value.isEmpty()) throw new IllegalArgumentException("Missing " + key);
        return value;
    }
    private static String optional(JsonObject o, String key) { return o.has(key) ? o.get(key).getAsString() : ""; }
}
