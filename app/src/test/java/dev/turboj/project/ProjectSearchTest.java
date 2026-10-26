package dev.turboj.project;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboj.project.ProjectSearch.Item;
import dev.turboj.project.ProjectSearch.Kind;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProjectSearchTest {
    @TempDir Path project;

    @Test void indexesFilesAndJavaDeclarationsButNotBuildOutput() throws Exception {
        Path main = Files.createDirectories(project.resolve("src/main/java/demo"));
        Files.writeString(project.resolve("pom.xml"), "<project/>");
        Files.writeString(main.resolve("Calculator.java"), """
                package demo;

                public class Calculator {
                    private int total;
                    enum Mode { SIMPLE }
                    public int add(int a, int b) { Runnable local = new Runnable() { public void run() {} }; return a + b; }
                    public Calculator() {}
                }
                """);
        Path packageNamedBuild = Files.createDirectories(main.resolve("build"));
        Files.writeString(packageNamedBuild.resolve("Builder.java"), "package demo.build;\nclass Builder {}\n");
        Files.createDirectories(project.resolve("target/classes"));
        Files.writeString(project.resolve("target/classes/Calculator.class"), "binary");
        Files.createDirectories(project.resolve(".git"));
        Files.writeString(project.resolve(".git/HEAD"), "ref");

        var items = new ProjectSearch().scan(project, Map.of());
        assertEquals(Set.of("pom.xml", "Calculator.java", "Builder.java"), names(items, Kind.FILE), "target/ and .git/ are skipped");
        assertEquals(Set.of("Calculator", "Mode", "Builder"), names(items, Kind.CLASS), "a package named build is indexed");
        assertEquals(Set.of("add"), names(items, Kind.METHOD), "constructors and anonymous-class methods are not");
        assertEquals(Set.of("total", "SIMPLE"), names(items, Kind.FIELD));
        Item add = items.stream().filter(item -> item.name().equals("add")).findFirst().orElseThrow();
        assertEquals("add(int, int) : int", add.label());
        assertEquals("Calculator", add.detail());
        String source = Files.readString(main.resolve("Calculator.java"));
        assertEquals(source.indexOf("add("), add.offset());
        assertEquals("demo.Calculator", items.stream().filter(item -> item.name().equals("Mode")).findFirst().orElseThrow().detail());
    }

    @Test void unsavedBuffersReplaceTheirFileOnDisk() throws Exception {
        Path file = project.resolve("Hello.java");
        Files.writeString(file, "class Hello {}\n");
        var search = new ProjectSearch();
        assertEquals(Set.of("Hello"), names(search.scan(project, Map.of()), Kind.CLASS));
        assertEquals(Set.of("Hello", "Typed"), names(search.scan(project, Map.of(file, "class Hello {}\nclass Typed {}\n")), Kind.CLASS));
    }

    @Test void ranksExactPrefixCamelHumpThenSubstringAndCurrentFileFirst() {
        Path a = Path.of("/p/A.java"), b = Path.of("/p/B.java");
        var items = List.of(
                new Item(Kind.CLASS, "CalculatorTest", "CalculatorTest", "", a, 0),
                new Item(Kind.CLASS, "Calculator", "Calculator", "", b, 0),
                new Item(Kind.CLASS, "ScientificCalculator", "ScientificCalculator", "", b, 0),
                new Item(Kind.METHOD, "calculate", "calculate()", "", b, 0),
                new Item(Kind.METHOD, "clear", "clear()", "", a, 0));
        assertEquals(List.of("Calculator", "CalculatorTest", "calculate()", "ScientificCalculator"), labels(ProjectSearch.search(items, EnumSet.allOf(Kind.class), "calc", b, 10)));
        assertEquals(List.of("CalculatorTest"), labels(ProjectSearch.search(items, EnumSet.of(Kind.CLASS), "CT", b, 10)), "CamelHump");
        assertEquals(List.of("CalculatorTest"), labels(ProjectSearch.search(items, EnumSet.of(Kind.CLASS), "CalcTe", b, 10)));
        assertEquals(List.of("ScientificCalculator"), labels(ProjectSearch.search(items, EnumSet.of(Kind.CLASS), "SciCal", b, 10)));
        assertEquals(List.of("clear()", "calculate()"), labels(ProjectSearch.search(items, EnumSet.of(Kind.METHOD), "", a, 10)),
                "an empty query lists the current file first, like a quick outline");
        assertTrue(ProjectSearch.search(items, EnumSet.of(Kind.CLASS), "xyz", a, 10).isEmpty());
        assertEquals(0, ProjectSearch.score("Calculator", "calculator"));
        assertEquals(-1, ProjectSearch.score("Calculator", "CTX"));
    }

    @Test void folderOrPackageInTheQueryNarrowsFilesAndClasses() {
        var items = List.of(new Item(Kind.FILE, "Calculator.java", "Calculator.java", "src/main/java/demo", Path.of("/p/m"), 0),
                new Item(Kind.FILE, "Calculator.java", "Calculator.java", "src/test/java/demo", Path.of("/p/t"), 0),
                new Item(Kind.CLASS, "Calculator", "Calculator", "demo.math", Path.of("/p/c"), 0));
        assertEquals(List.of("src/test/java/demo"), ProjectSearch.search(items, EnumSet.of(Kind.FILE), "test/java", null, 10)
                .stream().map(Item::detail).toList());
        assertEquals(1, ProjectSearch.search(items, EnumSet.of(Kind.CLASS), "math.Calc", null, 10).size());
    }

    private static Set<String> names(List<Item> items, Kind kind) {
        var names = new HashSet<String>();
        items.stream().filter(item -> item.kind() == kind).forEach(item -> names.add(item.name()));
        return names;
    }
    private static List<String> labels(List<Item> items) { return items.stream().map(Item::label).toList(); }
}
