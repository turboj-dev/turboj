package dev.turboj.refactorj.runtime;

import dev.turboj.refactorj.api.*;
import dev.turboj.refactorj.api.Model.*;
import dev.turboj.refactorj.api.Requests.*;
import dev.turboj.refactorj.transactions.InMemoryWorkspace;
import dev.turboj.refactorj.transactions.WorkspaceTransactions;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import javax.tools.ToolProvider;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;
import static org.junit.jupiter.api.Assertions.*;

/** Exercises the real embedded Java model and the actual JDT changes, without mocking Eclipse. */
class ModernizationIT {
    static RefactorJRuntime runtime;
    @TempDir Path root;

    @BeforeAll static void start() throws Exception { runtime = new RefactorJRuntime(); }
    @AfterAll static void stop() { if (runtime != null) runtime.close(); }

    @Test void anonymousToLambda() throws Exception {
        String output = transform("class C { Runnable r = new Runnable() { public void run() { System.out.println(1); } }; }",
                "new Runnable", AnonymousToLambda::new);
        assertTrue(output.contains("->"));
        compile(output);
    }

    @Test void lambdaToReference() throws Exception {
        String output = transform("class C { java.util.function.Function<String,Integer> f = x -> Integer.parseInt(x); }",
                "x ->", LambdaToMethodReference::new);
        assertTrue(output.contains("Integer::parseInt"));
        compile(output);
    }

    @Test void referenceToLambdaPreservesStaticTarget() throws Exception {
        String output = transform("class C { java.util.function.Function<String,Integer> f = Integer::parseInt; }",
                "Integer::", MethodReferenceToLambda::new);
        assertTrue(output.contains("->"));
        compile(output);
    }

    @Test void rejectsBoundReceiverEvaluationChange() throws Exception {
        rejected("class C { java.util.function.Supplier<String> f = new String()::toString; }",
                "new String", MethodReferenceToLambda::new);
    }

    @Test void rejectsSerializableAnonymousTargetsIncludingInheritedMarkers() throws Exception {
        rejected("class C { interface Task extends Runnable, java.io.Serializable {} Task task = new Task() { public void run() {} }; }",
                "new Task", AnonymousToLambda::new);
        rejected("class C { interface Base extends java.io.Serializable {} interface Task extends Base, Runnable {} Task task = new Task() { public void run() {} }; }",
                "new Task", AnonymousToLambda::new);
    }

    @Test void rejectsLambdaReceiverCaptureAndNullCheckTimingChanges() throws Exception {
        String nullable = "public class C { public static String run(){ String value = null; java.util.function.Supplier<String> task = () -> value.trim(); return \"created\"; } }";
        assertEquals("created", run(nullable));
        rejected(nullable, "() ->", LambdaToMethodReference::new);
        String mutable = "public class C { String value = \"first\"; public static String run(){ C c = new C(); java.util.function.Supplier<String> task = () -> c.value.trim(); c.value = \"second\"; return task.get(); } }";
        assertEquals("second", run(mutable));
        rejected(mutable, "() ->", LambdaToMethodReference::new);
        String effect = "public class C { static int count; static String value(){ count++; return \"text\"; } public static String run(){ java.util.function.Supplier<String> task = () -> value().trim(); return Integer.toString(count); } }";
        assertEquals("0", run(effect));
        rejected(effect, "() ->", LambdaToMethodReference::new);
    }

    @Test void safeThisAndUnboundReceiversPreserveRuntimeBehaviorAndUndo() throws Exception {
        String source = "public class C { String value(){ return \"ok\"; } public static String run(){ C c = new C(); java.util.function.Function<C,String> task = x -> x.value(); return task.apply(c); } }";
        String output = transform(source, "x ->", LambdaToMethodReference::new);
        assertEquals(run(source), run(output));
        assertTrue(output.contains("C::value"));
        source = "public class C { String value(){ return \"ok\"; } String local(){ java.util.function.Supplier<String> task = () -> this.value(); return task.get(); } public static String run(){ return new C().local(); } }";
        output = transform(source, "() ->", LambdaToMethodReference::new);
        assertEquals(run(source), run(output));
        assertTrue(output.contains("this::value"));
    }

