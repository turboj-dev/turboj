package dev.turboj.refactorj.lsp;

import com.google.gson.*;
import dev.turboj.refactorj.cli.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class LspIntegrationIT {
    @TempDir
    Path temporary;

    @Test
    void realWireRenameUsesUnsavedVersionedBufferAndNeverWritesDisk() throws Exception {
        Path root = temporary.toRealPath(), file = root.resolve("A.java");
        String disk = "class A {}\n",
                unsaved = "class A { int run(){ int total = 1; return total; } }\n";
        Files.writeString(file, disk);
        String uri = file.toUri().toString();
        var input = new ByteArrayOutputStream();
        List<JsonObject> requests = List.of(Json.object("jsonrpc", "2.0", "id", 1, "method",
                "initialize", "params", Json.object("rootUri", root.toUri().toString(),
                "capabilities", new JsonObject())), Json.object("jsonrpc", "2.0", "method",
                "initialized"), Json.object("jsonrpc", "2.0", "method", "textDocument/didOpen",
                "params", Json.object("textDocument", Json.object("uri", uri, "languageId", "java",
                "version", 7, "text", unsaved))), Json.object("jsonrpc", "2.0", "id", 2, "method",
                "textDocument/rename", "params", Json.object("textDocument", Json.object("uri",
                uri), "position", Json.position(unsaved, unsaved.indexOf("total")), "newName",
                "sum")), Json.object("jsonrpc", "2.0", "id", 3, "method",
                "textDocument/documentSymbol", "params", Json.object("textDocument", Json
                .object("uri", uri))), Json.object("jsonrpc", "2.0", "id", 4, "method",
                "shutdown"));

        for (JsonObject request : requests) {
            byte[] bytes = Json.GSON.toJson(request).getBytes(StandardCharsets.UTF_8);
            input.write(("Content-Length: " + bytes.length + "\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            input.write(bytes);
        }
        var output = new ByteArrayOutputStream();

        try (var handler = new LspHandler(root, List.of(root), List.of())) {
            new RpcServer(new ByteArrayInputStream(input.toByteArray()), output,
                    RpcServer.Framing.CONTENT_LENGTH, handler).run();
        }
        List<JsonObject> responses = read(output.toByteArray());
        assertEquals(4, responses.size(), output.toString());
        JsonObject rename = responses.get(1);
        assertFalse(rename.has("error"), rename.toString());
        JsonObject document = rename.getAsJsonObject("result").getAsJsonArray("documentChanges")
                .get(0).getAsJsonObject();
        assertEquals(7, document.getAsJsonObject("textDocument").get("version").getAsInt());
        assertEquals(2, document.getAsJsonArray("edits").size());
        assertEquals("sum", document.getAsJsonArray("edits").get(0).getAsJsonObject().get("newText")
                .getAsString());
        assertFalse(responses.get(2).getAsJsonArray("result").isEmpty());
        assertEquals(disk, Files.readString(file));
    }

    private static List<JsonObject> read(byte[] wire) throws IOException {
        var input = new ByteArrayInputStream(wire);
        List<JsonObject> result = new ArrayList<> ();

        while (input.available() > 0) {
            var header = new ByteArrayOutputStream();
            int state = 0;

            while (state < 4) {
                int c = input.read();

                if (c < 0)
                    throw new EOFException();
                header.write(c);
                state = switch (state) {
                    case 0 -> c == '\r' ? 1 : 0;
                    case 1 -> c == '\n' ? 2 : 0;
                    case 2 -> c == '\r' ? 3 : 0;
                    default -> c == '\n' ? 4 : 0;
                };
            }
            int length = Integer.parseInt(header.toString(StandardCharsets.US_ASCII).substring(16)
                    .trim());
            result.add(Json.GSON.fromJson(new String(input.readNBytes(length),
                    StandardCharsets.UTF_8), JsonObject.class));
        }
        return result;
    }
}
