package dev.javafmt.parser;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;

class JdtJavaParserTest {
    private final JavaParser parser = new JdtJavaParser();

    @Test
    void retainsExactTokensLiteralsAndComments() {
        String source = "// license\r\nclass A{/* block */String s=\"a\\tb\";char c='界';boolean b=true;}";
        ParsedSource parsed = parser.parse(source);
        assertTrue(parsed.valid(), () -> parsed.diagnostics().toString());
        assertEquals(source, parsed.source());
        assertEquals("// license", parsed.tokens().getFirst().raw());
        assertEquals(2, parsed.tokens().stream().filter(JavaToken::comment).count());
        assertEquals(List.of("\"a\\tb\"", "'界'", "true"), parsed.tokens().stream().filter(JavaToken::literal).map(JavaToken::raw).toList());
        int previous = 0;
        for (JavaToken token : parsed.tokens()) {
            assertEquals(source.substring(token.start(), token.end()), token.raw());
            assertTrue(token.start() >= previous);
            previous = token.end();
        }
    }

    @Test
    void exposesRawUnicodeEscapesAndDecodedLexemesSeparately() {
        String source = "class A { int " + "\\" + "u0061=1;String s=\"😀\"; }";
        ParsedSource parsed = parser.parse(source);
        assertTrue(parsed.valid(), () -> parsed.diagnostics().toString());
        JavaToken name = parsed.tokens().stream().filter(t -> t.text().equals("a")).findFirst().orElseThrow();
        assertEquals("\\" + "u0061", name.raw());
        assertEquals(6, name.end() - name.start());
        JavaToken literal = parsed.tokens().stream().filter(t -> t.raw().contains("😀")).findFirst().orElseThrow();
        assertEquals(4, literal.end() - literal.start());
    }

    @Test
    void parsesJava25CompactSourceAndModuleDeclarationsWithoutExternalFileIdentity() {
        for (String source : List.of("void main(){System.out.println(\"Hello\");}",
                "import module java.base; void main(){IO.println(\"Hello\");}",
                "open module example.module { requires java.base; exports example.api; }",
                "@Deprecated module m { uses java.lang.Runnable; }",
                "/** Package docs. */ @Deprecated package example.api;")) {
            ParsedSource result = parser.parse(source);
            assertTrue(result.valid(), () -> source + ": " + result.diagnostics());
        }
        assertNotNull(parser.parse("module m { requires java.base; }").unit().getModule());
        assertNull(parser.parse("class module { int requires; }").unit().getModule());
    }

    @Test
    void parsesModernLanguageAndProtectedTextBlocks() {
        String source = "sealed interface Shape permits Point {}\n"
                + "record Point(int x,int y) implements Shape {}\n"
                + "class Cases { String block=\"\"\"\n  first\\s\n    second\n  \"\"\";"
                + "int f(Object o){return switch(o){case Point(int x,int y) when x>0 -> x+y;case null -> 0;default -> -1;};}}";
        ParsedSource parsed = parser.parse(source);
        assertTrue(parsed.valid(), () -> parsed.diagnostics().toString());
        assertTrue(parsed.tokens().stream().anyMatch(t -> t.literal() && t.raw().equals("\"\"\"\n  first\\s\n    second\n  \"\"\"")));
    }

    @Test
    void returnsOriginalInputWithErrorsForMalformedAndPreviewSource() {
        for (String source : List.of("class X { void f( {", "class X {String x=\"unterminated;}",
                "/* unterminated", "class X { int f(Object value) { return switch (value) { case int i -> i; default -> 0; }; } }")) {
            ParsedSource result = parser.parse(source);
            assertFalse(result.valid(), source);
            assertFalse(result.diagnostics().isEmpty());
            assertEquals(source, result.source());
            for (ParseDiagnostic problem : result.diagnostics()) {
                assertTrue(problem.start() >= 0 && problem.end() <= source.length());
            }
        }
    }

    @Test
    void supportsEmptyFilesAndMarkdownJavadoc() {
        assertTrue(parser.parse("").valid());
        assertTrue(parser.parse(" \r\n\t").valid());
        String source = "/// Summary.\n/// More documentation.\nclass A {}";
        ParsedSource parsed = parser.parse(source);
        assertTrue(parsed.valid(), () -> parsed.diagnostics().toString());
        assertTrue(parsed.tokens().getFirst().comment());
        assertTrue(parsed.tokens().getFirst().raw().startsWith("/// Summary."));
    }

    @Test
    void doesNotResolveUnrelatedProjectTypesOrDependOnLocale() {
        String source = "class A { MissingType value; MissingType invoke(MissingType x) { return x; } }";
        ParsedSource parsed = parser.parse(source);
        assertTrue(parsed.valid(), () -> parsed.diagnostics().toString());
        assertFalse(parsed.unit().getAST().hasBindingsRecovery());
    }

    @Test
    void independentCallsAreSafeAcrossThreads() throws Exception {
        String source = "class A<T extends Comparable<T>> { T id(T x) { return x; } }";
        try (var pool = Executors.newFixedThreadPool(4)) {
            var tasks = java.util.stream.IntStream.range(0, 24)
                    .<java.util.concurrent.Callable<ParsedSource>>mapToObj(i -> () -> parser.parse(source)).toList();
            var expected = parser.parse(source);
            for (var future : pool.invokeAll(tasks)) {
                ParsedSource actual = future.get();
                assertTrue(actual.valid());
                assertEquals(expected.tokens(), actual.tokens());
                assertNotSame(expected.unit(), actual.unit());
            }
        }
    }

    @Test
    void originalFixtureCorpusParses() throws Exception {
        Path corpus = Path.of("..", "javafmt-test-fixtures", "original");
        try (var files = Files.walk(corpus)) {
            var sources = files.filter(path -> path.toString().endsWith(".java")).sorted().toList();
            assertTrue(sources.size() >= 10);
            for (Path file : sources) {
                ParsedSource parsed = parser.parse(Files.readString(file));
                assertTrue(parsed.valid(), () -> file + ": " + parsed.diagnostics());
            }
        }
    }
}