    @Test void switchExpression() throws Exception {
        String output = transform("class C { int f(int n) { switch(n) { case 1: return 2; default: return 3; } } }",
                "switch", SwitchToExpression::new);
        assertTrue(output.contains("->"));
        compile(output);
    }

    @Test void explicitToVarAndBack() throws Exception {
        String output = transform("class C { int f() { int n = 42; return n; } }", "int n", TypeToVar::new);
        assertTrue(output.contains("var n"));
        compile(output);
        output = transform(output, "var n", VarToType::new);
        assertTrue(output.contains("int n"));
        compile(output);
    }

    @Test void rejectsTargetTypedGenericInference() throws Exception {
        rejected("class C { void f() { java.util.List<String> values = java.util.Collections.emptyList(); } }",
                "java.util.List", TypeToVar::new);
    }

    @Test void rejectsTypeNarrowingThroughVar() throws Exception {
        rejected("class C { void f() { Number value = Integer.valueOf(1); } }", "Number value", TypeToVar::new);
    }

    @Test void organizesUnusedAndMissingImports() throws Exception {
        String output = transform("import java.util.Set; class C { java.util.List<String> names; }", "class C", OrganizeImports::new);
        assertFalse(output.contains("import java.util.Set"));
        compile(output);
    }

    @Test void organizeImportsSearchesForUnqualifiedTypes() throws Exception {
        String output = transform("class C { ArrayList<String> names = new ArrayList<>(); }", "class C", OrganizeImports::new);
        assertTrue(output.contains("import java.util.ArrayList;"));
        compile(output);
    }

    @Test void addsResolvedImportOnly() throws Exception {
        String output = transform("class C { List<String> names; }", "List", location -> new AddImport(location, "java.util.List", false));
        assertTrue(output.contains("import java.util.List;"));
        compile(output);
        rejected("class C {}", "class C", location -> new AddImport(location, "unavailable.project.Type", false));
    }

    @Test void removesOnlyProvenUnusedImport() throws Exception {
        String output = transform("import java.util.List; class C {}", "java.util.List", RemoveImport::new);
        assertFalse(output.contains("import"));
        compile(output);
        rejected("import java.util.List; class C { List<String> names; }", "java.util.List", RemoveImport::new);
    }

    @Test void implementsMissingMembers() throws Exception {
        String output = transform("class C implements Runnable { }", "class C", location -> new ImplementMethods(location, List.of()));
        assertTrue(output.contains("void run()"));
        compile(output);
    }

    @Test void createsMethodFromResolvedArgumentTypes() throws Exception {
        String output = transform("class C { int f() { return missing(1); } }", "missing", CreateMethod::new);
        assertTrue(output.contains("int missing("));
        compile(output);
    }

    @Test void createsConstructorFromInvocation() throws Exception {
        String output = transform("class C { static C f() { return new C(1); } }", "new C", CreateConstructor::new);
        assertTrue(output.contains("C(int"));
        compile(output);
    }

    @Test void createsFieldAndLocalFromContext() throws Exception {
        String field = transform("class C { void f() { count = 1; } }", "count", CreateField::new);
        assertTrue(field.contains("int count"));
        compile(field);
        String local = transform("class C { void f() { count = 1; } }", "count", CreateLocal::new);
        assertTrue(local.contains("int count"));
        compile(local);
    }

    @Test void correctsCompilerConfirmedTypeMismatch() throws Exception {
        String output = transform("class C { void f() { int value = \"text\"; System.out.println(value); } }",
                "value =", FixTypeMismatch::new);
        assertTrue(output.contains("String value"));
        compile(output);
    }

