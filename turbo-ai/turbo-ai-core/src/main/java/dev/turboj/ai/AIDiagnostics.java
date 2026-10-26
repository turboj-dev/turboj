package dev.turboj.ai;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The last provider exchanges, in memory only, already sanitized: source fields become their length,
 * keys never enter, and a response body is kept only as a short, secret-free error message. Nothing is
 * written to disk or logs; {@link #report()} is for an explicit "copy diagnostics" action.
 */
public final class AIDiagnostics {
    /** One HTTP exchange. {@code status} is -1 when no response arrived. */
    public record Exchange(
            Instant at,
            String provider,
            String method,
            String base,
            String path,
            String model,
            String request,
            int status,
            String response,
            long millis,
            AIError error) {
        public String describe() {
            return String.format(Locale.ROOT, "%s %s %s%s model=%s → %s in %d ms%s%n  request %s%n  response %s",
                    at, provider, method, base + path, model, status < 0 ? "no response" : "HTTP " + status, millis,
                    error == null ? "" : " [" + error + "]", request, response.isEmpty() ? "-" : response);
        }
    }

    private static final AIDiagnostics GLOBAL = new AIDiagnostics();
    private static final int SIZE = 32;
    /** Fields that carry source or instructions built from source. */
    private static final Set<String> SOURCE_FIELDS = Set.of("prompt", "suffix", "input", "input_prefix",
            "input_suffix", "input_extra", "instructions", "messages", "system", "context");
    private static final Pattern SECRET = Pattern.compile(
            "(?i)(sk-[a-z0-9_-]{8,}|xai-[a-z0-9]{8,}|bearer\\s+\\S+|api[_-]?key\\S*|-----BEGIN)");

    private final ArrayDeque<Exchange> recent = new ArrayDeque<>();

    public static AIDiagnostics global() {
        return GLOBAL;
    }

    public synchronized void record(Exchange exchange) {
        if (recent.size() == SIZE) recent.removeFirst();
        recent.addLast(exchange);
    }

    public synchronized List<Exchange> recent() {
        return List.copyOf(recent);
    }

    public synchronized Exchange last() {
        return recent.peekLast();
    }

    public synchronized void clear() {
        recent.clear();
    }

    public synchronized String report() {
        var out = new StringBuilder("TurboAI diagnostics (sanitized: no source, no keys)\n");
        for (Exchange exchange : recent) out.append(exchange.describe()).append('\n');
        return out.toString();
    }

    /** The request body with every source-bearing field replaced by its size. */
    static String sanitizeRequest(JsonObject body) {
        if (body == null) return "-";
        var out = new StringBuilder("{");
        for (Map.Entry<String, JsonElement> entry : body.entrySet()) {
            if (out.length() > 1) out.append(", ");
            out.append(entry.getKey()).append('=');
            JsonElement value = entry.getValue();
            if (SOURCE_FIELDS.contains(entry.getKey()))
                out.append('<').append(value.isJsonPrimitive() ? value.getAsString().length() : value.toString().length())
                        .append(" chars>");
            else out.append(value.toString().length() > 120 ? "<" + value.toString().length() + " chars>" : value);
        }
        return out.append('}').toString();
    }

    /** A provider's error message, kept only when short and free of anything that looks like a secret. */
    static String sanitizeResponse(String message) {
        if (message == null || message.isBlank()) return "";
        String single = message.replaceAll("\\p{Cntrl}", " ").strip();
        if (single.length() > 240 || SECRET.matcher(single).find()) return "<" + single.length() + " chars withheld>";
        return single;
    }
}
