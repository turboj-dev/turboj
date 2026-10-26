package dev.turboj.ai;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * "Test Completion": one real request with a synthetic Java snippet, never the user's source. It checks
 * that the backend answers, that the answer survives the insertion filter, and how long it took.
 */
public final class CompletionProbe {
    public record Result(boolean valid, String suggestion, long firstMillis, long totalMillis, String route, String message) {}

    static final String PREFIX = """
            public class Calculator {
                public int multiply(int a, int b) {
                    return\s""";
    static final String SUFFIX = """

                }
            }
            """;

    private CompletionProbe() {}

    public static AICompletionRequest request() {
        return new AICompletionRequest(Path.of("Calculator.java"), 1, PREFIX.length(), PREFIX, SUFFIX, "", 25, false);
    }

    public static Result run(AICompletionProvider provider, Cancellation cancellation) {
        var request = request();
        long start = System.nanoTime();
        var first = new AtomicLong();
        var last = new AtomicReference<>("");
        try {
            provider.stream(request, text -> {
                first.compareAndSet(0, System.nanoTime());
                last.set(text);
            }, cancellation);
        } catch (IOException failure) {
            String message = failure instanceof AIException typed ? typed.getMessage() : "The provider failed: " + failure.getClass().getSimpleName();
            return new Result(false, "", -1, millis(start), route(provider), message);
        }
        long total = millis(start);
        long firstMillis = first.get() == 0 ? -1 : TimeUnit.NANOSECONDS.toMillis(first.get() - start);
        String usable = CompletionValidation.usable(request, last.get(), true);
        String shown = usable.strip();
        boolean valid = !usable.isBlank();
        String message = valid
                ? String.format(Locale.ROOT, "Test completion: '%s' in %d ms (first text after %d ms) from %s.", shown, total, firstMillis, route(provider))
                : "Test completion: the model answered, but nothing it returned is a safe insertion: '" + abbreviate(last.get()) + "'.";
        return new Result(valid, shown, firstMillis, total, route(provider), message);
    }

    private static String route(AICompletionProvider provider) {
        if (provider instanceof FallbackChain chain && chain.lastResult() != null) return chain.lastResult().route();
        return provider.label();
    }

    private static String abbreviate(String text) {
        String single = text.replaceAll("\\s+", " ").strip();
        return single.length() > 60 ? single.substring(0, 60) + "…" : single;
    }

    private static long millis(long start) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }
}
