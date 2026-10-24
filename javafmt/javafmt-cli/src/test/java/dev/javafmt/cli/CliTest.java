package dev.javafmt.cli;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.javafmt.core.TextEdit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.List;
import java.util.Set;

class CliTest {
    @TempDir Path directory;
    private static final String SOURCE = "class C{int x=1;}";
    private static final String EXPECTED = "class C {\n    int x = 1;\n}\n";

    private record Result(int status, String out, String err) {}

    private Result run(String input, String... args) {
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        int status =
                Main.run(
                        args,
                        new ByteArrayInputStream(input.getBytes(StandardCharsets.UTF_8)),
                        out,
                        err);
        return new Result(
                status, out.toString(StandardCharsets.UTF_8), err.toString(StandardCharsets.UTF_8));
    }

    @Test
    void stdinPrintsCanonicalSourceAndDoesNotMixDiagnosticsIntoIt() {
        Result result = run(SOURCE, "--stdin");
        assertEquals(0, result.status, result.err);
        assertEquals(EXPECTED, result.out);
        assertEquals("", result.err);
        Result invalid = run("class {", "--stdin");
        assertEquals(2, invalid.status);
        assertEquals("", invalid.out);
        assertFalse(invalid.err.isBlank());
    }

    @Test
    void checkIsReadOnlyAndWriteIsIdempotentWithStableExitCodes() throws Exception {
        Path file = directory.resolve("C.java");
        Files.writeString(file, SOURCE);
        assertEquals(1, run("", "--check", file.toString()).status);
        assertEquals(SOURCE, Files.readString(file));
        Result write = run("", "-w", file.toString());
        assertEquals(0, write.status, write.err);
        assertEquals(EXPECTED, Files.readString(file));
        var modified = Files.getLastModifiedTime(file);
        assertEquals(0, run("", "-w", file.toString()).status);
        assertEquals(
                modified, Files.getLastModifiedTime(file), "No-op write must not replace the file");
        assertEquals(0, run("", "--check", file.toString()).status);
    }

    @Test
    void directoryTraversalIsSortedSkipsBuildDirectoriesAndDoesNotFollowLinks() throws Exception {
        Files.writeString(directory.resolve("Z.java"), "class Z{}");
        Files.writeString(directory.resolve("A.java"), "class A{}");
        Files.writeString(directory.resolve(".Hidden.java"), "invalid");
        for (String skipped : List.of("target", "build", "out", "node_modules", ".git")) {
            Path child = Files.createDirectory(directory.resolve(skipped));
            Files.writeString(child.resolve("Bad.java"), "not Java");
        }
        Files.createSymbolicLink(directory.resolve("Linked.java"), directory.resolve("Z.java"));
        Result result = run("", "--check", directory.toString());
        assertEquals(1, result.status, result.err);
        assertEquals(
                List.of(
                        directory.resolve("A.java").toString(),
                        directory.resolve("Z.java").toString()),
                result.out.lines().toList());
        assertEquals(2, run("", directory.toString()).status);
        assertEquals(2, run("", "-w", directory.resolve("Linked.java").toString()).status);
    }

    @Test
    void malformedUtf8AndSyntaxNeverOverwriteInput() throws Exception {
        Path malformed = directory.resolve("Malformed.java");
        byte[] bytes = {(byte) 0xc3, (byte) 0x28};
        Files.write(malformed, bytes);
        assertEquals(2, run("", "-w", malformed.toString()).status);
        assertArrayEquals(bytes, Files.readAllBytes(malformed));
        Files.writeString(malformed, "class {");
        assertEquals(2, run("", "-w", malformed.toString()).status);
        assertEquals("class {", Files.readString(malformed));
    }

    @Test
    void safeReplacementPreservesPermissionsAndRejectsExternalChanges() throws Exception {
        Path file = directory.resolve("C.java");
        Files.writeString(file, SOURCE);
        Set<PosixFilePermission> permissions =
                Set.of(
                        PosixFilePermission.OWNER_READ,
                        PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.GROUP_READ);
        Files.setPosixFilePermissions(file, permissions);
        SafeFiles.Snapshot stale = SafeFiles.read(file);
        Files.writeString(file, "class External {}\n");
        assertThrows(java.io.IOException.class, () -> SafeFiles.write(stale, EXPECTED));
        assertEquals("class External {}\n", Files.readString(file));
        Files.writeString(file, SOURCE);
        assertEquals(0, run("", "-w", file.toString()).status);
        assertEquals(permissions, Files.getPosixFilePermissions(file));
        try (var children = Files.list(directory)) {
            assertFalse(children.anyMatch(p -> p.getFileName().toString().startsWith(".javafmt-")));
        }
    }

