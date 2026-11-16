package dev.turboj.testing.intelligence;

import com.google.gson.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Compiler and instrumented-process evidence attached to one source collection, never per-test attribution. */
public record CoverageProof(String sourceHash, String compilationId, String compilerIdentity,
        String classIdentity, String processId, String sessionId, String collectionId,
        Map<Path,String> classFiles) {
    public CoverageProof { classFiles = Map.copyOf(classFiles); }

    public static CoverageProof read(JsonObject value, Path root) throws IOException {
        if (!"1".equals(string(value,"schemaVersion"))) throw new IOException("Unsupported coverage provenance schema");
        for (String key : List.of("compilationId","compilerIdentity","processId","sessionId","collectionId","executionHashes"))
            if (string(value,key).isBlank() || string(value,key).length() > 16384) throw new IOException("Invalid provenance " + key);
        try {
            UUID.fromString(string(value,"collectionId"));
            for (String id : string(value,"compilationId").split(",",-1)) UUID.fromString(id);
            Set<String> sessions = new HashSet<>();
            for (String session : string(value,"sessionId").split(",",-1)) {
                String prefix = "cherry:" + string(value,"collectionId") + ":";
                if (!session.startsWith(prefix) || !sessions.add(session)) throw new IllegalArgumentException("Session identity");
                UUID.fromString(session.substring(prefix.length()));
            }
            String[] processes = string(value,"processId").split(",",-1);
            if (processes.length != sessions.size()) throw new IllegalArgumentException("Process/session inventory");
            for (String process : processes) {
                int separator = process.indexOf('@');
                if (separator < 1 || Long.parseLong(process.substring(0,separator)) <= 0)
                    throw new IllegalArgumentException("Process identity");
                java.time.Instant.parse(process.substring(separator + 1));
            }
            for (String hash : string(value,"executionHashes").split(",",-1))
                if (!hash.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("Execution digest");
        } catch (RuntimeException invalid) { throw new IOException("Malformed compiler/process provenance",invalid); }
        String sourceHash = digest(value,"sourceHash"), classesHash = digest(value,"classIdentity");
        Map<Path,String> classes = new LinkedHashMap<>();
        JsonArray files = value.getAsJsonArray("classFiles");
        if (files == null || files.isEmpty() || files.size() > 4096) throw new IOException("Invalid provenance class inventory");
        for (JsonElement entry : files) {
            JsonObject file = entry.getAsJsonObject();
            Path path = root.resolve(string(file,"path")).toAbsolutePath().normalize();
            if (!path.startsWith(root) || classes.putIfAbsent(path,digest(file,"sha256")) != null)
                throw new IOException("Invalid or duplicate provenance class path");
        }
        return new CoverageProof(sourceHash,string(value,"compilationId"),string(value,"compilerIdentity"),classesHash,
                string(value,"processId"),string(value,"sessionId"),string(value,"collectionId"),classes);
    }

    /** Invoke on a worker, once per distinct proof per snapshot, never in paint or on the EDT. */
    public boolean matchesClasses(Path root) {
        try {
            Path realRoot = root.toRealPath();
            for (var entry : classFiles.entrySet()) {
                Path path = entry.getKey();
                if (!path.toRealPath().startsWith(realRoot) || Files.size(path) > 16L * 1024 * 1024) return false;
                MessageDigest digest = MessageDigest.getInstance("SHA-256");
                try (InputStream input = Files.newInputStream(path)) {
                    byte[] buffer = new byte[8192]; long total = 0;
                    for (int count; (count = input.read(buffer)) >= 0;) {
                        total += count; if (total > 16L * 1024 * 1024) return false;
                        digest.update(buffer,0,count);
                    }
                }
                if (!HexFormat.of().formatHex(digest.digest()).equals(entry.getValue())) return false;
            }
            return true;
        } catch (IOException | NoSuchAlgorithmException invalid) { return false; }
    }

    private static String digest(JsonObject value,String key) throws IOException {
        String text = string(value,key);
        if (!text.matches("[a-f0-9]{64}")) throw new IOException("Invalid provenance digest " + key);
        return text;
    }
    private static String string(JsonObject value,String key) {
        JsonElement field = value.get(key);
        return field == null || field.isJsonNull() ? "" : field.getAsString();
    }
}
