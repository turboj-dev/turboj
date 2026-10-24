package dev.turboj.ai;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.*;
import com.sun.net.httpserver.*;

import org.junit.jupiter.api.Test;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

class AIProviderTest {
    private AIConfig config(
            AIConfig.Mode mode, AIConfig.LocalBackend backend, String endpoint, int timeout) {
        return new AIConfig(
                mode,
                backend,
                endpoint,
                "fim-test",
                "https://api.openai.com/v1",
                "test-model",
                true,
                false,
                mode == AIConfig.Mode.CLOUD,
                200,
                timeout,
                128,
                4_096,
                "*.key",
                false);
    }

    private AICompletionRequest request() {
        return new AICompletionRequest(
                Path.of("Square.java"),
                9,
                42,
                "class Square { int square(int value) {\n",
                "\n}}",
                "",
                25,
                false);
    }

    @Test
    void disabledConfigurationCannotCreateProviderOrUseCredentials() {
        AtomicInteger credentials = new AtomicInteger();
        assertThrows(
                IllegalStateException.class,
                () ->
                        AIProviders.create(
                                AIConfig.defaults(),
                                () -> {
                                    credentials.incrementAndGet();
                                    return "unused";
                                }));
        assertFalse(AIConfig.defaults().automatic());
        assertEquals(0, credentials.get());
        AIConfig noConsent =
                new AIConfig(
                        AIConfig.Mode.CLOUD,
                        AIConfig.LocalBackend.OLLAMA,
                        null,
                        null,
                        null,
                        null,
                        true,
                        true,
                        false,
                        200,
                        30,
                        256,
                        4096,
                        null,
                        false);
        assertThrows(
                IllegalArgumentException.class,
                () -> AIProviders.create(noConsent, () -> "unused"));
    }

    @Test
    void localModeRejectsRemoteEndpointsAndCloudRejectsHttpOrCredentials() {
        for (String endpoint :
                List.of(
                        "http://example.com",
                        "http://127.0.0.1.example.com",
                        "http://127.0.0.1@evil.example",
                        "file:///tmp/model",
                        "http://localhost/?key=secret",
                        "http://localhost/#fragment")) {
            assertThrows(
                    IllegalArgumentException.class,
                    () ->
                            config(AIConfig.Mode.LOCAL, AIConfig.LocalBackend.OLLAMA, endpoint, 2)
                                    .validate());
        }
        assertDoesNotThrow(
                () ->
                        config(
                                        AIConfig.Mode.LOCAL,
                                        AIConfig.LocalBackend.OLLAMA,
                                        "http://[::1]:11434",
                                        2)
                                .validate());
        assertThrows(
                IllegalArgumentException.class,
                () -> AIConfig.checkedEndpoint("http://api.openai.com/v1", true));
        assertThrows(
                IllegalArgumentException.class,
                () -> AIConfig.checkedEndpoint("https://user:key@api.openai.com/v1", true));
    }

    @Test
    void ollamaPreservesFimPrefixSuffixStreamsCumulativeUnicodeAndKeepsWarm() throws Exception {
        try (Fixture server =
                        new Fixture(
                                exchange -> {
                                    assertEquals(
                                            "/api/generate", exchange.getRequestURI().getPath());
                                    assertNull(
                                            exchange.getRequestHeaders().getFirst("Authorization"));
                                    JsonObject body = body(exchange);
                                    assertEquals(
                                            request().prefix(), body.get("prompt").getAsString());
                                    assertEquals(
                                            request().suffix(), body.get("suffix").getAsString());
                                    assertEquals("2m", body.get("keep_alive").getAsString());
                                    assertFalse(body.has("raw"));
                                    assertEquals(
                                            128,
                                            body.getAsJsonObject("options")
                                                    .get("num_predict")
                                                    .getAsInt());
                                    send(
                                            exchange,
                                            "application/x-ndjson",
                                            "{\"response\":\"  return \",\"done\":false}\n"
                                                + "{\"response\":\"value * value; //"
                                                + " π\",\"done\":true}\n");
                                });
                AICompletionProvider provider =
                        AIProviders.create(
                                config(
                                        AIConfig.Mode.LOCAL,
                                        AIConfig.LocalBackend.OLLAMA,
                                        server.base(),
                                        2),
                                () -> {
                                    fail("local must not read credentials");
                                    return "";
                                })) {
            List<String> updates = new ArrayList<>();
            provider.stream(request(), updates::add, new Cancellation());
            assertEquals(List.of("  return ", "  return value * value; // π"), updates);
            assertTrue(provider.metrics().connectionNanos() > 0);
            assertTrue(
                    provider.metrics().firstDeltaNanos() >= provider.metrics().connectionNanos());
            assertTrue(provider.metrics().totalNanos() >= provider.metrics().firstDeltaNanos());
            server.assertHealthy();
        }
    }

