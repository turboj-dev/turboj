package com.turboj.compiler;

public interface CompilerBackend extends AutoCloseable {
    CompilationResult compile(SourceSnapshot source);

    @Override
    default void close() {}
}
