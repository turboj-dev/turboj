package dev.turboj.ai.local;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import dev.turboj.ai.AIConfig;
import dev.turboj.ai.AIError;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LocalModelManagerTest {
    private static final String MODEL = "qwen2.5-coder:1.5b-base";
    @TempDir Path directory;
    private final List<LocalModelManager.Status> statuses = new CopyOnWriteArrayList<>();
    private final List<AutoCloseable> cleanup = new ArrayList<>();

    @AfterEach
    void close() throws Exception {
        for (AutoCloseable item : cleanup.reversed()) item.close();
    }

    @Test
    void noRuntimeInstalledSaysHowToInstallOne() throws Exception {
        int port = freePort();
        var status = manager(locator(null), unusedLauncher()).configure(config(port, AIConfig.LocalRuntime.AUTOMATIC)).get(10, TimeUnit.SECONDS);
        assertEquals(LocalModelManager.State.UNAVAILABLE, status.state());
        assertEquals(AIError.RUNTIME_NOT_INSTALLED, status.error());
        assertTrue(status.detail().contains("brew install ollama"), status.detail());
    }

    @Test
    void anInstalledStoppedRuntimeIsStartedOnLoopbackOwnedLoadedAndWarmed() throws Exception {
        var ollama = new FakeOllama(List.of(MODEL));
        int port = ollama.port;
        var launched = new AtomicInteger();
        var environment = new ArrayList<Map<String, String>>();
        var process = new FakeProcess(ollama);
        ProcessLauncher launcher = (command, env, log) -> {
            launched.incrementAndGet();
            environment.add(env);
            assertEquals(List.of(executable().toString(), "serve"), command);
            ollama.start();
            return process;
        };
        var manager = manager(locator(executable()), launcher);
        var status = manager.configure(config(port, AIConfig.LocalRuntime.AUTOMATIC)).get(20, TimeUnit.SECONDS);
        assertEquals(LocalModelManager.State.READY, status.state(), status.detail());
        assertEquals(LocalModelManager.Ownership.MANAGED, status.ownership());
        assertEquals(1, launched.get());
        assertEquals("127.0.0.1:" + port, environment.getFirst().get("OLLAMA_HOST"), "bound to loopback");
        assertEquals("30m", environment.getFirst().get("OLLAMA_KEEP_ALIVE"));
        var generations = ollama.bodies("/api/generate");
        assertEquals(2, generations.size(), "one load, one warm-up");
        for (JsonObject body : generations) {
            assertEquals(AIConfig.LOCAL_CONTEXT_TOKENS, body.getAsJsonObject("options").get("num_ctx").getAsInt(),
                    "the warm-up uses the request's context size, or Ollama would reload");
            assertEquals("30m", body.get("keep_alive").getAsString());
        }
        assertTrue(generations.get(1).has("suffix"), "the warm-up is a real FIM request");
        assertTrue(statuses.stream().map(LocalModelManager.Status::state).toList()
                .containsAll(List.of(LocalModelManager.State.DETECTING_RUNTIME, LocalModelManager.State.STARTING_RUNTIME,
                        LocalModelManager.State.LOADING_MODEL, LocalModelManager.State.READY)));
        manager.close();
        assertTrue(process.stopped.get(), "the server TurboJ started stops with it");
        assertFalse(Files.exists(directory.resolve("managed-runtime.json")));
    }

    @Test
    void aHealthyRunningServerIsReusedAndNeverStopped() throws Exception {
        var ollama = new FakeOllama(List.of(MODEL)).start();
        int port = ollama.port;
        var manager = manager(locator(executable()), unusedLauncher());
        var status = manager.configure(config(port, AIConfig.LocalRuntime.AUTOMATIC)).get(10, TimeUnit.SECONDS);
        assertEquals(LocalModelManager.State.READY, status.state(), status.detail());
        assertEquals(LocalModelManager.Ownership.EXTERNAL, status.ownership());
        assertEquals("Ollama 0.40.10", status.runtime());
        manager.close();
        assertTrue(ollama.answers(), "an external server keeps running");
    }

    @Test
    void aMissingModelIsReportedWithSizeAndDownloadsOnlyWhenAsked() throws Exception {
        var ollama = new FakeOllama(List.of("qwen2.5-coder:0.5b-base")).start();
        int port = ollama.port;
        var manager = manager(locator(executable()), unusedLauncher());
        var status = manager.configure(config(port, AIConfig.LocalRuntime.EXTERNAL)).get(10, TimeUnit.SECONDS);
        assertEquals(LocalModelManager.State.UNAVAILABLE, status.state());
        assertEquals(AIError.MODEL_DOWNLOAD_REQUIRED, status.error());
        assertEquals("Model not installed", status.summary());
        assertTrue(status.detail().contains("986 MB, Apache-2.0, from registry.ollama.ai/library/qwen2.5-coder/1.5b-base"), status.detail());
        assertEquals(List.of("qwen2.5-coder:0.5b-base"), status.installed());
        assertTrue(ollama.bodies("/api/pull").isEmpty(), "nothing is downloaded without consent");
        status = manager.download().get(20, TimeUnit.SECONDS);
        assertEquals(LocalModelManager.State.READY, status.state(), status.detail());
        assertEquals(MODEL, ollama.bodies("/api/pull").getFirst().get("model").getAsString());
        assertTrue(statuses.stream().anyMatch(s -> s.state() == LocalModelManager.State.DOWNLOADING_MODEL && s.progress() > 0.4 && s.progress() < 0.6),
                "progress is reported");
    }

    @Test
    void aDownloadStopsWhenTheDiskIsTooFullOrTheUserCancels() throws Exception {
        var ollama = new FakeOllama(List.of()).start();
        int port = ollama.port;
        var full = new LocalModelManager(directory, statuses::add, locator(executable()), unusedLauncher(), System::nanoTime,
                Duration.ofSeconds(30), store -> 100_000_000L);
        cleanup.add(full);
        full.configure(config(port, AIConfig.LocalRuntime.EXTERNAL)).get(10, TimeUnit.SECONDS);
        var status = full.download().get(10, TimeUnit.SECONDS);
        assertEquals(AIError.MODEL_DOWNLOAD_REQUIRED, status.error());
        assertTrue(status.detail().startsWith("Not enough disk space for " + MODEL), status.detail());
        assertTrue(ollama.bodies("/api/pull").isEmpty());

        ollama.slowPull = new CountDownLatch(1);
        var manager = manager(locator(executable()), unusedLauncher());
        manager.configure(config(port, AIConfig.LocalRuntime.EXTERNAL)).get(10, TimeUnit.SECONDS);
        var download = manager.download();
        assertTrue(ollama.pulling.await(5, TimeUnit.SECONDS));
        manager.cancelDownload();
        ollama.slowPull.countDown();
        status = download.get(10, TimeUnit.SECONDS);
        assertEquals(LocalModelManager.State.UNAVAILABLE, status.state());
        assertEquals("Download cancelled; Download again resumes it.", status.detail());
    }

    @Test
    void externalPolicyNeverStartsAndManagedPolicyRefusesSomeoneElsesPort() throws Exception {
        var ollama = new FakeOllama(List.of(MODEL));
        int port = ollama.port;
        var status = manager(locator(executable()), unusedLauncher()).configure(config(port, AIConfig.LocalRuntime.EXTERNAL))
                .get(10, TimeUnit.SECONDS);
        assertEquals(AIError.RUNTIME_NOT_RUNNING, status.error());
        ollama.start();
        status = manager(locator(executable()), unusedLauncher()).configure(config(port, AIConfig.LocalRuntime.MANAGED))
                .get(10, TimeUnit.SECONDS);
        assertEquals(AIError.INVALID_CONFIGURATION, status.error());
        assertTrue(status.detail().startsWith("Another Ollama server already uses port " + port), status.detail());
    }

    @Test
    void aModelWithoutFimIsRefusedAndAModelSwitchLoadsTheNewOne() throws Exception {
        var ollama = new FakeOllama(List.of(MODEL, "llama3:latest")).start();
        int port = ollama.port;
        var manager = manager(locator(executable()), unusedLauncher());
        var status = manager.configure(config(port, AIConfig.LocalRuntime.EXTERNAL).withLocal(AIConfig.LocalBackend.OLLAMA,
                "http://127.0.0.1:" + port, "llama3", AIConfig.LocalRuntime.EXTERNAL)).get(10, TimeUnit.SECONDS);
        assertEquals(AIError.UNSUPPORTED_MODEL_CAPABILITY, status.error(), "llama3 resolves to llama3:latest, which has no .Suffix");
        status = manager.configure(config(port, AIConfig.LocalRuntime.EXTERNAL)).get(10, TimeUnit.SECONDS);
        assertEquals(LocalModelManager.State.READY, status.state());
        assertEquals(MODEL, ollama.bodies("/api/generate").getLast().get("model").getAsString());
    }

    @Test
    void anOwnedServerThatCrashesIsRestartedAndUnloadedModelsShowAsIdle() throws Exception {
        var ollama = new FakeOllama(List.of(MODEL));
        int port = ollama.port;
        var processes = new CopyOnWriteArrayList<FakeProcess>();
        ProcessLauncher launcher = (command, env, log) -> {
            ollama.start();
            var process = new FakeProcess(ollama);
            processes.add(process);
            return process;
        };
        var manager = new LocalModelManager(directory, statuses::add, locator(executable()), launcher, System::nanoTime,
                Duration.ofMillis(100), store -> Long.MAX_VALUE);
        cleanup.add(manager);
        assertEquals(LocalModelManager.State.READY, manager.configure(config(port, AIConfig.LocalRuntime.AUTOMATIC)).get(20, TimeUnit.SECONDS).state());
        ollama.loaded = false;
        await(() -> manager.status().state() == LocalModelManager.State.IDLE);
        assertTrue(manager.ready(), "an unloaded model still takes requests; the server reloads it");
        processes.getFirst().crash();
        await(() -> processes.size() == 2 && manager.status().state() == LocalModelManager.State.READY);
        assertEquals(LocalModelManager.Ownership.MANAGED, manager.status().ownership());
    }

    @Test
    void theWatchdogStopsTheServerWhenItsOwnerDiesAndForwardsTerm() throws Exception {
        Process owner = new ProcessBuilder("sleep", "1").start();
        Process watchdog = new ProcessBuilder("/bin/sh", "-c", ProcessLauncher.WatchdogScript.TEXT, "turbo-ai-watchdog",
                Long.toString(owner.pid()), "sleep", "300").start();
        ProcessHandle server = awaitChild(watchdog);
        assertTrue(server.isAlive());
        assertTrue(owner.waitFor(5, TimeUnit.SECONDS));
        assertTrue(server.onExit().get(5, TimeUnit.SECONDS) != null && !server.isAlive(), "no orphan after the owner is gone");
        assertTrue(watchdog.waitFor(5, TimeUnit.SECONDS));

        Process watched = new ProcessBuilder("/bin/sh", "-c", ProcessLauncher.WatchdogScript.TEXT, "turbo-ai-watchdog",
                Long.toString(ProcessHandle.current().pid()), "sleep", "300").start();
        ProcessHandle child = awaitChild(watched);
        watched.destroy();
        child.onExit().get(5, TimeUnit.SECONDS);
        assertFalse(child.isAlive(), "TERM to the watchdog stops the server");
    }

    @Test
    void intelMacsAreFlaggedAsUnverified() {
        assertEquals(" (Intel Macs are not verified)", LocalModelManager.architectureNote("Mac OS X", "x86_64"));
        assertEquals("", LocalModelManager.architectureNote("Mac OS X", "aarch64"));
        assertEquals("", LocalModelManager.architectureNote("Linux", "x86_64"));
    }

    private static ProcessHandle awaitChild(Process process) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            Optional<ProcessHandle> child = process.toHandle().children().findFirst();
            if (child.isPresent()) return child.get();
            Thread.sleep(10);
        }
        throw new AssertionError("no child");
    }

    private static void await(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < end) {
            if (condition.getAsBoolean()) return;
            Thread.sleep(20);
        }
        throw new AssertionError("condition not reached");
    }

    private LocalModelManager manager(RuntimeLocator locator, ProcessLauncher launcher) {
        var manager = new LocalModelManager(directory, statuses::add, locator, launcher, System::nanoTime, Duration.ofSeconds(30),
                store -> Long.MAX_VALUE);
        cleanup.add(manager);
        return manager;
    }

    private Path executable() throws IOException {
        Path bin = Files.createDirectories(directory.resolve("bin"));
        Path ollama = bin.resolve("ollama");
        if (!Files.exists(ollama)) {
            Files.writeString(ollama, "#!/bin/sh\n");
            ollama.toFile().setExecutable(true);
        }
        return ollama.toAbsolutePath().normalize();
    }

    private RuntimeLocator locator(Path executable) {
        return new RuntimeLocator(executable == null ? List.of() : List.of(executable.getParent()), List.of());
    }

    private static ProcessLauncher unusedLauncher() {
        return (command, environment, log) -> {
            throw new AssertionError("nothing may be started");
        };
    }

    private static AIConfig config(int port, AIConfig.LocalRuntime runtime) {
        return new AIConfig(AIConfig.Mode.LOCAL, AIConfig.LocalBackend.OLLAMA, "http://127.0.0.1:" + port, MODEL, null, null, true,
                false, false, 200, 30, 64, 4_096, "", false).withLocal(AIConfig.LocalBackend.OLLAMA, "http://127.0.0.1:" + port, MODEL, runtime);
    }

    private static int freePort() throws IOException {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private final class FakeProcess implements ProcessLauncher.ManagedProcess {
        final FakeOllama server;
        final AtomicBoolean stopped = new AtomicBoolean();
        volatile boolean alive = true;

        FakeProcess(FakeOllama server) {
            this.server = server;
        }

        void crash() {
            alive = false;
            server.stop();
        }

        public long pid() { return 1; }
        public Optional<Long> serverPid() { return Optional.of(1L); }
        public boolean alive() { return alive; }

        public void stop(Duration grace) {
            stopped.set(true);
            alive = false;
            server.stop();
        }
    }

    /** Enough of Ollama 0.40's API: version, tags, ps, show, generate, pull. */
    /**
     * Enough of Ollama 0.40's API. It holds its port from the start, so no other test can take it, and answers only
     * while "running": until then and after a crash it drops connections, as a port with no server does.
     */
    private final class FakeOllama implements AutoCloseable {
        final int port;
        final List<String> models;
        final List<String[]> requests = new CopyOnWriteArrayList<>();
        volatile boolean loaded = true, running;
        volatile CountDownLatch slowPull;
        final CountDownLatch pulling = new CountDownLatch(1);
        final HttpServer server;

        FakeOllama(List<String> models) throws IOException {
            this.models = new CopyOnWriteArrayList<>(models);
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
            server.createContext("/", exchange -> {
                if (!running) { exchange.close(); return; }
                handle(exchange);
            });
            server.start();
            port = server.getAddress().getPort();
            cleanup.add(this);
        }

        FakeOllama start() {
            running = true;
            return this;
        }

        void stop() {
            running = false;
        }

        boolean answers() {
            return running;
        }

        List<JsonObject> bodies(String path) {
            return requests.stream().filter(r -> r[0].equals(path)).map(r -> JsonParser.parseString(r[1]).getAsJsonObject()).toList();
        }

        private void handle(HttpExchange exchange) throws IOException {
            String path = exchange.getRequestURI().getPath();
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(new String[] {path, body.isEmpty() ? "{}" : body});
            switch (path) {
                case "/api/version" -> send(exchange, 200, "{\"version\":\"0.40.10\"}");
                case "/api/tags" -> send(exchange, 200, list());
                case "/api/ps" -> send(exchange, 200, loaded ? list() : "{\"models\":[]}");
                case "/api/show" -> {
                    String model = JsonParser.parseString(body).getAsJsonObject().get("model").getAsString();
                    if (model.startsWith("llama3")) send(exchange, 200, "{\"template\":\"{{ .Prompt }}\"}");
                    else send(exchange, 200, "{\"template\":\"{{- if .Suffix }}<|fim_prefix|>{{ .Prompt }}{{ end }}\"}");
                }
                case "/api/generate" -> send(exchange, 200, "{\"response\":\"\",\"done\":true,\"load_duration\":1000}");
                case "/api/pull" -> {
                    exchange.sendResponseHeaders(200, 0);
                    var out = exchange.getResponseBody();
                    out.write("{\"status\":\"pulling manifest\"}\n{\"status\":\"pulling 6a77\",\"digest\":\"sha256:6a77\",\"total\":1000,\"completed\":500}\n"
                            .getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    pulling.countDown();
                    if (slowPull != null) {
                        try {
                            slowPull.await(5, TimeUnit.SECONDS);
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                        out.write("{\"status\":\"pulling 6a77\",\"digest\":\"sha256:6a77\",\"total\":1000,\"completed\":600}\n".getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    } else {
                        models.add(MODEL);
                        out.write("{\"status\":\"verifying sha256 digest\"}\n{\"status\":\"success\"}\n".getBytes(StandardCharsets.UTF_8));
                    }
                    exchange.close();
                }
                default -> send(exchange, 404, "404 page not found");
            }
        }

        private String list() {
            var out = new StringBuilder("{\"models\":[");
            for (int i = 0; i < models.size(); i++) out.append(i == 0 ? "" : ",").append("{\"name\":\"").append(models.get(i)).append("\"}");
            return out.append("]}").toString();
        }

        private void send(HttpExchange exchange, int status, String text) throws IOException {
            byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        }

        @Override
        public void close() {
            running = false;
            server.stop(0);
        }
    }
}