    @Test
    void llamaInfillUsesNativeFieldsAndSseStopEvent() throws Exception {
        try (Fixture server =
                        new Fixture(
                                exchange -> {
                                    assertEquals("/infill", exchange.getRequestURI().getPath());
                                    JsonObject body = body(exchange);
                                    assertEquals(
                                            request().prefix(),
                                            body.get("input_prefix").getAsString());
                                    assertEquals(
                                            request().suffix(),
                                            body.get("input_suffix").getAsString());
                                    send(
                                            exchange,
                                            "text/event-stream",
                                            ": ping\n\n"
                                                + "data: {\"content\":\"return value *"
                                                + " value;\",\"stop\":false}\n\n"
                                                + "data: {\"content\":\"\",\"stop\":true}\n\n");
                                });
                AICompletionProvider provider =
                        AIProviders.create(
                                config(
                                        AIConfig.Mode.LOCAL,
                                        AIConfig.LocalBackend.LLAMA_CPP,
                                        server.base(),
                                        2),
                                null)) {
            List<String> updates = new ArrayList<>();
            provider.stream(request(), updates::add, new Cancellation());
            assertEquals(List.of("return value * value;"), updates);
            server.assertHealthy();
        }
    }

    @Test
    void responsesUsesTypedEventsUntrustedJsonInputAndNoStorage() throws Exception {
        try (Fixture server =
                        new Fixture(
                                exchange -> {
                                    assertEquals("/responses", exchange.getRequestURI().getPath());
                                    assertEquals(
                                            "Bearer fixture-key",
                                            exchange.getRequestHeaders().getFirst("Authorization"));
                                    JsonObject body = body(exchange);
                                    assertFalse(body.get("store").getAsBoolean());
                                    assertEquals("test-model", body.get("model").getAsString());
                                    assertEquals(
                                            request().suffix(),
                                            JsonParser.parseString(body.get("input").getAsString())
                                                    .getAsJsonObject()
                                                    .get("suffix")
                                                    .getAsString());
                                    assertTrue(
                                            body.get("instructions")
                                                    .getAsString()
                                                    .contains("untrusted source data"));
                                    assertFalse(body.has("tools"));
                                    assertFalse(body.has("temperature"));
                                    send(
                                            exchange,
                                            "text/event-stream",
                                            "event: response.output_text.delta\r\n"
                                                + "data:"
                                                + " {\"type\":\"response.output_text.delta\",\"delta\":\"return"
                                                + " value\",\"item_id\":\"msg1\"}\r\n\r\n"
                                                + "data:"
                                                + " {\"type\":\"response.output_text.delta\",\"delta\":\""
                                                + " * value;\",\"item_id\":\"msg1\"}\n\n"
                                                + "data:"
                                                + " {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n");
                                });
                AICompletionProvider provider =
                        new HttpAICompletionProvider(
                                config(
                                        AIConfig.Mode.CLOUD,
                                        AIConfig.LocalBackend.OLLAMA,
                                        server.base(),
                                        2),
                                () -> "fixture-key",
                                URI.create(server.base()))) {
            List<String> updates = new ArrayList<>();
            provider.stream(request(), updates::add, new Cancellation());
            assertEquals(List.of("return value", "return value * value;"), updates);
            server.assertHealthy();
        }
    }

