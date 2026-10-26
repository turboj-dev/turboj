package dev.turboj.ai;

/**
 * Why an AI operation failed. {@link #retryable()} errors may pass with time (a server starting, a rate
 * limit, a timeout); the others need a different configuration and keep a circuit open until it changes.
 */
public enum AIError {
    RUNTIME_NOT_INSTALLED(false),
    RUNTIME_NOT_RUNNING(true),
    MODEL_NOT_FOUND(false),
    MODEL_NOT_LOADED(true),
    UNSUPPORTED_ENDPOINT(false),
    UNSUPPORTED_MODEL_CAPABILITY(false),
    INVALID_CONFIGURATION(false),
    AUTHENTICATION_FAILED(false),
    CONNECTION_FAILED(true),
    TIMEOUT(true),
    RATE_LIMITED(true),
    MODEL_DOWNLOAD_REQUIRED(false),
    MODEL_LOAD_FAILED(true),
    RESPONSE_PARSE_ERROR(true),
    INFERENCE_FAILED(true);

    private final boolean retryable;

    AIError(boolean retryable) {
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }
}
