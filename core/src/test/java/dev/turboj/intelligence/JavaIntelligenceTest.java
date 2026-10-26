package dev.turboj.intelligence;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboj.refactorj.api.JavaIntelligence.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.util.zip.ZipEntry;

import javax.tools.ToolProvider;

class JavaIntelligenceTest {
    @TempDir Path directory;

    private Context context(String source) {
        return new Context(
                directory.resolve("Main.java"),
                source,
                1,
                Map.of(),
                List.of(directory),
                List.of(),
                1);
    }

    @Test
    void externallyChangedReferencedSourceInvalidatesTheSameContextCache() throws Exception {
        Path file = directory.resolve("Calculator.java");
        Files.writeString(file, "public class Calculator { public int first(){return 1;} }");
        String source =
                "public class Main { void demo(){ Calculator calculator=new Calculator();"
                        + " calculator.fi } }";
        Context context = context(source);
        try (var service = new JdtJavaIntelligenceService()) {
            assertTrue(
                    service.complete(context, source.indexOf(".fi") + 3).proposals().stream()
                            .anyMatch(p -> p.text().equals("first()")));
            long previous = Files.getLastModifiedTime(file).toMillis();
            Files.writeString(
                    file, "public class Calculator { public int finalValue(){return 2;} }");
            Files.setLastModifiedTime(
                    file, java.nio.file.attribute.FileTime.fromMillis(previous + 1000));
            var refreshed = service.complete(context, source.indexOf(".fi") + 3).proposals();
            assertFalse(refreshed.stream().anyMatch(p -> p.text().equals("first()")));
            assertTrue(refreshed.stream().anyMatch(p -> p.text().equals("finalValue()")));
        }
    }

    @Test
    void completionUsesReceiverTypesAndSafeZeroArgumentMethod() {
        String source =
                "public class Main { public static void main(String[] args) { System.out.pr } }";
        try (var service = new JdtJavaIntelligenceService()) {
            var result = service.complete(context(source), source.indexOf(".pr") + 3);
            assertFalse(result.proposals().isEmpty());
            var first = result.proposals().getFirst();
            assertEquals("println()", first.text());
            assertTrue(first.display().contains("void println()"));
            assertEquals("pr", source.substring(first.start(), first.end()));
            assertTrue(result.proposals().stream().anyMatch(p -> p.display().contains("printf")));
            assertTrue(result.proposals().stream().anyMatch(p -> p.display().contains("print(")));
            assertTrue(result.durationNanos() > 0);
        }
    }

    @Test
    void overloadNavigationAndSourceJavadocAreSemantic() {
        String source =
                """
                public class Main {
                  /** Doubles an integer. @param value the number */
                  int process(int value) { return value * 2; }
                  String process(String value) { return value; }
                  void demo() { process(10); process("hi"); }
                }
                """;
        try (var service = new JdtJavaIntelligenceService()) {
            Context c = context(source);
            Symbol integer = service.resolveSymbol(c, source.indexOf("process(10)"));
            assertNotNull(integer);
            assertTrue(integer.signature().contains("int process(int"));
            assertEquals(
                    source.indexOf("process(int"), service.findDeclaration(c, integer).offset());
            Symbol string = service.resolveSymbol(c, source.indexOf("process(\"hi\")"));
            assertNotNull(string);
            assertNotEquals(integer.key(), string.key());
            assertEquals(
                    source.indexOf("process(String"), service.findDeclaration(c, string).offset());
            assertTrue(service.documentation(c, integer).text().contains("Doubles an integer."));
        }
    }