    @Test
    void sourceFreeConnectionChecksDoNotGenerateAndRequireFimTemplate() throws Exception {
        AtomicInteger count = new AtomicInteger();
        try (Fixture server =
                        new Fixture(
                                exchange -> {
                                    assertEquals("/api/show", exchange.getRequestURI().getPath());
                                    JsonObject body = body(exchange);
                                    assertEquals(Set.of("model"), body.keySet());
                                    send(
                                            exchange,
                                            "application/json",
                                            count.getAndIncrement() == 0
                                                    ? "{\"template\":\"{{ if .Suffix"
                                                          + " }}fim{{end}}\"}"
                                                    : "{\"template\":\"chat only\"}");
                                },
                                false);
                AICompletionProvider provider =
                        AIProviders.create(
                                config(
                                        AIConfig.Mode.LOCAL,
                                        AIConfig.LocalBackend.OLLAMA,
                                        server.base(),
                                        2),
                                null)) {
            assertTrue(provider.check(new Cancellation()).available());
            assertFalse(provider.check(new Cancellation()).available());
            server.assertHealthy();
        }
    }

    @Test
    void cloudCheckOnlyRetrievesModelMetadata() throws Exception {
        try (Fixture server =
                        new Fixture(
                                exchange -> {
                                    assertEquals("GET", exchange.getRequestMethod());
                                    assertEquals(
                                            "/models/test-model",
                                            exchange.getRequestURI().getPath());
                                    assertEquals(
                                            0, exchange.getRequestBody().readAllBytes().length);
                                    send(exchange, "application/json", "{\"id\":\"test-model\"}");
                                });
                AICompletionProvider provider =
                        new HttpAICompletionProvider(
                                config(
                                        AIConfig.Mode.CLOUD,
                                        AIConfig.LocalBackend.OLLAMA,
                                        server.base(),
                                        2),
                                () -> "fixture-key",
                                URI.create(server.base()))) {
            assertTrue(provider.check(new Cancellation()).available());
            server.assertHealthy();
        }
    }

    @Test
    void explicitOllamaReleaseUsesEmptyGenerationAndZeroKeepAlive() throws Exception {
        try (Fixture server =
                        new Fixture(
                                exchange -> {
                                    JsonObject body = body(exchange);
                                    assertFalse(body.has("prompt"));
                                    assertFalse(body.has("suffix"));
                                    assertEquals(0, body.get("keep_alive").getAsInt());
                                    send(exchange, "application/json", "{\"done\":true}");
                                });
                AICompletionProvider provider =
                        AIProviders.create(
                                config(
                                        AIConfig.Mode.LOCAL,
                                        AIConfig.LocalBackend.OLLAMA,
                                        server.base(),
                                        2),
                                null)) {
            provider.release(new Cancellation());
            server.assertHealthy();
        }
    }

    @Test
    void redirectDoesNotForwardCredentialAndErrorDoesNotEchoProviderBody() throws Exception {
        AtomicInteger received = new AtomicInteger();
        try (Fixture redirected =
                        new Fixture(
                                exchange -> {
                                    received.incrementAndGet();
                                    send(exchange, "application/json", "{}");
                                });
                Fixture server =
                        new Fixture(
                                exchange -> {
                                    exchange.getResponseHeaders()
                                            .set("Location", redirected.base());
                                    exchange.sendResponseHeaders(302, 0);
                                    exchange.getResponseBody()
                                            .write(
                                                    "secret-source-and-key"
                                                            .getBytes(StandardCharsets.UTF_8));
                                    exchange.close();
                                });
                AICompletionProvider provider =
                        new HttpAICompletionProvider(
                                config(
                                        AIConfig.Mode.CLOUD,
                                        AIConfig.LocalBackend.OLLAMA,
                                        server.base(),
                                        2),
                                () -> "fixture-key",
                                URI.create(server.base()))) {
            IOException error =
                    assertThrows(
                            IOException.class,
                            () -> provider.stream(request(), s -> fail(), new Cancellation()));
            assertEquals("AI provider returned HTTP 302.", error.getMessage());
            assertEquals(0, received.get());
            server.assertHealthy();
        }
    }

