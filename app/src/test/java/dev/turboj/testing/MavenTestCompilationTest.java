package dev.turboj.testing;

import dev.turboj.mavenj.api.MavenModels.*;
import dev.turboj.mavenj.api.MavenModels.Module;
import dev.turboj.project.MavenCompilationWorkspace;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MavenTestCompilationTest {
    @TempDir Path root;

    @Test void editorCompilationCannotReplacePreparedTestsOrTheirFingerprint() throws Exception {
        Path main = Files.createDirectories(root.resolve("src/main/java"));
        Path tests = Files.createDirectories(root.resolve("src/test/java"));
        Path source = main.resolve("Value.java");
        Files.writeString(source, "public class Value { public static int value(){return 1;} }");
        Files.writeString(tests.resolve("ValueTest.java"),
                "public class ValueTest { public static int value(){return Value.value();} }");
        var java = new JavaConfiguration("17", "", "", "UTF-8", Path.of(System.getProperty("java.home")),
                Map.of(), List.of(), ProcessorConfiguration.none(root.resolve("generated")), false);
        var module = new Module("example", new Coordinates("example", "example", "1", "jar", ""),
                root, root.resolve("pom.xml"), "jar", "", List.of(), List.of(main), List.of(tests),
                List.of(), List.of(), List.of(), List.of(), root.resolve("target/classes"),
                root.resolve("target/test-classes"), java, java, List.of(), List.of(),
                new Classpaths(Map.of(), Map.of(), true), List.of(), List.of(), Map.of(), "",
                FastPath.SUPPORTED, List.of());
        var model = new Workspace(root, module.pom(), 1, List.of(module), List.of(module.id()),
                List.of(), "model", root.resolve("repository"), List.of(), true, List.of(), 0, false);
        List<Path> pinnedPaths;
        try (var compiler = new MavenCompilationWorkspace()) {
            try (var prepared = MavenTestCompilation.prepare(compiler, model, Map.of(), 10, (m, f) -> true)) {
                assertTrue(prepared.result.success(), prepared.result.diagnostics().toString());
                var execution = prepared.executions.get(module.id());
                pinnedPaths = execution.classpath();
                String fingerprint = prepared.fingerprints.get(module.id());
                // Deterministic competing compilation before test workers launch, as observed
                // between Maven test selection and execution in the native first-launch flow.
                assertTrue(compiler.compile(model, Map.of(source,
                        "public class Value { public static int value(){return 2;} }"), 11, true).success());
                assertNotEquals(fingerprint, compiler.sourceFingerprint(module.id(), true));
                assertEquals(fingerprint, prepared.fingerprints.get(module.id()));
                assertThrows(IllegalStateException.class, () -> compiler.pinExecution(module.id(), true, 1, 10));
                var urls = new java.net.URL[pinnedPaths.size()];
                for (int i = 0; i < urls.length; i++) urls[i] = pinnedPaths.get(i).toUri().toURL();
                try (var loader = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader())) {
                    assertEquals(1, loader.loadClass("ValueTest").getMethod("value").invoke(null));
                }
            }
            assertTrue(pinnedPaths.stream().noneMatch(Files::exists), "Closing owns and removes all pinned copies");
            try (var skipped = MavenTestCompilation.prepare(compiler, model, Map.of(), 12, (m, f) -> false)) {
                assertTrue(skipped.result.success());
                assertFalse(skipped.fingerprints.isEmpty());
                assertTrue(skipped.executions.isEmpty(), "Skipped/compile-only modules do not copy runtime artifacts");
            }
            try (var failed = MavenTestCompilation.prepare(compiler, model, Map.of(source, "invalid java"), 13, (m, f) -> true)) {
                assertFalse(failed.result.success());
                assertTrue(failed.executions.isEmpty());
            }
        }
    }
}
