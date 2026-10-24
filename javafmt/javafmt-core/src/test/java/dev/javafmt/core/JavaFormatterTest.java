package dev.javafmt.core;

import static org.junit.jupiter.api.Assertions.*;

import dev.javafmt.parser.JavaToken;
import dev.javafmt.parser.JdtJavaParser;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavaFormatterTest {
    private static final Path CORPUS = Path.of("..", "javafmt-test-fixtures", "original");
    private final JavaFormatter formatter = new JavaFormatter();
    private final JdtJavaParser parser = new JdtJavaParser();
    @TempDir Path temporary;

    @Test
    void helloWorldMatchesTheCanonicalGolden() throws Exception {
        String input = resource("HelloWorld.input.java");
        String expected = resource("HelloWorld.expected.java");
        assertEquals(expected, formatter.format(input));
        assertEquals(expected, formatter.format(expected));
        assertTrue(formatter.format(new FormatRequest(expected)).edits().isEmpty());
    }

    @Test
    void originalModernCorpusIsIdempotentAndPreservesEveryToken() throws Exception {
        List<String> failures = new ArrayList<>();
        try (var paths = Files.walk(CORPUS)) {
            for (Path file : paths.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                try { verify(Files.readString(file)); }
                catch (AssertionError | RuntimeException error) { failures.add(file.getFileName() + ": " + error.getMessage()); }
            }
        }
        assertEquals(List.of(), failures);
    }

    @Test
    void pinnedOpenJdkCorpusPreservesTokensCommentsAndIdempotence() throws Exception {
        Path directory = Path.of("..", "javafmt-test-fixtures", "openjdk");
        for (String name : List.of("Objects.java", "Optional.java")) {
            String source = Files.readString(directory.resolve(name));
            String formatted = verify(source);
            assertEquals(rawComments(source), rawComments(formatted));
        }
    }

    @Test
    void determinismSurvivesSeededWhitespaceFuzzingAndProgramShapes() throws Exception {
        Random random = new Random(0x5eed25);
        List<String> failures = new ArrayList<>();
        for (int iteration = 0; iteration < 240; iteration++) {
            String source = shape(iteration);
            var parsed = parser.parse(source);
            assertTrue(parsed.valid(), () -> parsed.diagnostics().toString());
            StringBuilder noisy = new StringBuilder();
            for (JavaToken token : parsed.tokens()) {
                noisy.append(switch (random.nextInt(5)) { case 0 -> "\n"; case 1 -> "\r\n\t"; case 2 -> "   "; default -> " "; });
                noisy.append(token.raw());
            }
            String value = noisy.toString();
            try {
                verify(value);
                assertEquals(formatter.format(source), formatter.format(value), "Whitespace-only variants must converge");
            } catch (AssertionError | RuntimeException error) {
                Path repro = temporary.resolve("Fuzz" + iteration + ".java");
                Files.writeString(repro, value);
                failures.add("seed=0x5eed25 iteration=" + iteration + " repro=" + repro + ": " + error.getMessage());
            }
        }
        assertEquals(List.of(), failures);
    }

    @Test
    void literalRuntimeValuesRemainIdenticalAfterFormatting() throws Exception {
        String original = Files.readString(CORPUS.resolve("RuntimeValues.java"));
        String formatted = verify(original);
        assertEquals(compileAndRun(original, "before"), compileAndRun(formatted, "after"));
        assertEquals(compileAndRun(original.replace("\n", "\r\n"), "crlf-before"),
                compileAndRun(formatter.format(original.replace("\n", "\r\n")), "crlf-after"));
    }

    @Test
    void eachOrdinaryOriginalFixtureCompilesBeforeAndAfterFormatting() throws Exception {
        var compiler = ToolProvider.getSystemJavaCompiler();
        assertNotNull(compiler);
        try (var paths = Files.list(CORPUS)) {
            for (Path file : paths.filter(p -> p.toString().endsWith(".java")).sorted().toList()) {
                String source = Files.readString(file);
                for (int pass = 0; pass < 2; pass++) {
                    Path directory = Files.createDirectories(temporary.resolve(file.getFileName() + "-" + pass));
                    Path input = directory.resolve(file.getFileName());
                    Files.writeString(input, pass == 0 ? source : verify(source));
                    ByteArrayOutputStream errors = new ByteArrayOutputStream();
                    int exit = compiler.run(null, errors, errors, "--release", "25", "-proc:none", "-d", directory.toString(), input.toString());
                    assertEquals(0, exit, () -> input + ": " + errors.toString(StandardCharsets.UTF_8));
                }
            }
        }
    }

    @Test
    void invalidSourceFailsWithOriginalAndNoEdits() {
        for (String source : List.of("class A {", "class A {String x=\"bad;}", "class A{void f(){if( }}", "/* missing end")) {
            FormatResult result = formatter.format(new FormatRequest(source, 42, LineEndingPolicy.LF));
            assertFalse(result.success());
            assertEquals(source, result.formattedText());
            assertEquals(List.of(), result.edits());
            assertEquals(42, result.sourceVersion());
            assertFalse(result.diagnostics().isEmpty());
        }
    }

    @Test
    void preservesCommentsImportOrderAndUnicodeEscapes() {
        String source = "/*license*/\nimport java.util.Set; // sets\n/*lists*/import java.util.List;\nclass A{"
                + "int \\u0061=1; // trailing\n/** {@code a+b} */int f(){return \\u0061;}}";
        String formatted = verify(source);
        assertTrue(formatted.indexOf("java.util.Set") < formatted.indexOf("java.util.List"));
        assertEquals(rawComments(source), rawComments(formatted));
        assertTrue(formatted.contains("\\u0061"));
    }

    @Test
    void lineEndingPoliciesApplyOnlyToWhitespaceOutsideTokens() {
        String source = "class A{\r\nint a=1;\r\nint b=2;\r\n}\r\n";
        FormatResult preserved = formatter.format(new FormatRequest(source, 4, LineEndingPolicy.PRESERVE));
        assertTrue(preserved.success(), () -> preserved.diagnostics().toString());
        assertFalse(preserved.formattedText().replace("\r\n", "").contains("\n"));
        assertFalse(formatter.format(source).contains("\r"));
        assertEquals("\n", formatter.format(""));
        assertEquals("\n", formatter.format(" \r\n\t\n"));
    }

    @Test
    void rangeFormattingDoesNotRewriteAnUnselectedMethod() {
        String source = "class A {\n    int first( ){return 1;}\n    int second( ){int value=2;return value;}\n}\n";
        int start = source.indexOf("int second");
        int end = source.indexOf("}\n", start) + 1;
        var result = formatter.formatRange(new FormatRequest(source, 87, LineEndingPolicy.PRESERVE), new TextRange(start, end));
        assertTrue(result.success(), () -> result.diagnostics().toString());
        assertEquals(87, result.sourceVersion());
        assertTrue(result.formattedText().contains("int first( ){return 1;}"));
        assertTrue(result.formattedText().contains("int second() {"));
        assertEquals(result.formattedText(), TextEdit.apply(source, result.edits()));
        assertEquals(result.formattedText(), formatter.formatRange(new FormatRequest(result.formattedText()),
                new TextRange(TextEdit.mapOffset(start, result.edits()), TextEdit.mapOffset(end, result.edits()))).formattedText());
    }

    @Test
    void editsAreSmallOrderedWhitespaceOnlyAndMapIdentifiers() {
        String source = "class A{int first=1;int second=2;int sum(){return first+second;}}";
        FormatResult result = formatter.format(new FormatRequest(source, 99, LineEndingPolicy.LF));
        assertTrue(result.success(), () -> result.diagnostics().toString());
        int previous = 0;
        for (TextEdit edit : result.edits()) {
            assertTrue(edit.start() >= previous);
            assertTrue(source.substring(edit.start(), edit.end()).isBlank());
            assertTrue(edit.replacement().isBlank());
            assertTrue(edit.end() - edit.start() < source.length());
            previous = edit.end();
        }
        for (String name : List.of("A", "first", "second", "sum")) {
            int offset = source.indexOf(name);
            int mapped = TextEdit.mapOffset(offset, result.edits());
            assertTrue(result.formattedText().startsWith(name, mapped), name + " mapped to " + mapped);
        }
    }

    @Test
    void nestedAndLongSyntaxTerminatesWithoutTokenLoss() {
        for (String source : List.of(
                "class A { String " + "longName".repeat(30) + " = \"" + "x".repeat(180) + "\"; }",
                "class A { int f(int x){return " + "(".repeat(80) + "x+1" + ")".repeat(80) + ";} }",
                "class A { java.util.Map<String,java.util.List<java.util.Map<String,Integer>>> value; }")) {
            verify(source);
        }
    }

    @Test
    void publicFormatterIsDeterministicAcrossThreadsAndLocale() throws Exception {
        String source = shape(3);
        String expected = formatter.format(source);
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            try (var pool = Executors.newFixedThreadPool(4)) {
                var tasks = java.util.stream.IntStream.range(0, 32)
                        .<java.util.concurrent.Callable<String>>mapToObj(i -> () -> formatter.format(source)).toList();
                for (var result : pool.invokeAll(tasks)) assertEquals(expected, result.get());
            }
        } finally { Locale.setDefault(previous); }
    }

    private String verify(String source) {
        FormatResult first = formatter.format(new FormatRequest(source));
        assertTrue(first.success(), () -> first.diagnostics() + "\nSOURCE:\n" + source);
        FormatResult second = formatter.format(new FormatRequest(first.formattedText()));
        assertTrue(second.success(), () -> second.diagnostics().toString());
        assertEquals(first.formattedText(), second.formattedText(), "Idempotence");
        assertEquals(List.of(), second.edits(), "Canonical input must be a no-op");
        assertEquals(first.formattedText(), TextEdit.apply(source, first.edits()));
        assertEquals(parser.parse(source).tokens().stream().map(JavaToken::raw).toList(),
                parser.parse(first.formattedText()).tokens().stream().map(JavaToken::raw).toList());
        return first.formattedText();
    }

    private List<String> rawComments(String source) {
        return parser.parse(source).tokens().stream().filter(JavaToken::comment).map(JavaToken::raw).toList();
    }

    private String compileAndRun(String source, String folder) throws Exception {
        Path directory = Files.createDirectories(temporary.resolve(folder));
        Path file = directory.resolve("RuntimeValues.java"); Files.writeString(file, source);
        ByteArrayOutputStream error = new ByteArrayOutputStream();
        int result = ToolProvider.getSystemJavaCompiler().run(null, error, error, "--release", "25", "-proc:none", "-d", directory.toString(), file.toString());
        assertEquals(0, result, () -> error.toString(StandardCharsets.UTF_8));
        Process process = new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", directory.toString(), "RuntimeValues").redirectErrorStream(true).start();
        if (!process.waitFor(10, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("Runtime fixture timed out"); }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.exitValue(), output);
        return output;
    }

    private static String resource(String name) throws Exception {
        try (var stream = JavaFormatterTest.class.getResourceAsStream("/golden/" + name)) {
            assertNotNull(stream); return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static String shape(int seed) {
        int value = seed % 17 + 1;
        return switch (seed % 6) {
            case 0 -> "class A { int f(int a,int b){ int total=" + value + ";if(a<b){total+=a;}else{total-=b;}return total;} }";
            case 1 -> "class A<T extends Number & Comparable<T>> { java.util.List<? extends T> values; int f(int a){return (a>>2)+(a>>>1)+(a<<3);} }";
            case 2 -> "class A { int f(int n){ int sum=0;for(int i=0;i<n;i++){sum+=i;}while(n>0)n--;return sum;} }";
            case 3 -> "record A(int x,int y) { int total(){return x+y;} static int read(Object value){return switch(value){case A(int x,int y)->x+y;case null->0;default->-1;};} }";
            case 4 -> "class A { java.util.function.IntUnaryOperator f=x->x+" + value + ";void print(){java.util.List.of(1,2,3).stream().map(x->x*2).forEach(System.out::println);} }";
            default -> "class A { int f(int x){int[] values={1,2,3};try{for(int value:values)x+=value;}finally{x++;}return x;} }";
        };
    }
}
