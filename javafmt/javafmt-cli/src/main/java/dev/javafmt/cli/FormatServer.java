package dev.javafmt.cli;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import dev.javafmt.core.FormatRequest;
import dev.javafmt.core.FormatResult;
import dev.javafmt.core.JavaFormatter;
import dev.javafmt.core.LineEndingPolicy;
import dev.javafmt.core.TextRange;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Versioned local stdio protocol. One active and one pending parse bound resident work. */
final class FormatServer {
    private static final int MAX_LINE_CHARS = 16 * 1024 * 1024;
    private final JavaFormatter formatter;
    private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
    private final ConcurrentHashMap<String, Job> jobs = new ConcurrentHashMap<>();
    private final ThreadPoolExecutor worker =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.MILLISECONDS,
                    new ArrayBlockingQueue<>(1),
                    runnable ->
                            Thread.ofPlatform()
                                    .daemon()
                                    .name("javafmt-worker")
                                    .unstarted(runnable));
    private PrintWriter output;

    FormatServer(JavaFormatter formatter) {
        this.formatter = formatter;
    }

    int serve(InputStream input, OutputStream out, PrintWriter err) throws Exception {
        output = new PrintWriter(out, false, StandardCharsets.UTF_8);
        var decoder =
                StandardCharsets.UTF_8
                        .newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT);
        BufferedReader reader = new BufferedReader(new InputStreamReader(input, decoder));
        try {
            String line;
            while (true) {
                try {
                    line = boundedLine(reader);
                } catch (OversizedLineException oversized) {
                    error(null, "too-large", "JSON line exceeds 16 Mi UTF-16 characters.");
                    continue;
                }
                if (line == null) break;
                if (line.isEmpty()) {
                    error(null, "invalid-request", "Expected a JSON request.");
                    continue;
                }
                receive(line);
            }
            worker.shutdown();
            if (!worker.awaitTermination(120, TimeUnit.SECONDS)) {
                for (Job job : jobs.values()) job.cancelled.set(true);
                worker.shutdownNow();
                err.println("javafmt: resident formatter did not finish before shutdown deadline.");
                return 2;
            }
            return output.checkError() ? 2 : 0;
        } finally {
            worker.shutdownNow();
            output.flush();
        }
    }

    private String boundedLine(BufferedReader reader) throws IOException {
        StringBuilder line = new StringBuilder();
        boolean oversized = false;
        int ch;
        while ((ch = reader.read()) != -1 && ch != '\n') {
            if (line.length() < MAX_LINE_CHARS) line.append((char) ch);
            else oversized = true;
        }
        if (oversized) throw new OversizedLineException();
        if (ch == -1 && line.isEmpty()) return null;
        if (!line.isEmpty() && line.charAt(line.length() - 1) == '\r')
            line.setLength(line.length() - 1);
        return line.toString();
    }

    private void receive(String line) {
        String id = null;
        try {
            JsonObject request = JsonParser.parseString(line).getAsJsonObject();
            if (!request.has("protocolVersion") || exactInt(request.get("protocolVersion")) != 1)
                throw new IllegalArgumentException("protocolVersion must be 1.");
            if (!request.has("id")
                    || !request.get("id").isJsonPrimitive()
                    || !request.getAsJsonPrimitive("id").isString())
                throw new IllegalArgumentException("id must be a nonempty string.");
            id = request.get("id").getAsString();
            if (id.isEmpty() || id.length() > 128 || id.chars().anyMatch(Character::isISOControl))
                throw new IllegalArgumentException("id must contain 1–128 noncontrol characters.");
            String op = request.has("op") ? request.get("op").getAsString() : "format";
            if (op.equals("cancel")) {
                Job current = jobs.remove(id);
                if (current != null) {
                    current.cancelled.set(true);
                    worker.remove(current);
                }
                JsonObject reply = base(id);
                reply.addProperty("success", false);
                reply.addProperty("cancelled", true);
                reply.addProperty("found", current != null);
                send(reply);
                return;
            }
            if (!op.equals("format")) throw new IllegalArgumentException("Unsupported operation.");
            if (!request.has("source")
                    || !request.get("source").isJsonPrimitive()
                    || !request.getAsJsonPrimitive("source").isString())
                throw new IllegalArgumentException("source must be a string.");
            String source = request.get("source").getAsString();
            if (source.length() > SafeFiles.MAX_BYTES)
                throw new IllegalArgumentException("Source is too large.");
            long version =
                    request.has("sourceVersion") ? exactLong(request.get("sourceVersion")) : 0;
            LineEndingPolicy endings =
                    request.has("lineEndings")
                            ? LineEndingPolicy.valueOf(request.get("lineEndings").getAsString())
                            : LineEndingPolicy.LF;
            TextRange range = null;
            if (request.has("range")) {
                JsonObject value = request.getAsJsonObject("range");
                range = new TextRange(exactInt(value.get("start")), exactInt(value.get("end")));
                if (range.end() > source.length())
                    throw new IllegalArgumentException("Range exceeds source length.");
            }
            Job job = new Job(id, new FormatRequest(source, version, endings), range);
            if (jobs.putIfAbsent(id, job) != null) {
                error(id, "duplicate-id", "A request with this id is already active.");
                return;
            }
            try {
                worker.execute(job);
            } catch (RejectedExecutionException busy) {
                jobs.remove(id, job);
                error(
                        id,
                        "busy",
                        "One request is active and one is pending; cancel obsolete requests"
                            + " first.");
            }
        } catch (Exception invalid) {
            error(id, "invalid-request", "Malformed or unsupported formatting request.");
        }
    }

    private final class Job implements Runnable {
        final String id;
        final FormatRequest request;
        final TextRange range;
        final AtomicBoolean cancelled = new AtomicBoolean();

        Job(String id, FormatRequest request, TextRange range) {
            this.id = id;
            this.request = request;
            this.range = range;
        }

        @Override
        public void run() {
            if (cancelled.get()) return;
            try {
                FormatResult result =
                        range == null
                                ? formatter.format(request)
                                : formatter.formatRange(request, range);
                JsonObject reply = gson.toJsonTree(result).getAsJsonObject();
                reply.addProperty("protocolVersion", 1);
                reply.addProperty("id", id);
                synchronized (FormatServer.this) {
                    if (!cancelled.get()) send(reply);
                }
            } catch (Exception failure) {
                synchronized (FormatServer.this) {
                    if (!cancelled.get())
                        error(
                                id,
                                "format-failed",
                                "Formatter could not process this source; no edits applied.");
                }
            } finally {
                jobs.remove(id, this);
            }
        }
    }

    private static JsonObject base(String id) {
        JsonObject response = new JsonObject();
        response.addProperty("protocolVersion", 1);
        if (id == null) response.add("id", com.google.gson.JsonNull.INSTANCE);
        else response.addProperty("id", id);
        return response;
    }

    private void error(String id, String code, String message) {
        JsonObject response = base(id);
        response.addProperty("success", false);
        response.addProperty("error", code);
        response.addProperty("message", message);
        send(response);
    }

    private synchronized void send(JsonObject response) {
        output.println(gson.toJson(response));
        output.flush();
    }

    private static int exactInt(com.google.gson.JsonElement value) {
        return Math.toIntExact(exactLong(value));
    }

    private static long exactLong(com.google.gson.JsonElement value) {
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException("Integer required.");
        return value.getAsBigDecimal().longValueExact();
    }

    private static final class OversizedLineException extends IOException {}
}
