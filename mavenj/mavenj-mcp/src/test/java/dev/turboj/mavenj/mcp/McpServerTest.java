package dev.turboj.mavenj.mcp;

import com.google.gson.*;
import dev.turboj.mavenj.api.Cancellation;
import dev.turboj.mavenj.api.MavenModels.ImportRequest;
import dev.turboj.mavenj.cli.Json;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class McpServerTest {
    @TempDir
    Path root;
    private static final String POM = "<project><modelVersion>4.0.0</modelVersion><groupId>example</groupId><artifactId>app</artifactId><version>1</version><properties><maven.compiler.release>21</maven.compiler.release></properties></project>";

    private JsonObject meta() {
        return Json.object("io.modelcontextprotocol/protocolVersion", McpServer.VERSION,
                "io.modelcontextprotocol/clientInfo", Json.object("name", "test", "version", "1"),
                "io.modelcontextprotocol/clientCapabilities", new JsonObject());
    }

    private JsonObject params(String tool, Object args) {
        return Json.object("_meta", meta(), "name", tool, "arguments", args);
    }

    private McpServer server(boolean write) throws Exception {
        Files.writeString(root.resolve("pom.xml"), POM);
        return new McpServer(ImportRequest.offline(root), write);
    }

    @Test
    void exercisesActualJsonLinesDiscoveryVersionErrorsAndInspection() throws Exception {
        try (var server = server(false)) {
            String input = Json.string(Json.object("jsonrpc", "2.0", "id", 1, "method",
                    "server/discover", "params", Json.object("_meta", meta()))) + "\n" + Json
                    .string(Json.object("jsonrpc", "2.0", "id", 2, "method", "initialize", "params",
                    new JsonObject())) + "\n" + Json.string(Json.object("jsonrpc", "2.0", "id", 3,
                    "method", "tools/call", "params", params("mavenj_inspect", new JsonObject())))
                    + "\n";
            var output = new ByteArrayOutputStream();
            server.serve(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), output);
            var messages = output.toString(StandardCharsets.UTF_8).lines()
                    .map(JsonParser::parseString).map(JsonElement::getAsJsonObject).toList();
            assertEquals(3, messages.size());
            assertEquals(McpServer.VERSION, messages.get(0).getAsJsonObject("result")
                    .getAsJsonArray("supportedVersions").get(0).getAsString());
            assertEquals(-32022, messages.get(1).getAsJsonObject("error").get("code").getAsInt());
            var result = messages.get(2).getAsJsonObject("result");
            assertFalse(result.get("isError").getAsBoolean(), result.toString());
            assertTrue(result.getAsJsonObject("structuredContent").getAsJsonObject("result")
                    .get("modules").isJsonArray());
        }
    }

    @Test
    void previewFirstWritesNeedServerPermissionAndExactAuthorizationAndRejectStalePlans() throws Exception {
        try (var server = server(true)) {
            var prepared = server.handle("tools/call", params("mavenj_prepare_pom_edit", Json
                    .object("operation", "add-module", "module", "child")), Cancellation.NONE)
                    .getAsJsonObject();
            var plan = prepared.getAsJsonObject("structuredContent").getAsJsonObject("result");
            String id = plan.get("planId").getAsString();
            assertEquals(POM, Files.readString(root.resolve("pom.xml")));
            var dry = server.handle("tools/call", params("mavenj_apply_pom_edit", Json
                    .object("planId", id)), Cancellation.NONE).getAsJsonObject();
            assertFalse(dry.get("isError").getAsBoolean());
            assertEquals(POM, Files.readString(root.resolve("pom.xml")));
            var denied = server.handle("tools/call", params("mavenj_apply_pom_edit", Json
                    .object("planId", id, "dryRun", false)), Cancellation.NONE).getAsJsonObject();
            assertTrue(denied.get("isError").getAsBoolean());
            var applied = server.handle("tools/call", params("mavenj_apply_pom_edit", Json
                    .object("planId", id, "dryRun", false, "confirmation", "APPLY " + id)),
                    Cancellation.NONE).getAsJsonObject();
            assertFalse(applied.get("isError").getAsBoolean(), applied.toString());
            assertTrue(Files.readString(root.resolve("pom.xml"))
                    .contains("<module>child</module>"));
            prepared = server.handle("tools/call", params("mavenj_prepare_pom_edit", Json
                    .object("operation", "add-module", "module", "second")), Cancellation.NONE)
                    .getAsJsonObject();
            id = prepared.getAsJsonObject("structuredContent").getAsJsonObject("result")
                    .get("planId").getAsString();
            Files.writeString(root.resolve("pom.xml"), "<!-- external change -->" + Files
                    .readString(root.resolve("pom.xml")));
            assertTrue(server.handle("tools/call", params("mavenj_apply_pom_edit", Json
                    .object("planId", id, "dryRun", false, "confirmation", "APPLY " + id)),
                    Cancellation.NONE).getAsJsonObject().get("isError").getAsBoolean());
        }
    }

    @Test
    void readonlyServerAndRootBoundariesCannotBeBypassedByArguments() throws Exception {
        try (var server = server(false)) {
            var escape = server.handle("tools/call", params("mavenj_prepare_pom_edit", Json
                    .object("file", "../pom.xml", "operation", "add-module", "module", "child")),
                    Cancellation.NONE).getAsJsonObject();
            assertTrue(escape.get("isError").getAsBoolean());
            var prepared = server.handle("tools/call", params("mavenj_prepare_pom_edit", Json
                    .object("operation", "add-module", "module", "child")), Cancellation.NONE)
                    .getAsJsonObject();
            String id = prepared.getAsJsonObject("structuredContent").getAsJsonObject("result")
                    .get("planId").getAsString();
            var denied = server.handle("tools/call", params("mavenj_apply_pom_edit", Json
                    .object("planId", id, "dryRun", false, "confirmation", "APPLY " + id)),
                    Cancellation.NONE).getAsJsonObject();
            assertTrue(denied.get("isError").getAsBoolean());
            assertEquals(POM, Files.readString(root.resolve("pom.xml")));
            var list = server.handle("tools/list", Json.object("_meta", meta()), Cancellation.NONE)
                    .getAsJsonObject().getAsJsonArray("tools");
            assertEquals(11, list.size());
            assertTrue(list.asList().stream().noneMatch(t -> t.getAsJsonObject().get("name")
                    .getAsString().contains("goal")));
        }
    }

    @Test
    void inspectionCannotExposeParentPomOutsideConfiguredRoot() throws Exception {
        Path allowed = Files.createDirectories(root.resolve("allowed")), privateDirectory = Files
                .createDirectories(root.resolve("private"));
        Files.writeString(privateDirectory.resolve("pom.xml"),
                "<project><modelVersion>4.0.0</modelVersion><groupId>private</groupId><artifactId>parent</artifactId><version>1</version><packaging>pom</packaging><properties><privateValue>CONFIDENTIAL_MARKER</privateValue></properties></project>");
        Files.writeString(allowed.resolve("pom.xml"),
                "<project><modelVersion>4.0.0</modelVersion><parent><groupId>private</groupId><artifactId>parent</artifactId><version>1</version><relativePath>../private/pom.xml</relativePath></parent><artifactId>child</artifactId></project>");
        try (var server = new McpServer(ImportRequest.offline(allowed), false)) {
            String input = Json.string(Json.object("jsonrpc", "2.0", "id", 1, "method",
                    "tools/call", "params", params("mavenj_inspect", new JsonObject()))) + "\n";
            var output = new ByteArrayOutputStream();
            server.serve(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), output);
            String result = output.toString(StandardCharsets.UTF_8);
            assertFalse(result.contains("CONFIDENTIAL_MARKER"));
            assertTrue(result.contains("ERROR"), result);
        }
    }
}
