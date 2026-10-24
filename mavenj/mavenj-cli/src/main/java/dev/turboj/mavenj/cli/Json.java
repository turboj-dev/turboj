package dev.turboj.mavenj.cli;

import com.google.gson.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import dev.turboj.mavenj.workspace.MavenLifecycle;

/** JSON wire representation shared by MavenJ's optional command-line/protocol adapters. */
public final class Json {
    private Json() {}

    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls()
            .registerTypeHierarchyAdapter(Path.class, (JsonSerializer<Path>)(path, type,
            context) -> new JsonPrimitive(path.toString())).registerTypeAdapter(Duration.class,
            (JsonSerializer<Duration>)(duration, type, context) -> new JsonPrimitive(duration
            .toString())).create();

    public static JsonElement tree(Object value) {
        return ordered(GSON.toJsonTree(value));
    }

    public static String string(Object value) {
        return GSON.toJson(tree(value));
    }

    public static String report(Object value) {
        return GSON.toJson(redacted(tree(value), ""));
    }

    public static JsonObject object(Object... pairs) {
        var result = new JsonObject();

        for (int i = 0; i < pairs.length; i += 2)
            result.add(pairs[i].toString(), tree(pairs[i + 1]));
        return result;
    }

    private static JsonElement ordered(JsonElement value) {
        if (value.isJsonObject()) {
            var result = new JsonObject();
            value.getAsJsonObject().keySet().stream().sorted().forEach(k -> result.add(k,
                    ordered(value.getAsJsonObject().get(k))));
            return result;
        }
        if (value.isJsonArray()) {
            var result = new JsonArray();
            value.getAsJsonArray().forEach(v -> result.add(ordered(v)));
            return result;
        }
        return value;
    }

    private static JsonElement redacted(JsonElement value, String key) {
        if (key.toLowerCase(Locale.ROOT)
                .matches(".*(password|passwd|credential|secret|authorization|api.?key|access.?token).*"))
            return new JsonPrimitive("<redacted>");
        if (value.isJsonObject()) {
            var result = new JsonObject();
            value.getAsJsonObject().entrySet().forEach(e -> result.add(e.getKey(), redacted(e
                    .getValue(), e.getKey())));
            return result;
        }
        if (value.isJsonArray()) {
            var result = new JsonArray();
            value.getAsJsonArray().forEach(v -> result.add(redacted(v, key)));
            return result;
        }
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            String text = MavenLifecycle.redact(value.getAsString(), Map.of());
            text = text
                    .replaceAll("(?is)(<([\\w.:-]*(?:password|token|secret|credential)[\\w.:-]*)[^>]*>)[^<]*(</\\2>)",
                    "$1&lt;redacted&gt;$3");
            return new JsonPrimitive(text);
        }
        return value;
    }
}
