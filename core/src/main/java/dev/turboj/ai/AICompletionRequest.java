package dev.turboj.ai;

import java.nio.file.Path;
import java.util.Objects;

/** Source is untrusted completion context, never provider instructions. Offsets are UTF-16. */
public record AICompletionRequest(
        Path file,
        long version,
        int offset,
        String prefix,
        String suffix,
        String context,
        int javaVersion,
        boolean larger) {
    public AICompletionRequest {
        Objects.requireNonNull(file);
        Objects.requireNonNull(prefix);
        Objects.requireNonNull(suffix);
        context = context == null ? "" : context;
        if (offset < 0
                || javaVersion < 8
                || javaVersion > 99
                || (long) prefix.length() + suffix.length() + context.length() > 131_072)
            throw new IllegalArgumentException("Invalid or oversized AI completion context.");
    }
}
