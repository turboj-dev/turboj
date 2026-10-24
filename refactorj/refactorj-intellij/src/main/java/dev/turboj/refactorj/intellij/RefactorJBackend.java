package dev.turboj.refactorj.intellij;

import com.google.gson.*;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.project.Project;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Compatible with IDEA's Java 21 runtime; one Java 25 process per project. */
public final class RefactorJBackend implements Disposable {
    public static final Gson JSON = new Gson();

    public record Source(String file, String text, long stamp) {}

    private final Project project;
    private final Map<Integer, CompletableFuture<JsonElement>> pending = new ConcurrentHashMap<> ();
    private final Map<String, Source> opened = new ConcurrentHashMap<> ();
    private final Map<String, Integer> versions = new ConcurrentHashMap<> ();
    private final AtomicInteger sequence = new AtomicInteger();
    private final Object writeLock = new Object();
    private volatile Process process;
    private volatile OutputStream output;
    private volatile boolean disposed;
    private volatile NativeAccess nativeAccess;
    private volatile AtomicBoolean activeCancellation;
    private final Object operationLock = new Object();

    public RefactorJBackend(Project project) {
        this.project = project;
    }

    public static JsonObject object(Object... values) {
        JsonObject result = new JsonObject();

        for (int i = 0; i < values.length; i += 2)
            result.add((String) values[i], JSON.toJsonTree(values[i + 1]));
        return result;
    }

    public long processId() {
        return nativeAccess != null ? ProcessHandle.current().pid() : process == null ? -1 : process.pid();
    }
    public boolean embedded() { return nativeAccess != null; }

    public JsonElement execute(String command, JsonElement argument, List<Source> sources,
            AtomicBoolean cancelled) throws Exception {
        synchronized (operationLock) {
            activeCancellation = cancelled;
            try { return executeLocked(command, argument, sources, cancelled); }
            finally { activeCancellation = null; }
        }
    }

    private JsonElement executeLocked(String command, JsonElement argument, List<Source> sources,
            AtomicBoolean cancelled) throws Exception {
        ensureStarted(cancelled);
        NativeAccess embedded = nativeAccess;
        if (embedded != null) return embedded.execute(command, argument, sources, cancelled);
        Set<String> currentFiles = new HashSet<> ();

        for (Source source : sources)
            currentFiles.add(source.file());
        for (String previous : new ArrayList<> (opened.keySet()))
            if (!currentFiles.contains(previous)) {
                notify("textDocument/didClose", object("textDocument", object("uri", Path
                        .of(previous).toUri().toString())));
                opened.remove(previous);
                versions.remove(previous);
            }
        for (Source source : sources) {
            Source previous = opened.get(source.file());

            if (previous != null && previous.stamp() == source.stamp() && previous.text()
                    .equals(source.text()))
                continue;
            int version = versions.merge(source.file(), 1, Integer::sum);
            String uri = Path.of(source.file()).toUri().toString();

            if (previous == null)
                notify("textDocument/didOpen", object("textDocument", object("uri", uri,
                        "languageId", "java", "version", version, "text", source.text())));
            else
                notify("textDocument/didChange", object("textDocument", object("uri", uri,
                        "version", version), "contentChanges", List.of(object("text", source
                        .text()))));
            opened.put(source.file(), source);
        }
        return request("workspace/executeCommand", object("command", command, "arguments", argument
                == null ? List.of() : List.of(argument)), cancelled);
    }

    public JsonArray available(Source source, int start, int end, List<Source> sources,
            AtomicBoolean cancelled) throws Exception {
        synchronized (operationLock) {
            executeLocked("refactorj.catalog", null, sources, cancelled);
            if (nativeAccess != null) return nativeAccess.execute("refactorj.available", object("file", source.file(), "start", start, "end", end), sources, cancelled).getAsJsonArray();
            return request("textDocument/codeAction", object("textDocument", object("uri", Path
                    .of(source.file()).toUri().toString()), "range", object("start", position(source
                    .text(), start), "end", position(source.text(), end)), "context",
                    object("diagnostics", List.of())), cancelled).getAsJsonArray();
        }
    }

    private void ensureStarted(AtomicBoolean cancelled) throws Exception {
        if (disposed)
            throw new IOException("RefactorJ is closed");
        if (cancelled.get()) throw new CancellationException();
        if (nativeAccess != null) return;
        if (process != null && process.isAlive())
            return;
        if (project.getBasePath() == null)
            throw new IOException("Open a local project first");
        var settings = RefactorJSettings.get(project).getState();
        if (Runtime.version().feature() >= 25 && !settings.forceResident) {
            nativeAccess = new NativeAccess(project); return;
        }
        Path libraries = PluginManagerCore.getPlugin(PluginId
                .getId("dev.turboj.refactorj.intellij")).getPluginPath().resolve("engine/lib");
        List<String> args = new ArrayList<> (List.of(settings.javaExecutable, "-Xmx768m", "-cp",
                libraries.resolve("*").toString(), "dev.turboj.refactorj.lsp.Main", "--root",
                project.getBasePath()));

        if (!settings.sourceRoots.isBlank()) {
            args.add("--source-root");
            args.add(settings.sourceRoots);
        }
        if (!settings.classpath.isBlank()) {
            args.add("--classpath");
            args.add(settings.classpath);
        }
        Process child = new ProcessBuilder(args).start();
        process = child;
        output = child.getOutputStream();
        opened.clear();
        versions.clear();
        Thread.ofVirtual().name("refactorj-stderr").start(() -> {
            try (var stderr = child.getErrorStream()) {
                stderr.transferTo(OutputStream.nullOutputStream());
            } catch (IOException ignored) {}
        });
        Thread.ofVirtual().name("refactorj-reader").start(() -> read(child));
        request("initialize", object("rootUri", Path.of(project.getBasePath()).toUri().toString(),
                "capabilities", object("general", object("positionEncodings", List.of("utf-16")))),
                cancelled);
        notify("initialized", new JsonObject());
    }

