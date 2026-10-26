package dev.turboj.ai;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

/** Typed errors, the two new cloud protocols and endpoint normalization against loopback fixtures. */
class AIProtocolTest {
    /** The exact answer of Ollama 0.40.10 for a model that is not installed (reproduced 26 Oct 2026). */
    private static final String OLLAMA_MISSING = "{\"error\":\"model 'qwen2.5-coder:1.5b-base' not found\"}";

    @Test
    void theReported404IsAMissingModelWithAnActionableMessage() throws Exception {
        var diagnostics = new AIDiagnostics();
        try (var server = new Fixture(exchange -> respond(exchange, 404, OLLAMA_MISSING))) {
            var provider = local(server, AIConfig.LocalBackend.OLLAMA, "qwen2.5-coder:1.5b-base", diagnostics);
            var error = assertThrows(AIException.class, () -> provider.stream(request(), s -> fail(), new Cancellation()));
            assertEquals(AIError.MODEL_NOT_FOUND, error.error());
            assertEquals("Local AI: model 'qwen2.5-coder:1.5b-base' is not installed in Ollama. Download it or choose an installed model.",
                    error.getMessage());
            var exchange = diagnostics.last();
            assertEquals("/api/show", exchange.path(), "the source-free preflight fails first");
            assertEquals(404, exchange.status());
            assertEquals("model 'qwen2.5-coder:1.5b-base' not found", exchange.response());
            assertEquals(1, server.requests.size(), "no generation is attempted after the preflight failed");
        }
    }

    @Test
    void a404WithoutModelWordsIsAWrongEndpointNotAMissingModel() throws Exception {
        try (var server = new Fixture(exchange -> respond(exchange, 404, "404 page not found"))) {
            var provider = local(server, AIConfig.LocalBackend.OLLAMA, "fim", new AIDiagnostics());
            var error = assertThrows(AIException.class, () -> provider.check(new Cancellation()));
            assertEquals(AIError.UNSUPPORTED_ENDPOINT, error.error());
            assertTrue(error.getMessage().contains("did not answer /api/show like an Ollama server"), error.getMessage());
        }
        try (var server = new Fixture(exchange -> respond(exchange, 404, "{\"error\":{\"code\":404,\"message\":\"File Not Found\",\"type\":\"not_found_error\"}}"))) {
            var provider = local(server, AIConfig.LocalBackend.LLAMA_CPP, "fim", new AIDiagnostics());
            var error = assertThrows(AIException.class, () -> provider.stream(request(), s -> fail(), new Cancellation()));
            assertEquals(AIError.UNSUPPORTED_ENDPOINT, error.error());
            assertTrue(error.getMessage().startsWith("Local AI: this llama.cpp server does not support /infill"), error.getMessage());
        }
    }

    @Test
    void noServerListeningIsARuntimeThatIsNotRunning() throws Exception {
        int port;
        try (var socket = new ServerSocket(0)) {
            port = socket.getLocalPort();
        }
        var config = config(AIConfig.Mode.LOCAL, AIConfig.LocalBackend.OLLAMA, "http://127.0.0.1:" + port, "fim");
        var provider = new HttpAICompletionProvider(config, null, null, new AIDiagnostics());
        var error = assertThrows(AIException.class, () -> provider.check(new Cancellation()));
        assertEquals(AIError.RUNTIME_NOT_RUNNING, error.error());
        assertEquals("Local AI: no Ollama server answers at http://127.0.0.1:" + port + ".", error.getMessage());
    }

    @Test
    void localEndpointsWithApiOrV1AreNormalizedToTheServerRoot() {
        assertEquals(URI.create("http://127.0.0.1:11434"),
                HttpAICompletionProvider.normalized(URI.create("http://127.0.0.1:11434/api/"), HttpAICompletionProvider.Protocol.OLLAMA));
        assertEquals(URI.create("http://localhost:11434"),
                HttpAICompletionProvider.normalized(URI.create("http://localhost:11434/v1"), HttpAICompletionProvider.Protocol.OLLAMA));
        assertEquals(URI.create("http://127.0.0.1:8080"),
                HttpAICompletionProvider.normalized(URI.create("http://127.0.0.1:8080/v1"), HttpAICompletionProvider.Protocol.LLAMA_CPP));
        assertEquals(URI.create("https://api.openai.com/v1"),
                HttpAICompletionProvider.normalized(URI.create("https://api.openai.com/v1"), HttpAICompletionProvider.Protocol.OPENAI_RESPONSES));
    }

