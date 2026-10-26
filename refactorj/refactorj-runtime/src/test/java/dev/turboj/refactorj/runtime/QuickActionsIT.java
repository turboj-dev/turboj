package dev.turboj.refactorj.runtime;

import dev.turboj.refactorj.api.*;
import dev.turboj.refactorj.api.JavaQuickActions.*;
import dev.turboj.refactorj.api.Model.*;
import dev.turboj.refactorj.transactions.*;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.util.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Real Equinox/JDT diagnostics, snapshot edits, compiler acceptance and undo; no semantic mocks. */
class QuickActionsIT {
    static RefactorJRuntime runtime;
    @TempDir Path root;
    @BeforeAll static void start() throws Exception { runtime = new RefactorJRuntime(); }
    @AfterAll static void stop() { runtime.close(); }

    @Test void caretWithoutFixMovesToClosestFixableProblemOnItsLineLikeEclipse() throws Exception {
        Path file = root.resolve("src/HelloWorld.java");
        String source = """
                public class HelloWorld {
                    public static void main(String[] args) {
                        System.out.println("Hello World!"); Calculator calculator = new Calculator();
                        int unrelated = 1;
                    }
                }
                """;
        var snapshot = snapshot(Map.of(file, source), List.of(root.resolve("src")), List.of());
        try (var engine = runtime.openWorkspace(snapshot)) {
            int declared = source.indexOf("Calculator"), created = source.lastIndexOf("Calculator");
            var afterWord = engine.quickActions().inspect(new Location(file, created + "Calculator".length()), Cancellation.NONE);
            assertEquals(new Location(file, created + "Calculator".length()), afterWord.location(), "touching caret stays put");
            assertEquals("Create class 'Calculator'", only(afterWord, Action.CREATE_CLASS).title());

            var endOfLine = engine.quickActions().inspect(new Location(file, source.indexOf(';', created) + 1), Cancellation.NONE);
            assertEquals(new Location(file, created), endOfLine.location(), "caret jumps to the closest problem");
            var proposal = only(endOfLine, Action.CREATE_CLASS);
            assertTrue(engine.quickActions().prepare(proposal, Cancellation.NONE).plan().valid());

            var lineStart = engine.quickActions().inspect(new Location(file, source.indexOf("System")), Cancellation.NONE);
            assertEquals(new Location(file, declared), lineStart.location());

            int otherLine = source.indexOf("int unrelated");
            var clean = engine.quickActions().inspect(new Location(file, otherLine), Cancellation.NONE);
            assertEquals(new Location(file, otherLine), clean.location(), "problems on other lines are never chosen");
            assertTrue(clean.fixes().isEmpty());

            var selection = new Location(file, source.indexOf("System"), source.indexOf("System") + 6);
            assertEquals(selection, engine.quickActions().inspect(selection, Cancellation.NONE).location(), "selections are honored");
        }
    }

    @Test void unqualifiedAssertionImportsItsStaticFavoriteAndTheLibraryWhenMissing() throws Exception {
        Path file = root.resolve("test/CalculatorTest.java");
        String source = """
                import org.junit.jupiter.api.Test;

                public class CalculatorTest {
                    @Test
                    public void test() {
                        assertEquals();
                    }
                }
                """;
        String imported = source.replace("import org.junit.jupiter.api.Test;\n",
                "import static org.junit.jupiter.api.Assertions.assertEquals;\n\nimport org.junit.jupiter.api.Test;\n");
        try (var engine = runtime.openWorkspace(snapshot(Map.of(file, source), List.of(root.resolve("test")), junitClasspath()))) {
            var proposal = only(engine.quickActions().inspect(at(file, source, "assertEquals"), Cancellation.NONE), Action.ADD_IMPORT);
            assertEquals("Import static 'Assertions.assertEquals'", proposal.title());
            assertEquals(imported, TextEdit.apply(source, engine.quickActions().prepare(proposal, Cancellation.NONE).plan().edits().getFirst().edits()));
        }
        String missing = source.replace("import org.junit.jupiter.api.Test;\n\n", "");
        try (var engine = runtime.openWorkspace(snapshot(Map.of(file, missing), List.of(root.resolve("test")), List.of()))) {
            var proposal = only(engine.quickActions().inspect(at(file, missing, "assertEquals"), Cancellation.NONE), Action.ADD_JUNIT_LIBRARY);
            assertTrue(TextEdit.apply(missing, engine.quickActions().prepare(proposal, Cancellation.NONE).plan().edits().getFirst().edits())
                    .startsWith("import static org.junit.jupiter.api.Assertions.assertEquals;"));
        }
    }

