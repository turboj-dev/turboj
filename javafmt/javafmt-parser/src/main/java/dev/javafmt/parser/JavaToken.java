package dev.javafmt.parser;

import java.util.Objects;

/**
 * A protected lexical source span, using zero-based UTF-16 offsets and an exclusive end.
 * {@code raw} is the exact source substring; {@code text} has JDT's Unicode translation.
 * Contextual keyword roles (including module directives and generic closers) require the AST.
 * Physical line terminators following line comments belong to the whitespace between tokens.
 */
public record JavaToken(int kind, int start, int end, String raw, String text, boolean comment,
        boolean lineComment, boolean literal) {
    public JavaToken {
        Objects.requireNonNull(raw);
        Objects.requireNonNull(text);
        if (start < 0 || end < start || raw.length() != end - start)
            throw new IllegalArgumentException("Invalid UTF-16 token span");
    }
}
