package dev.turboj.compiler;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkspaceCompilationTest {
    @Test void compilesMutuallyDependentUnsavedSourcesAndRejectsStaleArtifacts() {
        try (var compiler = new JavacCompilerBackend()) {
            var sources = List.of(new SourceSnapshot("p/A.java", "package p; public class A { public static int value() { return B.VALUE; } }", 4),
                    new SourceSnapshot("p/B.java", "package p; public class B { public static final int VALUE = 7; }", 9));
            var result = compiler.compileSources(sources, 15);
            assertTrue(result.success(), () -> result.diagnostics().toString());
            assertEquals(15, result.version()); assertEquals(Set.of("p.A", "p.B"), result.bytecode().keySet());
            var failure = compiler.compileSources(List.of(sources.getFirst(), new SourceSnapshot("p/B.java", "package p; public class B {}", 10)), 16);
            assertFalse(failure.success()); assertTrue(failure.bytecode().isEmpty());
            assertEquals("p/A.java", failure.diagnostics().stream().filter(d -> d.diagnostic().isError()).findFirst().orElseThrow().fileName());
            assertEquals(16, failure.version());
        }
    }
}