    @Test
    void crossFileNavigationUsesUnsavedSourcesAndGenerics() throws Exception {
        String stale =
                "package demo; public class Calculator { public String add(String a, String b) {"
                        + " return a+b; } }";
        String changed =
                "package demo; public class Calculator { /** Adds values. */ public int add(int a,"
                        + " int b) { return a+b; } }";
        Path calc = directory.resolve("demo/Calculator.java");
        Files.createDirectories(calc.getParent());
        Files.writeString(calc, stale);
        String main = "package demo; public class Main { int value = new Calculator().add(2, 3); }";
        Path mainPath = directory.resolve("demo/Main.java");
        Context context =
                new Context(
                        mainPath, main, 3, Map.of(calc, changed), List.of(directory), List.of(), 1);
        try (var service = new JdtJavaIntelligenceService()) {
            Symbol symbol = service.resolveSymbol(context, main.indexOf("add(2"));
            assertNotNull(symbol);
            assertTrue(symbol.signature().contains("int add(int"));
            Declaration declaration = service.findDeclaration(context, symbol);
            assertEquals(calc, declaration.file());
            assertEquals(changed, declaration.source());
            assertEquals(changed.indexOf("add(int"), declaration.offset());
            assertEquals(
                    stale,
                    Files.readString(calc),
                    "Unsaved context must never overwrite user's disk source");
            assertTrue(service.documentation(context, symbol).text().contains("Adds values."));
        }
    }

    @Test
    void unsavedSourceOverridesStaleProjectClassFiles() throws Exception {
        Path sourceFile = directory.resolve("demo/Calculator.java");
        Files.createDirectories(sourceFile.getParent());
        String stale =
                "package demo; public class Calculator { public String add(String a,String"
                    + " b){return a+b;} }";
        Files.writeString(sourceFile, stale);
        Path classes = Files.createDirectories(directory.resolve("target/classes"));
        assertEquals(
                0,
                ToolProvider.getSystemJavaCompiler()
                        .run(null, null, null, "-d", classes.toString(), sourceFile.toString()));
        String unsaved =
                "package demo; public class Calculator { public int add(int a,int b){return a+b;}"
                    + " }";
        String main = "package demo; public class Main { int answer=new Calculator().add(2,3); }";
        Context context =
                new Context(
                        directory.resolve("demo/Main.java"),
                        main,
                        1,
                        Map.of(sourceFile, unsaved),
                        List.of(directory),
                        List.of(classes),
                        1);
        try (var service = new JdtJavaIntelligenceService()) {
            Symbol symbol = service.resolveSymbol(context, main.indexOf("add(2"));
            assertNotNull(symbol);
            assertTrue(symbol.signature().contains("int add(int"), symbol.signature());
            assertEquals(unsaved, service.findDeclaration(context, symbol).source());
            String incomplete = main.replace("add(2,3)", "ad");
            Context completing =
                    new Context(
                            context.file(),
                            incomplete,
                            2,
                            context.openSources(),
                            context.sourceRoots(),
                            context.classpath(),
                            1);
            var proposals = service.complete(completing, incomplete.indexOf(".ad") + 3).proposals();
            assertTrue(proposals.stream().anyMatch(p -> p.display().contains("int add(int")));
            assertFalse(
                    proposals.stream().anyMatch(p -> p.display().contains("String add(String")));
        }
    }

    @Test
    void resolvesLocalFieldsTypesConstructorsAndInheritedMethods() {
        String source =
                """
                class Parent { int field; int twice(int a) { return 2*a; } }
                public class Main extends Parent {
                  Main() {}
                  void demo() { Main local = new Main(); int result = local.twice(local.field); }
                }
                """;
        try (var service = new JdtJavaIntelligenceService()) {
            Context c = context(source);
            Symbol inherited = service.resolveSymbol(c, source.indexOf("twice(local"));
            assertNotNull(inherited);
            assertEquals("Parent", inherited.owner());
            assertEquals(
                    source.indexOf("twice(int"), service.findDeclaration(c, inherited).offset());
            Symbol local = service.resolveSymbol(c, source.indexOf("local.twice"));
            assertNotNull(local);
            assertEquals("variable", local.kind());
            assertEquals(source.indexOf("local ="), service.findDeclaration(c, local).offset());
            Symbol field = service.resolveSymbol(c, source.indexOf("field);"));
            assertNotNull(field);
            assertEquals("field", field.kind());
            Symbol constructor = service.resolveSymbol(c, source.indexOf("Main();"));
            assertNotNull(constructor);
            assertEquals("constructor", constructor.kind());
            assertEquals(
                    source.indexOf("Main() {}"), service.findDeclaration(c, constructor).offset());
        }
    }

