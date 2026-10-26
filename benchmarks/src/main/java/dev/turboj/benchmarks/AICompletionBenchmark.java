package dev.turboj.benchmarks;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.turboj.ai.AICompletionProvider;
import dev.turboj.ai.AIConfig;
import dev.turboj.ai.AIProviders;
import dev.turboj.ai.Cancellation;
import dev.turboj.ai.CompletionProbe;
import dev.turboj.ai.CompletionValidation;
import dev.turboj.ai.java.CompletionContext;
import dev.turboj.ai.java.JavaSuggestionFilter;
import dev.turboj.intelligence.JdtJavaIntelligenceService;
import dev.turboj.refactorj.api.JavaIntelligence;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import javax.tools.ToolProvider;

/**
 * Real local FIM measurements over the autocomplete corpus (benchmarks/ai-completion): cold load, memory,
 * warm first-text and full latency, what survives the insertion filter and the Java filter, whether it
 * matches an expected completion, and whether the completed file compiles. JDT's deterministic first
 * proposal on the same carets is the baseline. A fixture's expected text is one acceptable answer, not
 * proof of general correctness.
 *
 * <p>Usage: AICompletionBenchmark [--endpoint URL] [--models a,b] [--runs N] [--out file.md]
 */
public final class AICompletionBenchmark {
    record Fixture(String id, Path file, int java, String category, String kind, List<String> expected, boolean compile, String classpath) {}

    record Sample(String fixture, String raw, String shown, String verdict, boolean match, boolean rejectedMatch, Boolean compiles,
            long firstMillis, long totalMillis) {}

    private static final String MARKER = "/*|*/";
    private static final Gson JSON = new Gson();
    private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();

    public static void main(String[] args) throws Exception {
        var options = options(args);
        Path root = Path.of(options.getOrDefault("corpus", "benchmarks/ai-completion")).toAbsolutePath();
        String endpoint = options.getOrDefault("endpoint", "http://127.0.0.1:11434");
        List<String> models = List.of(options.getOrDefault("models", "qwen2.5-coder:1.5b-base").split(","));
        int runs = Integer.parseInt(options.getOrDefault("runs", "3"));
        Path out = Path.of(options.getOrDefault("out", "benchmarks/results/ai-completion.md"));
        List<Fixture> corpus = corpus(root);
        String junit = junitClasspath(Path.of(options.getOrDefault("m2", ".cache/m2")));
        var report = new StringBuilder();
        report.append("# AI completion benchmark — ").append(Instant.now()).append("\n\n");
        report.append("- Machine: ").append(machine()).append("\n- Endpoint: ").append(endpoint).append(" (").append(version(endpoint))
                .append(")\n- Corpus: ").append(corpus.size()).append(" fixtures, ").append(runs).append(" runs each, temperature 0.1, num_ctx ")
                .append(AIConfig.LOCAL_CONTEXT_TOKENS).append(", max 256 tokens\n\n");
        var summary = new StringBuilder("| Model | Cold load | Memory | First text p50 / p95 | Full p50 / p95 | Shown | Match | Compiles (of shown) | Filtered | Wrongly filtered |\n|---|---|---|---|---|---|---|---|---|---|\n");
        var details = new StringBuilder();
        for (String model : models) {
            System.out.println("== " + model);
            for (String other : models) unload(endpoint, other);
            long[] cold = load(endpoint, model);
            long[] memory = memory(endpoint, model);
            AIConfig config = AIConfig.defaults().withMode(AIConfig.Mode.LOCAL)
                    .withLocal(AIConfig.LocalBackend.OLLAMA, endpoint, model, AIConfig.LocalRuntime.EXTERNAL);
            var samples = new ArrayList<Sample>();
            try (AICompletionProvider provider = AIProviders.create(config, null)) {
                CompletionProbe.run(provider, new Cancellation());
                for (Fixture fixture : corpus)
                    for (int run = 0; run < runs; run++) {
                        var sample = sample(provider, config, fixture, run == 0, junit);
                        samples.add(sample);
                        System.out.printf(Locale.ROOT, "%-22s %-13s %5d ms  %s%n", fixture.id(), sample.verdict(), sample.totalMillis(),
                                sample.shown().replace("\n", "⏎"));
                    }
            }
            summary.append(row(model, cold, memory, samples, runs));
            details.append(details(model, corpus, samples, runs));
        }
        report.append("## Summary\n\n").append(summary).append('\n');
        report.append(jdtBaseline(corpus, junit));
        report.append(details);
        Files.createDirectories(out.toAbsolutePath().getParent());
        Files.writeString(out, report);
        System.out.println("Report: " + out.toAbsolutePath());
    }