    @Test
    void malformedOversizedAndUnfinishedStreamsFailWithoutLeakingContent() throws Exception {
        for (String response :
                List.of(
                        "not-json\n",
                        "{\"response\":\"partial\",\"done\":false}\n",
                        "x".repeat(65_540) + "\n",
                        "{\"error\":\"private-source-key\"}\n")) {
            try (Fixture server =
                            new Fixture(
                                    exchange -> send(exchange, "application/x-ndjson", response));
                    AICompletionProvider provider =
                            AIProviders.create(
                                    config(
                                            AIConfig.Mode.LOCAL,
                                            AIConfig.LocalBackend.OLLAMA,
                                            server.base(),
                                            2),
                                    null)) {
                IOException error =
                        assertThrows(
                                IOException.class,
                                () -> provider.stream(request(), s -> {}, new Cancellation()));
                assertFalse(error.getMessage().contains("private-source-key"));
                server.assertHealthy();
            }
        }
    }

    @Test
    void stalledBodyTimesOutAndExplicitCancellationStopsIt() throws Exception {
        CountDownLatch opened = new CountDownLatch(1), release = new CountDownLatch(1);
        try (Fixture server =
                        new Fixture(
                                exchange -> {
                                    exchange.sendResponseHeaders(200, 0);
                                    exchange.getResponseBody().flush();
                                    opened.countDown();
                                    try {
                                        release.await(10, TimeUnit.SECONDS);
                                    } catch (InterruptedException e) {
                                        Thread.currentThread().interrupt();
                                    }
                                    exchange.close();
                                });
                AICompletionProvider provider =
                        AIProviders.create(
                                config(
                                        AIConfig.Mode.LOCAL,
                                        AIConfig.LocalBackend.OLLAMA,
                                        server.base(),
                                        1),
                                null)) {
            long start = System.nanoTime();
            assertThrows(
                    IOException.class,
                    () -> provider.stream(request(), s -> fail(), new Cancellation()));
            assertTrue(Duration.ofNanos(System.nanoTime() - start).toMillis() < 2_500);
            Cancellation cancellation = new Cancellation();
            var done =
                    CompletableFuture.runAsync(
                            () ->
                                    assertThrows(
                                            IOException.class,
                                            () ->
                                                    provider.stream(
                                                            request(), s -> fail(), cancellation)));
            assertTrue(opened.await(2, TimeUnit.SECONDS));
            long cancelled = System.nanoTime();
            cancellation.cancel();
            assertTrue(
                    Duration.ofNanos(System.nanoTime() - cancelled).toMillis() < 100,
                    "cancel caller must not block");
            done.get(2, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
    }

    @Test
    void cancellationRegisteredAfterCancelClosesResourceWithoutWaitingForIt() throws Exception {
        Cancellation cancellation = new Cancellation();
        cancellation.cancel();
        CountDownLatch closed = new CountDownLatch(1);
        cancellation.register(closed::countDown);
        assertTrue(closed.await(1, TimeUnit.SECONDS));
        assertThrows(InterruptedIOException.class, cancellation::throwIfCancelled);
    }

    @Test
    void localCloudModelMetadataIsRejectedBeforeSourceLeavesTheEditor() throws Exception {
        AtomicInteger generations = new AtomicInteger();
        try (Fixture server =
                        new Fixture(
                                exchange -> {
                                    if (exchange.getRequestURI().getPath().equals("/api/show")) {
                                        assertEquals(Set.of("model"), body(exchange).keySet());
                                        send(
                                                exchange,
                                                "application/json",
                                                "{\"template\":\"{{.Suffix}}\",\"remote_host\":\"https://ollama.com\",\"remote_model\":\"remote\"}");
                                    } else {
                                        generations.incrementAndGet();
                                        send(exchange, "application/json", "{}");
                                    }
                                },
                                false);
                AICompletionProvider provider =
                        AIProviders.create(
                                config(
                                        AIConfig.Mode.LOCAL,
                                        AIConfig.LocalBackend.OLLAMA,
                                        server.base(),
                                        2),
                                null)) {
            assertThrows(
                    IOException.class,
                    () -> provider.stream(request(), ignored -> fail(), new Cancellation()));
            assertEquals(0, generations.get());
            server.assertHealthy();
        }
    }

    @Test
    void currentCloudDefaultDisablesReasoningWithoutRestrictingOtherModels() throws Exception {
        AIConfig defaults = AIConfig.defaults();
        AIConfig enabled =
                new AIConfig(
                        AIConfig.Mode.CLOUD,
                        defaults.localBackend(),
                        defaults.localEndpoint(),
                        defaults.localModel(),
                        defaults.cloudEndpoint(),
                        defaults.cloudModel(),
                        true,
                        false,
                        true,
                        200,
                        2,
                        128,
                        4096,
                        "",
                        false);
        try (Fixture server =
                        new Fixture(
                                exchange -> {
                                    JsonObject body = body(exchange);
                                    assertEquals("gpt-6-luna", body.get("model").getAsString());
                                    assertEquals(
                                            "none",
                                            body.getAsJsonObject("reasoning")
                                                    .get("effort")
                                                    .getAsString());
                                    send(
                                            exchange,
                                            "text/event-stream",
                                            "data:"
                                                + " {\"type\":\"response.completed\",\"response\":{\"status\":\"completed\"}}\n\n");
                                });
                AICompletionProvider provider =
                        new HttpAICompletionProvider(
                                enabled, () -> "fixture-key", URI.create(server.base()))) {
            provider.stream(request(), ignored -> fail(), new Cancellation());
            server.assertHealthy();
        }
    }

    @Test
    void concurrentRegistrationAndCancellationNeverLoseResources() throws Exception {
        for (int run = 0; run < 100; run++) {
            Cancellation cancellation = new Cancellation();
            CountDownLatch closed = new CountDownLatch(20);
            Thread registering =
                    Thread.startVirtualThread(
                            () -> {
                                for (int n = 0; n < 20; n++)
                                    cancellation.register(closed::countDown);
                            });
            cancellation.cancel();
            registering.join();
            assertTrue(closed.await(2, TimeUnit.SECONDS));
        }
    }

    private static JsonObject body(HttpExchange exchange) throws IOException {
        return JsonParser.parseString(
                        new String(
                                exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8))
                .getAsJsonObject();
    }

    private static void send(HttpExchange exchange, String type, String content)
            throws IOException {
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(200, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    @FunctionalInterface
    interface Handler {
        void handle(HttpExchange exchange) throws Exception;
    }

    private static final class Fixture implements AutoCloseable {
        final HttpServer server;
        final ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();
        final AtomicReference<Throwable> failure = new AtomicReference<>();

        Fixture(Handler handler) throws IOException {
            this(handler, true);
        }

        Fixture(Handler handler, boolean autoShow) throws IOException {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.setExecutor(executor);
            server.createContext(
                    "/",
                    exchange -> {
                        try {
                            if (autoShow
                                    && exchange.getRequestURI().getPath().equals("/api/show")) {
                                JsonObject body = body(exchange);
                                assertEquals(Set.of("model"), body.keySet());
                                send(
                                        exchange,
                                        "application/json",
                                        "{\"template\":\"{{ if .Suffix }}fim{{end}}\"}");
                            } else handler.handle(exchange);
                        } catch (Throwable e) {
                            failure.compareAndSet(null, e);
                            exchange.close();
                        }
                    });
            server.start();
        }

        String base() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        void assertHealthy() {
            if (failure.get() != null) throw new AssertionError(failure.get());
        }

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
