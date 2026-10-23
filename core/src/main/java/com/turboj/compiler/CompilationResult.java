package com.turboj.compiler;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A result always identifies the source version actually compiled. */
public record CompilationResult(
        long version,
        boolean success,
        List<CompilerDiagnostic> diagnostics,
        Map<String, byte[]> bytecode,
        long durationNanos,
        boolean cacheHit,
        String mainClass) {
    public CompilationResult {
        diagnostics = List.copyOf(diagnostics);
        var copied = new LinkedHashMap<String, byte[]>();
        bytecode.forEach((name, bytes) -> copied.put(name, bytes.clone()));
        bytecode = Collections.unmodifiableMap(copied);
    }

    /** Callers receive independent byte arrays; cached artifacts cannot be changed by clients. */
    @Override
    public Map<String, byte[]> bytecode() {
        var copied = new LinkedHashMap<String, byte[]>();
        bytecode.forEach((name, bytes) -> copied.put(name, bytes.clone()));
        return Collections.unmodifiableMap(copied);
    }

    public long errorCount() {
        return diagnostics.stream().filter(CompilerDiagnostic::isError).count();
    }

    public double durationMillis() {
        return durationNanos / 1_000_000.0;
    }

    public String backend() {
        return "javac";
    }
}
