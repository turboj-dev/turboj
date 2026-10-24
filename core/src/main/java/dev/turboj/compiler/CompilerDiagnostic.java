package dev.turboj.compiler;

/** Lines/columns are one-based; start/end are UTF-16 offsets, end exclusive, or -1. */
public record CompilerDiagnostic(
        String kind, String message, long line, long column, long start, long end) {
    public boolean isError() {
        return "ERROR".equals(kind);
    }
}