    @Test void unresolvedJUnit4ImportsMoveToJupiterWithTheLibraryWhenItIsMissing() throws Exception {
        Path file = root.resolve("test/CalculatorTest.java");
        String source = """
                import org.junit.Before;
                import org.junit.Test;

                public class CalculatorTest {
                    @Before
                    public void setUp() {}

                    @Test
                    public void test() {
                        Calculator calculator = new Calculator();
                    }
                }
                """;
        String migrated = source.replace("org.junit.Before", "org.junit.jupiter.api.BeforeEach")
                .replace("org.junit.Test", "org.junit.jupiter.api.Test").replace("@Before\n", "@BeforeEach\n");
        try (var engine = runtime.openWorkspace(snapshot(Map.of(file, source), List.of(root.resolve("test")), List.of()))) {
            for (String caret : List.of("org.junit.Test", "Test\n    public")) {
                var proposal = only(engine.quickActions().inspect(at(file, source, caret), Cancellation.NONE), Action.ADD_JUNIT_LIBRARY);
                assertEquals("Add JUnit 5 library to the build path", proposal.title());
                var plan = engine.quickActions().prepare(proposal, Cancellation.NONE).plan();
                assertEquals(migrated, TextEdit.apply(source, plan.edits().getFirst().edits()), "from " + caret);
            }
        }
        try (var engine = runtime.openWorkspace(snapshot(Map.of(file, source), List.of(root.resolve("test")), junitClasspath()))) {
            var inspection = engine.quickActions().inspect(at(file, source, "org.junit.Test"), Cancellation.NONE);
            assertTrue(inspection.proposals().stream().noneMatch(p -> p.action() == Action.ADD_JUNIT_LIBRARY), "Jupiter is already there");
            var proposal = only(inspection, Action.ADD_IMPORT);
            assertEquals("Change JUnit 4 imports to JUnit 5", proposal.title());
            assertEquals(migrated, TextEdit.apply(source, engine.quickActions().prepare(proposal, Cancellation.NONE).plan().edits().getFirst().edits()));
        }
    }

    @Test void unresolvedJupiterAnnotationOffersTheLibraryOnlyWhileItIsMissing() throws Exception {
        Path file = root.resolve("test/CalculatorTest.java");
        String source = "package demo;\n\nclass CalculatorTest {\n    @Test\n    void adds() {}\n}\n";
        try (var engine = runtime.openWorkspace(snapshot(Map.of(file, source), List.of(root.resolve("test")), List.of()))) {
            var inspection = engine.quickActions().inspect(at(file, source, "Test\n"), Cancellation.NONE);
            var proposal = only(inspection, Action.ADD_JUNIT_LIBRARY);
            assertEquals("Add JUnit 5 library to the build path", proposal.title());
            assertEquals(Kind.FIX, proposal.kind());
            var plan = engine.quickActions().prepare(proposal, Cancellation.NONE).plan();
            assertTrue(plan.fileOperations().isEmpty(), "the host owns the library; the engine prepares only Java");
            assertEquals("package demo;\n\nimport org.junit.jupiter.api.Test;\n\nclass CalculatorTest {\n    @Test\n    void adds() {}\n}\n",
                    TextEdit.apply(source, plan.edits().getFirst().edits()));

            String plain = "class CalculatorTest {\n    @Override\n    @Missing\n    void adds() {}\n}\n";
            Path other = root.resolve("test/Other.java");
            engine.workspace().synchronize(snapshot(Map.of(file, source, other, plain), List.of(root.resolve("test")), List.of()), Cancellation.NONE);
            assertTrue(engine.quickActions().inspect(at(other, plain, "Missing"), Cancellation.NONE).proposals().stream()
                    .noneMatch(p -> p.action() == Action.ADD_JUNIT_LIBRARY), "only Jupiter annotations need JUnit");
        }
        try (var engine = runtime.openWorkspace(snapshot(Map.of(file, source), List.of(root.resolve("test")), junitClasspath()))) {
            var inspection = engine.quickActions().inspect(at(file, source, "Test\n"), Cancellation.NONE);
            assertTrue(inspection.proposals().stream().noneMatch(p -> p.action() == Action.ADD_JUNIT_LIBRARY));
            assertEquals("Import 'org.junit.jupiter.api.Test'", only(inspection, Action.ADD_IMPORT).title());
        }
    }

    @Test void misspelledCreatedTypeOffersASubclassAndTheSimilarExistingTypeLikeEclipse() throws Exception {
        Path file = root.resolve("src/CalculatorTest.java"), existing = root.resolve("src/Calculator.java");
        String source = "public class CalculatorTest {\n    void test() {\n        Calculator calculator = new Calculator1();\n    }\n}\n";
        var snapshot = snapshot(Map.of(file, source, existing, "public class Calculator {}\n"), List.of(root.resolve("src")), List.of());
        try (var engine = runtime.openWorkspace(snapshot)) {
            var inspection = engine.quickActions().inspect(new Location(file, source.indexOf(';') + 1), Cancellation.NONE);
            assertEquals(new Location(file, source.indexOf("Calculator1")), inspection.location(), "end of line jumps to the problem");
            assertEquals(List.of("Create class 'Calculator1'", "Change to 'Calculator'"), inspection.fixes().stream().map(Proposal::title).toList());

            var created = engine.quickActions().prepare(only(inspection, Action.CREATE_CLASS), Cancellation.NONE);
            assertEquals("Calculator", only(inspection, Action.CREATE_CLASS).parameters().get("superclass"));
            var transactions = new WorkspaceTransactions(new InMemoryWorkspace(snapshot));
            var applied = transactions.apply(created.plan(), Cancellation.NONE);
            assertEquals("public class Calculator1 extends Calculator {\n    \n}\n", applied.snapshot().source(root.resolve("src/Calculator1.java")).text());
            compile(applied.snapshot());

            var changed = engine.quickActions().prepare(only(inspection, Action.REFACTOR), Cancellation.NONE);
            var renamed = new WorkspaceTransactions(new InMemoryWorkspace(snapshot)).apply(changed.plan(), Cancellation.NONE);
            assertEquals(source.replace("Calculator1", "Calculator"), renamed.snapshot().source(file).text());
        }
    }