    private static Sample sample(AICompletionProvider provider, AIConfig config, Fixture fixture, boolean compile, String junit)
            throws Exception {
        String marked = Files.readString(fixture.file());
        int offset = marked.indexOf(MARKER);
        String source = marked.substring(0, offset) + marked.substring(offset + MARKER.length());
        var request = CompletionContext.build(fixture.file(), source, 1, offset, config, false, new Cancellation());
        var last = new AtomicReference<>("");
        var first = new AtomicLong();
        long start = System.nanoTime();
        try {
            provider.stream(request, text -> {
                first.compareAndSet(0, System.nanoTime());
                last.set(text);
            }, new Cancellation());
        } catch (IOException failure) {
            return new Sample(fixture.id(), "", "", "ERROR " + failure.getMessage(), false, false, null, -1, millis(start));
        }
        long total = millis(start);
        long firstMillis = first.get() == 0 ? -1 : TimeUnit.NANOSECONDS.toMillis(first.get() - start);
        String usable = CompletionValidation.usable(request, last.get(), true);
        String verdict;
        String shown = "";
        if (usable.isBlank()) verdict = "NO_INSERTION";
        else {
            var java = JavaSuggestionFilter.check(source, offset, usable, fixture.java());
            verdict = java.name();
            if (java == JavaSuggestionFilter.Verdict.ACCEPT) shown = usable;
        }
        boolean matches = matches(fixture, usable);
        Boolean compiles = null;
        if (compile && fixture.compile() && !shown.isEmpty())
            compiles = compiles(fixture, source.substring(0, offset) + shown + source.substring(offset), junit);
        return new Sample(fixture.id(), last.get(), shown, verdict, !shown.isEmpty() && matches, shown.isEmpty() && matches, compiles,
                firstMillis, total);
    }

    /** Whitespace-insensitive equality with any expected completion. */
    static boolean matches(Fixture fixture, String suggestion) {
        String got = normalize(suggestion);
        return !got.isEmpty() && fixture.expected().stream().map(AICompletionBenchmark::normalize).anyMatch(got::equals);
    }

    private static String normalize(String text) {
        return text.replaceAll("\\s+", " ").replaceAll(" ?([(){}\\[\\];,.<>=+*/-]) ?", "$1").strip();
    }

    private static boolean compiles(Fixture fixture, String source, String junit) throws IOException {
        Path work = Files.createTempDirectory("ai-bench-");
        String name = fixture.file().getFileName().toString();
        Path file = Files.writeString(work.resolve(name), source);
        var compiler = ToolProvider.getSystemJavaCompiler();
        var arguments = new ArrayList<>(List.of("--release", Integer.toString(fixture.java()), "-proc:none", "-d", work.resolve("out").toString(),
                "-Xlint:none", "-nowarn"));
        if (fixture.classpath().equals("junit")) arguments.addAll(List.of("-cp", junit));
        arguments.add(file.toString());
        int code = compiler.run(null, java.io.OutputStream.nullOutputStream(), java.io.OutputStream.nullOutputStream(), arguments.toArray(String[]::new));
        return code == 0;
    }

    private static String row(String model, long[] cold, long[] memory, List<Sample> samples, int runs) {
        long[] firsts = samples.stream().mapToLong(Sample::firstMillis).filter(v -> v >= 0).sorted().toArray();
        long[] totals = samples.stream().mapToLong(Sample::totalMillis).sorted().toArray();
        long shown = samples.stream().filter(s -> !s.shown().isEmpty()).count();
        long matched = samples.stream().filter(Sample::match).count();
        long compiled = samples.stream().filter(s -> Boolean.TRUE.equals(s.compiles())).count();
        long compileChecked = samples.stream().filter(s -> s.compiles() != null).count();
        long filtered = samples.stream().filter(s -> s.verdict().equals("SYNTAX_ERROR") || s.verdict().equals("UNKNOWN_NAME")).count();
        long wrong = samples.stream().filter(Sample::rejectedMatch).count();
        return String.format(Locale.ROOT, "| %s | %d ms | %.0f MB (%.0f MB GPU) | %d / %d ms | %d / %d ms | %d/%d | %d/%d (%.0f%%) | %d/%d | %d | %d |%n",
                model, cold[0], memory[0] / 1e6, memory[1] / 1e6, percentile(firsts, 50), percentile(firsts, 95),
                percentile(totals, 50), percentile(totals, 95), shown, samples.size(), matched, samples.size(),
                100.0 * matched / Math.max(1, samples.size()), compiled, compileChecked, filtered, wrong);
    }

