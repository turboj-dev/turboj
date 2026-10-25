package dev.turboj.project;

import dev.turboj.editor.document.PieceTable;
import dev.turboj.intelligence.ProjectContext;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class PlainProjectCompilerTest {
    @TempDir Path root;

    @Test void compilesMainAndSiblingSourcesUsingUnsavedBuffersButExcludesTests() throws Exception {
        Path main = write("src/main/java/example/Hello.java", "package example; public class Hello {public static void main(String[] args){System.out.println(new Counter().value());}}");
        Path sibling = write("src/main/java/example/Counter.java", "package example; public class Counter {}");
        write("src/test/java/example/CounterTest.java", "deliberately invalid test should not prevent main run");
        var active = snapshot(Files.readString(main));
        var overlay = snapshot("package example; public class Counter {public int value(){return 42;}}");
        try (var compiler = new PlainProjectCompiler()) {
            var result = compiler.compile(main, active, Map.of(sibling, overlay), context(main));
            assertTrue(result.active().success(), result.active().diagnostics().toString());
            assertEquals(Set.of("example.Hello", "example.Counter"), result.active().bytecode().keySet());
            assertEquals("example.Hello", result.active().mainClass());
            assertTrue(result.workspace().sources().stream().anyMatch(value -> value.source().contains("return 42")));
            assertEquals(active.version(), result.active().version());
        }
    }

    @Test void testCompilationSeesMainAndConfiguredJupiterAndNeverSelectsUnrelatedMain() throws Exception {
        Path main = write("src/main/java/example/Hello.java", "package example; public class Hello {public static void main(String[] args){}}");
        Path test = write("src/test/java/example/HelloTest.java", "package example; import org.junit.jupiter.api.Test; class HelloTest { @Test void works() {} }");
        write(".turboj/junit.properties", "runtime=bundled-jupiter-5.13.4\n");
        var config = context(test);
        try (var compiler = new PlainProjectCompiler()) {
            var result = compiler.compile(test, snapshot(Files.readString(test)), Map.of(), config);
            assertTrue(result.active().success(), result.active().diagnostics().toString());
            assertEquals(Set.of("example.Hello", "example.HelloTest"), result.active().bytecode().keySet());
            assertNull(result.active().mainClass());
        }
    }

    @Test void diagnosticInSiblingIsNotPaintedAsAnActiveFileRangeAndNoStaleBytecodeSurvives() throws Exception {
        Path main = write("src/main/java/example/Hello.java", "package example; public class Hello { public static void main(String[] args) {} }");
        Path other = write("src/main/java/example/Other.java", "package example; class Other {} ");
        try (var compiler = new PlainProjectCompiler()) {
            assertTrue(compiler.compile(main, snapshot(Files.readString(main)), Map.of(), context(main)).active().success());
            Files.writeString(other, "package example; class Other { Missing missing; }");
            var failed = compiler.compile(main, snapshot(Files.readString(main)), Map.of(), context(main));
            assertFalse(failed.active().success()); assertTrue(failed.active().bytecode().isEmpty());
            assertTrue(failed.active().diagnostics().getFirst().message().contains("Other.java"));
            assertEquals(-1, failed.active().diagnostics().getFirst().start());
            assertTrue(failed.workspace().diagnostics().stream().anyMatch(value -> value.fileName().endsWith("Other.java")));
        }
    }

    @Test void refusesMavenBoundariesInsteadOfCompilingReactorAsOneJavaProject() throws Exception {
        Path main = write("src/main/java/Main.java", "class Main {}");
        var plain = context(main);
        var module = new dev.turboj.refactorj.api.Model.JavaProjectConfiguration("main", plain.sourceRoots(), List.of(), List.of(), List.of(), "25", "", "", null, "UTF-8", false);
        var configured = new ProjectContext.Config(root, plain.sourceRoots(), List.of(), List.of(), 1, List.of(), List.of(module));
        try (var compiler = new PlainProjectCompiler()) {
            assertThrows(IllegalArgumentException.class, () -> compiler.compile(main, snapshot("class Main {}"), Map.of(), configured));
        }
    }

    private Path write(String path, String text) throws Exception {
        Path file = root.resolve(path); Files.createDirectories(file.getParent()); Files.writeString(file, text); return file;
    }
    private ProjectContext.Config context(Path active) throws Exception {
        write(".turboj/source-roots.txt", "src/main/java\nsrc/test/java\n");
        return ProjectContext.detect(active, Files.readString(active));
    }
    private static PieceTable.Snapshot snapshot(String text) { return new PieceTable(text).snapshot(); }
}
