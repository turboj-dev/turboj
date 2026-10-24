package com.turboj.ai;

import java.io.IOException;
import java.util.function.Consumer;

/** Blocking worker-thread API. Implementations stream cumulative text and never call Swing. */
public interface AICompletionProvider extends AutoCloseable {
    record Availability(boolean available, String message) {}

    record Metrics(long connectionNanos, long firstDeltaNanos, long totalNanos) {}

    void stream(
            AICompletionRequest request, Consumer<String> cumulativeText, Cancellation cancellation)
            throws IOException;

    Availability check(Cancellation cancellation) throws IOException;

    default Metrics metrics() {
        return new Metrics(0, 0, 0);
    }

    /** Explicit user action only: an Ollama model may be shared with other applications. */
    default void release(Cancellation cancellation) throws IOException {}

    @Override
    default void close() {}
}