    private static String details(String model, List<Fixture> corpus, List<Sample> samples, int runs) {
        var out = new StringBuilder("\n## " + model + "\n\n| Fixture | Kind | Java | Verdict | Match | Compiles | Full ms | Suggestion (first run) |\n|---|---|---|---|---|---|---|---|\n");
        for (Fixture fixture : corpus) {
            var mine = samples.stream().filter(s -> s.fixture().equals(fixture.id())).toList();
            var first = mine.getFirst();
            long matched = mine.stream().filter(Sample::match).count();
            out.append(String.format(Locale.ROOT, "| %s | %s | %d | %s | %d/%d | %s | %d | `%s` |%n", fixture.id(), fixture.kind(), fixture.java(),
                    first.verdict().length() > 40 ? first.verdict().substring(0, 40) + "…" : first.verdict(), matched, mine.size(),
                    first.compiles() == null ? "–" : first.compiles() ? "yes" : "no", first.totalMillis(),
                    escape(first.shown().isEmpty() ? first.raw() : first.shown())));
        }
        return out.toString();
    }

    /** JDT's first proposal at each caret, the deterministic completion TurboJ shows without AI. */
    private static String jdtBaseline(List<Fixture> corpus, String junit) {
        var service = new JdtJavaIntelligenceService();
        var out = new StringBuilder("## JDT baseline (no AI)\n\nJDT completes names, not statements: it is shown with its first proposal and the rank of an expected completion in its list.\n\n| Fixture | Kind | First proposal | Match | Expected at rank | ms |\n|---|---|---|---|---|---|\n");
        int matched = 0, listed = 0;
        var durations = new ArrayList<Long>();
        for (Fixture fixture : corpus) {
            try {
                String marked = Files.readString(fixture.file());
                int offset = marked.indexOf(MARKER);
                String source = marked.substring(0, offset) + marked.substring(offset + MARKER.length());
                List<Path> classpath = fixture.classpath().equals("junit") ? Stream.of(junit.split(java.io.File.pathSeparator)).map(Path::of).toList() : List.of();
                var context = new JavaIntelligence.Context(fixture.file(), source, 1, Map.of(), List.of(fixture.file().getParent()), classpath, 1, List.of());
                long start = System.nanoTime();
                var result = service.complete(context, offset);
                long ms = millis(start);
                durations.add(ms);
                String insertion = "";
                int rank = -1;
                for (int i = 0; i < result.proposals().size(); i++) {
                    var proposal = result.proposals().get(i);
                    String typed = source.substring(Math.min(proposal.start(), offset), offset);
                    String text = proposal.text().startsWith(typed) ? proposal.text().substring(typed.length()) : proposal.text();
                    if (i == 0) insertion = text;
                    // A name proposal matches when it is the expected text, with or without its "()".
                    String bare = text.endsWith("()") ? text.substring(0, text.length() - 2) : text;
                    if (rank < 0 && (matches(fixture, text) || matches(fixture, bare))) rank = i + 1;
                }
                boolean match = rank == 1;
                if (match) matched++;
                if (rank > 0) listed++;
                out.append(String.format(Locale.ROOT, "| %s | %s | `%s` | %s | %s | %d |%n", fixture.id(), fixture.kind(), escape(insertion),
                        match ? "yes" : "no", rank < 0 ? "–" : rank + " of " + result.proposals().size(), ms));
            } catch (Exception failure) {
                out.append("| ").append(fixture.id()).append(" | ").append(fixture.kind()).append(" | error: ")
                        .append(failure.getClass().getSimpleName()).append(" | no | – |\n");
            }
        }
        long[] sorted = durations.stream().mapToLong(Long::longValue).sorted().toArray();
        out.append(String.format(Locale.ROOT, "%nJDT first proposal matched %d/%d; an expected completion was in its list for %d/%d; latency p50 %d ms, p95 %d ms (the first call includes JDT start-up).%n%n",
                matched, corpus.size(), listed, corpus.size(), percentile(sorted, 50), percentile(sorted, 95)));
        return out.toString();
    }

    private static String escape(String text) {
        String single = text.replace("\n", "⏎").replace("|", "\\|").replace("`", "'");
        return single.length() > 90 ? single.substring(0, 90) + "…" : single;
    }

    private static long percentile(long[] sorted, int p) {
        if (sorted.length == 0) return -1;
        return sorted[Math.min(sorted.length - 1, (int) Math.ceil(sorted.length * p / 100.0) - 1)];
    }

