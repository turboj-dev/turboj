package dev.turboj.ai;

import java.io.IOException;
import java.time.Duration;
import java.util.Objects;

/**
 * A classified failure. The message is written for the user and never contains source text, keys or a
 * provider's response body; {@link #exchange()} carries the sanitized request/response metadata.
 */
public final class AIException extends IOException {
    private final AIError error;
    private final AIDiagnostics.Exchange exchange;
    private final Duration retryAfter;

    public AIException(AIError error, String message) {
        this(error, message, null, null, null);
    }

    public AIException(AIError error, String message, AIDiagnostics.Exchange exchange, Duration retryAfter, Throwable cause) {
        super(Objects.requireNonNull(message), cause);
        this.error = Objects.requireNonNull(error);
        this.exchange = exchange;
        this.retryAfter = retryAfter;
    }

    public AIError error() {
        return error;
    }

    /** The HTTP exchange that failed, if the failure came from one. */
    public AIDiagnostics.Exchange exchange() {
        return exchange;
    }

    /** A provider-requested pause (HTTP Retry-After), if any. */
    public Duration retryAfter() {
        return retryAfter;
    }

    /** The classification of any failure: typed errors keep theirs, cancellations are not errors. */
    public static AIError classify(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof AIException ai) return ai.error;
            if (t instanceof java.net.http.HttpTimeoutException) return AIError.TIMEOUT;
            if (t instanceof java.net.ConnectException) return AIError.CONNECTION_FAILED;
        }
        return AIError.INFERENCE_FAILED;
    }
}
