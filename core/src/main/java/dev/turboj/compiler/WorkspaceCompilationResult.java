package dev.turboj.compiler;

import java.util.*;

/** A complete immutable compilation snapshot for project commands and test execution. */
public record WorkspaceCompilationResult(long version, boolean success, List<SourceSnapshot> sources,
        List<SourceDiagnostic> diagnostics, Map<String, byte[]> bytecode, long durationNanos) {
    public record SourceDiagnostic(String fileName, CompilerDiagnostic diagnostic) {}
    public WorkspaceCompilationResult {
        sources = List.copyOf(sources); diagnostics = List.copyOf(diagnostics); bytecode = copy(bytecode);
    }
    @Override public Map<String, byte[]> bytecode() { return copy(bytecode); }
    private static Map<String, byte[]> copy(Map<String, byte[]> input) {
        Map<String, byte[]> result = new LinkedHashMap<>();
        input.forEach((name, bytes) -> result.put(name, bytes.clone()));
        return Collections.unmodifiableMap(result);
    }
}
