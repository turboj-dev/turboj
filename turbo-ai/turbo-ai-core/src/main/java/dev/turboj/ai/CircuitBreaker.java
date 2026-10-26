package dev.turboj.ai;

import java.time.Duration;
import java.util.Objects;
import java.util.function.LongSupplier;

/**
 * One provider route's breaker: CLOSED lets requests through; after {@code failureThreshold}
 * consecutive retryable failures it is OPEN for {@code openDuration}, then HALF_OPEN lets at most
 * {@code halfOpenMaxAttempts} probes through and closes after {@code successThresholdToClose} successes.
 * A failure that only a configuration change can fix (a missing model, a rejected key) opens it until
 * {@link #reset()}; a provider's Retry-After opens it for at least that long. Cancellations count as
 * neither success nor failure.
 */
public final class CircuitBreaker {
    public enum State { CLOSED, OPEN, HALF_OPEN }

    public record Config(int failureThreshold, Duration openDuration, int halfOpenMaxAttempts, int successThresholdToClose) {
        /** Autocomplete fails fast and probes again soon: a pause of a few seconds is invisible. */
        public static final Config COMPLETION = new Config(3, Duration.ofSeconds(20), 1, 1);

        public Config {
            if (failureThreshold < 1 || halfOpenMaxAttempts < 1 || successThresholdToClose < 1 || openDuration.isNegative())
                throw new IllegalArgumentException("Invalid circuit breaker configuration");
        }
    }

    /** What the breaker looks like now, for status lines and diagnostics. */
    public record Snapshot(State state, int consecutiveFailures, long millisUntilHalfOpen, AIError lastError, boolean latched) {}

    private final Config config;
    private final LongSupplier nanos;
    private int failures, successes, probes;
    private long openedAt, openFor;
    private boolean open, latched;
    private AIError lastError;

    public CircuitBreaker() {
        this(Config.COMPLETION, System::nanoTime);
    }

    public CircuitBreaker(Config config, LongSupplier nanos) {
        this.config = Objects.requireNonNull(config);
        this.nanos = Objects.requireNonNull(nanos);
    }

    public synchronized State state() {
        if (!open) return State.CLOSED;
        return latched || nanos.getAsLong() - openedAt < openFor ? State.OPEN : State.HALF_OPEN;
    }

    /** Whether a request may go to this route now; a HALF_OPEN permit must end in one of the on* calls. */
    public synchronized boolean tryAcquire() {
        return switch (state()) {
            case CLOSED -> true;
            case OPEN -> false;
            case HALF_OPEN -> {
                if (probes >= config.halfOpenMaxAttempts()) yield false;
                probes++;
                yield true;
            }
        };
    }

    public synchronized void onSuccess() {
        failures = 0;
        if (!open) return;
        probes = Math.max(0, probes - 1);
        if (++successes >= config.successThresholdToClose()) close();
    }

    public synchronized void onFailure(AIError error, Duration retryAfter) {
        lastError = Objects.requireNonNull(error);
        successes = 0;
        probes = Math.max(0, probes - 1);
        if (!error.retryable()) {
            trip(Long.MAX_VALUE);
            latched = true;
            return;
        }
        failures++;
        long pause = config.openDuration().toNanos();
        if (retryAfter != null) pause = Math.max(pause, retryAfter.toNanos());
        // A failed probe reopens at once; a Retry-After is honoured from the first failure.
        if (open || retryAfter != null || failures >= config.failureThreshold()) trip(pause);
    }

    public synchronized void onCancelled() {
        if (open) probes = Math.max(0, probes - 1);
    }

    public synchronized void reset() {
        close();
        lastError = null;
    }

    public synchronized Snapshot snapshot() {
        State state = state();
        long remaining = state == State.OPEN && !latched ? Math.max(0, openFor - (nanos.getAsLong() - openedAt)) / 1_000_000 : 0;
        return new Snapshot(state, failures, remaining, lastError, latched);
    }

    private void trip(long duration) {
        open = true;
        openedAt = nanos.getAsLong();
        openFor = duration;
        successes = 0;
    }

    private void close() {
        open = latched = false;
        failures = successes = probes = 0;
        openFor = 0;
    }
}
