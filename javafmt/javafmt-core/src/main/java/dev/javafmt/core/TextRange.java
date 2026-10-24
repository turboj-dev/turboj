package dev.javafmt.core;

/** Half-open UTF-16 source range. */
public record TextRange(int start, int end) {
    public TextRange {
        if (start < 0 || end < start)
            throw new IllegalArgumentException("Invalid range");
    }
}