    @Test void missingMethodInsideAnUnimportedAssertionCreatesTheMethodAndTheStaticImportTogether() throws Exception {
        Path file = root.resolve("src/CalculatorTest.java"), calculator = root.resolve("src/Calculator.java");
        String source = """
                import org.junit.jupiter.api.Test;

                public class CalculatorTest {
                    Calculator calculator = new Calculator();

                    @Test
                    public void testAdd() {
                        assertEquals(0, calculator.add(0, 0));
                    }
                }
                """;
        var snapshot = snapshot(Map.of(file, source, calculator, "public class Calculator {}\n"), List.of(root.resolve("src")), junitClasspath());
        try (var engine = runtime.openWorkspace(snapshot)) {
            var line = engine.quickActions().inspect(new Location(file, source.indexOf("        assertEquals")), Cancellation.NONE);
            assertEquals(new Location(file, source.indexOf("add(")), line.location(), "the caret anywhere on the line reaches add");
            var prepared = engine.quickActions().prepare(only(line, Action.CREATE_METHOD), Cancellation.NONE);
            var applied = new WorkspaceTransactions(new InMemoryWorkspace(snapshot)).apply(prepared.plan(), Cancellation.NONE);
            assertTrue(applied.snapshot().source(calculator).text().contains("int add(int"), applied.snapshot().source(calculator).text());
            assertTrue(applied.snapshot().source(file).text().contains("import static org.junit.jupiter.api.Assertions.assertEquals;"));
            compile(applied.snapshot());

            var onAssert = engine.quickActions().inspect(new Location(file, source.indexOf("assertEquals") + 3), Cancellation.NONE);
            assertEquals("Import static 'Assertions.assertEquals'", only(onAssert, Action.ADD_IMPORT).title(),
                    "the compiler hides the unresolved caller while its argument is in error");
        }
    }

    @Test void missingClassUsesUnsavedBufferAndPackageRootThenUndoRedo() throws Exception {
        Path file = root.resolve("src/example/Hello.java");
        String source = "package example; public class Hello { Calculator calculator = new Calculator(); }";
        var snapshot = snapshot(Map.of(file, source), List.of(root.resolve("src")), List.of());
        try (var engine = runtime.openWorkspace(snapshot)) {
            var inspection = engine.quickActions().inspect(at(file, source, "Calculator"), Cancellation.NONE);
            assertFalse(inspection.diagnostics().isEmpty());
            var proposal = only(inspection, Action.CREATE_CLASS);
            assertEquals(Kind.FIX, proposal.kind());
            var prepared = engine.quickActions().prepare(proposal, Cancellation.NONE);
            assertTrue(prepared.plan().valid(), prepared.plan().diagnostics().toString());
            assertEquals(root.resolve("src/example/Calculator.java"), prepared.focus().file());
            assertFalse(Files.exists(prepared.focus().file()));
            var buffers = new InMemoryWorkspace(snapshot); var transactions = new WorkspaceTransactions(buffers);
            var applied = transactions.apply(prepared.plan(), Cancellation.NONE);
            assertTrue(applied.snapshot().source(prepared.focus().file()).text().contains("package example;"));
            compile(applied.snapshot());
            assertEquals(snapshot.sources(), transactions.undo(applied.transactionId(), Cancellation.NONE).snapshot().sources());
            assertEquals(applied.snapshot().source(prepared.focus().file()).text(), transactions.redo(applied.transactionId(), Cancellation.NONE).snapshot().source(prepared.focus().file()).text());
            assertEquals(snapshot, engine.workspace().snapshot(), "Preparation never mutates the source model");
        }
    }

    @Test void nonTutorialGenericClassPreservesCrLfAndDoesNotEscapeRoot() throws Exception {
        Path file = root.resolve("module/src/main/java/store/Inventory.java");
        String source = "package store;\r\nclass Inventory { Shelf<String> shelf; }\r\n";
        try (var engine = runtime.openWorkspace(snapshot(Map.of(file, source), List.of(root.resolve("module/src/main/java")), List.of()))) {
            var result = engine.quickActions().prepare(only(engine.quickActions().inspect(at(file, source, "Shelf"), Cancellation.NONE), Action.CREATE_CLASS), Cancellation.NONE);
            assertTrue(result.plan().valid(), result.plan().diagnostics().toString());
            String created = result.plan().fileOperations().getFirst().content();
            assertTrue(created.contains("public class Shelf<T>"));
            assertFalse(created.replace("\r\n", "").contains("\n"));
            assertEquals(root.resolve("module/src/main/java/store/Shelf.java"), result.focus().file());
        }
    }

    @Test void refusesExistingDiskFileAndUnsupportedInterfaceCreation() throws Exception {
        Path file = root.resolve("src/Hello.java"), target = root.resolve("src/Shelf.java");
        Files.createDirectories(target.getParent()); Files.writeString(target, "user owned content");
        String source = "class Hello { Shelf value; }";
        try (var engine = runtime.openWorkspace(snapshot(Map.of(file, source), List.of(root.resolve("src")), List.of()))) {
            assertTrue(engine.quickActions().inspect(at(file, source, "Shelf"), Cancellation.NONE).proposals().stream().noneMatch(p -> p.action() == Action.CREATE_CLASS));
        }
        source = "class Hello implements MissingInterface {}";
        try (var engine = runtime.openWorkspace(snapshot(Map.of(file, source), List.of(root.resolve("src")), List.of()))) {
            assertTrue(engine.quickActions().inspect(at(file, source, "MissingInterface"), Cancellation.NONE).proposals().stream().noneMatch(p -> p.action() == Action.CREATE_CLASS));
        }
        assertEquals("user owned content", Files.readString(target));
    }

