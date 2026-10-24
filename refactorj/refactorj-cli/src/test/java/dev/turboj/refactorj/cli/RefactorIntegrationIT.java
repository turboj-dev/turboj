package dev.turboj.refactorj.cli;

import com.google.gson.*;
import dev.turboj.refactorj.api.*;
import dev.turboj.refactorj.transactions.*;
import java.io.*;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RefactorIntegrationIT {
    @TempDir
    Path temporary;

    @Test
    void serializedMemberSelectionsSurviveSessionRestart() throws Exception {
        Path root = temporary.toRealPath(), source = root.resolve("A.java");
        String original = "public class A { public int value(){ return 1; } public int other(){ return 2; } }\n";
        Files.writeString(source, original);
        String serialized;
        try (var first = new WorkspaceSession(DiskWorkspace.open(root, List.of(root), List.of()))) {
            var member = first.engine(Cancellation.NONE).workspace().resolve(new dev.turboj.refactorj.api.Model.Location(source, original.indexOf("value")), Cancellation.NONE);
            assertNotNull(member);
            var prepared = first.prepare(Json.object("operation", "extract-interface", "location", Json.object("file", "A.java", "start", original.indexOf("class A") + 6),
                    "name", "Value", "memberIds", List.of(member.id()), "replaceUsages", false), Cancellation.NONE);
            assertTrue(prepared.plan().valid(), () -> prepared.plan().diagnostics().toString());
            serialized = Json.GSON.toJson(prepared);
        }
        try (var second = new WorkspaceSession(DiskWorkspace.open(root, List.of(root), List.of()))) {
            var result = second.apply(Json.GSON.fromJson(serialized, WorkspaceSession.Prepared.class), Cancellation.NONE);
            String contract = Files.readString(root.resolve("Value.java"));
            assertTrue(contract.contains("value("));
            assertFalse(contract.contains("other("));
            assertTrue(Files.readString(source).contains("implements Value"));
            Path classes = Files.createDirectory(root.resolve("classes"));
            var errors = new ByteArrayOutputStream();
            assertEquals(0, javax.tools.ToolProvider.getSystemJavaCompiler().run(null, null, errors, "--release", "25", "-proc:none", "-d", classes.toString(), source.toString(), root.resolve("Value.java").toString()), errors::toString);
            second.undo(result.transactionId(), Cancellation.NONE);
            assertEquals(original, Files.readString(source));
            assertFalse(Files.exists(root.resolve("Value.java")));
        }
    }

    @Test
    void cliPreviewApplyUndoAndStalePlanUseRealSemanticEngine() throws Exception {
        Path root = temporary.toRealPath(), source = root.resolve("A.java");
        String original = "class A { int run(int value) { int total = value + 1; return total; } }\n";
        Files.writeString(source, original);
        var output = new ByteArrayOutputStream();
        var errors = new ByteArrayOutputStream();
        int column = original.indexOf("total") + 1;
        int status = Main.run(new String[] {"rename", "--root", root.toString(), "--symbol",
                "A.java:1:" + column, "--to", "sum"}, new PrintStream(output),
                new PrintStream(errors));
        assertEquals(0, status, errors.toString() + output);
        var prepared = Json.GSON.fromJson(output.toString(), WorkspaceSession.Prepared.class);
        assertTrue(prepared.plan().valid(), output.toString());
        assertEquals(original, Files.readString(source));
        assertFalse(Files.exists(root.resolve(".refactorj")));
        Path planFile = root.resolve("plan.json");
        Files.writeString(planFile, output.toString());
        output.reset();
        errors.reset();
        assertEquals(0, Main.run(new String[] {"apply", planFile.toString(), "--root", root
                .toString()}, new PrintStream(output), new PrintStream(errors)), errors.toString());
        assertEquals(original.replace("total", "sum"), Files.readString(source));
        String id = Json.GSON.fromJson(output.toString(), JsonObject.class).get("transactionId")
                .getAsString();
        output.reset();
        errors.reset();
        assertEquals(0, Main.run(new String[] {"undo", id, "--root", root.toString()},
                new PrintStream(output), new PrintStream(errors)), errors.toString());
        assertEquals(original, Files.readString(source));
        Files.writeString(source, original + "// user edit\n");
        output.reset();
        errors.reset();
        assertEquals(1, Main.run(new String[] {"apply", planFile.toString(), "--root", root
                .toString()}, new PrintStream(output), new PrintStream(errors)));
        assertTrue(Files.readString(source).endsWith("// user edit\n"));
    }

    @Test
    void tamperedPreviewCannotBecomeArbitraryWorkspaceWrite() throws Exception {
        Path root = temporary.toRealPath(), source = root.resolve("A.java");
        String original = "class A { int value; int get(){return value;} }";
        Files.writeString(source, original);
        try (var session = new WorkspaceSession(DiskWorkspace.open(root, List.of(root), List
                .of()))) {
            var prepared = session.prepare(Json.object("operation", "rename", "location", Json
                    .object("file", "A.java", "start", original.indexOf("value")), "name", "sum"),
                    Cancellation.NONE);
            var wire = Json.GSON.toJsonTree(prepared).getAsJsonObject();
            wire.getAsJsonObject("plan").getAsJsonArray("edits").get(0).getAsJsonObject()
                    .getAsJsonArray("edits").get(0).getAsJsonObject().addProperty("text",
                    "arbitrary");
            var corrupted = Json.GSON.fromJson(wire, WorkspaceSession.Prepared.class);
            assertThrows(TransactionException.class, () -> session.apply(corrupted,
                    Cancellation.NONE));
            assertEquals(original, Files.readString(source));
        }
    }
}
