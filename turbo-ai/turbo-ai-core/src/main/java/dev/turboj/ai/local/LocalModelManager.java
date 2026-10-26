package dev.turboj.ai.local;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.turboj.ai.AIConfig;
import dev.turboj.ai.AIError;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ConnectException;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * The local inference lifecycle, off the caller's thread: detect a runtime, reuse a healthy one or start
 * and own one, check the model, download it only when asked, load and warm it, watch its health and
 * restart an owned server that crashed. A server TurboAI did not start is never stopped, restarted or
 * reconfigured. Status changes go to the listener from the manager's own thread.
 */
public final class LocalModelManager implements AutoCloseable {
    public enum State { OFF, DETECTING_RUNTIME, STARTING_RUNTIME, DOWNLOADING_MODEL, LOADING_MODEL, READY, IDLE, UNAVAILABLE, ERROR }

    public enum Ownership { NONE, EXTERNAL, MANAGED }

    public record Status(State state, Ownership ownership, String runtime, String endpoint, String model, String detail,
            AIError error, double progress, List<String> installed, long loadMillis) {
        public static final Status OFF = new Status(State.OFF, Ownership.NONE, "", "", "", "", null, 0, List.of(), 0);

        public Status {
            installed = List.copyOf(installed);
        }

        /** Requests can go out: the model is loaded, or was and the server reloads it on demand. */
        public boolean ready() {
            return state == State.READY || state == State.IDLE;
        }

        /** A few words for a status line. */
        public String summary() {
            return switch (state) {
                case OFF -> "Off";
                case DETECTING_RUNTIME -> "Detecting runtime…";
                case STARTING_RUNTIME -> "Starting runtime…";
                case DOWNLOADING_MODEL -> String.format(Locale.ROOT, "Downloading %.0f%%", progress * 100);
                case LOADING_MODEL -> "Loading model…";
                case READY -> "Ready";
                case IDLE -> "Ready (idle)";
                case UNAVAILABLE -> error == AIError.MODEL_DOWNLOAD_REQUIRED ? "Model not installed"
                        : error == AIError.RUNTIME_NOT_INSTALLED ? "Runtime not installed" : "Unavailable";
                case ERROR -> "Error";
            };
        }

        Status with(State next, String text) {
            return new Status(next, ownership, runtime, endpoint, model, text, null, 0, installed, loadMillis);
        }

        Status withError(AIError value) {
            return new Status(state, ownership, runtime, endpoint, model, detail, value, progress, installed, loadMillis);
        }
    }

    private static final Gson JSON = new Gson();
    private static final Duration PROBE = Duration.ofMillis(800), START = Duration.ofSeconds(30);
    private static final int MAX_RESTARTS = 3;
    private static final long RESTART_WINDOW = TimeUnit.MINUTES.toNanos(10);

    private final Path stateDirectory;
    private final Consumer<Status> listener;
    private final RuntimeLocator locator;
    private final ProcessLauncher launcher;
    private final LongSupplier nanos;
    private final Duration healthInterval;
    private final java.util.function.ToLongFunction<Path> freeSpace;
    private final ScheduledExecutorService executor;
    private final HttpClient http;
    private volatile Status status = Status.OFF;
    private volatile AIConfig config;
    private volatile long generation;
    private volatile boolean closed;
    private volatile AtomicBoolean downloading;
    private ProcessLauncher.ManagedProcess owned;
    private int ownedPort = -1;
    private ScheduledFuture<?> monitor;
    private final ArrayList<Long> restarts = new ArrayList<>();

    public LocalModelManager(Path stateDirectory, Consumer<Status> listener) {
        this(stateDirectory, listener, RuntimeLocator.system(), ProcessLauncher.watchdog(), System::nanoTime);
    }

    public LocalModelManager(Path stateDirectory, Consumer<Status> listener, RuntimeLocator locator, ProcessLauncher launcher,
            LongSupplier nanos) {
        this(stateDirectory, listener, locator, launcher, nanos, Duration.ofSeconds(10), LocalModelManager::usableSpace);
    }