    @Test void methodCreationInfersIntForRealJUnitAssertionAndBodyPlaceholders() throws Exception {
        Path main = root.resolve("src/Calculator.java"), test = root.resolve("test/CalculatorTest.java");
        String source = "import static org.junit.jupiter.api.Assertions.assertEquals; class CalculatorTest { void test(){ assertEquals(42, new Calculator().add(20, 22)); } }";
        var snapshot = snapshot(Map.of(main, "public class Calculator {}", test, source), List.of(root.resolve("src"), root.resolve("test")), junitClasspath());
        try (var engine = runtime.openWorkspace(snapshot)) {
            var inspection = engine.quickActions().inspect(at(test, source, "add("), Cancellation.NONE);
            var proposal = only(inspection, Action.CREATE_METHOD);
            assertEquals("Create method 'add(int, int)' in Calculator", proposal.title());
            var result = engine.quickActions().prepare(proposal, Cancellation.NONE);
            assertTrue(result.plan().valid(), result.plan().diagnostics().toString());
            var applied = new WorkspaceTransactions(new InMemoryWorkspace(snapshot)).apply(result.plan(), Cancellation.NONE).snapshot();
            String generated = applied.source(main).text();
            assertTrue(generated.contains("int add(int"), generated);
            assertEquals(main, result.focus().file());
            assertEquals("return 0;", generated.substring(result.focus().start(), result.focus().end()));
            assertEquals(5, result.placeholders().size());
            compile(applied);
        }
    }

    @Test void methodInferenceFollowsNonTutorialStringArgumentsAndReturnContext() throws Exception {
        Path caller = root.resolve("Caller.java"), target = root.resolve("Formatter.java");
        String source = "class Caller { String call(){ return new Formatter().format(\"hello\", true); } }";
        var snapshot = snapshot(Map.of(caller, source, target, "class Formatter {}"), List.of(root), List.of());
        try (var engine = runtime.openWorkspace(snapshot)) {
            var proposal = only(engine.quickActions().inspect(at(caller, source, "format("), Cancellation.NONE), Action.CREATE_METHOD);
            assertTrue(proposal.title().contains("format(String, boolean)"));
            var result = engine.quickActions().prepare(proposal, Cancellation.NONE);
            assertTrue(result.plan().valid(), result.plan().diagnostics().toString());
            var changed = new WorkspaceTransactions(new InMemoryWorkspace(snapshot)).apply(result.plan(), Cancellation.NONE).snapshot();
            assertTrue(changed.source(target).text().contains("String format(String")); compile(changed);
        }
    }

    @Test void binaryTargetsDoNotOfferSourceCreation() throws Exception {
        Path file = root.resolve("Caller.java"); String source = "class Caller { void f(){ new String().missing(1); } }";
        try (var engine = runtime.openWorkspace(snapshot(Map.of(file, source), List.of(root), List.of()))) {
            assertTrue(engine.quickActions().inspect(at(file, source, "missing("), Cancellation.NONE).fixes().isEmpty());
        }
    }

    @Test void importsComeFromProjectResolutionAndAvoidUnrelatedErrors() throws Exception {
        Path file = root.resolve("C.java"); String source = "class C {\n    List<String> names; Missing missing;\n}";
        try (var engine = runtime.openWorkspace(snapshot(Map.of(file, source), List.of(root), List.of()))) {
            var inspected = engine.quickActions().inspect(at(file, source, "List"), Cancellation.NONE);
            var imported = inspected.fixes().stream().filter(p -> p.action() == Action.ADD_IMPORT && p.parameters().get("qualifiedName").equals("java.util.List")).findFirst().orElseThrow(() -> new AssertionError(inspected));
            var result = engine.quickActions().prepare(imported, Cancellation.NONE);
            assertTrue(result.plan().valid(), result.plan().diagnostics().toString());
            assertTrue(TextEdit.apply(source, result.plan().edits().getFirst().edits()).contains("import java.util.List;"));
            var onClass = engine.quickActions().inspect(at(file, source, "C {"), Cancellation.NONE);
            assertTrue(onClass.fixes().isEmpty());
            assertTrue(onClass.proposals().stream().anyMatch(p -> p.action() == Action.CREATE_JUNIT_TEST));
        }
    }

    @Test void junitProposalUsesClassAndMavenSourceSetContext() throws Exception {
        Path file = root.resolve("module/src/main/java/math/Summer.java");
        String source = "package math; public class Summer { public int sum(int a,int b){return a+b;} private void hidden(){} }";
        try (var engine = runtime.openWorkspace(snapshot(Map.of(file, source), List.of(root.resolve("module/src/main/java")), List.of()))) {
            var proposal = only(engine.quickActions().inspect(at(file, source, "Summer"), Cancellation.NONE), Action.CREATE_JUNIT_TEST);
            assertEquals(Kind.ASSIST, proposal.kind());
            assertEquals("math.Summer", proposal.parameters().get("qualifiedName"));
            assertEquals(root.resolve("module/src/test/java").toString(), proposal.parameters().get("testRoot"));
            assertEquals("sum(int, int)", proposal.parameters().get("methods"));
            assertThrows(IllegalArgumentException.class, () -> engine.quickActions().prepare(proposal, Cancellation.NONE));
        }
    }

    @Test void assistsAreContextualAndDirectTransformsAreValidated() throws Exception {
        Path file = root.resolve("C.java"); String source = "class C { void f(){ int count = 1; System.out.println(count); } }";
        try (var engine = runtime.openWorkspace(snapshot(Map.of(file, source), List.of(root), List.of()))) {
            var inspection = engine.quickActions().inspect(at(file, source, "count ="), Cancellation.NONE);
            assertTrue(inspection.fixes().isEmpty());
            assertTrue(inspection.assists().stream().anyMatch(p -> p.operation() == Operation.RENAME));
            var toVar = inspection.assists().stream().filter(p -> p.operation() == Operation.TYPE_TO_VAR).findFirst().orElseThrow();
            var result = engine.quickActions().prepare(toVar, Cancellation.NONE);
            assertTrue(result.plan().valid());
            assertTrue(TextEdit.apply(source, result.plan().edits().getFirst().edits()).contains("var count"));
        }
    }