    @Test
    void errorBodiesAreClassifiedByMeaningNotOnlyByStatus() {
        var p = HttpAICompletionProvider.Protocol.OLLAMA;
        assertEquals(AIError.MODEL_LOAD_FAILED, HttpAICompletionProvider.classify(p, 500,
                "model requires more system memory (9.1 GiB) than is available (4.0 GiB)", "", "/api/generate"));
        assertEquals(AIError.MODEL_NOT_LOADED, HttpAICompletionProvider.classify(HttpAICompletionProvider.Protocol.LLAMA_CPP, 503,
                "Loading model", "unavailable_error", "/infill"));
        assertEquals(AIError.UNSUPPORTED_MODEL_CAPABILITY, HttpAICompletionProvider.classify(p, 400,
                "registry.ollama.ai/library/llama3:latest does not support insert", "", "/api/generate"));
        assertEquals(AIError.RATE_LIMITED, HttpAICompletionProvider.classify(HttpAICompletionProvider.Protocol.ANTHROPIC_MESSAGES, 529,
                "Overloaded", "overloaded_error", "/messages"));
        assertEquals(AIError.MODEL_NOT_FOUND, HttpAICompletionProvider.classify(HttpAICompletionProvider.Protocol.ANTHROPIC_MESSAGES, 404,
                "model: claude-nonexistent", "not_found_error", "/messages"));
        assertEquals(AIError.INVALID_CONFIGURATION, HttpAICompletionProvider.classify(HttpAICompletionProvider.Protocol.OPENAI_RESPONSES, 400,
                "Unsupported parameter: 'temperature'", "invalid_request_error", "/responses"));
        assertEquals(AIError.UNSUPPORTED_ENDPOINT, HttpAICompletionProvider.classify(HttpAICompletionProvider.Protocol.OPENAI_RESPONSES, 404,
                "Invalid URL (POST /responses)", "invalid_request_error", "/responses"));
    }

    @Test
    void anthropicStreamsTextDeltasWithItsHeadersAndNoSamplingParameters() throws Exception {
        var seen = new AtomicReference<JsonObject>();
        var headers = new AtomicReference<String>();
        try (var server = new Fixture(exchange -> {
            seen.set(body(exchange));
            headers.set(exchange.getRequestHeaders().getFirst("x-api-key") + "|" + exchange.getRequestHeaders().getFirst("anthropic-version")
                    + "|" + exchange.getRequestHeaders().getFirst("Authorization"));
            assertEquals("/v1/messages", exchange.getRequestURI().getPath());
            sse(exchange, """
                    event: message_start
                    data: {"type":"message_start","message":{"id":"msg_1"}}

                    event: content_block_delta
                    data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":""}}

                    event: content_block_delta
                    data: {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"return value"}}

                    event: content_block_delta
                    data: {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":" * value;"}}

                    event: message_delta
                    data: {"type":"message_delta","delta":{"stop_reason":"end_turn"}}

                    event: message_stop
                    data: {"type":"message_stop"}

                    """);
        })) {
            var provider = cloud(server, AIConfig.CloudProvider.ANTHROPIC, "claude-haiku-5-5");
            var updates = new ArrayList<String>();
            provider.stream(request(), updates::add, new Cancellation());
            assertEquals(List.of("return value", "return value * value;"), updates);
            assertEquals("cloud-key|2026-10-21|null", headers.get(), "x-api-key, the API version, and no bearer token");
            JsonObject body = seen.get();
            assertEquals("claude-haiku-5-5", body.get("model").getAsString());
            assertTrue(body.get("stream").getAsBoolean());
            assertFalse(body.has("temperature") || body.has("top_p") || body.has("top_k"), "2026-10-21 rejects sampling parameters");
            assertEquals("disabled", body.getAsJsonObject("thinking").get("type").getAsString());
            assertEquals("low", body.getAsJsonObject("output_config").get("effort").getAsString());
            assertTrue(body.get("system").getAsString().contains("untrusted source data"));
            assertEquals("user", body.getAsJsonArray("messages").get(0).getAsJsonObject().get("role").getAsString());
        }
    }