    @Test
    void diffIsUnifiedAndDoesNotWrite() throws Exception {
        Path file = directory.resolve("C.java");
        Files.writeString(file, SOURCE);
        Result result = run("", "--diff", file.toString());
        assertEquals(0, result.status, result.err);
        assertTrue(result.out.startsWith("--- a/"));
        assertTrue(result.out.contains("@@ -1,1 +1,3 @@\n"));
        assertTrue(result.out.contains("-class C{int x=1;}\n\\ No newline at end of file\n"));
        assertTrue(result.out.contains("+    int x = 1;\n"));
        assertEquals(SOURCE, Files.readString(file));
        assertEquals("", UnifiedDiff.create("C.java", EXPECTED, EXPECTED));
        assertTrue(UnifiedDiff.create("empty.java", "", "\n").contains("@@ -0,0 +1,1 @@"));
        assertTrue(
                UnifiedDiff.create("line.java", "a", "a\n")
                        .contains("-a\n\\ No newline at end of file\n+a\n"));
    }

    @Test
    void multipleFilesRequireAnUnambiguousModeAndUnknownFlagsFail() throws Exception {
        Path a = Files.writeString(directory.resolve("A.java"), "class A{}");
        Path b = Files.writeString(directory.resolve("B.java"), "class B{}");
        assertEquals(2, run("", a.toString(), b.toString()).status);
        for (String[] args :
                List.of(
                        new String[] {"--tabs"},
                        new String[] {"-w", "--stdin"},
                        new String[] {"--check", "--diff", a.toString()},
                        new String[] {"--server", "--stdin"}))
            assertEquals(2, run("", args).status);
        assertEquals(0, run("", "--help").status);
        assertTrue(run("", "--version").out.contains("Style specification: JavaFMT 1"));
    }

    @Test
    void residentServerReturnsVersionedMinimalEditsAndContinuesAfterMalformedRequest() {
        JsonObject request = new JsonObject();
        request.addProperty("protocolVersion", 1);
        request.addProperty("id", "document-7");
        request.addProperty("source", SOURCE);
        request.addProperty("sourceVersion", 7);
        Result result = run("{broken}\n" + request + "\n", "--server");
        assertEquals(0, result.status, result.err);
        List<JsonObject> messages =
                result.out
                        .lines()
                        .map(line -> JsonParser.parseString(line).getAsJsonObject())
                        .toList();
        assertEquals(2, messages.size());
        JsonObject formatted =
                messages.stream().filter(v -> v.has("sourceVersion")).findFirst().orElseThrow();
        assertEquals("document-7", formatted.get("id").getAsString());
        assertEquals(7, formatted.get("sourceVersion").getAsLong());
        assertTrue(formatted.get("success").getAsBoolean());
        assertEquals(EXPECTED, formatted.get("formattedText").getAsString());
        var edits =
                formatted.getAsJsonArray("edits").asList().stream()
                        .map(v -> v.getAsJsonObject())
                        .map(
                                e ->
                                        new TextEdit(
                                                e.get("start").getAsInt(),
                                                e.get("end").getAsInt(),
                                                e.get("replacement").getAsString()))
                        .toList();
        assertEquals(EXPECTED, TextEdit.apply(SOURCE, edits));
        assertFalse(edits.isEmpty());
    }

    @Test
    void residentProtocolRejectsFractionalOffsetsAndAcknowledgesCancellation() {
        Result invalid =
                run(
                        "{\"protocolVersion\":1.5,\"id\":\"fractional\",\"source\":\"class"
                            + " X{}\"}\n",
                        "--server");
        assertEquals(0, invalid.status);
        assertTrue(invalid.out.contains("invalid-request"));
        Result cancel =
                run("{\"protocolVersion\":1,\"op\":\"cancel\",\"id\":\"gone\"}\n", "--server");
        JsonObject response = JsonParser.parseString(cancel.out.strip()).getAsJsonObject();
        assertTrue(response.get("cancelled").getAsBoolean());
        assertFalse(response.get("found").getAsBoolean());
    }
}
