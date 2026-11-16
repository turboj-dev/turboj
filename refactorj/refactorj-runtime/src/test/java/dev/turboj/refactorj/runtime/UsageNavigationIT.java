package dev.turboj.refactorj.runtime;

import dev.turboj.refactorj.api.*;
import dev.turboj.refactorj.api.Model.*;
import dev.turboj.refactorj.api.JavaStructure.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class UsageNavigationIT {
    static RefactorJRuntime runtime;
    @TempDir Path root;
    @BeforeAll static void start() throws Exception { runtime = new RefactorJRuntime(); }
    @AfterAll static void stop() { runtime.close(); }
    private List<Path> classpath() throws Exception {
        return List.of(Path.of(Test.class.getProtectionDomain().getCodeSource().getLocation().toURI()));
    }
    private final class Fixture implements AutoCloseable {
        final RefactorJ engine;
        final Map<Path,String> sources = new LinkedHashMap<>();
        final List<Path> roots;
        Fixture(Map<String,String> files) throws Exception { this(files, List.of(root), List.of()); }
        Fixture(Map<String,String> files, List<Path> roots, List<JavaProjectConfiguration> projects) throws Exception {
            this.roots = roots;
            for (var entry : files.entrySet()) sources.put(root.resolve(entry.getKey()), entry.getValue());
            var snapshots = sources.entrySet().stream().map(e -> new SourceSnapshot(e.getKey(),e.getValue(),1,true)).toList();
            engine = runtime.openWorkspace(new WorkspaceSnapshot(root,1,snapshots,roots,classpath(),projects));
        }
        List<Target> query(String file, String marker, Navigation action) throws Exception {
            Path path = root.resolve(file); String text = sources.get(path); int offset = text.indexOf(marker); assertTrue(offset >= 0);
            var context = new JavaIntelligence.Context(path,text,1,sources,roots,classpath(),1);
            return ((JavaIntelligenceProvider)engine).intelligence().navigationTargets(context,offset,action,"");
        }
        @Override public void close() { engine.close(); }
    }
    @Test void findsOnlyResolvedOverloadUsagesAndRecognizedTestMethods() throws Exception {
        try (var f = new Fixture(Map.of(
                "p/Calculator.java", "package p; public class Calculator { public int add(int a,int b){return a+b;} public String add(String a,String b){return a+b;} }",
                "p/CalculatorTest.java", "package p; import org.junit.jupiter.api.Test; class CalculatorTest { @Test void exact(){new Calculator().add(1,2);} @Test void overload(){new Calculator().add(\"a\",\"b\");} void helper(){new Calculator().add(3,4);} }",
                "q/OtherTest.java", "package q; import org.junit.jupiter.api.Test; class OtherTest { @Test void another(){new p.Calculator().add(5,6);} }",
                "p/Decoy.java", "package p; @interface Test {} class Decoy { @Test void fake(){new Calculator().add(1,2);} String s=\"add(1,2)\"; }"))) {
            var usages = f.query("p/Calculator.java","add(int",Navigation.REFERENCES);
            assertEquals(4,usages.size(),usages.toString());
            assertTrue(usages.stream().anyMatch(t -> t.label().contains("helper")));
            var tests = f.query("p/Calculator.java","add(int",Navigation.RELATED_TESTS);
            assertEquals(1,tests.stream().filter(t -> t.kind()==TargetKind.DEFAULT_COUNTERPART).count());
            var verified = tests.stream().filter(t -> t.kind()==TargetKind.TEST_USAGE).toList();
            assertEquals(2,verified.size(),tests.toString());
            assertTrue(verified.stream().anyMatch(t -> t.label().contains("exact")));
            assertTrue(verified.stream().anyMatch(t -> t.label().contains("another")));
            assertTrue(verified.stream().allMatch(t -> f.sources.get(t.file()).substring(t.start(),t.end()).startsWith("add")));
        }
    }
    @Test void inheritedContractsComposedAnnotationsAndMethodReferencesKeepDeclarationIdentity() throws Exception {
        try (var f = new Fixture(Map.of(
                "p/Base.java", "package p; public class Base { public int value(){return 42;} }",
                "p/Calculator.java", "package p; public class Calculator extends Base {}",
                "p/Contract.java", "package p; import org.junit.jupiter.api.Test; @Test @interface Check {} class Contract { @Check void inherited(){java.util.function.IntSupplier value=new Calculator()::value;} } class ConcreteTest extends Contract {}"))) {
            var tests = f.query("p/Base.java","value()",Navigation.RELATED_TESTS);
            assertEquals(1,tests.size(),tests.toString());
            assertEquals(TargetKind.TEST_USAGE,tests.getFirst().kind());
            assertTrue(tests.getFirst().label().contains("Contract.inherited"));
            var reverse = f.query("p/Contract.java","inherited()",Navigation.RELATED_TESTS);
            assertTrue(reverse.stream().anyMatch(t -> t.kind()==TargetKind.PRODUCTION_USAGE && t.file().endsWith("Base.java")),reverse.toString());
        }
    }
    @Test void unsavedReferencesWinOverDiskAndUnknownAnnotationsAreNotProof() throws Exception {
        Files.createDirectories(root.resolve("p"));
        Path disk = root.resolve("p/Calls.java"); Files.writeString(disk,"package p; class Calls {}\n");
        try (var f = new Fixture(Map.of(
                "p/Calculator.java", "package p; public class Calculator { public int value(){return 42;} public int unused(){return 0;} }",
                "p/Calls.java", "package p; class Calls { @org.junit.jupiter.api.Test void unsaved(){new Calculator().value();} @MissingTest void unknown(){new Calculator().value();} }"))) {
            assertEquals(2,f.query("p/Calculator.java","value()",Navigation.REFERENCES).size());
            var related = f.query("p/Calculator.java","value()",Navigation.RELATED_TESTS);
            assertEquals(1,related.size(),related.toString());
            assertTrue(related.getFirst().label().contains("unsaved"));
            assertTrue(f.query("p/Calculator.java","unused()",Navigation.RELATED_TESTS).isEmpty());
            assertEquals("package p; class Calls {}\n",Files.readString(disk));
        }
    }
    @Test void counterpartNamesAreScopedByPackageAndMavenCompilationBoundary() throws Exception {
        List<Path> roots = List.of(root.resolve("a/src/main/java"),root.resolve("a/src/test/java"),root.resolve("b/src/main/java"),root.resolve("b/src/test/java"));
        List<JavaProjectConfiguration> projects = new ArrayList<>();
        for(int i=0;i<roots.size();i++) projects.add(new JavaProjectConfiguration("p"+i,List.of(roots.get(i)),classpath(),List.of(),i%2==1?List.of("p"+(i-1)):List.of(),"25","","",Path.of(System.getProperty("java.home")),"UTF-8",false));
        try (var f = new Fixture(Map.of(
                "a/src/main/java/p/C.java","package p; public class C { public void run(){} }",
                "b/src/main/java/p/C.java","package p; public class C { public void run(){} }",
                "a/src/test/java/p/CTest.java","package p; class CTest { @org.junit.jupiter.api.Test void a(){new C().run();} }",
                "b/src/test/java/p/CTest.java","package p; class CTest { @org.junit.jupiter.api.Test void b(){new C().run();} }",
                "a/src/test/java/q/CTest.java","package q; class CTest {}"),roots,projects)) {
            var related = f.query("a/src/main/java/p/C.java","run()",Navigation.RELATED_TESTS);
            var defaults = related.stream().filter(t -> t.kind()==TargetKind.DEFAULT_COUNTERPART).toList();
            assertEquals(1,defaults.size(),related.toString());
            assertEquals(root.resolve("a/src/test/java/p/CTest.java"),defaults.getFirst().file());
            var verified = related.stream().filter(t -> t.kind()==TargetKind.TEST_USAGE).toList();
            assertEquals(1,verified.size(),related.toString());
            assertEquals(defaults.getFirst().file(),verified.getFirst().file());
        }
    }
    @Test void referencesRefreshAfterUnsavedTestChanges() throws Exception {
        try(var f=new Fixture(Map.of("p/C.java","package p; public class C { public void call(){} }",
                "p/Checks.java","package p; class Checks { @org.junit.jupiter.api.Test void check(){new C().call();} }"))) {
            assertEquals(1,f.query("p/C.java","call()",Navigation.RELATED_TESTS).size());
            f.sources.put(root.resolve("p/Checks.java"),"package p; class Checks { @org.junit.jupiter.api.Test void check(){} }");
            f.engine.workspace().synchronize(new WorkspaceSnapshot(root,2,f.sources.entrySet().stream()
                    .map(e -> new SourceSnapshot(e.getKey(),e.getValue(),2,true)).toList(),f.roots,classpath()),Cancellation.NONE);
            assertTrue(f.query("p/C.java","call()",Navigation.REFERENCES).isEmpty());
            assertTrue(f.query("p/C.java","call()",Navigation.RELATED_TESTS).isEmpty());
        }
    }
    @Test void aDependentTestModuleIsVerifiedButNotTheDefaultCounterpart() throws Exception {
        Path main=root.resolve("library/src/main/java"),tests=root.resolve("contracts/src/test/java");
        var projects=List.of(
                new JavaProjectConfiguration("library#main",List.of(main),classpath(),List.of(),List.of(),"25","","",Path.of(System.getProperty("java.home")),"UTF-8",false),
                new JavaProjectConfiguration("contracts#test",List.of(tests),classpath(),List.of(),List.of("library#main"),"25","","",Path.of(System.getProperty("java.home")),"UTF-8",false));
        try(var f=new Fixture(Map.of("library/src/main/java/p/C.java","package p; public class C { public void call(){} }",
                "contracts/src/test/java/p/CTest.java","package p; class CTest { @org.junit.jupiter.api.Test void contract(){new C().call();} }"),List.of(main,tests),projects)) {
            var related=f.query("library/src/main/java/p/C.java","call()",Navigation.RELATED_TESTS);
            assertTrue(related.stream().noneMatch(t -> t.kind()==TargetKind.DEFAULT_COUNTERPART));
            assertEquals(1,related.stream().filter(t -> t.kind()==TargetKind.TEST_USAGE).count());
        }
    }
    @Test void unicodeCrLfAndNestedNonTestMethodsKeepExactOffsets() throws Exception {
        String calls="package p;\r\n// ☕ 😀\r\nclass Checks {\r\n @org.junit.jupiter.api.Test void café(){new C().value(); Runnable r=new Runnable(){public void run(){new C().value();}};}\r\n}";
        try(var f=new Fixture(Map.of("p/C.java","package p; public class C { public int value(){return 1;} }","p/Checks.java",calls))) {
            assertEquals(2,f.query("p/C.java","value()",Navigation.REFERENCES).size());
            var tests=f.query("p/C.java","value()",Navigation.RELATED_TESTS);
            assertEquals(1,tests.size(),tests.toString());
            assertEquals(calls.indexOf("value()"),tests.getFirst().start());
            assertEquals("value()",calls.substring(tests.getFirst().start(),tests.getFirst().end()));
            assertTrue(tests.getFirst().label().contains("café"));
        }
    }
    @Test void documentationAndDeferredNestedInitializersAreNotTestCalls() throws Exception {
        try(var f=new Fixture(Map.of("p/C.java","package p; public class C { public static int value(){return 1;} }",
                "p/Checks.java","package p; class Checks { /** @see C#value() */ @org.junit.jupiter.api.Test void check(){class Deferred {int n=C.value();} Object o=new Object(){int n=C.value();};} }"))) {
            assertFalse(f.query("p/C.java","value()",Navigation.REFERENCES).isEmpty());
            assertTrue(f.query("p/C.java","value()",Navigation.RELATED_TESTS).isEmpty());
            assertTrue(f.query("p/Checks.java","check()",Navigation.RELATED_TESTS).isEmpty());
        }
    }
}
