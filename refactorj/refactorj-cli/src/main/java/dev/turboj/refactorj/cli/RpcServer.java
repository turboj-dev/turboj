package dev.turboj.refactorj.cli;

import com.google.gson.*;
import dev.turboj.refactorj.api.Cancellation;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded stdio JSON-RPC transport with a serial semantic worker and prompt cancellation. */
public final class RpcServer {
    public enum Framing {
        CONTENT_LENGTH,
        JSON_LINES
    }

    public interface Handler {
        JsonElement handle(String method, JsonObject params,
                Cancellation cancellation) throws Exception;
        default boolean stopAfter(String method) { return false; }
    }

    public static final class RpcError extends RuntimeException {
        public final int code;
        public final JsonElement data;

        public RpcError(int code, String message) {
            this(code, message, JsonNull.INSTANCE);
        }

        public RpcError(int code, String message, JsonElement data) {
            super(message);
            this.code = code;
            this.data = data;
        }
    }

    private static final int MAX_MESSAGE = 16 * 1024 * 1024;
    private final InputStream input;
    private final OutputStream output;
    private final Framing framing;
    private final Handler handler;
    private final Map<String, AtomicBoolean> cancellations = new ConcurrentHashMap<> ();

    public RpcServer(InputStream input, OutputStream output, Framing framing, Handler handler) {
        this.input = input;
        this.output = output;
        this.framing = framing;
        this.handler = handler;
    }

    public void run() throws IOException, InterruptedException {
        var executor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<> (64), r -> new Thread(r, "refactorj-protocol"));