    private JsonElement request(String method, JsonObject params,
            AtomicBoolean cancelled) throws Exception {
        if (cancelled.get())
            throw new CancellationException();
        int id = sequence.incrementAndGet();
        var future = new CompletableFuture<JsonElement> ();
        pending.put(id, future);
        try {
            send(object("jsonrpc", "2.0", "id", id, "method", method, "params", params));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);

            for (;;) {
                if (cancelled.get() || Thread.currentThread().isInterrupted()) {
                    notify("$/cancelRequest", object("id", id));
                    throw new CancellationException();
                }
                if (System.nanoTime() >= deadline) {
                    notify("$/cancelRequest", object("id", id));
                    throw new IOException("RefactorJ request timed out");
                }
                try {
                    return future.get(100, TimeUnit.MILLISECONDS);
                } catch (TimeoutException waiting) {}
            }
        } finally {
            pending.remove(id);
        }
    }

    private void notify(String method, JsonObject params) throws IOException {
        send(object("jsonrpc", "2.0", "method", method, "params", params));
    }

    private void send(JsonObject value) throws IOException {
        byte[] bytes = JSON.toJson(value).getBytes(StandardCharsets.UTF_8);
        synchronized (writeLock) {
            if (output == null)
                throw new IOException("Backend is stopped");
            output.write(("Content-Length: " + bytes.length + "\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            output.write(bytes);
            output.flush();
        }
    }

    private void read(Process child) {
        try (InputStream input = child.getInputStream()) {
            for (;;) {
                int length = -1, count = 0;

                for (;;) {
                    String header = line(input);

                    if (header == null)
                        throw new EOFException("RefactorJ stopped; configure Java 25 in Settings → Tools → RefactorJ");
                    if (header.isEmpty())
                        break;
                    if (++count > 16)
                        throw new IOException("Too many protocol headers");
                    if (header.startsWith("Content-Length: ")) {
                        if (length >= 0)
                            throw new IOException("Duplicate length");
                        length = Integer.parseInt(header.substring(16));
                    }
                }
                if (length < 0 || length > 64 * 1024 * 1024)
                    throw new IOException("Invalid protocol length");
                byte[] bytes = input.readNBytes(length);

                if (bytes.length != length)
                    throw new EOFException();
                JsonObject response = JSON.fromJson(new String(bytes, StandardCharsets.UTF_8),
                        JsonObject.class);

                if (!response.has("id") || response.get("id").isJsonNull())
                    continue;
                var future = pending.get(response.get("id").getAsInt());

                if (future != null) {
                    if (response.has("error"))
                        future.completeExceptionally(new IOException(response
                                .getAsJsonObject("error").get("message").getAsString()));
                    else
                        future.complete(response.get("result"));
                }
            }
        } catch (Exception error) {
            if (process == child)
                pending.values().forEach(f -> f.completeExceptionally(error));
        } finally {
            child.destroy();
        }
    }

    private static String line(InputStream input) throws IOException {
        var result = new ByteArrayOutputStream();
        int c;

        while ((c = input.read()) != -1 && c != '\n') {
            if (result.size() > 8192)
                throw new IOException("Oversized protocol header");
            if (c != '\r')
                result.write(c);
        }
        return c == -1 && result.size() == 0 ? null : result.toString(StandardCharsets.US_ASCII);
    }

    private static JsonObject position(String text, int offset) {
        int line = 0, start = 0;

        for (int i = 0; i < offset; i++)
            if (text.charAt(i) == '\n') {
                line++;
                start = i + 1;
            }
        return object("line", line, "character", offset - start);
    }

    public void reset() {
        AtomicBoolean active = activeCancellation; if (active != null) active.set(true);
        NativeAccess embedded = nativeAccess; nativeAccess = null;
        if (embedded != null) Thread.ofVirtual().name("refactorj-close").start(() -> {
            synchronized (operationLock) { try { embedded.close(); } catch (Exception ignored) {} }
        });
        pending.values().forEach(f -> f
                .completeExceptionally(new IOException("RefactorJ settings changed")));
        pending.clear();
        if (process != null)
            process.destroy();
        process = null;
        output = null;
        opened.clear();
        versions.clear();
    }

    @Override
    public void dispose() {
        disposed = true;
        reset();
    }
}
