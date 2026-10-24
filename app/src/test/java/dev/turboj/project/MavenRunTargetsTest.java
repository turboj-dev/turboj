package dev.turboj.project;

import dev.turboj.compiler.JavacCompilerBackend;
import dev.turboj.compiler.SourceSnapshot;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MavenRunTargetsTest {
    @TempDir Path directory;

    @Test void java21RequiresPublicStaticMainWithStringArray() {
        var module = compile("app", false,
                source("app", "Main", "public static void main(String[] args) {}"),
                source("app", "PrivateMain", "private static void main(String[] args) {}"),
                source("app", "InstanceMain", "public void main(String[] args) {}"),
                source("app", "NoArgs", "public static void main() {}"),
                source("app", "WrongArgs", "public static void main(int[] args) {}"));
        assertEquals(List.of(target("app", "Main")), MavenRunTargets.candidates(result(module), null));
    }

    @Test void activeSourceWinsOverOtherModulesAndItsLabelIdentifiesTheModule() {
        var first = source("first", "One", "public static void main(String[] args) {}");
        var second = source("second", "Two", "public static void main(String[] args) {}");
        var candidates = MavenRunTargets.candidates(result(compile("first", false, first),
                compile("second", false, second)), Path.of(second.fileName()));
        assertEquals(List.of(target("second", "Two")), candidates);
        assertEquals("second · example.Two", candidates.getFirst().toString());
    }

    @Test void nonMainSourceAndPomFindTheProjectMain() {
        var main = source("app", "Main", "public static void main(String[] args) {}");
        var helper = source("app", "Helper", "public int value() { return 7; }");
        var compiled = result(compile("app", false, main, helper));
        assertEquals(List.of(target("app", "Main")), MavenRunTargets.candidates(compiled, Path.of(helper.fileName())));
        assertEquals(List.of(target("app", "Main")), MavenRunTargets.candidates(compiled, directory.resolve("pom.xml")));
    }

    @Test void allMainTargetsAreSortedAndEqualClassNamesInDifferentModulesSurvive() {
        var second = compile("z-module", false, source("z-module", "Main", "public static void main(String[] args) {}"));
        var first = compile("a-module", false,
                source("a-module", "Zulu", "public static void main(String[] args) {}"),
                source("a-module", "Main", "public static void main(String[] args) {}"));
        assertEquals(List.of(target("a-module", "Main"), target("a-module", "Zulu"), target("z-module", "Main")),
                MavenRunTargets.candidates(result(second, first), null));
    }

    @Test void testHelpersAreIgnoredUnlessTheirSourceIsActive() {
        var main = compile("app", false, source("app", "Main", "public static void main(String[] args) {}"));
        var testSource = source("app-tests", "TestHelper", "public static void main(String[] args) {}");
        var tests = compile("app", true, testSource);
        var compiled = result(tests, main);
        assertEquals(List.of(target("app", "Main")), MavenRunTargets.candidates(compiled, null));
        assertEquals(List.of(new MavenRunTargets.Target("app", "example.TestHelper", true)),
                MavenRunTargets.candidates(compiled, Path.of(testSource.fileName())));
    }

    @Test void unsuccessfulCompilationNeverOffersPreviouslyCompiledMains() {
        var module = compile("app", false, source("app", "Main", "public static void main(String[] args) {}"));
        var failed = new MavenCompilationWorkspace.Result(1, 1, false, List.of(module), List.of(), 1);
        assertTrue(MavenRunTargets.candidates(failed, null).isEmpty());
    }

    private SourceSnapshot source(String module, String name, String body) {
        return new SourceSnapshot(directory.resolve(module + "/src/example/" + name + ".java").toString(),
                "package example; public class " + name + " { " + body + " }", 1);
    }

    private MavenCompilationWorkspace.ModuleResult compile(String module, boolean tests, SourceSnapshot... sources) {
        try (var compiler = new JavacCompilerBackend(21, List.of())) {
            var compilation = compiler.compileSources(List.of(sources), 1);
            assertTrue(compilation.success(), compilation.diagnostics().toString());
            return new MavenCompilationWorkspace.ModuleResult(module, tests, true, false,
                    directory.resolve(module + "/output"), compilation);
        }
    }

    private MavenCompilationWorkspace.Result result(MavenCompilationWorkspace.ModuleResult... modules) {
        return new MavenCompilationWorkspace.Result(1, 1, true, List.of(modules), List.of(), 1);
    }

    private MavenRunTargets.Target target(String module, String name) {
        return new MavenRunTargets.Target(module, "example." + name, false);
    }
}
