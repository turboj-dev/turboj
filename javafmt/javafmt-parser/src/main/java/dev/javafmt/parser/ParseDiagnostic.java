package dev.javafmt.parser;

import java.util.Objects;

/** A syntax failure at a UTF-16 source range. All diagnostics prevent formatting. */
public record ParseDiagnostic(int start, int end, String message) {
    public ParseDiagnostic {
        if (start < 0 || end < start)
            throw new IllegalArgumentException("Invalid diagnostic span");
        Objects.requireNonNull(message);
    }
}