        try {
            for (String payload;(payload = read()) != null;) {
                JsonObject request;

                try {
                    request = Json.GSON.fromJson(payload, JsonObject.class);
                } catch (RuntimeException malformed) {
                    error(JsonNull.INSTANCE, -32700, "Invalid JSON", JsonNull.INSTANCE);
                    continue;
                }
                if (request == null || !request.has("jsonrpc") || !request.get("jsonrpc")
                        .isJsonPrimitive() || !request.getAsJsonPrimitive("jsonrpc").isString()
                        || !"2.0".equals(request.get("jsonrpc").getAsString()) || !request
                        .has("method") || !request.get("method").isJsonPrimitive() || !request
                        .getAsJsonPrimitive("method").isString()) {
                    error(JsonNull.INSTANCE, -32600, "Invalid JSON-RPC request", JsonNull.INSTANCE);
                    continue;
                }
                JsonElement id = request.get("id");
                String method = request.get("method").getAsString();

                if (id != null && (id.isJsonNull() || !id.isJsonPrimitive() || !(id
                        .getAsJsonPrimitive().isString() || id.getAsJsonPrimitive().isNumber()))) {
                    error(JsonNull.INSTANCE, -32600, "Invalid request id", JsonNull.INSTANCE);
                    continue;
                }
                JsonObject params;

                try {
                    params = request.has("params") ? request
                            .getAsJsonObject("params") : new JsonObject();
                } catch (RuntimeException invalid) {
                    if (id != null)
                        error(id, -32602, "Parameters must be an object", JsonNull.INSTANCE);
                    continue;
                }
                if (params == null)
                    params = new JsonObject();
                if (method.equals("$/cancelRequest") || method.equals("notifications/cancelled")) {
                    JsonElement target = params.get(method.startsWith("$/") ? "id" : "requestId");

                    if (target != null) {
                        var flag = cancellations.get(target.toString());

                        if (flag != null)
                            flag.set(true);
                    }
                    continue;
                }
                var cancelled = new AtomicBoolean();
                String key = id == null ? null : id.toString();

                if (key != null && cancellations.putIfAbsent(key, cancelled) != null) {
                    error(id, -32600, "Duplicate pending request id", JsonNull.INSTANCE);
                    continue;
                }
                JsonObject arguments = params;

                try {
                    executor.execute(() -> {
                        try {
                            cancelledCheck(cancelled);
                            JsonElement result = handler.handle(method, arguments, cancelled::get);
                            cancelledCheck(cancelled);
                            if (id != null)
                                send(Json.object("jsonrpc", "2.0", "id", id, "result", result
                                        == null ? JsonNull.INSTANCE : result));
                        } catch (RpcError failure) {
                            if (id != null)
                                quietError(id, failure.code, failure.getMessage(), failure.data);
                        } catch (CancellationException failure) {
                            if (id != null)
                                quietError(id, -32800, "Request cancelled", JsonNull.INSTANCE);
                        } catch (IllegalArgumentException failure) {
                            if (id != null)
                                quietError(id, -32602, failure.getMessage(), JsonNull.INSTANCE);
                        } catch (Exception failure) {
                            if (id != null)
                                quietError(id, -32603, failure.getMessage() == null ? failure
                                        .getClass().getSimpleName() : failure.getMessage(),
                                        JsonNull.INSTANCE);
                        } finally {
                            if (key != null)
                                cancellations.remove(key, cancelled);
                        }
                    });
                } catch (RejectedExecutionException overloaded) {
                    if (key != null) {
                        cancellations.remove(key);
                        error(id, -32603, "Request queue is full; retry after current work",
                                JsonNull.INSTANCE);
                    } else
                        throw new IOException("Notification queue is full; reconnect and resynchronize documents");
                }
                if (id == null && handler.stopAfter(method)) break;
            }
        } finally {
            executor.shutdown();
            if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                cancellations.values().forEach(c -> c.set(true));
                executor.shutdownNow();
                executor.awaitTermination(5, TimeUnit.SECONDS);
            }
        }
    }

    private static void cancelledCheck(AtomicBoolean flag) {
        if (flag.get())
            throw new CancellationException();
    }

    private void quietError(JsonElement id, int code, String message, JsonElement data) {
        try {
            error(id, code, message, data);
        } catch (IOException ignored) {}
    }

    private void error(JsonElement id, int code, String message,
            JsonElement data) throws IOException {
        send(Json.object("jsonrpc", "2.0", "id", id, "error", Json.object("code", code, "message",
                message, "data", data)));
    }

    private synchronized void send(JsonObject response) throws IOException {
        byte[] bytes = Json.GSON.toJson(response).getBytes(StandardCharsets.UTF_8);

        if (framing == Framing.CONTENT_LENGTH)
            output.write(("Content-Length: " + bytes.length + "\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
        output.write(bytes);
        if (framing == Framing.JSON_LINES)
            output.write('\n');
        output.flush();
    }

    private String read() throws IOException {
        if (framing == Framing.JSON_LINES) {
            byte[] bytes = line(MAX_MESSAGE);
            return bytes == null ? null : decode(bytes);
        }
        int length = -1, headers = 0;

        for (;;) {
            byte[] bytes = line(8192);

            if (bytes == null) {
                if (headers == 0)
                    return null;
                throw new EOFException("Truncated headers");
            }
            String header = new String(bytes, StandardCharsets.US_ASCII).stripTrailing();

            if (header.isEmpty())
                break;
            if (++headers > 16)
                throw new IOException("Too many headers");
            int colon = header.indexOf(':');

            if (colon <= 0)
                throw new IOException("Malformed header");
            if (header.substring(0, colon).equalsIgnoreCase("Content-Length")) {
                if (length >= 0)
                    throw new IOException("Duplicate Content-Length");
                try {
                    length = Integer.parseInt(header.substring(colon + 1).trim());
                } catch (NumberFormatException bad) {
                    throw new IOException("Invalid Content-Length", bad);
                }
            }
        }
        if (length < 0 || length > MAX_MESSAGE)
            throw new IOException("Invalid message length");
        byte[] bytes = input.readNBytes(length);

        if (bytes.length != length)
            throw new EOFException("Truncated message");
        return decode(bytes);
    }

    private byte[] line(int limit) throws IOException {
        var bytes = new ByteArrayOutputStream();
        int value;

        while ((value = input.read()) != -1 && value != '\n') {
            if (bytes.size() >= limit)
                throw new IOException("Message exceeds limit");
            bytes.write(value);
        }
        return value == -1 && bytes.size() == 0 ? null : bytes.toByteArray();
    }

    private static String decode(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
    }
}
