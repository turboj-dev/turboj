package dev.turboj.compiler;

import java.util.Objects;

/** Immutable source. Offsets and source versions belong to the editor, not UTF-8 bytes. */
public record SourceSnapshot(String fileName, String source, long version) {
    public SourceSnapshot {
        Objects.requireNonNull(fileName, "fileName");
        Objects.requireNonNull(source, "source");
        if (fileName.isBlank()) throw new IllegalArgumentException("Source file name is empty");
    }

    /** Infer an unsaved compilation-unit name using Java syntax, not text matching. Call off EDT. */
    public static SourceSnapshot forBuffer(String source, long version) {
        return new SourceSnapshot(
                dev.turboj.language.JdtSyntaxAnalyzer.sourceFileName(source), source, version);
    }
}