    @Test void removesCompilerProvenUnreachableStatement() throws Exception {
        String output = transform("class C { void f() { return; System.out.println(1); } }",
                "System.out", RemoveUnreachable::new);
        assertFalse(output.contains("println"));
        compile(output);
        rejected("class C { void f() { System.out.println(1); } }", "System.out", RemoveUnreachable::new);
    }

    private String transform(String source, String selection, Function<Location, Request> request) throws Exception {
        Plan plan = plan(source, selection, request);
        assertTrue(plan.valid(), () -> plan.diagnostics().toString());
        assertFalse(plan.edits().isEmpty(), "An applicable transformation must produce real edits");
        assertTrue(plan.fileOperations().isEmpty());
        Path file = root.resolve("C.java");
        String output = TextEdit.apply(source, plan.edits().stream().filter(edit -> edit.file().equals(file)).findFirst().orElseThrow().edits());
        InMemoryWorkspace buffers = new InMemoryWorkspace(plan.before());
        WorkspaceTransactions transactions = new WorkspaceTransactions(buffers);
        var applied = transactions.apply(plan, Cancellation.NONE);
        assertEquals(output, applied.snapshot().source(file).text());
        assertEquals(source, transactions.undo(applied.transactionId(), Cancellation.NONE).snapshot().source(file).text());
        assertEquals(output, transactions.redo(applied.transactionId(), Cancellation.NONE).snapshot().source(file).text());
        assertEquals(source, Files.readString(file), "Preparing a preview must not write source files");
        return output;
    }

    private void rejected(String source, String selection, Function<Location, Request> request) throws Exception {
        Plan plan = plan(source, selection, request);
        assertFalse(plan.valid(), "Unsafe or inapplicable transformation was accepted");
        var buffers = new InMemoryWorkspace(plan.before());
        var transactions = new WorkspaceTransactions(buffers);
        assertThrows(RuntimeException.class, () -> transactions.apply(plan, Cancellation.NONE));
        assertEquals(source, buffers.snapshot().source(root.resolve("C.java")).text());
        assertTrue(transactions.history().isEmpty());
        assertEquals(source, Files.readString(root.resolve("C.java")));
    }

    private String run(String source) throws Exception {
        Path destination = Files.createTempDirectory(root, "runtime-");
        Path file = destination.resolve("C.java");
        Files.writeString(file, source);
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        int exit = ToolProvider.getSystemJavaCompiler().run(null, null, errors, "--release", "25", "-proc:none", "-d", destination.toString(), file.toString());
        assertEquals(0, exit, errors::toString);
        try (var loader = new java.net.URLClassLoader(new java.net.URL[]{destination.toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
            return (String) loader.loadClass("C").getMethod("run").invoke(null);
        }
    }

    private Plan plan(String source, String selection, Function<Location, Request> factory) throws Exception {
        root = root.toRealPath();
        Path file = root.resolve("C.java");
        Files.writeString(file, source);
        WorkspaceSnapshot snapshot = new WorkspaceSnapshot(root, 1,
                List.of(new SourceSnapshot(file, source, 1, true)), List.of(root), List.of());
        try (RefactorJ engine = runtime.openWorkspace(snapshot)) {
            int offset = source.indexOf(selection);
            assertTrue(offset >= 0);
            return engine.prepare(factory.apply(new Location(file, offset, offset + selection.length())), Cancellation.NONE);
        }
    }

    private void compile(String source) throws Exception {
        Path destination = Files.createTempDirectory(root, "compile-");
        Path file = destination.resolve("C.java");
        Files.writeString(file, source);
        ByteArrayOutputStream errors = new ByteArrayOutputStream();
        int exit = ToolProvider.getSystemJavaCompiler().run(null, null, errors, "--release", "25", "-proc:none", "-d", destination.toString(), file.toString());
        assertEquals(0, exit, () -> errors.toString() + "\n" + source);
    }
}
