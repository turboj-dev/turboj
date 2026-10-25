package dev.turboj.testing.intelligence;

import static dev.turboj.testing.intelligence.LineIntelligence.*;
import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.Gson;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TestSourceResolverTest {
    @TempDir Path root;

    @Test void normalizedJUnitResultsResolveOnlyTheirHashedTestDeclarationsAndKeepUnknownFreshness() throws Exception {
        String source = "package demo;\nimport org.junit.jupiter.api.Test;\nclass PriceTest {\n"
                + "  @Test void appliesDiscount() {}\n  @Test void keepsPrice() {}\n}\n";
        Path tests = write("src/test/java/demo/PriceTest.java", source);
        Path production = write("src/main/java/demo/Price.java", "package demo; class Price { int appliesDiscount() {return 90;} }");
        Map<Path, String> hashes = hashes(tests, production);
        Gson gson = new Gson();
        CherryEvents reader = new CherryEvents(root);
        reader.accept(gson.toJson(Map.of("schemaVersion", 1, "event", "run.started", "runId", "run", "sequence", 0,
                "workspaceRoot", root.toString(), "sourceBefore", hashes)));
        reader.accept(gson.toJson(Map.of("schemaVersion", 1, "event", "phase.completed", "runId", "run", "sequence", 1,
                "runtimeId", "maven", "completeness", "complete", "tests", List.of(
                        Map.of("name", "demo.PriceTest::appliesDiscount", "status", "failed"),
                        Map.of("name", "demo.PriceTest::keepsPrice", "status", "passed")))));
        Run resolved = TestSourceResolver.resolve(reader.snapshot());
        assertEquals(2, resolved.tests().size());
        for (int i = 0; i < 2; i++) {
            TestResult test = resolved.tests().get(i);
            assertEquals(Freshness.UNKNOWN, test.freshness());
            assertEquals(1, test.associations().size());
            Association association = test.associations().getFirst();
            assertEquals(tests, association.file()); assertEquals(4 + i, association.line());
            assertEquals(Attribution.TEST_DECLARATION, association.kind());
            assertTrue(association.evidence().contains(hashes.get(tests)));
        }
        assertEquals(TestStatus.FAILED, resolved.tests().getFirst().status());
        assertTrue(resolved.coverage().isEmpty());
        assertSame(resolved, TestSourceResolver.resolve(resolved), "Repeated phase inspection is idempotent");
    }

    @Test void nestedIdentityIsExactAndInheritedOrDisplayNamesRemainUnattributed() throws Exception {
        Path file = write("Outer.java", "package p; import org.junit.jupiter.api.Test; class Outer {\n"
                + "@Test void same() {}\nclass Inner { @Test void same() {} }\n}\n"
                + "class Child extends Outer {}\n");
        Run run = run(hashes(file), "p.Outer::same", "p.Outer$Inner::same", "p.Outer.Inner::same",
                "p.Child::same", "p.Outer::same[1]", "same", "p.Outer::same()");
        List<TestResult> tests = TestSourceResolver.resolve(run).tests();
        assertEquals(2, tests.get(0).associations().getFirst().line());
        assertEquals(3, tests.get(1).associations().getFirst().line());
        assertEquals(3, tests.get(2).associations().getFirst().line());
        for (int i = 3; i < tests.size(); i++) assertTrue(tests.get(i).associations().isEmpty());
    }

    @Test void duplicateClassesAndOverloadedMethodNamesCannotChooseAnArbitraryDeclaration() throws Exception {
        Path first = write("one/SameTest.java", "package p; class SameTest { @org.junit.jupiter.api.Test void check() {} }");
        Path second = write("two/SameTest.java", "package p; class SameTest { @org.junit.jupiter.api.Test void check() {} }");
        assertTrue(TestSourceResolver.resolve(run(hashes(first, second), "p.SameTest::check")).tests().getFirst().associations().isEmpty());
        Path overloaded = write("Overloaded.java", "package p; class Overloaded { @org.junit.jupiter.api.Test void check() {} void check(int i) {} }");
        assertTrue(TestSourceResolver.resolve(run(hashes(overloaded), "p.Overloaded::check")).tests().getFirst().associations().isEmpty());
    }

    @Test void changedSourceAndStaleRunsCannotReuseCachedDeclarationEvidence() throws Exception {
        Path file = write("DemoTest.java", "class DemoTest { @org.junit.jupiter.api.Test void check() {} }");
        Run original = run(hashes(file), "DemoTest::check");
        Run resolved = TestSourceResolver.resolve(original);
        assertEquals(1, resolved.tests().getFirst().associations().size());
        Files.writeString(file, "\n" + Files.readString(file));
        assertTrue(TestSourceResolver.resolve(original).tests().getFirst().associations().isEmpty());
        assertTrue(TestSourceResolver.resolve(resolved).tests().getFirst().associations().isEmpty());
        Run current = run(hashes(file), "DemoTest::check");
        TestResult stale = current.tests().getFirst().withFreshness(Freshness.STALE);
        assertTrue(TestSourceResolver.resolve(new Run(current.id(), current.root(), current.state(), List.of(stale), Map.of(),
                current.sourceHashes(), "", null, 1)).tests().getFirst().associations().isEmpty());
        assertEquals(2, TestSourceResolver.resolve(current).tests().getFirst().associations().getFirst().line());
    }

    @Test void ordinaryHelpersCustomAnnotationsInvalidJavaAndUninventoriedFilesAreNotTests() throws Exception {
        Path plain = write("Plain.java", "class Plain { void check() {} }");
        Path custom = write("Custom.java", "@interface Test {} class Custom { @Test void check() {} }");
        Path malformed = write("Broken.java", "class Broken { @org.junit.jupiter.api.Test void check( {} }");
        Path wildcard = write("Wildcard.java", "import org.junit.jupiter.api.*; class Wildcard { @Test void check() {} }");
        write("Hidden.java", "class Hidden { @org.junit.jupiter.api.Test void check() {} }");
        Run resolved = TestSourceResolver.resolve(run(hashes(plain, custom, malformed, wildcard), "Plain::check", "Custom::check", "Broken::check", "Hidden::check", "Wildcard::check"));
        assertTrue(resolved.tests().stream().allMatch(test -> test.associations().isEmpty()));
    }

    @Test void junit3DirectDeclarationsAndJUnit4ImportsAreSupportedWithoutGuessingInheritance() throws Exception {
        Path old = write("OldTest.java", "import junit.framework.TestCase; class OldTest extends TestCase {\n"
                + "public void testWorks() {}\nvoid testPrivate() {}\npublic static void testStatic() {}\n}");
        Path junit4 = write("FourTest.java", "import org.junit.Test; class FourTest { @Test public void works() {} }");
        List<TestResult> tests = TestSourceResolver.resolve(run(hashes(old, junit4), "OldTest::testWorks", "OldTest::testPrivate",
                "OldTest::testStatic", "FourTest::works")).tests();
        assertEquals(1, tests.get(0).associations().size()); assertTrue(tests.get(1).associations().isEmpty());
        assertTrue(tests.get(2).associations().isEmpty()); assertEquals(1, tests.get(3).associations().size());
    }

    @Test void resolverPreservesAdapterLocationsAndDoesNotDuplicateAReportedDeclaration() throws Exception {
        Path file = write("T.java", "class T { @org.junit.jupiter.api.Test void check() {} }");
        Association reported = new Association(file, 1, Attribution.TEST_DECLARATION, "Adapter location");
        TestResult original = test("T::check");
        TestResult located = new TestResult(original.id(), original.runId(), original.suite(), original.name(), original.project(),
                original.module(), original.packageName(), original.phase(), original.runtime(), original.invocation(), original.status(),
                original.freshness(), 0, "", List.of(reported));
        Run run = new Run("run", root, RunState.COMPLETED, List.of(located), Map.of(), hashes(file), "message", null, 1);
        assertSame(run, TestSourceResolver.resolve(run));
    }

    @Test void anIncompleteInventoryCannotHideAnAmbiguousClassOrEscapeTheProject() throws Exception {
        Path first = write("one/T.java", "class T { @org.junit.jupiter.api.Test void check() {} }");
        Path second = write("two/T.java", "class T { @org.junit.jupiter.api.Test void check() {} }");
        Run duplicate = run(hashes(first, second), "T::check");
        Files.delete(second);
        assertTrue(TestSourceResolver.resolve(duplicate).tests().getFirst().associations().isEmpty());
        Map<Path, String> inventory = new LinkedHashMap<>(hashes(first));
        inventory.put(root.resolve("../outside.java"), sha256("ignored"));
        assertTrue(TestSourceResolver.resolve(run(inventory, "T::check")).tests().getFirst().associations().isEmpty());
        Path oversized = write("Large.java", " ".repeat(2 * 1024 * 1024 + 1));
        assertTrue(TestSourceResolver.resolve(run(hashes(first, oversized), "T::check")).tests().getFirst().associations().isEmpty());
    }

    private Path write(String name, String content) throws Exception {
        Path file = root.resolve(name); Files.createDirectories(file.getParent()); Files.writeString(file, content); return file;
    }
    private Map<Path, String> hashes(Path... paths) throws Exception {
        Map<Path, String> result = new LinkedHashMap<>();
        for (Path path : paths) result.put(path, sha256(Files.readString(path)));
        return result;
    }
    private Run run(Map<Path, String> hashes, String... names) {
        return new Run("run", root, RunState.COMPLETED, Arrays.stream(names).map(TestSourceResolverTest::test).toList(), Map.of(), hashes, "", null, 1);
    }
    private static TestResult test(String name) {
        return new TestResult(name, "run", "suite", name, "project", "module", "package", "unit", "maven", "variant",
                TestStatus.PASSED, Freshness.UNKNOWN, 1, "", List.of());
    }
}