    @Test
    void jdkSourceNavigationAndDocumentation() {
        String source =
                "public class Main { String value = \"hello\"; String result = value.substring(1,"
                        + " 3); }";
        try (var service = new JdtJavaIntelligenceService()) {
            Context c = context(source);
            Symbol symbol = service.resolveSymbol(c, source.indexOf("substring"));
            assertNotNull(symbol);
            assertEquals("java.lang.String", symbol.owner());
            assertTrue(symbol.signature().contains("substring(int"));
            Declaration declaration = service.findDeclaration(c, symbol);
            if (Files.isRegularFile(Path.of(System.getProperty("java.home"), "lib", "src.zip"))) {
                assertNotNull(declaration.source());
                assertTrue(declaration.readOnly());
                assertTrue(
                        declaration
                                .source()
                                .substring(declaration.offset())
                                .startsWith("substring("));
                Documentation docs = service.documentation(c, symbol);
                assertTrue(docs.text().contains("substring"));
                assertTrue(docs.text().contains("beginIndex"));
                assertTrue(docs.text().contains("endIndex"));
                assertTrue(docs.signature().contains("int beginIndex, int endIndex"));
            } else assertTrue(declaration.origin().contains("not attached"));
        }
    }

    @Test
    void signatureHelpTracksNestedCallsAndVarargs() {
        String source =
                "public class Main { void demo() { System.out.printf(\"%s %d\", Math.max(1,2), 3);"
                        + " } }";
        try (var service = new JdtJavaIntelligenceService()) {
            SignatureHelp help = service.signatureHelp(context(source), source.indexOf(", 3") + 2);
            assertNotNull(help);
            assertEquals(2, help.activeParameter());
            assertTrue(
                    help.signatures().stream()
                            .anyMatch(s -> s.contains("printf(String") && s.contains("Object...")));
            SignatureHelp nested =
                    service.signatureHelp(context(source), source.indexOf("1,2") + 2);
            assertNotNull(nested);
            assertEquals(1, nested.activeParameter());
            assertTrue(nested.signatures().stream().allMatch(s -> s.contains("max(")));
        }
    }

    @Test
    void incompleteSourceRecoversWithoutInventingMembersAndIgnoresLiterals() {
        try (var service = new JdtJavaIntelligenceService()) {
            String source = "public class Main { void demo() { String word = \"hey\"; word.sub } }";
            var result = service.complete(context(source), source.indexOf(".sub") + 4);
            assertTrue(result.proposals().stream().anyMatch(p -> p.text().equals("substring()")));
            assertTrue(result.proposals().stream().noneMatch(p -> p.text().contains("println")));
            String literal = "public class Main { String word = \"System.out.pr\"; }";
            assertTrue(
                    service.complete(context(literal), literal.indexOf(".pr") + 3)
                            .proposals()
                            .isEmpty());
            String comment = "public class Main { /* System.out.pr */ }";
            assertTrue(
                    service.complete(context(comment), comment.indexOf(".pr") + 3)
                            .proposals()
                            .isEmpty());
        }
    }