    @Test void assignExpressionToLocalOrFieldUsesRealJdtChanges() throws Exception {
        Path file = root.resolve("C.java"); String source = "class C { void f(){ Integer.valueOf(1); } }";
        try (var engine = runtime.openWorkspace(snapshot(Map.of(file, source), List.of(root), List.of()))) {
            var inspection = engine.quickActions().inspect(at(file, source, "valueOf"), Cancellation.NONE);
            for (Operation operation : List.of(Operation.CREATE_LOCAL, Operation.CREATE_FIELD)) {
                var proposal = inspection.assists().stream().filter(p -> p.operation() == operation).findFirst().orElseThrow(() -> new AssertionError(inspection));
                var result = engine.quickActions().prepare(proposal, Cancellation.NONE);
                assertTrue(result.plan().valid(), result.plan().diagnostics().toString());
                var applied = new WorkspaceTransactions(new InMemoryWorkspace(result.plan().before())).apply(result.plan(), Cancellation.NONE).snapshot();
                assertTrue(applied.source(file).text().contains("= Integer.valueOf(1)")); compile(applied);
            }
        }
    }

    @Test void staleOrForgedProposalCannotApplyAndCancellationIsObserved() throws Exception {
        Path file = root.resolve("C.java"); String source = "class C { Missing missing; }";
        var snapshot = snapshot(Map.of(file, source), List.of(root), List.of());
        try (var engine = runtime.openWorkspace(snapshot)) {
            var api = engine.quickActions();
            var proposal = only(api.inspect(at(file, source, "Missing"), Cancellation.NONE), Action.CREATE_CLASS);
            var forged = new Proposal(proposal.id(), proposal.kind(), proposal.action(), "tampered", proposal.explanation(), proposal.location(), proposal.operation(), proposal.parameters());
            assertThrows(IllegalArgumentException.class, () -> api.prepare(forged, Cancellation.NONE));
            assertThrows(java.util.concurrent.CancellationException.class, () -> api.inspect(proposal.location(), () -> true));
            var next = new WorkspaceSnapshot(root, 2, List.of(new SourceSnapshot(file, source + " ", 2, true)), List.of(root), List.of());
            engine.workspace().synchronize(next, Cancellation.NONE);
            assertThrows(IllegalStateException.class, () -> api.prepare(proposal, Cancellation.NONE));
        }
    }

    @Test void runRoutingUsesResolvedJUnitAnnotationsIncludingMetaAndInheritedTests() throws Exception {
        Path file = root.resolve("Behavior.java");
        String source = "import org.junit.jupiter.api.Test; class Behavior { @Test void adds(){ } }";
        assertTestClass(file, source, source.length(), true, Map.of());
        source = "class Behavior { @org.junit.jupiter.api.TestFactory java.util.List<org.junit.jupiter.api.DynamicTest> dynamic(){return java.util.List.of();} }";
        assertTestClass(file, source, source.indexOf("Behavior"), true, Map.of());
        source = "class Behavior { @org.junit.jupiter.api.RepeatedTest(2) void repeat(){} }";
        assertTestClass(file, source, source.indexOf("repeat"), true, Map.of());
        source = "class Behavior extends Base {}";
        assertTestClass(file, source, source.length(), true, Map.of(root.resolve("Base.java"), "class Base { @org.junit.jupiter.api.Test void inherited(){} }"));
        source = "class Behavior { @CustomTest void composed(){} }";
        assertTestClass(file, source, 0, true, Map.of(root.resolve("CustomTest.java"), "@org.junit.jupiter.api.Test @interface CustomTest {}"));
        source = "class Behavior { @org.junit.jupiter.api.Nested class Nested { @org.junit.jupiter.api.Test void verifies(){} } }";
        assertTestClass(file, source, source.indexOf("Behavior"), true, Map.of());
    }

    @Test void namesAndUnresolvedOrUnrelatedAnnotationsCannotPretendToBeTests() throws Exception {
        Path file = root.resolve("BehaviorTest.java");
        for (String source : List.of("class BehaviorTest { void testSomething(){} }", "class BehaviorTest { @Test void example(){} }",
                "class BehaviorTest { @org.junit.jupiter.api.Test private void hidden(){} }", "class BehaviorTest { @org.junit.jupiter.api.Test static void invalid(){} }"))
            assertTestClass(file, source, source.length(), false, Map.of());
        String source = "class BehaviorTest { @Test void example(){} }";
        assertTestClass(file, source, 0, false, Map.of(root.resolve("Test.java"), "@interface Test {}"));
    }

    @Test void mainDetectionUsesResolvedSignaturesAndJava25LaunchForms() throws Exception {
        for (String source : List.of("class Entry { public static void main(String[] args){} }",
                "class Entry { public static void main(String... args){} }",
                "class Entry { static void main(){} }", "class Entry { void main(){} }",
                "class Entry { protected void main(String[] args){} }", "void main() {}",
                "class Entry { static class Nested { public static void main(String[] args){} } }"))
            assertMain(root.resolve("Entry.java"), source, Map.of(), "25", true);
    }

