package dev.turboj.refactorj.runtime;

import dev.turboj.refactorj.api.*;
import dev.turboj.refactorj.api.JavaStructure.*;
import dev.turboj.refactorj.api.Model.*;
import java.io.ByteArrayOutputStream;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Real embedded JDT operations, compiler validation and observable runtime behavior. */
class KeyboardJavaIT {
    static RefactorJRuntime runtime;
    @TempDir Path root;
    @BeforeAll static void start() throws Exception { runtime = new RefactorJRuntime(); }
    @AfterAll static void stop() { runtime.close(); }
    private final class Fixture implements AutoCloseable {
        final RefactorJ engine; final JavaIntelligence api; final JavaIntelligence.Context context;
        Fixture(String source) throws Exception {
            Path file = root.resolve("C.java");
            var snapshot = new WorkspaceSnapshot(root, 1, List.of(new SourceSnapshot(file, source, 1, true)), List.of(root), List.of());
            engine = runtime.openWorkspace(snapshot); api = ((JavaIntelligenceProvider)engine).intelligence();
            context = new JavaIntelligence.Context(file, source, 1, Map.of(file, source), List.of(root), List.of(), 1);
        }
        EditResult edit(Edit edit, String marker) { int p = context.source().indexOf(marker); assertTrue(p >= 0); return api.editStructure(context, p, p, edit); }
        String apply(EditResult result) {
            assertTrue(result.applicable(), result.explanation());
            assertEquals(context.source(), engine.workspace().snapshot().source(context.file()).text(), "Preparation must not mutate workspace");
            return Model.TextEdit.apply(context.source(), result.edits().stream().map(e -> new Model.TextEdit(e.start(), e.end(), e.text())).toList());
        }
        @Override public void close() { engine.close(); }
    }
    @Test void expansionFollowsActualAstAndIncludesUnsavedSource() throws Exception {
        String source = "class C { int f(int n) { return Math.abs(n + 1); } }";
        try (var f = new Fixture(source)) {
            int p = source.indexOf("n +"); int start = p, end = p; var ranges = new ArrayList<Range>();
            while (true) { Range r = f.api.selectStructure(f.context, start, end, Selection.EXPAND); if (r == null) break; assertTrue(r.start() <= start && r.end() >= end); ranges.add(r); start = r.start(); end = r.end(); }
            assertEquals("n", source.substring(ranges.getFirst().start(), ranges.getFirst().end()));
            assertTrue(ranges.stream().anyMatch(r -> source.substring(r.start(), r.end()).equals("n + 1")));
            assertTrue(ranges.stream().anyMatch(r -> source.substring(r.start(), r.end()).startsWith("return")));
            assertEquals(new Range(0, source.length()), ranges.getLast());
            assertTrue(source.substring(f.api.selectStructure(f.context, p, p, Selection.METHOD).start()).startsWith("int f"));
            assertTrue(source.substring(f.api.selectStructure(f.context, p, p, Selection.BLOCK).start()).startsWith("{"));
        }
    }
    @Test void bracketSelectionUsesJavaTokensRatherThanLiteralCharacters() throws Exception {
        String source = "class C { void f(){ System.out.println(\"(not syntax)\"); } }";
        try (var f = new Fixture(source)) {
            int p = source.indexOf("not syntax"); var range = f.api.selectStructure(f.context, p, p, Selection.BRACKETS);
            assertEquals("(\"(not syntax)\")", source.substring(range.start(), range.end()));
            int brace = source.indexOf('{'); var typeRange = f.api.selectStructure(f.context, brace, brace, Selection.BRACKETS);
            assertEquals(brace, typeRange.start()); assertEquals(source.length(), typeRange.end());
        }
    }
    @Test void rejectsContextThatDoesNotMatchSharedWorkspace() throws Exception {
        try (var f = new Fixture("class C {}")) {
            var stale = new JavaIntelligence.Context(f.context.file(), "class Different {}", 2, Map.of(), List.of(root), List.of(), 1);
            assertThrows(IllegalArgumentException.class, () -> f.api.editStructure(stale, 0, 0, Edit.CONSTRUCTOR));
        }
    }
    @Test void methodMovePreservesRuntimeAndAttachedComments() throws Exception {
        String source = "public class C {\n /** first */\n public static int a() {\n return 21;\n }\n /** second */\n public static int b() { return a() * 2; }\n}";
        try (var f = new Fixture(source)) {
            EditResult result = f.edit(Edit.MOVE_STATEMENT_UP, "b()");
            assertTrue(result.warnings().isEmpty()); String changed = f.apply(result);
            assertTrue(changed.indexOf("/** second */") < changed.indexOf("/** first */"));
            assertEquals(invoke(source, "b"), invoke(changed, "b"));
            assertEquals(42, invoke(changed, "b"));
        }
    }
    @Test void multilineStatementsMoveAsUnitsAndWarnForEffects() throws Exception {
        String source = "class C { void f() { System.out.println(\n 1 + 2);\n System.out.println(4); } }";
        try (var f = new Fixture(source)) {
            EditResult result = f.edit(Edit.MOVE_STATEMENT_DOWN, "println(");
            assertFalse(result.warnings().isEmpty()); String changed = f.apply(result);
            assertTrue(changed.indexOf("println(4)") < changed.indexOf("1 + 2")); compile(changed);
        }
    }
    @Test void methodMoveRetainsCrLfAndUnicodeComments() throws Exception {
        String source = "class C {\r\n    // first α\r\n    int first(){ return 21; }\r\n    // second β\r\n    int second(){ return first()*2; }\r\n}";
        try (var f = new Fixture(source)) {
            String changed = f.apply(f.edit(Edit.MOVE_BLOCK_UP, "second()"));
            assertTrue(changed.indexOf("// second β") < changed.indexOf("// first α"));
            assertFalse(changed.replace("\r\n", "").contains("\n")); compile(changed);
        }
    }
    @Test void selectedCompleteStatementsMoveTogether() throws Exception {
        String source = "class C { int f() { int a = 1; int b = 2; int c = 3; return a+b+c; } }";
        try (var f = new Fixture(source)) {
            int start = source.indexOf("int b"), finish = source.indexOf(" return");
            var result = f.api.editStructure(f.context, finish, start, Edit.MOVE_BLOCK_UP);
            String changed = f.apply(result); assertTrue(changed.indexOf("int c") < changed.indexOf("int a")); compile(changed);
            assertTrue(result.caret() > result.anchor());
        }
    }
    @Test void indentedSelectionMovesStatementsRatherThanEnclosingMethod() throws Exception {
        String source = "class C { int f() {\n    int a = 1;\n    int b = 2;\n    int c = 3;\n    return a+b+c;\n} }";
        try (var f = new Fixture(source)) {
            int start = source.indexOf("    int b"), finish = source.indexOf("    return");
            String changed = f.apply(f.api.editStructure(f.context, finish, start, Edit.MOVE_BLOCK_UP));
            assertTrue(changed.indexOf("int c") < changed.indexOf("int a")); compile(changed);
        }
    }
    @Test void completesSemicolonFromValidatedCandidateAst() throws Exception {
        String source = "class C { void f() { System.out.println(42)\n} }";
        try (var f = new Fixture(source)) {
            int offset = source.indexOf("\n");
            String changed = f.apply(f.api.editStructure(f.context, offset, offset, Edit.COMPLETE_STATEMENT));
            assertTrue(changed.contains("println(42);")); compile(changed);
        }
        try (var f = new Fixture("class C { void f(){ System.out.println(42); } }")) {
            assertFalse(f.edit(Edit.COMPLETE_STATEMENT, "42").applicable());
        }
    }
    @Test void rejectsVariableLifetimeChangeAndControlFlow() throws Exception {
        try (var f = new Fixture("class C { int f() { int n = 1; n++; return n; } }")) {
            assertFalse(f.edit(Edit.MOVE_STATEMENT_UP, "n++").applicable());
            assertFalse(f.edit(Edit.MOVE_STATEMENT_UP, "return").applicable());
            assertFalse(f.edit(Edit.MOVE_STATEMENT_UP, "int n").applicable());
        }
    }
    @Test void fieldsOnlyReorderWhenConstantInitializationIsSafe() throws Exception {
        try (var f = new Fixture("class C { final int a = 1; final int b = 2; }")) { compile(f.apply(f.edit(Edit.MOVE_BLOCK_UP, "b ="))); }
        try (var f = new Fixture("class C { int a = f(); int b = f(); int f(){return 1;} }")) { assertFalse(f.edit(Edit.MOVE_BLOCK_UP, "b =").applicable()); }
    }
    @Test void nestedMethodsAndLambdasKeepTheirParentScope() throws Exception {
        String source = "class C { class Inner { int a(){return 1;} int b(){java.util.function.IntSupplier r = () -> 2; return r.getAsInt();} } }";
        try (var f = new Fixture(source)) { String changed = f.apply(f.edit(Edit.MOVE_BLOCK_UP, "b()")); compile(changed); assertTrue(changed.indexOf("b()") < changed.indexOf("a()")); }
    }
    @Test void pureArgumentsMoveWithExplicitMeaningWarning() throws Exception {
        String source = "class C { int f(int a,int b){return a-b;} int g(){return f(20, 22);} }";
        try (var f = new Fixture(source)) { var result = f.edit(Edit.MOVE_ELEMENT_RIGHT, "20,"); assertFalse(result.warnings().isEmpty()); assertTrue(f.apply(result).contains("f(22, 20)")); }
    }
    @Test void rejectsSideEffectArgumentsAndOverloadChange() throws Exception {
        try (var f = new Fixture("class C { int f(int a,int b){return a;} int g(){int i=1; return f(i++, 2);} }")) { assertFalse(f.edit(Edit.MOVE_ELEMENT_RIGHT, "i++,").applicable()); }
        try (var f = new Fixture("class C { void f(int a,long b){} void f(long a,int b){} void g(){f(1, 2L);} }")) { assertFalse(f.edit(Edit.MOVE_ELEMENT_RIGHT, "1, 2L").applicable()); }
    }
    @Test void arrayAndEnumElementsMoveWithoutBreakingSyntax() throws Exception {
        try (var f = new Fixture("class C { int[] a = {1, 2, 3}; }")) { assertTrue(f.apply(f.edit(Edit.MOVE_ELEMENT_LEFT, "2, 3")).contains("{2, 1, 3}")); }
        try (var f = new Fixture("class C { enum E { A, B, C } }")) { assertTrue(f.apply(f.edit(Edit.MOVE_ELEMENT_LEFT, "B,")).contains("B, A, C")); }
    }
    @Test void parameterReorderingUsesRealChangeSignatureAndUpdatesCalls() throws Exception {
        String source = "class C { int f(int a, int b){return a-b;} int g(){return f(20,22);} }";
        try (var f = new Fixture(source)) {
            var request = f.api.parameterMove(f.context, source.indexOf("int b") + 4, -1);
            assertNotNull(request); var plan = f.engine.prepare(request, Cancellation.NONE);
            assertTrue(plan.valid(), plan.diagnostics().toString());
            String changed = Model.TextEdit.apply(source, plan.edits().getFirst().edits());
            assertTrue(changed.contains("f(int b, int a)")); assertTrue(changed.contains("f(22,20)") || changed.contains("f(22, 20)")); compile(changed);
            RefactoringAssertions.roundTrip(plan);
        }
    }
    @TestFactory List<DynamicTest> generatorsProduceCompilableJava() {
        return List.of(Edit.CONSTRUCTOR, Edit.GETTERS, Edit.SETTERS, Edit.GETTERS_SETTERS, Edit.EQUALS, Edit.HASH_CODE, Edit.TO_STRING, Edit.JAVADOC).stream().map(action -> DynamicTest.dynamicTest(action.name(), () -> {
            String source = "public class C<T> { private int count; private String name; private int[] values; private T value; }";
            try (var f = new Fixture(source)) { compile(f.apply(f.edit(action, "C<T>"))); }
        })).toList();
    }
    @Test void generatorsDoNotDuplicateOrSetFinalFields() throws Exception {
        try (var f = new Fixture("class C { final int count = 1; public int getCount(){return count;} }")) {
            assertFalse(f.edit(Edit.GETTERS, "class C").applicable()); assertFalse(f.edit(Edit.SETTERS, "class C").applicable());
        }
        try (var f = new Fixture("class C { public String toString(){return \"x\";} }")) { assertFalse(f.edit(Edit.TO_STRING, "class C").applicable()); }
    }
    @Test void equalityAndHashHandleArraysAndFloatingPoint() throws Exception {
        String source = "public class C { double value; int[] numbers = {1,2}; }";
        try (var f = new Fixture(source)) {
            String changed = f.apply(f.edit(Edit.EQUALS, "class C")); Path classes = compile(changed);
            try (var loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()})) {
                Class<?> c = loader.loadClass("C"); Object a = c.getConstructor().newInstance(), b = c.getConstructor().newInstance();
                assertEquals(a,b); assertEquals(a.hashCode(),b.hashCode());
                var field = c.getDeclaredField("value"); field.setAccessible(true); field.setDouble(a, Double.NaN); field.setDouble(b, Double.NaN); assertEquals(a,b); assertEquals(a.hashCode(),b.hashCode());
            }
        }
    }
    @Test void implementMethodsUsesGenericBindingsAndExplicitStub() throws Exception {
        try (var f = new Fixture("class C implements java.util.function.Function<String,Integer> { }")) {
            var result = f.edit(Edit.IMPLEMENT_METHODS, "class C"); String changed = f.apply(result); compile(changed);
            assertTrue(changed.contains("java.lang.Integer apply(java.lang.String arg0)")); assertTrue(changed.contains("UnsupportedOperationException")); assertFalse(result.warnings().isEmpty());
        }
    }
    @Test void overrideDelegatesToInheritedImplementation() throws Exception {
        try (var f = new Fixture("class Base { public int value(){return 42;} } public class C extends Base { }")) {
            String changed = f.apply(f.edit(Edit.OVERRIDE_METHODS, "class C")); compile(changed); assertTrue(changed.contains("super.value()"));
        }
    }
    @Test void surroundUsesCompleteSyntaxAndRefusesScopeNarrowing() throws Exception {
        try (var f = new Fixture("class C { void f(){ System.out.println(42); } }")) {
            compile(f.apply(f.edit(Edit.SURROUND_IF, "println"))); compile(f.apply(f.edit(Edit.SURROUND_TRY_FINALLY, "println")));
        }
        try (var f = new Fixture("class C { int f(){ int n=1; return n; } }")) { assertFalse(f.edit(Edit.SURROUND_IF, "int n").applicable()); }
    }
    @Test void navigationUsesBindingsAndIndexedSources() throws Exception {
        String source = "interface I { int f(); } class Base {} class C extends Base implements I { public int f(){return g();} int g(){return 42;} }";
        try (var f = new Fixture(source)) {
            assertFalse(f.api.navigationTargets(f.context, 0, Navigation.TYPES, "C").isEmpty());
            assertFalse(f.api.navigationTargets(f.context, 0, Navigation.SYMBOLS, "g").isEmpty());
            assertFalse(f.api.navigationTargets(f.context, source.indexOf("int f")+4, Navigation.IMPLEMENTATIONS, "").isEmpty());
            assertFalse(f.api.navigationTargets(f.context, source.lastIndexOf("int g")+4, Navigation.CALLERS, "").isEmpty());
            assertFalse(f.api.navigationTargets(f.context, source.lastIndexOf("int f")+4, Navigation.CALLEES, "").isEmpty());
            assertFalse(f.api.navigationTargets(f.context, source.indexOf("class C")+6, Navigation.TYPE_HIERARCHY, "").isEmpty());
            assertFalse(f.api.navigationTargets(f.context, source.lastIndexOf("int g")+4, Navigation.REFERENCES, "").isEmpty());
            assertFalse(f.api.navigationTargets(f.context, source.indexOf("class C"), Navigation.NEXT_METHOD, "").isEmpty());
        }
    }
    @Test void smartCompletionFiltersAgainstResolvedAssignmentType() throws Exception {
        String source = "class C { int count; String content; void f(){ int n = co; } }";
        try (var f = new Fixture(source)) {
            var result = f.api.completeSmart(f.context, source.indexOf("co;") + 2);
            assertTrue(result.proposals().stream().anyMatch(c -> c.text().equals("count")), result.toString());
            assertFalse(result.proposals().stream().anyMatch(c -> c.text().equals("content")), result.toString());
        }
    }
    @Test void recordsSharedModelKeyboardPreparationMeasurements() throws Exception {
        String source = "class C { int first(){ return 21; } int second(){ return first()*2; } }";
        long cold = System.nanoTime();
        try (var f = new Fixture(source)) {
            int caret = source.indexOf("second");
            f.api.selectStructure(f.context, caret, caret, Selection.EXPAND);
            long firstWorkspace = System.nanoTime() - cold;
            java.lang.management.ThreadMXBean bean = java.lang.management.ManagementFactory.getThreadMXBean();
            var allocations = bean instanceof com.sun.management.ThreadMXBean memory && memory.isThreadAllocatedMemorySupported() ? memory : null;
            if (allocations != null && !allocations.isThreadAllocatedMemoryEnabled()) allocations.setThreadAllocatedMemoryEnabled(true);
            for (int i=0;i<20;i++) { f.api.selectStructure(f.context, caret, caret, Selection.EXPAND); f.api.editStructure(f.context, caret, caret, Edit.MOVE_STATEMENT_UP); }
            long[] selection = new long[60], moves = new long[60], selectionBytes = new long[60], moveBytes = new long[60];
            long thread = Thread.currentThread().threadId();
            for (int i=0;i<60;i++) {
                long bytes = allocations == null ? 0 : allocations.getThreadAllocatedBytes(thread), start = System.nanoTime();
                f.api.selectStructure(f.context, caret, caret, Selection.EXPAND); selection[i] = System.nanoTime()-start;
                selectionBytes[i] = allocations == null ? -1 : allocations.getThreadAllocatedBytes(thread)-bytes;
                bytes = allocations == null ? 0 : allocations.getThreadAllocatedBytes(thread); start = System.nanoTime();
                assertTrue(f.api.editStructure(f.context, caret, caret, Edit.MOVE_STATEMENT_UP).applicable()); moves[i] = System.nanoTime()-start;
                moveBytes[i] = allocations == null ? -1 : allocations.getThreadAllocatedBytes(thread)-bytes;
            }
            String classHash;
            try (var stream = f.api.getClass().getResourceAsStream("JdtKeyboardEditing.class")) { classHash = HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(stream.readAllBytes())); }
            String json = "{\n  \"timestamp\":\"" + java.time.Instant.now() + "\",\n  \"jdk\":\"" + System.getProperty("java.runtime.version") + "\",\n  \"os\":\"" + System.getProperty("os.name") + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch") + "\",\n  \"backendClassSha256\":\"" + classHash + "\",\n  \"sourceCharacters\":" + source.length() + ",\n  \"warmup\":20,\n  \"samples\":60,\n  \"newWorkspaceFirstSelectionNanos\":" + firstWorkspace + ",\n  \"selection\":" + metrics(selection, selectionBytes) + ",\n  \"movePreparation\":" + metrics(moves, moveBytes) + ",\n  \"scope\":\"Warm Eclipse runtime; one cached in-memory source AST. Monotonic API-call durations only, excluding editor dispatch, EDT, painting and OS input. Move includes candidate binding/compiler validation. Single new-workspace observation is not process cold-start. Development probe, not a cross-IDE benchmark.\"\n}\n";
            Path report=Path.of("target/keyboard-semantic-performance.json"); Files.createDirectories(report.getParent()); Files.writeString(report,json);
        }
    }
    private static String metrics(long[] nanos,long[] allocations) { Arrays.sort(nanos); Arrays.sort(allocations); return "{\"p50Nanos\":"+nanos[29]+",\"p95Nanos\":"+nanos[56]+",\"p99Nanos\":"+nanos[59]+",\"medianAllocatedBytes\":"+allocations[29]+"}"; }
    private Path compile(String source) throws Exception {
        Path directory = Files.createTempDirectory(root, "compiled-"); Path file = directory.resolve("C.java"); Files.writeString(file, source);
        var errors = new ByteArrayOutputStream(); int result = ToolProvider.getSystemJavaCompiler().run(null, null, errors, "-proc:none", "-d", directory.toString(), file.toString());
        assertEquals(0, result, errors.toString() + "\n" + source); return directory;
    }
    private Object invoke(String source, String method) throws Exception {
        Path classes = compile(source);
        try (var loader = new URLClassLoader(new java.net.URL[]{classes.toUri().toURL()})) { return loader.loadClass("C").getMethod(method).invoke(null); }
    }
}