    @Test
    void anthropicModelFamiliesGetTheLeastThinkingTheyAccept() {
        assertEquals("between_tools", thinking("claude-sonnet-5-5"));
        assertNull(thinking("claude-opus-5-5"), "Opus 5.5 cannot disable thinking; only effort is lowered");
        assertNull(thinking("claude-fable-5-1"));
        assertEquals("disabled", thinking("claude-opus-5"));
        var legacy = new JsonObject();
        HttpAICompletionProvider.anthropicLatency(legacy, "claude-haiku-4-5");
        assertEquals(0, legacy.size(), "Haiku 4.5 rejects effort");
    }

    @Test
    void anthropicErrorEventsAndRefusalsAreTyped() throws Exception {
        try (var server = new Fixture(exchange -> sse(exchange, """
                event: error
                data: {"type":"error","error":{"type":"overloaded_error","message":"Overloaded"}}

                """))) {
            var error = assertThrows(AIException.class,
                    () -> cloud(server, AIConfig.CloudProvider.ANTHROPIC, "claude-haiku-5-5").stream(request(), s -> {}, new Cancellation()));
            assertEquals(AIError.RATE_LIMITED, error.error());
        }
        try (var server = new Fixture(exchange -> sse(exchange, """
                data: {"type":"message_delta","delta":{"stop_reason":"refusal"}}

                """))) {
            var error = assertThrows(AIException.class,
                    () -> cloud(server, AIConfig.CloudProvider.ANTHROPIC, "claude-opus-5-5").stream(request(), s -> {}, new Cancellation()));
            assertEquals("Anthropic declined this request.", error.getMessage());
        }
    }

    @Test
    void xaiUsesChatCompletionsChunksAndDone() throws Exception {
        var seen = new AtomicReference<JsonObject>();
        try (var server = new Fixture(exchange -> {
            seen.set(body(exchange));
            assertEquals("/v1/chat/completions", exchange.getRequestURI().getPath());
            assertEquals("Bearer cloud-key", exchange.getRequestHeaders().getFirst("Authorization"));
            sse(exchange, """
                    data: {"choices":[{"index":0,"delta":{"role":"assistant"}}]}

                    data: {"choices":[{"index":0,"delta":{"content":"return value"}}]}

                    data: {"choices":[{"index":0,"delta":{"content":" * value;"},"finish_reason":null}]}

                    data: [DONE]

                    """);
        })) {
            var updates = new ArrayList<String>();
            cloud(server, AIConfig.CloudProvider.XAI, "grok-test").stream(request(), updates::add, new Cancellation());
            assertEquals(List.of("return value", "return value * value;"), updates);
            assertEquals("system", seen.get().getAsJsonArray("messages").get(0).getAsJsonObject().get("role").getAsString());
            assertFalse(seen.get().has("temperature"));
        }
    }

    @Test
    void rejectedKeysAndRateLimitsAreTypedAndRetryAfterIsKept() throws Exception {
        try (var server = new Fixture(exchange -> respond(exchange, 401,
                "{\"error\":{\"message\":\"Incorrect API key provided: sk-proj-abcdefghijklmnop\",\"type\":\"invalid_request_error\"}}"))) {
            var diagnostics = new AIDiagnostics();
            var provider = new HttpAICompletionProvider(cloudConfig(server, AIConfig.CloudProvider.OPENAI, "m"), () -> "cloud-key",
                    URI.create(server.base() + "/v1"), diagnostics);
            var error = assertThrows(AIException.class, () -> provider.stream(request(), s -> {}, new Cancellation()));
            assertEquals(AIError.AUTHENTICATION_FAILED, error.error());
            assertFalse(error.getMessage().contains("sk-proj"));
            assertEquals("<" + "Incorrect API key provided: sk-proj-abcdefghijklmnop".length() + " chars withheld>",
                    diagnostics.last().response(), "a key echoed by the provider never reaches diagnostics");
        }
        try (var server = new Fixture(exchange -> {
            exchange.getResponseHeaders().set("retry-after", "7");
            respond(exchange, 429, "{\"error\":{\"message\":\"slow down\",\"type\":\"rate_limit_error\"}}");
        })) {
            var error = assertThrows(AIException.class,
                    () -> cloud(server, AIConfig.CloudProvider.XAI, "grok-test").stream(request(), s -> {}, new Cancellation()));
            assertEquals(AIError.RATE_LIMITED, error.error());
            assertEquals(Duration.ofSeconds(7), error.retryAfter());
        }
    }