    private static long[] load(String endpoint, String model) throws Exception {
        var body = new JsonObject();
        body.addProperty("model", model);
        body.addProperty("prompt", "");
        body.addProperty("stream", false);
        body.addProperty("keep_alive", "30m");
        var options = new JsonObject();
        options.addProperty("num_ctx", AIConfig.LOCAL_CONTEXT_TOKENS);
        body.add("options", options);
        long start = System.nanoTime();
        JsonObject response = post(endpoint, "/api/generate", body);
        long loadNanos = response.has("load_duration") ? response.get("load_duration").getAsLong() : 0;
        return new long[] {millis(start), TimeUnit.NANOSECONDS.toMillis(loadNanos)};
    }

    private static void unload(String endpoint, String model) {
        try {
            var body = new JsonObject();
            body.addProperty("model", model);
            body.addProperty("keep_alive", 0);
            body.addProperty("stream", false);
            post(endpoint, "/api/generate", body);
        } catch (Exception ignored) {
        }
    }

    private static long[] memory(String endpoint, String model) throws Exception {
        var response = HTTP.send(HttpRequest.newBuilder(URI.create(endpoint + "/api/ps")).build(), HttpResponse.BodyHandlers.ofString());
        JsonArray models = JsonParser.parseString(response.body()).getAsJsonObject().getAsJsonArray("models");
        for (JsonElement item : models) {
            JsonObject value = item.getAsJsonObject();
            if (value.get("name").getAsString().equals(model)) return new long[] {value.get("size").getAsLong(), value.get("size_vram").getAsLong()};
        }
        return new long[] {0, 0};
    }

    private static String version(String endpoint) {
        try {
            var response = HTTP.send(HttpRequest.newBuilder(URI.create(endpoint + "/api/version")).build(), HttpResponse.BodyHandlers.ofString());
            return "Ollama " + JsonParser.parseString(response.body()).getAsJsonObject().get("version").getAsString();
        } catch (Exception e) {
            return "unknown";
        }
    }

    private static JsonObject post(String endpoint, String path, JsonObject body) throws Exception {
        var response = HTTP.send(HttpRequest.newBuilder(URI.create(endpoint + path)).timeout(Duration.ofMinutes(3))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(JSON.toJson(body))).build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) throw new IOException(path + " HTTP " + response.statusCode() + " " + response.body());
        return JsonParser.parseString(response.body()).getAsJsonObject();
    }

    private static List<Fixture> corpus(Path root) throws IOException {
        var fixtures = new ArrayList<Fixture>();
        for (JsonElement item : JsonParser.parseString(Files.readString(root.resolve("corpus.json"))).getAsJsonArray()) {
            JsonObject value = item.getAsJsonObject();
            var expected = new ArrayList<String>();
            value.getAsJsonArray("expected").forEach(e -> expected.add(e.getAsString()));
            fixtures.add(new Fixture(value.get("id").getAsString(), root.resolve(value.get("file").getAsString()), value.get("java").getAsInt(),
                    value.get("category").getAsString(), value.get("kind").getAsString(), expected, value.get("compile").getAsBoolean(),
                    value.get("classpath").getAsString()));
        }
        return fixtures;
    }

    private static String junitClasspath(Path m2) throws IOException {
        var jars = new ArrayList<String>();
        for (String[] artifact : new String[][] {{"org/junit/jupiter/junit-jupiter-api", "5.13.4"}, {"org/junit/platform/junit-platform-commons", "1.13.4"},
                {"org/opentest4j/opentest4j", "1.3.0"}, {"org/apiguardian/apiguardian-api", "1.1.2"}}) {
            Path directory = m2.resolve(artifact[0]).resolve(artifact[1]);
            if (!Files.isDirectory(directory)) continue;
            try (var files = Files.list(directory)) {
                files.filter(f -> f.toString().endsWith(artifact[1] + ".jar")).findFirst().ifPresent(f -> jars.add(f.toAbsolutePath().toString()));
            }
        }
        return String.join(java.io.File.pathSeparator, jars);
    }

    private static String machine() {
        String chip = "";
        try {
            var process = new ProcessBuilder("sysctl", "-n", "machdep.cpu.brand_string", "hw.memsize").start();
            var lines = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).strip().split("\n");
            chip = lines[0] + ", " + Long.parseLong(lines[1].strip()) / (1L << 30) + " GB";
        } catch (Exception ignored) {
        }
        return chip + ", " + System.getProperty("os.name") + " " + System.getProperty("os.version") + ", Java " + System.getProperty("java.version");
    }

    private static Map<String, String> options(String[] args) {
        var options = new LinkedHashMap<String, String>();
        for (int i = 0; i + 1 < args.length; i += 2) options.put(args[i].replaceFirst("^--", ""), args[i + 1]);
        return options;
    }

    private static long millis(long start) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }
}
