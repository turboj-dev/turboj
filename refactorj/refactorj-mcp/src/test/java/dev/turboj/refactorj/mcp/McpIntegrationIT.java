package dev.turboj.refactorj.mcp;

import com.google.gson.*;
import dev.turboj.refactorj.api.Cancellation;
import dev.turboj.refactorj.cli.*;
import dev.turboj.refactorj.transactions.*;
import java.nio.file.*;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class McpIntegrationIT {
    @TempDir
    Path temporary;

    @Test
    void realSemanticPlanRequiresWriteAuthorizationAndCanUndo() throws Exception {
        Path root = temporary.toRealPath(), source = root.resolve("A.java");
        String original = "class A { int value; int get(){return value;} }";
        Files.writeString(source, original);
        try (var session = new WorkspaceSession(DiskWorkspace.open(root, List.of(root), List
                .of()))) {
            var readOnly = new McpHandler(session, false);
            var writable = new McpHandler(session, true);
            JsonObject request = Json.object("operation", "rename", "location", Json.object("file",
                    "A.java", "start", original.indexOf("value")), "name", "amount");
            JsonObject result = call(readOnly, "refactorj_prepare", Json.object("request",
                    request));
            assertFalse(result.get("isError").getAsBoolean(), result.toString());
            JsonObject prepared = result.getAsJsonObject("structuredContent")
                    .getAsJsonObject("result");
            String id = prepared.getAsJsonObject("plan").get("id").getAsString();
            assertEquals(original, Files.readString(source));
            JsonObject args = Json.object("prepared", prepared, "dryRun", false, "confirmation",
                    "APPLY " + id);
            assertTrue(call(readOnly, "refactorj_apply", args).get("isError").getAsBoolean());
            assertEquals(original, Files.readString(source));
            JsonObject applied = call(writable, "refactorj_apply", args);
            assertFalse(applied.get("isError").getAsBoolean(), applied.toString());
            assertEquals(original.replace("value", "amount"), Files.readString(source));
            String transaction = applied.getAsJsonObject("structuredContent")
                    .getAsJsonObject("result").get("transactionId").getAsString();
            JsonObject undo = call(writable, "refactorj_undo", Json.object("transactionId",
                    transaction, "dryRun", false, "confirmation", "UNDO " + transaction));
            assertFalse(undo.get("isError").getAsBoolean(), undo.toString());
            assertEquals(original, Files.readString(source));
        }
    }

    private static JsonObject call(McpHandler handler, String name, JsonObject args) {
        return handler.handle("tools/call", Json.object("_meta", McpProtocolTest.meta(), "name",
                name, "arguments", args), Cancellation.NONE).getAsJsonObject();
    }
}
