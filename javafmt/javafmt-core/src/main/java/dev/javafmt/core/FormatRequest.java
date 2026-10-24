package dev.javafmt.core;

import java.util.Objects;

public record FormatRequest(String source, long sourceVersion, LineEndingPolicy lineEndings) {
    public FormatRequest {
        Objects.requireNonNull(source);
        Objects.requireNonNull(lineEndings);
    }

    public FormatRequest(String source) {
        this(source, 0, LineEndingPolicy.LF);
    }
}