    @Test void mainDetectionRejectsNamesAndInvalidSignaturesWithoutExecutingSource() throws Exception {
        for (String source : List.of("class Entry { String message=\"public static void main(String[] args)\"; }",
                "class Entry { private static void main(String[] args){} }",
                "class Entry { public static int main(String[] args){return 0;} }",
                "class Entry { public static void main(Object[] args){} }",
                "class Entry { public static void main(String[][] args){} }",
                "class Entry { public static void main(String[] args,int extra){} }",
                "class Entry { static class String {} public static void main(String[] args){} }"))
            assertMain(root.resolve("Entry.java"), source, Map.of(), "25", false);
    }

    @Test void mainDetectionFollowsInheritedMembersAndAccessBoundaries() throws Exception {
        Path file = root.resolve("Entry.java");
        assertMain(file, "class Entry extends Parent {}", Map.of(root.resolve("Parent.java"),
                "class Parent { public static void main(String[] args){} }"), "25", true);
        assertMain(file, "class Entry extends Parent {}", Map.of(root.resolve("Parent.java"),
                "class Parent { void main(){} }"), "25", true);
        assertMain(file, "class Entry implements Parent {}", Map.of(root.resolve("Parent.java"),
                "interface Parent { default void main(){} }"), "25", true);
        assertMain(file, "class Entry implements Parent {}", Map.of(root.resolve("Parent.java"),
                "interface Parent { static void main(){} }"), "25", false);
        assertMain(root.resolve("other/Entry.java"), "package other; class Entry extends base.Parent {}",
                Map.of(root.resolve("base/Parent.java"), "package base; public class Parent { void main(){} }"), "25", false);
        assertMain(root.resolve("other/Entry.java"), "package other; class Entry extends base.Parent {}",
                Map.of(root.resolve("base/Parent.java"), "package base; public class Parent { protected void main(){} }"), "25", true);
        assertMain(file, "class Entry implements Parent, Child {}", Map.of(root.resolve("Parent.java"),
                "interface Parent { void main(); }", root.resolve("Child.java"),
                "interface Child extends Parent { default void main(){} }"), "25", true);
    }

    @Test void mainDetectionHonorsConfiguredTargetInsteadOfRuntimeJdk() throws Exception {
        assertMain(root.resolve("Entry.java"), "class Entry { public static void main(String[] args){} }", Map.of(), "21", true);
        assertMain(root.resolve("Entry.java"), "class Entry { void main(){} }", Map.of(), "21", false);
        assertMain(root.resolve("Entry.java"), "class Entry { static void main(String[] args){} }", Map.of(), "21", false);
    }

    @Test void missingOverloadFocusTracksNewMethodAmongExistingSameArityMethods() throws Exception {
        Path caller = root.resolve("Caller.java"), target = root.resolve("Maths.java");
        String source = "class Caller { int call(){ return new Maths().sum(20,22); } }";
        var snapshot = snapshot(Map.of(caller, source, target, "class Maths { String sum(String first,String second){return first+second;} }"), List.of(root), List.of());
        try (var engine = runtime.openWorkspace(snapshot)) {
            var inspection = engine.quickActions().inspect(at(caller, source, "sum("), Cancellation.NONE);
            var prepared = engine.quickActions().prepare(only(inspection, Action.CREATE_METHOD), Cancellation.NONE);
            assertTrue(prepared.plan().valid(), prepared.plan().diagnostics().toString());
            String after = TextEdit.apply(snapshot.source(target).text(), prepared.plan().edits().getFirst().edits());
            assertEquals("return 0;", after.substring(prepared.focus().start(), prepared.focus().end()));
        }
    }

    @Test void linkedMethodRenameRebasesCurrentDeclarationAndUpdatesOnlyBoundCallers() throws Exception {
        Path target = root.resolve("Calculator.java"), caller = root.resolve("Use.java");
        String declaration = "class Calculator { int add(int a,int b){return a+b;} }";
        String source = "class Use { int f(){ return new Calculator().add(20,22); } String text=\"add\"; }";
        WorkspaceSnapshot baseline = snapshot(Map.of(target, declaration, caller, source), List.of(root), List.of());
        int offset = declaration.indexOf("add(");
        WorkspaceSnapshot current = new WorkspaceSnapshot(root, 2, List.of(new SourceSnapshot(target,
                declaration.substring(0, offset) + "sum" + declaration.substring(offset + 3), 2, true),
                baseline.source(caller)), List.of(root), List.of());
        try (var engine = runtime.openWorkspace(current)) {
            var result = engine.quickActions().renameLinked(baseline, new Location(target, offset, offset + 3), "sum", Cancellation.NONE);
            assertTrue(result.plan().valid(), result.plan().diagnostics().toString());
            assertEquals(current, result.plan().before());
            assertEquals(current, engine.workspace().snapshot(), "Temporary baseline must be restored even during semantic inspection");
            assertEquals(List.of(caller), result.plan().edits().stream().map(FileEdits::file).toList());
            var after = new WorkspaceTransactions(new InMemoryWorkspace(current)).apply(result.plan(), Cancellation.NONE).snapshot();
            assertTrue(after.source(caller).text().contains(".sum(20,22)"));
            assertTrue(after.source(caller).text().contains("\"add\""));
            compile(after);
        }
    }

