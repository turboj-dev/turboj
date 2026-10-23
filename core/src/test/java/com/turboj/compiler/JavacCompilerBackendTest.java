package com.turboj.compiler;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.Arrays;

class JavacCompilerBackendTest {
    private static final String HELLO =
            "public class Hello { public static void main(String[] args) { System.out.println(\"Hello TurboJ!\"); } }";

    @Test
    void compilesInMemoryAndCachesOnlyIdenticalSource() {
        try (var compiler = new JavacCompilerBackend()) {
            var first = compiler.compile(new SourceSnapshot("Hello.java", HELLO, 1));
            assertTrue(first.success(), first.diagnostics().toString());
            assertFalse(first.cacheHit());
            assertEquals("Hello", first.mainClass());
            assertTrue(first.durationNanos() > 0);
            assertTrue(first.bytecode().get("Hello").length > 0);
            var cached = compiler.compile(new SourceSnapshot("Hello.java", HELLO, 2));
            assertTrue(cached.cacheHit());
            assertEquals(2, cached.version());
            var changed =
                    compiler.compile(
                            new SourceSnapshot(
                                    "Hello.java", HELLO.replace("Hello TurboJ!", "Changed!"), 3));
            assertTrue(changed.success());
            assertFalse(changed.cacheHit());
            assertFalse(
                    Arrays.equals(first.bytecode().get("Hello"), changed.bytecode().get("Hello")));
        }
    }

    @Test
    void unresolvedSymbolsNeverReturnOldBytecode() {
        try (var compiler = new JavacCompilerBackend()) {
            compiler.compile(new SourceSnapshot("Hello.java", HELLO, 1));
            String broken = HELLO.replace("\"Hello TurboJ!\"", "undefinedVariable");
            var result = compiler.compile(new SourceSnapshot("Hello.java", broken, 2));
            assertFalse(result.success());
            assertTrue(result.bytecode().isEmpty());
            assertNull(result.mainClass());
            var diagnostic =
                    result.diagnostics().stream()
                            .filter(CompilerDiagnostic::isError)
                            .findFirst()
                            .orElseThrow();
            assertTrue(diagnostic.message().contains("cannot find symbol"));
            assertEquals(
                    "undefinedVariable",
                    broken.substring((int) diagnostic.start(), (int) diagnostic.end()));
        }
    }

    @Test
    void supportsJava25CompactAndInstanceMainWithoutLoadingUserClasses() {
        try (var compiler = new JavacCompilerBackend()) {
            var compact =
                    compiler.compile(
                            new SourceSnapshot(
                                    "Compact.java", "void main() { System.out.println(25); }", 1));
            assertTrue(compact.success(), compact.diagnostics().toString());
            assertEquals("Compact", compact.mainClass());
            var instance =
                    compiler.compile(
                            new SourceSnapshot(
                                    "Instance.java",
                                    "class Instance { void main(String[] args) { } }",
                                    2));
            assertTrue(instance.success());
            assertEquals("Instance", instance.mainClass());
        }
    }

    @Test
    void preservesPackagesAndDoesNotExposeCachedByteArrays() {
        try (var compiler = new JavacCompilerBackend()) {
            var first =
                    compiler.compile(new SourceSnapshot("Hello.java", "package demo; " + HELLO, 1));
            assertTrue(first.success());
            assertEquals("demo.Hello", first.mainClass());
            first.bytecode().get("demo.Hello")[0] = 0;
            var cached =
                    compiler.compile(new SourceSnapshot("Hello.java", "package demo; " + HELLO, 2));
            assertEquals((byte) 0xCA, cached.bytecode().get("demo.Hello")[0]);
        }
    }

    @Test
    void doesNotLeakEditorDependenciesIntoUserClasspath() {
        try (var compiler = new JavacCompilerBackend()) {
            var result =
                    compiler.compile(
                            new SourceSnapshot(
                                    "Leak.java",
                                    "class Leak { com.turboj.compiler.SourceSnapshot value; }",
                                    1));
            assertFalse(result.success());
        }
    }
}