    @Test
    void diagnosticsKeepSizesNotSource() {
        var body = new JsonObject();
        body.addProperty("model", "qwen2.5-coder:1.5b-base");
        body.addProperty("prompt", "class Secret { String token; }");
        body.addProperty("suffix", "}");
        body.addProperty("stream", true);
        String text = AIDiagnostics.sanitizeRequest(body);
        assertEquals("{model=\"qwen2.5-coder:1.5b-base\", prompt=<30 chars>, suffix=<1 chars>, stream=true}", text);
    }

    @Test
    void modelsAreListedFromEachServerShape() throws Exception {
        try (var server = new Fixture(exchange -> respond(exchange, 200,
                "{\"models\":[{\"name\":\"qwen2.5-coder:1.5b-base\"},{\"name\":\"qwen2.5-coder:0.5b-base\"}]}"))) {
            assertEquals(List.of("qwen2.5-coder:1.5b-base", "qwen2.5-coder:0.5b-base"),
                    local(server, AIConfig.LocalBackend.OLLAMA, "x", new AIDiagnostics()).models(new Cancellation()));
            assertEquals("/api/tags", server.requests.getFirst());
        }
        try (var server = new Fixture(exchange -> respond(exchange, 200, "{\"data\":[{\"id\":\"claude-haiku-5-5\",\"type\":\"model\"}]}"))) {
            assertEquals(List.of("claude-haiku-5-5"), cloud(server, AIConfig.CloudProvider.ANTHROPIC, "m").models(new Cancellation()));
            assertEquals("/v1/models", server.requests.getFirst());
        }
    }

    private static String thinking(String model) {
        var body = new JsonObject();
        HttpAICompletionProvider.anthropicLatency(body, model);
        assertEquals("low", body.getAsJsonObject("output_config").get("effort").getAsString());
        return body.has("thinking") ? body.getAsJsonObject("thinking").get("type").getAsString() : null;
    }

    private static HttpAICompletionProvider local(Fixture server, AIConfig.LocalBackend backend, String model, AIDiagnostics diagnostics) {
        return new HttpAICompletionProvider(config(AIConfig.Mode.LOCAL, backend, server.base(), model), null, null, diagnostics);
    }

    private static HttpAICompletionProvider cloud(Fixture server, AIConfig.CloudProvider provider, String model) {
        return new HttpAICompletionProvider(cloudConfig(server, provider, model), () -> "cloud-key", URI.create(server.base() + "/v1"),
                new AIDiagnostics());
    }

    private static AIConfig cloudConfig(Fixture server, AIConfig.CloudProvider provider, String model) {
        return config(AIConfig.Mode.CLOUD, AIConfig.LocalBackend.OLLAMA, "http://127.0.0.1:1", "fim")
                .withCloud(provider, provider.defaultEndpoint(), model, true);
    }

    private static AIConfig config(AIConfig.Mode mode, AIConfig.LocalBackend backend, String endpoint, String model) {
        return new AIConfig(mode, backend, endpoint, model, null, null, true, false, mode == AIConfig.Mode.CLOUD, 200, 5, 64,
                4_096, "", false);
    }

    private static AICompletionRequest request() {
        return new AICompletionRequest(Path.of("Square.java"), 1, 10, "class Square { int square(int value) {\n", "\n}}", "", 25, false);
    }

    private static JsonObject body(HttpExchange exchange) throws IOException {
        return JsonParser.parseString(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject();
    }

    private static void respond(HttpExchange exchange, int status, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void sse(HttpExchange exchange, String events) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
        respond(exchange, 200, events);
    }

    @FunctionalInterface
    interface Handler {
        void handle(HttpExchange exchange) throws Exception;
    }

    static final class Fixture implements AutoCloseable {
        final HttpServer server;
        final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        final List<String> requests = java.util.Collections.synchronizedList(new ArrayList<>());

        Fixture(Handler handler) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext("/", exchange -> {
                requests.add(exchange.getRequestURI().getPath());
                try {
                    handler.handle(exchange);
                } catch (Throwable e) {
                    exchange.sendResponseHeaders(599, -1);
                    exchange.close();
                }
            });
            server.start();
        }

        String base() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
