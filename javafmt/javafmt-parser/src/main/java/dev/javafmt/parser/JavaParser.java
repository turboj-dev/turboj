package dev.javafmt.parser;

/** A syntax-only parser boundary. Implementations must retain the supplied source unchanged. */
public interface JavaParser {
    ParsedSource parse(String source);
}