    LocalModelManager(Path stateDirectory, Consumer<Status> listener, RuntimeLocator locator, ProcessLauncher launcher,
            LongSupplier nanos, Duration healthInterval, java.util.function.ToLongFunction<Path> freeSpace) {
        this.healthInterval = Objects.requireNonNull(healthInterval);
        this.freeSpace = Objects.requireNonNull(freeSpace);
        this.stateDirectory = Objects.requireNonNull(stateDirectory);
        this.listener = Objects.requireNonNull(listener);
        this.locator = Objects.requireNonNull(locator);
        this.launcher = Objects.requireNonNull(launcher);
        this.nanos = Objects.requireNonNull(nanos);
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            var thread = new Thread(r, "turbo-ai-local");
            thread.setDaemon(true);
            return thread;
        });
        http = HttpClient.newBuilder().connectTimeout(PROBE).followRedirects(HttpClient.Redirect.NEVER)
                .proxy(ProxySelector.of(null)).build();
    }

    public Status status() {
        return status;
    }

    public boolean ready() {
        return status.ready();
    }

    /** Apply preferences: start, reuse, switch model or stop. Returns at once. */
    public CompletableFuture<Status> configure(AIConfig next) {
        long ticket = ++generation;
        config = next;
        return CompletableFuture.supplyAsync(() -> {
            if (ticket == generation) apply(next, ticket);
            return status;
        }, executor);
    }

    /** Run detection again, for a Retry action after the user installed or started something. */
    public CompletableFuture<Status> retry() {
        AIConfig current = config;
        return current == null ? CompletableFuture.completedFuture(status) : configure(current);
    }

    /**
     * Download the configured model through the server, with the user's consent already given. Checks the
     * free space first; a cancelled download resumes next time from the parts the server kept.
     */
    public CompletableFuture<Status> download() {
        AIConfig current = config;
        if (current == null || current.localBackend() != AIConfig.LocalBackend.OLLAMA)
            return CompletableFuture.completedFuture(status);
        long ticket = generation;
        var flag = new AtomicBoolean();
        downloading = flag;
        return CompletableFuture.supplyAsync(() -> {
            try {
                pull(current, flag);
            } catch (IOException failure) {
                if (flag.get()) publish(status.with(State.UNAVAILABLE, "Download cancelled; Download again resumes it.")
                        .withError(AIError.MODEL_DOWNLOAD_REQUIRED));
                else publish(status.with(State.UNAVAILABLE, failure.getMessage()).withError(errorOf(failure)));
                return status;
            } finally {
                downloading = null;
            }
            return null;
        }, task -> Thread.ofVirtual().name("turbo-ai-download").start(task)).thenCompose(done -> done != null || ticket != generation
                ? CompletableFuture.completedFuture(status) : configure(current));
    }

    public void cancelDownload() {
        var flag = downloading;
        if (flag != null) flag.set(true);
    }

    /** Stop what this manager owns. An external server keeps running with its models. */
    @Override
    public void close() {
        if (closed) return;
        closed = true;
        generation++;
        cancelDownload();
        try {
            executor.submit(this::stopOwned).get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            stopOwned();
        }
        executor.shutdownNow();
    }

    private void apply(AIConfig next, long ticket) {
        if (monitor != null) monitor.cancel(false);
        boolean wanted = next.mode() == AIConfig.Mode.LOCAL || next.mode() == AIConfig.Mode.CLOUD;
        URI endpoint = root(next);
        if (!wanted || owned != null && endpoint.getPort() != ownedPort) stopOwned();
        if (!wanted) {
            publish(Status.OFF);
            return;
        }
        try {
            if (next.localBackend() == AIConfig.LocalBackend.OLLAMA) ensureOllama(next, endpoint, ticket);
            else ensureLlama(next, endpoint, ticket);
        } catch (IOException failure) {
            if (ticket == generation) publish(status.with(State.ERROR, failure.getMessage()).withError(errorOf(failure)));
        }
        if (ticket == generation && !closed && status.state() != State.OFF)
            monitor = executor.scheduleWithFixedDelay(() -> health(ticket), healthInterval.toMillis(), healthInterval.toMillis(),
                    TimeUnit.MILLISECONDS);
    }

    private void ensureOllama(AIConfig next, URI endpoint, long ticket) throws IOException {
        // Cloud mode only uses an already running local server as a fallback; it starts nothing.
        boolean detectOnly = next.mode() == AIConfig.Mode.CLOUD;
        AIConfig.LocalRuntime policy = detectOnly ? AIConfig.LocalRuntime.EXTERNAL : next.localRuntime();
        var base = new Status(State.DETECTING_RUNTIME, Ownership.NONE, "Ollama", endpoint.toString(), next.localModel(), "",
                null, 0, List.of(), 0);
        publish(base);
        adoptRecorded(endpoint);
        String version = ollamaVersion(endpoint);
        Ownership ownership;
        if (version == null && owned != null && owned.alive()) version = waitForOllama(endpoint);
        if (version == null) {
            if (policy == AIConfig.LocalRuntime.EXTERNAL) {
                publish(base.with(State.UNAVAILABLE, "No Ollama server answers at " + endpoint
                        + ". Start Ollama, or choose Automatic so TurboJ starts it.").withError(AIError.RUNTIME_NOT_RUNNING));
                return;
            }
            Optional<Path> executable = locator.ollama();
            if (executable.isEmpty()) {
                publish(base.with(State.UNAVAILABLE, "Ollama is not installed. Install it from ollama.com or with "
                        + "'brew install ollama', then choose Retry.").withError(AIError.RUNTIME_NOT_INSTALLED));
                return;
            }
            publish(base.with(State.STARTING_RUNTIME, "Starting " + executable.get() + " on " + endpoint.getHost() + ":" + endpoint.getPort()));
            startOllama(executable.get(), endpoint, next);
            version = waitForOllama(endpoint);
            if (version == null) {
                stopOwned();
                throw new LocalException(AIError.RUNTIME_NOT_RUNNING, "Ollama did not start; see " + log("ollama"));
            }
            ownership = Ownership.MANAGED;
        } else {
            ownership = owned != null && owned.alive() && endpoint.getPort() == ownedPort ? Ownership.MANAGED : Ownership.EXTERNAL;
            if (ownership == Ownership.EXTERNAL && policy == AIConfig.LocalRuntime.MANAGED) {
                publish(base.with(State.UNAVAILABLE, "Another Ollama server already uses port " + endpoint.getPort()
                        + ". Choose External to use it, or another port for the TurboJ server.").withError(AIError.INVALID_CONFIGURATION));
                return;
            }
        }
        if (ticket != generation) return;
        String runtime = "Ollama " + version + architectureNote();
        base = new Status(State.DETECTING_RUNTIME, ownership, runtime, endpoint.toString(), next.localModel(), "", null, 0, List.of(), 0);
        List<String> installed = ollamaModels(endpoint);
        base = new Status(State.DETECTING_RUNTIME, ownership, runtime, endpoint.toString(), next.localModel(), "", null, 0, installed, 0);
        String wanted = ModelCatalog.canonical(next.localModel());
        if (installed.stream().map(ModelCatalog::canonical).noneMatch(wanted::equals)) {
            String size = ModelCatalog.find(next.localModel()).map(m -> " (" + m.size() + ", " + m.license() + ", from " + m.source() + ")").orElse("");
            publish(base.with(State.UNAVAILABLE, "Local completion model not installed: " + next.localModel() + size + ".")
                    .withError(AIError.MODEL_DOWNLOAD_REQUIRED));
            return;
        }
        JsonObject show = post(endpoint, "/api/show", object("model", next.localModel()), PROBE.multipliedBy(5));
        if (show.has("remote_host") || show.has("remote_model")) {
            publish(base.with(State.UNAVAILABLE, "Local AI refuses remotely hosted Ollama models.").withError(AIError.INVALID_CONFIGURATION));
            return;
        }
        if (!(show.has("template") && show.get("template").getAsString().contains(".Suffix"))) {
            publish(base.with(State.UNAVAILABLE, "Model '" + next.localModel() + "' has no fill-in-the-middle template. "
                    + "Choose a FIM base model such as " + ModelCatalog.DEFAULT + ".").withError(AIError.UNSUPPORTED_MODEL_CAPABILITY));
            return;
        }
        if (detectOnly) {
            publish(base.with(State.IDLE, "Local fallback available"));
            return;
        }
        publish(base.with(State.LOADING_MODEL, "Loading " + next.localModel()));
        long start = nanos.getAsLong();
        load(endpoint, next);
        if (ticket != generation) return;
        long millis = TimeUnit.NANOSECONDS.toMillis(nanos.getAsLong() - start);
        publish(new Status(State.READY, ownership, runtime, endpoint.toString(), next.localModel(),
                ownership == Ownership.MANAGED ? "Started by TurboJ" : "Using the running server", null, 1, installed, millis));
    }

    private void ensureLlama(AIConfig next, URI endpoint, long ticket) throws IOException {
        var base = new Status(State.DETECTING_RUNTIME, Ownership.NONE, "llama.cpp", endpoint.toString(), next.localModel(), "",
                null, 0, List.of(), 0);
        publish(base);
        String health = llamaHealth(endpoint);
        Ownership ownership = Ownership.EXTERNAL;
        if (health == null) {
            if (next.mode() == AIConfig.Mode.CLOUD || next.localRuntime() == AIConfig.LocalRuntime.EXTERNAL) {
                publish(base.with(State.UNAVAILABLE, "No llama-server answers at " + endpoint + ".").withError(AIError.RUNTIME_NOT_RUNNING));
                return;
            }
            Optional<Path> executable = locator.llamaServer();
            if (executable.isEmpty()) {
                publish(base.with(State.UNAVAILABLE, "llama-server is not installed ('brew install llama.cpp').")
                        .withError(AIError.RUNTIME_NOT_INSTALLED));
                return;
            }
            Path model = Path.of(next.localModel());
            if (!next.localModel().endsWith(".gguf") || !Files.isRegularFile(model)) {
                publish(base.with(State.UNAVAILABLE, "For a TurboJ-managed llama.cpp server, set Model to a local .gguf file.")
                        .withError(AIError.INVALID_CONFIGURATION));
                return;
            }
            publish(base.with(State.STARTING_RUNTIME, "Starting " + executable.get()));
            owned = launcher.start(List.of(executable.get().toString(), "-m", model.toString(), "--host", endpoint.getHost(),
                    "--port", Integer.toString(endpoint.getPort()), "-c", Integer.toString(AIConfig.LOCAL_CONTEXT_TOKENS)),
                    Map.of(), log("llama-server"));
            ownedPort = endpoint.getPort();
            ownership = Ownership.MANAGED;
        }
        long deadline = nanos.getAsLong() + START.multipliedBy(4).toNanos();
        while (!"ok".equals(health = llamaHealth(endpoint))) {
            if (ticket != generation || nanos.getAsLong() > deadline || owned != null && ownership == Ownership.MANAGED && !owned.alive())
                throw new LocalException(AIError.MODEL_LOAD_FAILED, "llama-server did not become ready; see " + log("llama-server"));
            publish(base.with(State.LOADING_MODEL, "Loading model"));
            sleep(250);
        }
        publish(new Status(State.READY, ownership, "llama.cpp", endpoint.toString(), next.localModel(),
                ownership == Ownership.MANAGED ? "Started by TurboJ" : "Using the running server", null, 1, List.of(), 0));
    }

    /** Load with the context size of every later request, then one token of FIM so the first suggestion is warm. */
    private void load(URI endpoint, AIConfig next) throws IOException {
        var options = new JsonObject();
        options.addProperty("num_ctx", AIConfig.LOCAL_CONTEXT_TOKENS);
        var loadRequest = object("model", next.localModel());
        loadRequest.addProperty("keep_alive", next.keepAlive());
        loadRequest.addProperty("stream", false);
        loadRequest.add("options", options);
        post(endpoint, "/api/generate", loadRequest, Duration.ofSeconds(Math.max(60, next.timeoutSeconds())));
        var warm = object("model", next.localModel());
        warm.addProperty("prompt", "class A {\n    int one() {\n        return ");
        warm.addProperty("suffix", ";\n    }\n}\n");
        warm.addProperty("keep_alive", next.keepAlive());
        warm.addProperty("stream", false);
        var warmOptions = options.deepCopy();
        warmOptions.addProperty("num_predict", 1);
        warmOptions.addProperty("temperature", 0.1);
        warm.add("options", warmOptions);
        post(endpoint, "/api/generate", warm, Duration.ofSeconds(Math.max(60, next.timeoutSeconds())));
    }

    private void pull(AIConfig current, AtomicBoolean cancelled) throws IOException {
        URI endpoint = root(current);
        var model = ModelCatalog.find(current.localModel());
        Path store = modelStore();
        long needed = model.map(m -> m.bytes() + m.bytes() / 10).orElse(2_000_000_000L) + 512L * 1024 * 1024;
        long free = freeSpace.applyAsLong(store);
        if (free < needed)
            throw new LocalException(AIError.MODEL_DOWNLOAD_REQUIRED, String.format(Locale.ROOT,
                    "Not enough disk space for %s: %.1f GB needed in %s, %.1f GB free.", current.localModel(), needed / 1e9, store, free / 1e9));
        publish(status.with(State.DOWNLOADING_MODEL, "Downloading " + current.localModel() + model.map(m -> " (" + m.size() + ")").orElse("")));
        var body = object("model", current.localModel());
        body.addProperty("stream", true);
        var request = HttpRequest.newBuilder(endpoint.resolve("/api/pull")).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.toJson(body))).build();
        HttpResponse<InputStream> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Download interrupted.");
        }
        var totals = new java.util.LinkedHashMap<String, long[]>();
        try (var reader = new BufferedReader(new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
            if (response.statusCode() != 200) throw new LocalException(AIError.MODEL_NOT_FOUND, "The server refused to download "
                    + current.localModel() + " (HTTP " + response.statusCode() + ").");
            String line;
            while ((line = reader.readLine()) != null) {
                if (cancelled.get() || closed) throw new IOException("Download cancelled.");
                if (line.isBlank()) continue;
                JsonObject event = JsonParser.parseString(line).getAsJsonObject();
                if (event.has("error")) {
                    String message = event.get("error").getAsString().toLowerCase(Locale.ROOT);
                    throw new LocalException(message.contains("not exist") || message.contains("not found")
                            ? AIError.MODEL_NOT_FOUND : AIError.CONNECTION_FAILED,
                            "Download of " + current.localModel() + " failed: the registry has no such model or could not be reached.");
                }
                if (event.has("digest") && event.has("total")) {
                    totals.put(event.get("digest").getAsString(), new long[] {event.get("total").getAsLong(),
                            event.has("completed") ? event.get("completed").getAsLong() : 0});
                    long total = totals.values().stream().mapToLong(v -> v[0]).sum();
                    long done = totals.values().stream().mapToLong(v -> v[1]).sum();
                    double progress = total == 0 ? 0 : (double) done / total;
                    if (Math.abs(progress - status.progress()) >= 0.01)
                        publish(new Status(State.DOWNLOADING_MODEL, status.ownership(), status.runtime(), status.endpoint(),
                                status.model(), status.detail(), null, progress, status.installed(), 0));
                }
                if (event.has("status") && "success".equals(event.get("status").getAsString())) return;
            }
        }
        throw new LocalException(AIError.CONNECTION_FAILED, "Download of " + current.localModel() + " ended before it completed.");
    }

    private void health(long ticket) {
        if (ticket != generation || closed) return;
        AIConfig current = config;
        if (current == null || status.state() == State.DOWNLOADING_MODEL) return;
        URI endpoint = root(current);
        boolean ollama = current.localBackend() == AIConfig.LocalBackend.OLLAMA;
        boolean alive = ollama ? ollamaVersion(endpoint) != null : "ok".equals(llamaHealth(endpoint));
        if (!alive) {
            if (status.ownership() == Ownership.MANAGED && (owned == null || !owned.alive())) {
                long now = nanos.getAsLong();
                restarts.removeIf(at -> now - at > RESTART_WINDOW);
                if (restarts.size() >= MAX_RESTARTS) {
                    publish(status.with(State.ERROR, "The TurboJ-managed server stopped " + MAX_RESTARTS
                            + " times in 10 minutes; see " + log(ollama ? "ollama" : "llama-server") + ".").withError(AIError.RUNTIME_NOT_RUNNING));
                    return;
                }
                restarts.add(now);
                owned = null;
                apply(current, ticket);
            } else if (status.ready()) {
                publish(status.with(State.UNAVAILABLE, "The local server stopped answering.").withError(AIError.RUNTIME_NOT_RUNNING));
            }
            return;
        }
        if (!status.ready() && status.error() != null && status.error().retryable()) {
            apply(current, ticket);
            return;
        }
        if (ollama && status.ready()) {
            try {
                boolean loaded = loadedModels(endpoint).stream().map(ModelCatalog::canonical)
                        .anyMatch(ModelCatalog.canonical(current.localModel())::equals);
                State next = loaded ? State.READY : State.IDLE;
                if (next != status.state())
                    publish(status.with(next, loaded ? status.detail() : "Unloaded after " + current.keepAlive() + " idle; it reloads on the next suggestion"));
            } catch (IOException ignored) {
                // The next probe decides.
            }
        }
    }

    private void startOllama(Path executable, URI endpoint, AIConfig next) throws IOException {
        var environment = new java.util.HashMap<String, String>();
        environment.put("OLLAMA_HOST", endpoint.getHost() + ":" + endpoint.getPort());
        environment.put("OLLAMA_KEEP_ALIVE", next.keepAlive());
        environment.put("OLLAMA_NUM_PARALLEL", "1");
        environment.put("OLLAMA_MAX_LOADED_MODELS", "1");
        environment.put("OLLAMA_CONTEXT_LENGTH", Integer.toString(AIConfig.LOCAL_CONTEXT_TOKENS));
        environment.put("OLLAMA_NO_CLOUD", "1");
        owned = launcher.start(List.of(executable.toString(), "serve"), environment, log("ollama"));
        ownedPort = endpoint.getPort();
        record(executable, endpoint);
    }

    private String waitForOllama(URI endpoint) {
        long deadline = nanos.getAsLong() + START.toNanos();
        while (nanos.getAsLong() < deadline && !closed) {
            String version = ollamaVersion(endpoint);
            if (version != null) return version;
            if (owned == null || !owned.alive()) return null;
            sleep(150);
        }
        return null;
    }

    private void stopOwned() {
        if (owned != null) {
            owned.stop(Duration.ofSeconds(3));
            owned = null;
            ownedPort = -1;
        }
        try {
            Files.deleteIfExists(stateDirectory.resolve("managed-runtime.json"));
        } catch (IOException ignored) {
        }
    }

    /** The server this manager started before a crash of the whole application: still ours to stop. */
    private void adoptRecorded(URI endpoint) {
        if (owned != null) return;
        Path file = stateDirectory.resolve("managed-runtime.json");
        try {
            if (!Files.isRegularFile(file)) return;
            JsonObject recorded = JsonParser.parseString(Files.readString(file)).getAsJsonObject();
            long pid = recorded.get("serverPid").getAsLong();
            String executable = recorded.get("executable").getAsString();
            int port = recorded.get("port").getAsInt();
            Optional<ProcessHandle> handle = ProcessHandle.of(pid);
            if (port == endpoint.getPort() && handle.isPresent() && handle.get().isAlive()
                    && handle.get().info().command().map(executable::equals).orElse(false)) {
                ProcessHandle server = handle.get();
                owned = new ProcessLauncher.ManagedProcess() {
                    public long pid() { return server.pid(); }
                    public Optional<Long> serverPid() { return Optional.of(server.pid()); }
                    public boolean alive() { return server.isAlive(); }
                    public void stop(Duration grace) {
                        server.destroy();
                        try {
                            server.onExit().get(grace.toMillis(), TimeUnit.MILLISECONDS);
                        } catch (Exception e) {
                            server.destroyForcibly();
                        }
                    }
                };
                ownedPort = port;
            } else Files.deleteIfExists(file);
        } catch (IOException | RuntimeException ignored) {
            try {
                Files.deleteIfExists(file);
            } catch (IOException alsoIgnored) {
            }
        }
    }

    private void record(Path executable, URI endpoint) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        Optional<Long> server = owned.serverPid();
        while (server.isEmpty() && System.nanoTime() < deadline && owned.alive()) {
            sleep(20);
            server = owned.serverPid();
        }
        var recorded = new JsonObject();
        recorded.addProperty("serverPid", server.orElse(owned.pid()));
        recorded.addProperty("watchdogPid", owned.pid());
        recorded.addProperty("executable", executable.toString());
        recorded.addProperty("port", endpoint.getPort());
        try {
            Files.createDirectories(stateDirectory);
            Files.writeString(stateDirectory.resolve("managed-runtime.json"), JSON.toJson(recorded));
        } catch (IOException ignored) {
            // The watchdog still stops the server with this JVM.
        }
    }

    private String ollamaVersion(URI endpoint) {
        try {
            JsonObject version = get(endpoint, "/api/version", PROBE);
            return version.has("version") ? version.get("version").getAsString() : "";
        } catch (IOException e) {
            return null;
        }
    }

    private List<String> ollamaModels(URI endpoint) throws IOException {
        return names(get(endpoint, "/api/tags", PROBE.multipliedBy(5)), "models");
    }

    private List<String> loadedModels(URI endpoint) throws IOException {
        return names(get(endpoint, "/api/ps", PROBE), "models");
    }

    private static List<String> names(JsonObject listing, String array) {
        var names = new ArrayList<String>();
        if (listing.has(array) && listing.get(array).isJsonArray())
            for (JsonElement item : (JsonArray) listing.get(array))
                if (item.isJsonObject() && item.getAsJsonObject().has("name")) names.add(item.getAsJsonObject().get("name").getAsString());
        return names;
    }

    private String llamaHealth(URI endpoint) {
        try {
            var response = http.send(HttpRequest.newBuilder(endpoint.resolve("/health")).timeout(PROBE).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 503) return "loading";
            JsonObject health = JsonParser.parseString(response.body()).getAsJsonObject();
            return health.has("status") ? health.get("status").getAsString() : null;
        } catch (IOException | RuntimeException e) {
            return null;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        }
    }

    private JsonObject get(URI endpoint, String path, Duration timeout) throws IOException {
        return send(HttpRequest.newBuilder(endpoint.resolve(path)).timeout(timeout).GET().build());
    }

    private JsonObject post(URI endpoint, String path, JsonObject body, Duration timeout) throws IOException {
        return send(HttpRequest.newBuilder(endpoint.resolve(path)).timeout(timeout).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JSON.toJson(body))).build());
    }

    private JsonObject send(HttpRequest request) throws IOException {
        try {
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            JsonObject parsed;
            try {
                parsed = JsonParser.parseString(response.body()).getAsJsonObject();
            } catch (RuntimeException e) {
                parsed = new JsonObject();
            }
            if (response.statusCode() / 100 != 2) {
                String message = parsed.has("error") && parsed.get("error").isJsonPrimitive() ? parsed.get("error").getAsString() : "";
                String lower = message.toLowerCase(Locale.ROOT);
                AIError error = lower.contains("not found") ? AIError.MODEL_NOT_FOUND
                        : lower.contains("memory") || lower.contains("load") ? AIError.MODEL_LOAD_FAILED
                        : response.statusCode() == 404 ? AIError.UNSUPPORTED_ENDPOINT : AIError.INFERENCE_FAILED;
                throw new LocalException(error, switch (error) {
                    case MODEL_NOT_FOUND -> "The local server has no model '" + config.localModel() + "'.";
                    case MODEL_LOAD_FAILED -> "The local server could not load '" + config.localModel() + "'; it may need more memory than is free.";
                    case UNSUPPORTED_ENDPOINT -> request.uri() + " is not an Ollama API (HTTP 404).";
                    default -> "The local server failed (HTTP " + response.statusCode() + ").";
                });
            }
            return parsed;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted.");
        } catch (ConnectException e) {
            throw new LocalException(AIError.RUNTIME_NOT_RUNNING, "No local server answers at " + request.uri().getAuthority() + ".");
        }
    }

    private static JsonObject object(String key, String value) {
        var object = new JsonObject();
        object.addProperty(key, value);
        return object;
    }

    /** Native APIs live at the server root, whatever path was typed after the port. */
    static URI root(AIConfig config) {
        URI uri = URI.create(config.localEndpoint());
        int port = uri.getPort() > 0 ? uri.getPort() : config.localBackend() == AIConfig.LocalBackend.OLLAMA ? 11434 : 8080;
        return URI.create(uri.getScheme() + "://" + uri.getHost() + ":" + port);
    }

    private Path log(String name) {
        return stateDirectory.resolve("logs").resolve(name + "-managed.log");
    }

    private static Path modelStore() {
        String custom = System.getenv("OLLAMA_MODELS");
        return custom != null && !custom.isBlank() ? Path.of(custom) : Path.of(System.getProperty("user.home"), ".ollama", "models");
    }

    private static String architectureNote() {
        return architectureNote(System.getProperty("os.name", ""), System.getProperty("os.arch", ""));
    }

    static String architectureNote(String os, String arch) {
        return os.startsWith("Mac") && !arch.equals("aarch64") ? " (Intel Macs are not verified)" : "";
    }

    private static long usableSpace(Path store) {
        Path probe = store;
        while (probe != null && !Files.exists(probe)) probe = probe.getParent();
        try {
            return probe == null ? Long.MAX_VALUE : Files.getFileStore(probe).getUsableSpace();
        } catch (IOException e) {
            return Long.MAX_VALUE;
        }
    }

    private static AIError errorOf(Throwable failure) {
        return failure instanceof LocalException local ? local.error : AIError.CONNECTION_FAILED;
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private void publish(Status next) {
        status = next;
        try {
            listener.accept(next);
        } catch (RuntimeException ignored) {
            // A listener's failure must not stop the lifecycle.
        }
    }

    static final class LocalException extends IOException {
        final AIError error;

        LocalException(AIError error, String message) {
            super(message);
            this.error = error;
        }
    }
}