    @Test void linkedRenameRefusesConcurrentEditsAndRestoresCurrentAfterConflict() throws Exception {
        Path target = root.resolve("Calculator.java"), caller = root.resolve("Use.java");
        String declaration = "class Calculator { int add(){return 1;} int sum(){return 2;} }";
        String source = "class Use { int f(){return new Calculator().add();} }";
        WorkspaceSnapshot baseline = snapshot(Map.of(target, declaration, caller, source), List.of(root), List.of());
        int offset = declaration.indexOf("add(");
        String typed = declaration.substring(0, offset) + "sum" + declaration.substring(offset + 3);
        var current = new WorkspaceSnapshot(root, 2, List.of(new SourceSnapshot(target, typed, 2, true), baseline.source(caller)), List.of(root), List.of());
        try (var engine = runtime.openWorkspace(current)) {
            var result = engine.quickActions().renameLinked(baseline, new Location(target, offset, offset + 3), "sum", Cancellation.NONE);
            assertFalse(result.plan().valid());
            assertEquals(current, engine.workspace().snapshot());
            var concurrent = new WorkspaceSnapshot(root, 3, List.of(new SourceSnapshot(target, typed + " ", 3, true), baseline.source(caller)), List.of(root), List.of());
            engine.workspace().synchronize(concurrent, Cancellation.NONE);
            assertThrows(IllegalStateException.class, () -> engine.quickActions().renameLinked(baseline, new Location(target, offset, offset + 3), "sum", Cancellation.NONE));
            assertEquals(concurrent, engine.workspace().snapshot());
        }
    }

    @Test void linkedParameterRenameUpdatesBoundBodyReferencesAndRefreshesTemplateRanges() throws Exception {
        Path target = root.resolve("Arithmetic.java");
        String source = "class Arithmetic { int sum(int first,int second){return first+second;} int other(int first){return first;} }";
        WorkspaceSnapshot baseline = snapshot(Map.of(target, source), List.of(root), List.of());
        int offset = source.indexOf("first");
        String typed = source.substring(0, offset) + "leftOperand" + source.substring(offset + 5);
        var current = new WorkspaceSnapshot(root, 2, List.of(new SourceSnapshot(target, typed, 2, true)), List.of(root), List.of());
        try (var engine = runtime.openWorkspace(current)) {
            var prepared = engine.quickActions().renameLinked(baseline, new Location(target, offset, offset + 5), "leftOperand", Cancellation.NONE);
            assertTrue(prepared.plan().valid(), prepared.plan().diagnostics().toString());
            assertEquals(current, prepared.plan().before());
            assertEquals(current, engine.workspace().snapshot());
            var after = new WorkspaceTransactions(new InMemoryWorkspace(current)).apply(prepared.plan(), Cancellation.NONE).snapshot();
            String changed = after.source(target).text();
            assertTrue(changed.contains("return leftOperand+second;"), changed);
            assertTrue(changed.contains("int other(int first){return first;}"), changed);
            assertEquals("leftOperand", changed.substring(prepared.focus().start(), prepared.focus().end()));
            assertEquals(List.of("body", "parameter.0", "parameter.1", "returnType", "methodName"),
                    prepared.placeholders().stream().map(JavaQuickActions.Placeholder::group).toList());
            var slots = prepared.placeholders().stream().collect(java.util.stream.Collectors.toMap(JavaQuickActions.Placeholder::group,
                    p -> changed.substring(p.location().start(), p.location().end())));
            assertEquals("return leftOperand+second;", slots.get("body"));
            assertEquals("leftOperand", slots.get("parameter.0"));
            assertEquals("second", slots.get("parameter.1"));
            assertEquals("int", slots.get("returnType"));
            assertEquals("sum", slots.get("methodName"));
            compile(after);
        }
    }

    @Test void linkedParameterRenameRejectsConcurrentBodyEditsAndNameConflicts() throws Exception {
        Path target = root.resolve("Arithmetic.java");
        String source = "class Arithmetic { int sum(int first,int second){return first+second;} }";
        WorkspaceSnapshot baseline = snapshot(Map.of(target, source), List.of(root), List.of());
        int offset = source.indexOf("first");
        String typed = source.substring(0, offset) + "second" + source.substring(offset + 5);
        var current = new WorkspaceSnapshot(root, 2, List.of(new SourceSnapshot(target, typed, 2, true)), List.of(root), List.of());
        try (var engine = runtime.openWorkspace(current)) {
            var result = engine.quickActions().renameLinked(baseline, new Location(target, offset, offset + 5), "second", Cancellation.NONE);
            assertFalse(result.plan().valid());
            assertEquals(current, engine.workspace().snapshot());
            var concurrent = new WorkspaceSnapshot(root, 3, List.of(new SourceSnapshot(target, typed.replace("first+second", "first-second"), 3, true)), List.of(root), List.of());
            engine.workspace().synchronize(concurrent, Cancellation.NONE);
            assertThrows(IllegalStateException.class, () -> engine.quickActions().renameLinked(baseline, new Location(target, offset, offset + 5), "second", Cancellation.NONE));
            assertEquals(concurrent, engine.workspace().snapshot());
        }
    }

    @Test void linkedRenameRejectsLocalAndConstructorParameterDeclarations() throws Exception {
        Path target = root.resolve("Arithmetic.java");
        for (String source : List.of("class Arithmetic { int sum(){int original=1; return original;} }",
                "class Arithmetic { Arithmetic(int original){} }")) {
            WorkspaceSnapshot baseline = snapshot(Map.of(target, source), List.of(root), List.of());
            int offset = source.indexOf("original");
            String typed = source.substring(0, offset) + "changed" + source.substring(offset + 8);
            var current = new WorkspaceSnapshot(root, 2, List.of(new SourceSnapshot(target, typed, 2, true)), List.of(root), List.of());
            try (var engine = runtime.openWorkspace(current)) {
                assertThrows(IllegalArgumentException.class, () -> engine.quickActions().renameLinked(baseline,
                        new Location(target, offset, offset + 8), "changed", Cancellation.NONE));
                assertEquals(current, engine.workspace().snapshot());
            }
        }
    }