    @Test
    void binaryDependencyUsesLocalSourceAttachmentWithoutNetwork() throws Exception {
        Path libsrc = directory.resolve("libsrc/library/Maths.java");
        Files.createDirectories(libsrc.getParent());
        String library =
                "package library; public class Maths { /** Triples the number.\n"
                        + " * @param value input\n"
                        + " * @return three times value\n"
                        + " */ public static int triple(int value) { return value*3; } }";
        Files.writeString(libsrc, library);
        Path classes = Files.createDirectories(directory.resolve("classes"));
        assertEquals(
                0,
                ToolProvider.getSystemJavaCompiler()
                        .run(null, null, null, "-d", classes.toString(), libsrc.toString()));
        Path binary = directory.resolve("maths.jar"),
                attachment = directory.resolve("maths-sources.jar");
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(binary))) {
            out.putNextEntry(new ZipEntry("library/Maths.class"));
            out.write(Files.readAllBytes(classes.resolve("library/Maths.class")));
            out.closeEntry();
        }
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(attachment))) {
            out.putNextEntry(new ZipEntry("library/Maths.java"));
            out.write(library.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            out.closeEntry();
        }
        String source = "import library.Maths; public class Main { int n = Maths.triple(4); }";
        Context c =
                new Context(
                        directory.resolve("Main.java"),
                        source,
                        1,
                        Map.of(),
                        List.of(directory),
                        List.of(binary),
                        1);
        try (var service = new JdtJavaIntelligenceService()) {
            Symbol symbol = service.resolveSymbol(c, source.indexOf("triple"));
            assertNotNull(symbol);
            Declaration declaration = service.findDeclaration(c, symbol);
            assertNotNull(declaration.source());
            assertTrue(declaration.readOnly());
            assertTrue(service.documentation(c, symbol).text().contains("Triples the number."));
            String incomplete = source.replace("triple(4)", "tri");
            Context c2 =
                    new Context(
                            c.file(),
                            incomplete,
                            2,
                            Map.of(),
                            List.of(directory),
                            List.of(binary),
                            1);
            assertTrue(
                    service.complete(c2, incomplete.indexOf(".tri") + 4).proposals().stream()
                            .anyMatch(p -> p.text().equals("triple()")));
        }
    }

    @Test
    void documentationMarkupCannotLoadResourcesOrExecuteScripts() {
        String cleaned =
                JdtJavaIntelligenceService.plainJavadoc(
                        "/** <p>Useful {@code x < y}.</p><script>fetch('secret')</script><img"
                                + " src='https://bad.test/pixel'> */");
        assertTrue(cleaned.contains("Useful"));
        assertFalse(cleaned.contains("fetch"));
        assertFalse(cleaned.contains("https://"));
        assertFalse(cleaned.contains("<img"));
    }

    @Test
    void localCompletionDoesNotLeakSiblingScopes() {
        String source =
                "public class Main { void one() { int hidden = 1; } void two() { int visibleCount ="
                        + " 2; vis } }";
        try (var service = new JdtJavaIntelligenceService()) {
            var result = service.complete(context(source), source.lastIndexOf("vis") + 3);
            assertTrue(result.proposals().stream().anyMatch(p -> p.text().equals("visibleCount")));
            assertFalse(result.proposals().stream().anyMatch(p -> p.text().equals("hidden")));
        }
    }

    @Test
    void unsavedPublicClassNameWorksWithoutSaving() {
        String source =
                "public class Hello { public static void main(String[] args) { System.out.pr } }";
        try (var service = new JdtJavaIntelligenceService()) {
            assertEquals(
                    "println()",
                    service.complete(context(source), source.indexOf(".pr") + 3)
                            .proposals()
                            .getFirst()
                            .text());
        }
    }

    @Test
    void importedTypeCompletionIncludesAnAtomicImportEdit() {
        String source = "package demo;\npublic class Main { ArrayL list; }";
        try (var service = new JdtJavaIntelligenceService()) {
            var result = service.complete(context(source), source.indexOf("ArrayL") + 6);
            var proposal =
                    result.proposals().stream()
                            .filter(p -> p.detail().equals("java.util.ArrayList"))
                            .findFirst()
                            .orElseThrow();
            assertEquals("ArrayList", proposal.text());
            assertEquals(1, proposal.additionalEdits().size());
            assertEquals(
                    "\nimport java.util.ArrayList;", proposal.additionalEdits().getFirst().text());
            assertEquals(source.indexOf(';') + 1, proposal.additionalEdits().getFirst().start());
        }
    }

    @Test
    void genericReceiverPreservesSubstitutionAndInheritedDeclaration() {
        String source =
                "import java.util.ArrayList; public class Main { void demo() { ArrayList<String>"
                        + " words = new ArrayList<>(); words.ge } }";
        try (var service = new JdtJavaIntelligenceService()) {
            var result = service.complete(context(source), source.indexOf(".ge") + 3);
            assertTrue(
                    result.proposals().stream()
                            .anyMatch(p -> p.display().contains("String get(int")));
            String call = source.replace("words.ge", "words.get(0).substring(1);");
            Symbol symbol = service.resolveSymbol(context(call), call.indexOf("substring"));
            assertNotNull(symbol);
            assertEquals("java.lang.String", symbol.owner());
        }
    }

    @Test
    void genericTypeCommasDoNotAdvanceTheParameterHint() {
        String source =
                "import java.util.*; public class Main { void use(Map<String,Integer> map,int n){}"
                        + " void demo(){ use(new HashMap<String,Integer>(), 2); } }";
        try (var service = new JdtJavaIntelligenceService()) {
            var help = service.signatureHelp(context(source), source.indexOf(", 2") + 2);
            assertNotNull(help);
            assertEquals(1, help.activeParameter());
        }
    }

    @Test
    void ambiguousOverloadsDoNotNavigateToAnArbitraryMethod() {
        String source =
                "public class Main { void use(String a){} void use(Integer a){} void demo(){"
                        + " use(null); } }";
        try (var service = new JdtJavaIntelligenceService()) {
            assertNull(service.resolveSymbol(context(source), source.indexOf("use(null)")));
        }
    }

    @Test
    void completionDoesNotLeakPatternVariablesOutsideTheirFlowScope() {
        String source =
                "public class Main { void demo(Object input) { if(input instanceof String scoped) {"
                        + " scop } sco } }";
        try (var service = new JdtJavaIntelligenceService()) {
            var inside = service.complete(context(source), source.indexOf("scop }") + 4);
            assertTrue(inside.proposals().stream().anyMatch(p -> p.text().equals("scoped")));
            var outside = service.complete(context(source), source.indexOf("sco }") + 3);
            assertFalse(outside.proposals().stream().anyMatch(p -> p.text().equals("scoped")));
        }
    }

    @Test
    void nearestLocalAndOverriddenMethodTakePrecedence() {
        String source =
                "class Parent { Number answer(){return 1;} } public class Main extends Parent { int"
                        + " value; @Override Integer answer(){return 2;} void demo(){ String"
                        + " value=\"hi\"; val } }";
        try (var service = new JdtJavaIntelligenceService()) {
            var locals = service.complete(context(source), source.indexOf("val }") + 3);
            var value = locals.proposals().stream().filter(p -> p.text().equals("value")).toList();
            assertEquals(1, value.size());
            assertTrue(value.getFirst().display().contains("String value"));
            String methods = source.replace(" val }", " ans }");
            var answers =
                    service
                            .complete(context(methods), methods.indexOf("ans }") + 3)
                            .proposals()
                            .stream()
                            .filter(p -> p.text().equals("answer()"))
                            .toList();
            assertEquals(1, answers.size());
            assertTrue(answers.getFirst().display().contains("Integer answer()"));
        }
    }

    @Test
    void conflictingTypeImportUsesTheQualifiedName() {
        String source = "import java.awt.List; public class Main { Lis values; }";
        try (var service = new JdtJavaIntelligenceService()) {
            var completions = service.complete(context(source), source.indexOf("Lis values") + 3);
            var list =
                    completions.proposals().stream()
                            .filter(p -> p.detail().equals("java.util.List"))
                            .findFirst()
                            .orElseThrow();
            assertEquals("java.util.List", list.text());
            assertTrue(list.additionalEdits().isEmpty());
        }
    }

    @Test
    void annotationCompletionImportsJupiterAndAddsTheLibraryOnlyWhenMissing() throws Exception {
        String source = "package demo;\n\nclass CalculatorTest {\n    @Test\n    void adds() {}\n}\n";
        int offset = source.indexOf("@Test") + 5;
        Path test = directory.resolve("CalculatorTest.java");
        var missing = new Context(test, source, 1, Map.of(), List.of(directory), List.of(), 1);
        List<Path> jupiter = List.of(Path.of(Test.class.getProtectionDomain().getCodeSource().getLocation().toURI()),
                Path.of(org.apiguardian.api.API.class.getProtectionDomain().getCodeSource().getLocation().toURI()));
        var available = new Context(test, source, 1, Map.of(), List.of(directory), jupiter, 1);
        try (var service = new JdtJavaIntelligenceService()) {
            var offered = service.complete(missing, offset).proposals().getFirst();
            assertEquals("Test", offered.text());
            assertEquals("org.junit.jupiter.api.Test", offered.detail());
            assertEquals(Completion.JUNIT_JUPITER, offered.library());
            assertEquals(List.of(new TextEdit(13, 13, "\nimport org.junit.jupiter.api.Test;")), offered.additionalEdits());

            var resolved = service.complete(available, offset).proposals();
            assertEquals("org.junit.jupiter.api.Test", resolved.getFirst().detail(), "the exact name comes first");
            assertEquals("", resolved.getFirst().library(), "an available library is never added again");
            assertTrue(resolved.stream().allMatch(p -> p.detail().startsWith("org.junit.") || p.detail().startsWith("java.lang.")
                    || p.detail().startsWith("jdk.")), "only annotation types after '@': " + resolved);
        }
    }

    @Test
    void assertionsCompleteByNameWithTheirStaticImportOnlyInTestSources() throws Exception {
        String source = "import org.junit.jupiter.api.Test;\n\npublic class CalculatorTest {\n    @Test\n    public void test() {\n        assertEq\n    }\n}\n";
        int offset = source.indexOf("assertEq") + 8;
        Path test = directory.resolve("CalculatorTest.java");
        List<Path> jupiter = List.of(Path.of(Test.class.getProtectionDomain().getCodeSource().getLocation().toURI()),
                Path.of(org.apiguardian.api.API.class.getProtectionDomain().getCodeSource().getLocation().toURI()),
                Path.of(org.opentest4j.AssertionFailedError.class.getProtectionDomain().getCodeSource().getLocation().toURI()));
        var importStatic = List.of(new TextEdit(34, 34, "\nimport static org.junit.jupiter.api.Assertions.assertEquals;"));
        try (var service = new JdtJavaIntelligenceService()) {
            var available = service.complete(new Context(test, source, 1, Map.of(), List.of(directory), jupiter, 1), offset).proposals();
            var assertEquals = available.stream().filter(p -> p.text().equals("assertEquals()")).findFirst().orElseThrow(() -> new AssertionError(available));
            assertEquals("org.junit.jupiter.api.Assertions", assertEquals.detail());
            assertEquals(importStatic, assertEquals.additionalEdits());
            assertEquals("assertEquals(".length(), assertEquals.caret(), "the caret lands between the parentheses");
            assertEquals("", assertEquals.library());

            var missing = service.complete(new Context(test, source, 1, Map.of(), List.of(directory), List.of(), 1), offset).proposals();
            var offered = missing.stream().filter(p -> p.text().equals("assertEquals()")).findFirst().orElseThrow(() -> new AssertionError(missing));
            assertEquals(Completion.JUNIT_JUPITER, offered.library());

            String plain = "public class Main {\n    void run() {\n        assertEq\n    }\n}\n";
            var none = service.complete(new Context(directory.resolve("Main.java"), plain, 1, Map.of(), List.of(directory), jupiter, 1),
                    plain.indexOf("assertEq") + 8).proposals();
            assertTrue(none.stream().noneMatch(p -> p.text().startsWith("assertEquals")), "not offered outside test sources: " + none);
        }
    }

    @Test
    void annotationCompletionReplacesAnUnresolvedJUnit4Import() throws Exception {
        String source = "import org.junit.Test;\n\npublic class CalculatorTest {\n    @Test\n    public void test() {}\n}\n";
        int offset = source.indexOf("@Test") + 5;
        Path test = directory.resolve("CalculatorTest.java");
        List<Path> jupiter = List.of(Path.of(Test.class.getProtectionDomain().getCodeSource().getLocation().toURI()),
                Path.of(org.apiguardian.api.API.class.getProtectionDomain().getCodeSource().getLocation().toURI()));
        var replaceImport = List.of(new TextEdit(7, 21, "org.junit.jupiter.api.Test"));
        try (var service = new JdtJavaIntelligenceService()) {
            var missing = service.complete(new Context(test, source, 1, Map.of(), List.of(directory), List.of(), 1), offset).proposals();
            assertEquals("org.junit.jupiter.api.Test", missing.getFirst().detail());
            assertEquals(replaceImport, missing.getFirst().additionalEdits(), "import org.junit.Test becomes Jupiter's");
            assertEquals(Completion.JUNIT_JUPITER, missing.getFirst().library());

            var available = service.complete(new Context(test, source, 1, Map.of(), List.of(directory), jupiter, 1), offset).proposals();
            assertEquals(replaceImport, available.getFirst().additionalEdits());
            assertEquals("", available.getFirst().library());
            assertEquals(1, available.stream().filter(p -> p.detail().equals("org.junit.jupiter.api.Test")).count(), "no qualified duplicate");
        }
    }
}
