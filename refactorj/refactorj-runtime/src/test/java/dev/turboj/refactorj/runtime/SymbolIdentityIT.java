package dev.turboj.refactorj.runtime;

import dev.turboj.refactorj.api.*;
import dev.turboj.refactorj.api.Model.*;
import dev.turboj.refactorj.api.Requests.*;
import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.util.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class SymbolIdentityIT {
    @TempDir Path root;

    @Test void reopenedWorkspaceResolvesTheSamePublicIdentityAndRejectsForeignHandles() throws Exception {
        String source = "class C { int value; int read(){return value;} }";
        var snapshot = snapshot(root, source);
        String id;
        try (var runtime = new RefactorJRuntime()) {
            try (var first = runtime.openWorkspace(snapshot)) {
                id = first.workspace().resolve(new Location(root.resolve("C.java"), source.indexOf("value")), Cancellation.NONE).id();
                assertTrue(id.startsWith("refactorj-symbol:1:"));
                assertEquals(1, first.workspace().references(id, Cancellation.NONE).size());
            }
            try (var second = runtime.openWorkspace(snapshot)) {
                assertEquals(id, second.workspace().resolve(new Location(root.resolve("C.java"), source.indexOf("value")), Cancellation.NONE).id());
                assertEquals(1, second.workspace().references(id, Cancellation.NONE).size());
                assertThrows(IllegalArgumentException.class, () -> second.workspace().references("=otherProject/src<C.java[C^value", Cancellation.NONE));
                Path other = Files.createDirectory(root.resolve("other"));
                try (var foreign = runtime.openWorkspace(snapshot(other, source))) {
                    assertThrows(IllegalArgumentException.class, () -> foreign.workspace().references(id, Cancellation.NONE));
                }
            }
        }
        assertFalse(Files.exists(root.resolve("C.java")), "Semantic queries never save unsaved buffers");
    }

    @Test void implementMethodsAcceptsPublicMethodSelectorsAndPreservesSelectionAcrossReopen() throws Exception {
        String source = "interface I { int selected(); int other(); } abstract class C implements I { }";
        var snapshot = snapshot(root, source);
        try (var runtime = new RefactorJRuntime()) {
            String id;
            try (var first = runtime.openWorkspace(snapshot)) {
                id = first.workspace().resolve(new Location(root.resolve("C.java"), source.indexOf("selected")), Cancellation.NONE).id();
            }
            try (var engine = runtime.openWorkspace(snapshot)) {
                var plan = engine.prepare(new ImplementMethods(new Location(root.resolve("C.java"), source.indexOf("class C") + 6), List.of(id)), Cancellation.NONE);
                assertTrue(plan.valid(), () -> plan.diagnostics().toString());
                String output = TextEdit.apply(source, plan.edits().getFirst().edits());
                assertTrue(output.lastIndexOf("selected(") > output.indexOf("abstract class C"), output);
                assertTrue(output.lastIndexOf("other(") < output.indexOf("abstract class C"), output);
                RefactoringAssertions.roundTrip(plan);
                Path file = root.resolve("C.java");
                Files.writeString(file, output);
                Path classes = Files.createDirectory(root.resolve("classes"));
                var errors = new ByteArrayOutputStream();
                assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, errors, "--release", "25", "-proc:none", "-d", classes.toString(), file.toString()), errors::toString);
            }
        }
    }

    @Test void effectfulInlineUsesTheSamePublicReferenceIdentity() throws Exception {
        String source = "class C { int next(){return 1;} int run(){int value=next(); return value;} }";
        try (var runtime = new RefactorJRuntime(); var engine = runtime.openWorkspace(snapshot(root, source))) {
            var plan = engine.prepare(new InlineVariable(new Location(root.resolve("C.java"), source.indexOf("value="))), Cancellation.NONE);
            assertTrue(plan.valid(), () -> plan.diagnostics().toString());
            String output = TextEdit.apply(source, plan.edits().getFirst().edits());
            assertTrue(output.contains("return next();"), output);
            RefactoringAssertions.roundTrip(plan);
        }
    }

    private static WorkspaceSnapshot snapshot(Path root, String source) {
        return new WorkspaceSnapshot(root, 1, List.of(new SourceSnapshot(root.resolve("C.java"), source, 1, true)), List.of(root), List.of());
    }
}
