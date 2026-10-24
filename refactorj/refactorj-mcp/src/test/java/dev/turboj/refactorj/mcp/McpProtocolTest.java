package dev.turboj.refactorj.mcp;

import com.google.gson.*;
import dev.turboj.refactorj.api.*;
import dev.turboj.refactorj.cli.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class McpProtocolTest {
    static JsonObject meta() {
        return Json.object("io.modelcontextprotocol/protocolVersion", McpHandler.VERSION,
                "io.modelcontextprotocol/clientInfo", Json.object("name", "protocol-test",
                "version", "1"), "io.modelcontextprotocol/clientCapabilities", new JsonObject());
    }

    @Test
    void discoversWithoutInitializeAndRequiresMetadataOnEveryRequest() throws Exception {
        JsonObject params = Json.object("_meta", meta());
        String input = Json.GSON.toJson(Json.object("jsonrpc", "2.0", "id", 1, "method",
                "server/discover", "params", params)) + "\n" + Json.GSON.toJson(Json
                .object("jsonrpc", "2.0", "id", 2, "method", "tools/list", "params", params)) + "\n"
                + Json.GSON.toJson(Json.object("jsonrpc", "2.0", "id", 3, "method", "tools/list"))
                + "\n";
        var output = new ByteArrayOutputStream();
        new RpcServer(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), output,
                RpcServer.Framing.JSON_LINES, new McpHandler(null, false)).run();
        var responses = output.toString(StandardCharsets.UTF_8).lines().map(s -> Json.GSON
                .fromJson(s, JsonObject.class)).toList();
        assertEquals("complete", responses.get(0).getAsJsonObject("result").get("resultType")
                .getAsString());
        assertEquals(McpHandler.VERSION, responses.get(0).getAsJsonObject("result")
                .getAsJsonArray("supportedVersions").get(0).getAsString());
        assertEquals(10, responses.get(1).getAsJsonObject("result").getAsJsonArray("tools").size());
        assertEquals(-32022, responses.get(2).getAsJsonObject("error").get("code").getAsInt());
    }

    @Test
    void allOperationsHaveTypedSchemasAndMutationAnnotations() {
        JsonArray tools = McpHandler.tools();
        var seen = new HashSet<String> ();

        for (JsonElement value : tools) {
            JsonObject tool = value.getAsJsonObject();
            assertTrue(seen.add(Json.string(tool, "name")));
            if (Json.string(tool, "name").equals("refactorj_prepare")) {
                JsonArray choices = tool.getAsJsonObject("inputSchema")
                        .getAsJsonObject("properties").getAsJsonObject("request")
                        .getAsJsonArray("oneOf");
                assertEquals(Operation.values().length, choices.size());
            }
            if (Json.string(tool, "name").equals("refactorj_apply"))
                assertTrue(tool.getAsJsonObject("annotations").get("destructiveHint")
                        .getAsBoolean());
        }
    }

    @Test
    void oldHandshakeAndMissingClientMetadataAreRejected() {
        McpHandler handler = new McpHandler(null, false);
        var old = assertThrows(RpcServer.RpcError.class, () -> handler.handle("initialize",
                new JsonObject(), Cancellation.NONE));
        assertEquals(-32022, old.code);
        JsonObject metadata = meta();
        metadata.remove("io.modelcontextprotocol/clientCapabilities");
        var invalid = assertThrows(RpcServer.RpcError.class, () -> handler.handle("server/discover",
                Json.object("_meta", metadata), Cancellation.NONE));
        assertEquals(-32602, invalid.code);
    }
}
