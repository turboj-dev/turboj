package dev.turboj.refactorj.cli;

import com.google.gson.*;
import dev.turboj.refactorj.api.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ProtocolTest {
    @Test
    void nestedInterfaceUsageLocationsAreRootRelativeAndCannotEscape() {
        Path root = Path.of("/tmp/project");
        var wire = Json.object("operation", "introduce-interface", "location", Json.object("file", "A.java", "start", 1),
                "interfaceName", "Runnable", "usages", List.of(Json.object("file", "sub/Caffè.java", "start", 3)));
        var request = (Requests.IntroduceInterface) Json.request(wire, root);
        assertEquals(root.resolve("sub/Caffè.java"), request.usages().getFirst().file());
        assertEquals(3, request.usages().getFirst().end());
        wire.getAsJsonArray("usages").get(0).getAsJsonObject().addProperty("file", "../outside.java");
        assertThrows(IllegalArgumentException.class, () -> Json.request(wire, root));
    }

    @Test
    void locationsAndFlagsRejectLossyOrCoercedJsonValues() {
        Path root = Path.of("/tmp/project");
        for (String offset : List.of("1.5", "2147483648", "-2147483649")) {
            var wire = Json.GSON.fromJson("{\"operation\":\"rename\",\"name\":\"name\",\"location\":{\"file\":\"A.java\",\"start\":" + offset + "}}", JsonObject.class);
            assertThrows(IllegalArgumentException.class, () -> Json.request(wire, root));
        }
        var wire = Json.object("operation", "rename", "name", "name", "hierarchy", "false", "location", Json.object("file", "A.java", "start", 0));
        assertThrows(IllegalArgumentException.class, () -> Json.request(wire, root));
        assertThrows(IllegalArgumentException.class, () -> Json.bool(Json.object("dryRun", "false"), "dryRun", true));
        assertTrue(Json.bool(new JsonObject(), "dryRun", true));
    }

    @Test
    void roundTripsEveryTypedOperationWithUnicodePaths() {
        Path root = Path.of("/tmp/project");

        for (Operation operation : Json.typedOperations()) {
            JsonObject wire = Json.object("operation", operation.id(), "location", Json
                    .object("file", "Caffè.java", "start", 3));
            var request = Json.request(wire, root);
            assertEquals(operation, request.operation());
            assertEquals(root.resolve("Caffè.java"), request.location().file());
            assertEquals(request, Json.request(Json.request(request), root));
        }
        assertThrows(IllegalArgumentException.class, () -> Json.request(Json.object("operation",
                "rename", "location", Json.object("file", "../secret.java", "start", 0)), root));
        assertThrows(IllegalArgumentException.class, () -> Json.request(Json.object("operation",
                "rename", "arbitrary", true, "location", Json.object("file", "A.java", "start", 0)),
                root));
    }

    @Test
    void contextualClassCreationIsNotAdvertisedAsATypedCliRequest() {
        Path root = Path.of("/tmp/project");
        var failure = assertThrows(IllegalArgumentException.class, () -> Json.request(
                Json.object("operation", "create-class", "location", Json.object("file", "A.java", "start", 0)), root));
        assertTrue(failure.getMessage().contains("contextual JavaQuickActions proposal"));
        assertThrows(IllegalArgumentException.class, () -> Json.requestClass(Operation.CREATE_CLASS));
        var advertised = new HashSet<String>();
        for (JsonElement entry : Json.catalog()) advertised.add(entry.getAsJsonObject().get("operation").getAsString());
        var typed = new HashSet<String>();
        for (Operation operation : Operation.values()) if (operation != Operation.CREATE_CLASS) typed.add(operation.id());
        assertEquals(typed, advertised);
        assertEquals(typed, new HashSet<>(Json.typedOperations().stream().map(Operation::id).toList()));
        assertFalse(advertised.contains("create-class"));
    }

    @Test
    void utf16AndCrlfPositionsRoundTrip() {
        String text = "class A {\r\nString x = \"😀\";\r\n}";
        int offset = Json.offset(text, 1, 14);
        assertEquals(1, Json.position(text, offset).get("line").getAsInt());
        assertThrows(IllegalArgumentException.class, () -> Json.offset(text, 1, 13));
        assertThrows(IllegalArgumentException.class, () -> Json.offset(text, 5, 0));
        assertThrows(IllegalArgumentException.class, () -> Json.offset(text, 0, 12));
    }

    @Test
    void jsonLinesProtocolProcessesErrorsWithoutBreakingNextRequest() throws Exception {
        String input = "garbage\n{\"jsonrpc\":12,\"method\":true}\n{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"echo\",\"params\":{\"text\":\"caffè 😀\"}}\n";
        var output = new ByteArrayOutputStream();
        new RpcServer(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), output,
                RpcServer.Framing.JSON_LINES, (method, params, cancel) -> params).run();
        var lines = output.toString(StandardCharsets.UTF_8).lines().map(s -> Json.GSON.fromJson(s,
                JsonObject.class)).toList();
        assertEquals(3, lines.size());
        assertEquals(-32700, lines.get(0).getAsJsonObject("error").get("code").getAsInt());
        assertEquals(-32600, lines.get(1).getAsJsonObject("error").get("code").getAsInt());
        assertEquals("caffè 😀", lines.get(2).getAsJsonObject("result").get("text").getAsString());
    }

    @Test
    void contentLengthUsesUtf8BytesAndHandlesNotifications() throws Exception {
        var input = new ByteArrayOutputStream();

        for (JsonObject value : List.of(Json.object("jsonrpc", "2.0", "method", "notify"), Json
                .object("jsonrpc", "2.0", "id", "😀", "method", "echo", "params", Json
                .object("text", "é")))) {
            byte[] bytes = Json.GSON.toJson(value).getBytes(StandardCharsets.UTF_8);
            input.write(("Content-Length: " + bytes.length + "\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            input.write(bytes);
        }
        var output = new ByteArrayOutputStream();
        var methods = new ArrayList<String> ();
        new RpcServer(new ByteArrayInputStream(input.toByteArray()), output,
                RpcServer.Framing.CONTENT_LENGTH, (method, params, cancel) -> {
            methods.add(method);
            return params;
        }).run();
        assertEquals(List.of("notify", "echo"), methods);
        String response = output.toString(StandardCharsets.UTF_8);
        int boundary = response.indexOf("\r\n\r\n");
        int length = Integer.parseInt(response.substring("Content-Length: ".length(), boundary));
        assertEquals(response.substring(boundary + 4).getBytes(StandardCharsets.UTF_8).length,
                length);
        assertEquals("é", Json.GSON.fromJson(response.substring(boundary + 4), JsonObject.class)
                .getAsJsonObject("result").get("text").getAsString());
    }

    @Test
    void cancellationIsReadWhileSemanticWorkerRuns() throws Exception {
        String input = Json.GSON.toJson(Json.object("jsonrpc", "2.0", "id", 1, "method", "slow"))
                + "\n" + Json.GSON.toJson(Json.object("jsonrpc", "2.0", "method", "$/cancelRequest",
                "params", Json.object("id", 1))) + "\n";
        var output = new ByteArrayOutputStream();
        new RpcServer(new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)), output,
                RpcServer.Framing.JSON_LINES, (method, params, cancel) -> {
            long until = System.nanoTime() + 5_000_000_000L;

            while (!cancel.cancelled() && System.nanoTime() < until)
                Thread.onSpinWait();
            cancel.check();
            return JsonNull.INSTANCE;
        }).run();
        assertEquals(-32800, Json.GSON.fromJson(output.toString(StandardCharsets.UTF_8),
                JsonObject.class).getAsJsonObject("error").get("code").getAsInt());
    }

    @Test
    void rejectsTruncatedOrDuplicateLengthAndOversizedFrames() {
        for (String input : List.of("Content-Length: 20\r\n\r\n{}",
                "Content-Length: 2\r\nContent-Length: 2\r\n\r\n{}",
                "Content-Length: 999999999\r\n\r\n")) {
            assertThrows(IOException.class, () -> new RpcServer(new ByteArrayInputStream(input
                    .getBytes(StandardCharsets.US_ASCII)), new ByteArrayOutputStream(),
                    RpcServer.Framing.CONTENT_LENGTH, (m, p, c) -> p).run());
        }
    }

    @Test
    void cliHelpAndInvalidOptionsUseStableExitCodes() {
        var output = new ByteArrayOutputStream();
        var stream = new PrintStream(output);
        assertEquals(0, Main.run(new String[] {"--help"}, stream, stream));
        assertTrue(output.toString().contains("preview"));
        assertEquals(2, Main.run(new String[] {"rename", "--nonexistent"}, stream, stream));
    }
}
