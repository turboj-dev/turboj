package dev.javafmt.intellij;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.project.Project;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** One resident process per project; no source ever crosses a network boundary. */
public final class FormatterBackend implements Disposable {
    private static final Gson JSON = new Gson();
    private static final int MAX_RESPONSE_CHARS = 48 * 1024 * 1024;
    private final Project project;
    private final Map<String, CompletableFuture<JsonObject>> pending = new ConcurrentHashMap<>();
    private final Object writeLock = new Object();
    private volatile Process process;
    private volatile BufferedWriter input;
    private volatile boolean disposed;

    public FormatterBackend(Project project) { this.project = project; }

    public JsonObject format(String id, long version, String source, Integer start, Integer end,
                             AtomicBoolean cancelled) throws Exception {
        if (cancelled.get()) throw new java.util.concurrent.CancellationException();
        ensureStarted();
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        if (pending.size() >= 2) throw new IOException("javafmt is busy; retry formatting shortly.");
        pending.put(id, future);
        Map<String, Object> request = new HashMap<>();
        request.put("protocolVersion", 1);
        request.put("id", id);
        request.put("sourceVersion", version);
        request.put("source", source);
        request.put("lineEndings", "PRESERVE");
        if (start != null) request.put("range", Map.of("start", start, "end", end));
        try {
            if (cancelled.get()) throw new java.util.concurrent.CancellationException();
            send(request);
            if (cancelled.get()) cancel(id);
            JsonObject result = future.get(25, TimeUnit.SECONDS);
            if (!result.has("protocolVersion") || result.get("protocolVersion").getAsInt() != 1
                    || !result.has("id") || !id.equals(result.get("id").getAsString())) {
                throw new IOException("Invalid javafmt response identity.");
            }
            if (result.has("sourceVersion") && result.get("sourceVersion").getAsLong() != version) {
                throw new IOException("Stale javafmt response.");
            }
            if (result.has("success") && result.get("success").getAsBoolean() && !result.has("sourceVersion")) {
                throw new IOException("Missing javafmt source version.");
            }
            return result;
        } catch (java.util.concurrent.TimeoutException timeout) {
            cancel(id);
            throw new IOException("javafmt exceeded its 25-second timeout; source was not changed.", timeout);
        } finally {
            pending.remove(id);
        }
    }

    /** May be called from EDT: invalidate immediately, queue pipe IO off EDT. */
    public void cancel(String id) {
        CompletableFuture<JsonObject> future = pending.remove(id);
        if (future != null) future.cancel(false);
        Thread.ofVirtual().name("javafmt-cancel").start(() -> {
            try { send(Map.of("protocolVersion", 1, "op", "cancel", "id", id)); }
            catch (IOException ignored) { /* A terminated backend is already cancelled. */ }
        });
    }

    private synchronized void ensureStarted() throws IOException {
        if (disposed) throw new IOException("javafmt backend is closed.");
        if (process != null && process.isAlive()) return;
        var state = JavafmtSettings.get(project).getState();
        Path jar = state.engineJar.isBlank()
                ? PluginManagerCore.getPlugin(PluginId.getId("dev.javafmt.intellij"))
                    .getPluginPath().resolve("engine/javafmt.jar")
                : Path.of(state.engineJar);
        if (!Files.isRegularFile(jar)) throw new IOException("javafmt engine JAR not found: " + jar);
        Process child = new ProcessBuilder(state.javaExecutable, "-Xmx512m", "-jar",
                jar.toAbsolutePath().toString(), "--server").start();
        process = child;
        input = new BufferedWriter(new OutputStreamWriter(child.getOutputStream(), StandardCharsets.UTF_8));
        Thread.ofVirtual().name("javafmt-stderr").start(() -> {
            try (var stderr = child.getErrorStream()) { stderr.transferTo(java.io.OutputStream.nullOutputStream()); }
            catch (IOException ignored) { }
        });
        Thread.ofVirtual().name("javafmt-reader").start(() -> readResponses(child));
    }

    private void readResponses(Process child) {
        try (var reader = new BufferedReader(new InputStreamReader(child.getInputStream(), StandardCharsets.UTF_8))) {
            while (true) {
                String line = boundedLine(reader);
                if (line == null) break;
                JsonObject response = JSON.fromJson(line, JsonObject.class);
                if (response == null || !response.has("id") || response.get("id").isJsonNull()) continue;
                CompletableFuture<JsonObject> future = pending.get(response.get("id").getAsString());
                if (future != null) future.complete(response);
            }
        } catch (Exception failure) {
            if (process == child) failPending(new IOException("Invalid javafmt engine response.", failure));
        } finally {
            if (process == child) failPending(new IOException("javafmt stopped. Configure a Java 25 executable in Settings → Tools → javafmt."));
            child.destroy();
        }
    }

    private static String boundedLine(BufferedReader reader) throws IOException {
        StringBuilder line = new StringBuilder();
        int c;
        while ((c = reader.read()) != -1 && c != '\n') {
            if (line.length() >= MAX_RESPONSE_CHARS) throw new IOException("Oversized javafmt response.");
            line.append((char) c);
        }
        return c == -1 && line.isEmpty() ? null : line.toString();
    }

    private void send(Map<String, ?> message) throws IOException {
        synchronized (writeLock) {
            BufferedWriter writer = input;
            if (writer == null) throw new IOException("javafmt engine is not running.");
            writer.write(JSON.toJson(message));
            writer.newLine();
            writer.flush();
        }
    }

    private void failPending(Exception failure) {
        pending.values().forEach(future -> future.completeExceptionally(failure));
        pending.clear();
    }

    public synchronized long processId() { return process == null ? -1 : process.pid(); }

    public synchronized void reset() {
        failPending(new IOException("javafmt settings changed."));
        if (process != null) process.destroy();
        process = null;
        input = null;
    }

    @Override public synchronized void dispose() { disposed = true; reset(); }
}