    @Test void crossSourceSetMethodCreationUsesDeclaredProjectDependency() throws Exception {
        Path mainRoot = root.resolve("src/main/java"), testRoot = root.resolve("src/test/java");
        Path target = mainRoot.resolve("math/Calculator.java"), caller = testRoot.resolve("math/CalculatorTest.java");
        String source = "package math; import static org.junit.jupiter.api.Assertions.assertEquals; class CalculatorTest { @org.junit.jupiter.api.Test void adds(){assertEquals(42,new Calculator().add(20,22));} }";
        var cp = junitClasspath();
        var projects = List.of(new JavaProjectConfiguration("main", List.of(mainRoot), List.of(), List.of(), List.of(), "25", "", "", Path.of(System.getProperty("java.home")), "UTF-8", false),
                new JavaProjectConfiguration("test", List.of(testRoot), cp, List.of(), List.of("main"), "25", "", "", Path.of(System.getProperty("java.home")), "UTF-8", false));
        var snapshot = new WorkspaceSnapshot(root, 1, List.of(new SourceSnapshot(target, "package math; public class Calculator {}", 1, true),
                new SourceSnapshot(caller, source, 1, true)), List.of(mainRoot, testRoot), cp, projects);
        try (var engine = runtime.openWorkspace(snapshot)) {
            var prepared = engine.quickActions().prepare(only(engine.quickActions().inspect(at(caller, source, "add("), Cancellation.NONE), Action.CREATE_METHOD), Cancellation.NONE);
            assertTrue(prepared.plan().valid(), prepared.plan().diagnostics().toString());
            assertEquals(target, prepared.focus().file());
            var after = new WorkspaceTransactions(new InMemoryWorkspace(snapshot)).apply(prepared.plan(), Cancellation.NONE).snapshot();
            assertTrue(after.source(target).text().contains("int add(int")); compile(after);
        }
    }

    private void assertTestClass(Path file, String source, int offset, boolean expected, Map<Path, String> others) throws Exception {
        var files = new HashMap<>(others); files.put(file, source); var classpath = junitClasspath();
        try (var engine = runtime.openWorkspace(snapshot(files, List.of(root), classpath))) {
            var intelligence = ((JavaIntelligenceProvider) engine).intelligence();
            var context = new JavaIntelligence.Context(file, source, 1, files, List.of(root), classpath, 1);
            assertEquals(expected, intelligence.isTestClass(context, offset), source);
            if (expected) assertNotNull(intelligence.testTarget(context, offset), "Run target must remain resolvable at EOF/outside the primary type");
        }
    }

    private void assertMain(Path file, String source, Map<Path, String> others, String target, boolean expected) throws Exception {
        var files = new HashMap<>(others); files.put(file, source);
        var projects = List.of(new JavaProjectConfiguration("main", List.of(root), List.of(), List.of(), List.of(),
                target, "", "", Path.of(System.getProperty("java.home")), "UTF-8", false));
        var before = snapshot(files, List.of(root), List.of());
        var snapshot = new WorkspaceSnapshot(root, 1, before.sources(), before.sourceRoots(), before.classpath(), projects);
        try (var engine = runtime.openWorkspace(snapshot)) {
            var context = new JavaIntelligence.Context(file, source, 1, files, List.of(root), List.of(), 1, List.of(), projects);
            assertEquals(expected, ((JavaIntelligenceProvider) engine).intelligence().hasMainMethod(context), source);
        }
    }

    private WorkspaceSnapshot snapshot(Map<Path, String> files, List<Path> roots, List<Path> classpath) {
        return new WorkspaceSnapshot(root, 1, files.entrySet().stream().map(e -> new SourceSnapshot(e.getKey(), e.getValue(), 1, true)).toList(), roots, classpath);
    }
    private static Location at(Path file, String source, String marker) { int start = source.indexOf(marker); assertTrue(start >= 0); return new Location(file, start); }
    private static Proposal only(Inspection inspection, Action action) { return inspection.proposals().stream().filter(p -> p.action() == action).findFirst().orElseThrow(() -> new AssertionError("Missing " + action + ": " + inspection)); }
    private static List<Path> junitClasspath() throws Exception {
        return List.of(Path.of(Assertions.class.getProtectionDomain().getCodeSource().getLocation().toURI()),
                Path.of(org.opentest4j.AssertionFailedError.class.getProtectionDomain().getCodeSource().getLocation().toURI()),
                Path.of(org.junit.platform.commons.JUnitException.class.getProtectionDomain().getCodeSource().getLocation().toURI()),
                Path.of(org.apiguardian.api.API.class.getProtectionDomain().getCodeSource().getLocation().toURI()));
    }
    private void compile(WorkspaceSnapshot snapshot) throws Exception {
        Path output = Files.createTempDirectory(root, "javac-"); var args = new ArrayList<String>(List.of("-proc:none", "-d", output.toString()));
        if (!snapshot.classpath().isEmpty()) { args.add("-classpath"); args.add(String.join(java.io.File.pathSeparator, snapshot.classpath().stream().map(Path::toString).toList())); }
        for (var source : snapshot.sources()) {
            Path copy = output.resolve(root.relativize(source.file())); Files.createDirectories(copy.getParent()); Files.writeString(copy, source.text()); args.add(copy.toString());
        }
        var errors = new ByteArrayOutputStream();
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, errors, args.toArray(String[]::new)), errors::toString);
    }
}
