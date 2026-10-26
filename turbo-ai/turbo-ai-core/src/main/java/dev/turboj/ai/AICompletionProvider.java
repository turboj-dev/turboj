package dev.turboj.ai;

import java.io.IOException;
import java.util.List;
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

    /** The models this provider's server or key can use; source-free. */
    default List<String> models(Cancellation cancellation) throws IOException {
        return List.of();
    }

    /** Short provider name for messages and status ("Ollama", "Anthropic"). */
    default String label() {
        return "AI";
    }

    /** Explicit user action only: an Ollama model may be shared with other applications. */
    default void release(Cancellation cancellation) throws IOException {}

    @Override
    default void close() {}
}
