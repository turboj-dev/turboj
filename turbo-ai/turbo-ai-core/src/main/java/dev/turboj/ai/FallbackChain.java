package dev.turboj.ai;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Ordered provider routes, each behind its own {@link CircuitBreaker}. A request goes to the first route
 * that is configured and whose breaker admits it; a failure before any text moves on to the next one. Once
 * a route has streamed text, its failure ends the request: suggestions from two models are never mixed.
 * Routes are ordered cheapest and most private first, so a fallback never escalates cost or exposure;
 * whether a cloud route is in the chain at all is the caller's explicit decision.
 */
public final class FallbackChain implements AICompletionProvider {
    /** One provider with its breaker; {@code configured} skips it without counting a failure. */
    public record Route(String id, AICompletionProvider provider, CircuitBreaker breaker, BooleanSupplier configured) {
        public Route {
            Objects.requireNonNull(id);
            Objects.requireNonNull(provider);
            breaker = breaker == null ? new CircuitBreaker() : breaker;
            configured = configured == null ? () -> true : configured;
        }

        public Route(String id, AICompletionProvider provider) {
            this(id, provider, null, null);
        }
    }

    /** Which route answered the last request, and which were tried or skipped before it. */
    public record Result(String route, boolean fallbackUsed, List<String> attempted) {}

    private final List<Route> routes;
    private volatile Result last;
    private volatile Route lastRoute;

    public FallbackChain(List<Route> routes) {
        if (routes.isEmpty()) throw new IllegalArgumentException("A fallback chain needs at least one route");
        this.routes = List.copyOf(routes);
    }

    public List<Route> routes() {
        return routes;
    }

    /** The outcome of the last completed request, or null. */
    public Result lastResult() {
        return last;
    }

    @Override
    public String label() {
        Route used = lastRoute;
        return (used == null ? routes.getFirst() : used).provider().label();
    }

    @Override
    public void stream(AICompletionRequest request, Consumer<String> cumulative, Cancellation cancellation)
            throws IOException {
        var attempted = new ArrayList<String>();
        AIException failure = null;
        for (Route route : routes) {
            cancellation.throwIfCancelled();
            if (!route.configured().getAsBoolean()) {
                attempted.add(route.id() + " (not ready)");
                continue;
            }
            if (!route.breaker().tryAcquire()) {
                attempted.add(route.id() + " (paused)");
                continue;
            }
            boolean[] emitted = {false};
            try {
                route.provider().stream(request, text -> {
                    emitted[0] = true;
                    cumulative.accept(text);
                }, cancellation);
                route.breaker().onSuccess();
                lastRoute = route;
                last = new Result(route.id(), route != routes.getFirst(), List.copyOf(attempted));
                return;
            } catch (InterruptedIOException cancelled) {
                route.breaker().onCancelled();
                throw cancelled;
            } catch (IOException | RuntimeException error) {
                if (cancellation.isCancelled()) {
                    route.breaker().onCancelled();
                    throw new InterruptedIOException("AI request cancelled.");
                }
                AIException typed = error instanceof AIException ai ? ai
                        : new AIException(AIException.classify(error), route.provider().label() + " failed to generate a completion.",
                                null, null, error);
                route.breaker().onFailure(typed.error(), typed.retryAfter());
                attempted.add(route.id() + " (" + typed.error() + ")");
                if (emitted[0]) throw typed;
                failure = typed;
            }
        }
        if (failure != null) throw failure;
        throw unavailable(attempted);
    }

    /** No route was tried: report why the first one is out, which is what the user configured. */
    private AIException unavailable(List<String> attempted) {
        Route first = routes.getFirst();
        CircuitBreaker.Snapshot breaker = first.breaker().snapshot();
        if (!first.configured().getAsBoolean())
            return new AIException(AIError.MODEL_NOT_LOADED, first.provider().label() + " is not ready yet.");
        AIError error = breaker.lastError() == null ? AIError.INFERENCE_FAILED : breaker.lastError();
        String when = breaker.latched() ? "until its settings change" : "for " + Math.max(1, breaker.millisUntilHalfOpen() / 1000) + " s";
        return new AIException(error, first.provider().label() + " is paused " + when + " after " + error
                + ". Tried: " + String.join(", ", attempted) + ".");
    }

    @Override
    public Availability check(Cancellation cancellation) throws IOException {
        return routes.getFirst().provider().check(cancellation);
    }

    @Override
    public List<String> models(Cancellation cancellation) throws IOException {
        return routes.getFirst().provider().models(cancellation);
    }

    @Override
    public Metrics metrics() {
        Route used = lastRoute;
        return (used == null ? routes.getFirst() : used).provider().metrics();
    }

    @Override
    public void release(Cancellation cancellation) throws IOException {
        routes.getFirst().provider().release(cancellation);
    }

    @Override
    public void close() {
        for (Route route : routes) route.provider().close();
    }
}
