package dev.turboj.ai;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

class FallbackChainTest {
    private final AtomicLong now = new AtomicLong();

    @Test
    void breakerOpensAfterThresholdProbesOnceAndClosesOnSuccess() {
        var breaker = new CircuitBreaker(new CircuitBreaker.Config(3, Duration.ofSeconds(20), 1, 1), now::get);
        for (int i = 0; i < 2; i++) {
            assertTrue(breaker.tryAcquire());
            breaker.onFailure(AIError.TIMEOUT, null);
        }
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state(), "two failures are below the threshold");
        breaker.onFailure(AIError.TIMEOUT, null);
        assertEquals(CircuitBreaker.State.OPEN, breaker.state());
        assertFalse(breaker.tryAcquire());
        now.addAndGet(Duration.ofSeconds(21).toNanos());
        assertEquals(CircuitBreaker.State.HALF_OPEN, breaker.state());
        assertTrue(breaker.tryAcquire(), "one probe");
        assertFalse(breaker.tryAcquire(), "only one probe at a time");
        breaker.onSuccess();
        assertEquals(CircuitBreaker.State.CLOSED, breaker.state());
    }

    @Test
    void aFailedProbeReopensAndConfigurationErrorsLatchUntilReset() {
        var breaker = new CircuitBreaker(new CircuitBreaker.Config(1, Duration.ofSeconds(5), 1, 1), now::get);
        breaker.onFailure(AIError.CONNECTION_FAILED, null);
        now.addAndGet(Duration.ofSeconds(6).toNanos());
        assertTrue(breaker.tryAcquire());
        breaker.onFailure(AIError.CONNECTION_FAILED, null);
        assertEquals(CircuitBreaker.State.OPEN, breaker.state(), "a failed probe reopens at once");

        var latched = new CircuitBreaker(CircuitBreaker.Config.COMPLETION, now::get);
        latched.onFailure(AIError.MODEL_NOT_FOUND, null);
        now.addAndGet(Duration.ofHours(1).toNanos());
        assertEquals(CircuitBreaker.State.OPEN, latched.state(), "a missing model does not heal with time");
        assertTrue(latched.snapshot().latched());
        latched.reset();
        assertTrue(latched.tryAcquire());
    }

    @Test
    void retryAfterOpensAtOnceForAtLeastThatLongAndCancellationIsNeutral() {
        var breaker = new CircuitBreaker(new CircuitBreaker.Config(3, Duration.ofSeconds(1), 1, 1), now::get);
        breaker.onFailure(AIError.RATE_LIMITED, Duration.ofSeconds(30));
        assertEquals(CircuitBreaker.State.OPEN, breaker.state());
        now.addAndGet(Duration.ofSeconds(29).toNanos());
        assertEquals(CircuitBreaker.State.OPEN, breaker.state());
        now.addAndGet(Duration.ofSeconds(2).toNanos());
        assertTrue(breaker.tryAcquire());
        breaker.onCancelled();
        assertTrue(breaker.tryAcquire(), "a cancelled probe gives its permit back");
    }

    @Test
    void theNextRouteAnswersWhenTheFirstFailsBeforeAnyText() throws Exception {
        var primary = new Fake("Ollama").fail(AIError.MODEL_LOAD_FAILED);
        var fallback = new Fake("Ollama small").answer("return a * b;");
        var chain = new FallbackChain(List.of(new FallbackChain.Route("local:1.5b", primary),
                new FallbackChain.Route("local:0.5b", fallback)));
        var text = new ArrayList<String>();
        chain.stream(request(), text::add, new Cancellation());
        assertEquals(List.of("return a * b;"), text);
        assertEquals(new FallbackChain.Result("local:0.5b", true, List.of("local:1.5b (MODEL_LOAD_FAILED)")), chain.lastResult());
    }

    @Test
    void notReadyAndPausedRoutesAreSkippedWithoutCountingFailures() throws Exception {
        var loading = new AtomicBoolean(false);
        var primary = new Fake("Ollama").answer("x");
        var chain = new FallbackChain(List.of(new FallbackChain.Route("local", primary, new CircuitBreaker(), loading::get)));
        var error = assertThrows(AIException.class, () -> chain.stream(request(), s -> {}, new Cancellation()));
        assertEquals(AIError.MODEL_NOT_LOADED, error.error());
        assertEquals(0, primary.calls.get(), "nothing is sent while the model loads");
        assertEquals(CircuitBreaker.State.CLOSED, chain.routes().getFirst().breaker().state());
        loading.set(true);
        chain.stream(request(), s -> {}, new Cancellation());
        assertEquals(1, primary.calls.get());
    }

    @Test
    void anOpenCircuitStopsRequestsAndSaysWhy() throws Exception {
        var primary = new Fake("Ollama").fail(AIError.MODEL_NOT_FOUND);
        var chain = new FallbackChain(List.of(new FallbackChain.Route("local", primary)));
        assertThrows(AIException.class, () -> chain.stream(request(), s -> {}, new Cancellation()));
        var error = assertThrows(AIException.class, () -> chain.stream(request(), s -> {}, new Cancellation()));
        assertEquals(1, primary.calls.get(), "a latched route is not called again");
        assertEquals(AIError.MODEL_NOT_FOUND, error.error());
        assertTrue(error.getMessage().contains("until its settings change"), error.getMessage());
    }

    @Test
    void textAlreadyShownIsNeverMixedWithAnotherModelAndCancellationIsNotAFailure() throws Exception {
        var partial = new Fake("Ollama").answer("return a").thenFail(AIError.INFERENCE_FAILED);
        var fallback = new Fake("Other").answer("other");
        var chain = new FallbackChain(List.of(new FallbackChain.Route("a", partial), new FallbackChain.Route("b", fallback)));
        assertThrows(AIException.class, () -> chain.stream(request(), s -> {}, new Cancellation()));
        assertEquals(0, fallback.calls.get());

        var cancelled = new Cancellation();
        var slow = new Fake("Ollama") {
            @Override
            public void stream(AICompletionRequest request, Consumer<String> text, Cancellation cancellation) throws IOException {
                calls.incrementAndGet();
                cancelled.cancel();
                throw new InterruptedIOException("AI request cancelled.");
            }
        };
        var other = new FallbackChain(List.of(new FallbackChain.Route("a", slow), new FallbackChain.Route("b", fallback)));
        assertThrows(InterruptedIOException.class, () -> other.stream(request(), s -> {}, cancelled));
        assertEquals(CircuitBreaker.State.CLOSED, other.routes().getFirst().breaker().state());
        assertEquals(0, fallback.calls.get());
    }

    @Test
    void localModeNeverReachesTheCloudUnlessExplicitlyAllowed() {
        var local = new AIConfig(AIConfig.Mode.LOCAL, AIConfig.LocalBackend.OLLAMA, null, "qwen2.5-coder:1.5b-base", null, null,
                true, false, true, 200, 5, 64, 4_096, "", false);
        var chain = (FallbackChain) AIProviders.create(local.withLocalFallback("qwen2.5-coder:0.5b-base", false), () -> "k", () -> true);
        assertEquals(List.of("local:qwen2.5-coder:1.5b-base", "local:qwen2.5-coder:0.5b-base"),
                chain.routes().stream().map(FallbackChain.Route::id).toList());
        var allowed = (FallbackChain) AIProviders.create(local.withLocalFallback("", true), () -> "k", () -> true);
        assertEquals(List.of("local:qwen2.5-coder:1.5b-base", "cloud:openai/gpt-6-luna"),
                allowed.routes().stream().map(FallbackChain.Route::id).toList());
        var noConsent = local.withCloud(AIConfig.CloudProvider.OPENAI, null, null, false);
        assertThrows(IllegalArgumentException.class, () -> noConsent.withLocalFallback("", true).validate(),
                "a cloud fallback requires source-sharing consent");
    }

    private static AICompletionRequest request() {
        return new AICompletionRequest(Path.of("Calculator.java"), 1, 5, "return ", ";", "", 25, false);
    }

    static class Fake implements AICompletionProvider {
        final String label;
        final AtomicInteger calls = new AtomicInteger();
        String text;
        AIError failure, after;

        Fake(String label) {
            this.label = label;
        }

        Fake answer(String value) {
            text = value;
            return this;
        }

        Fake fail(AIError error) {
            failure = error;
            return this;
        }

        Fake thenFail(AIError error) {
            after = error;
            return this;
        }

        @Override
        public void stream(AICompletionRequest request, Consumer<String> cumulative, Cancellation cancellation) throws IOException {
            calls.incrementAndGet();
            if (failure != null) throw new AIException(failure, label + " failed");
            cumulative.accept(text);
            if (after != null) throw new AIException(after, label + " failed late");
        }

        @Override
        public Availability check(Cancellation cancellation) {
            return new Availability(true, "ok");
        }

        @Override
        public String label() {
            return label;
        }
    }
}
