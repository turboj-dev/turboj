package dev.javafmt.core;

import java.util.List;

public record FormatResult(String formattedText, List<TextEdit> edits, long sourceVersion,
        String formatterVersion, int styleVersion, List<FormatDiagnostic> diagnostics,
        boolean success, TextRange formattedRange) {
    public FormatResult {
        edits = List.copyOf(edits);
        diagnostics = List.copyOf(diagnostics);
    }
}
