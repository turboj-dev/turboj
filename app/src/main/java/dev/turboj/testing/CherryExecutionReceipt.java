package dev.turboj.testing;

import com.google.gson.*;
import dev.turboj.testing.intelligence.LineIntelligence.Run;
import dev.turboj.testing.intelligence.LineIntelligence.RunState;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Binds controller-observed process completion to the exact retained event stream. */
public final class CherryExecutionReceipt {
    private static final int MAX_RECEIPT_BYTES = 64 * 1024;
    private static final long MAX_EVENT_BYTES = 64L * 1024 * 1024;
    private static final int MAX_RUN_ID = 1024, MAX_MESSAGE = 8192;
    private static final Set<RunState> TERMINAL = Set.of(RunState.COMPLETED,RunState.FAILED,
            RunState.CANCELLED,RunState.INCOMPLETE,RunState.UNAVAILABLE);
    private CherryExecutionReceipt() {}

    public static void write(Path events,Run outcome) throws IOException {
        if (!TERMINAL.contains(outcome.state())) throw new IOException("Cannot persist a nonterminal Cherry execution");
        if (outcome.id().length() > MAX_RUN_ID) throw new IOException("Cherry run identity exceeds receipt limit");
        JsonObject receipt = new JsonObject();
        receipt.addProperty("schemaVersion",1);
        receipt.addProperty("runId",outcome.id());
        receipt.addProperty("eventsSHA256",hash(events));
        receipt.addProperty("state",outcome.state().name());
        String message = outcome.message();
        if (message.length() > MAX_MESSAGE) message = message.substring(0,MAX_MESSAGE - 1) + "…";
        receipt.addProperty("message",message);
        byte[] bytes = receipt.toString().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_RECEIPT_BYTES) throw new IOException("Cherry completion receipt exceeds size limit");
        Path destination = receiptPath(events);
        Path temporary = Files.createTempFile(destination.toAbsolutePath().getParent(),"cherry-completion-",".tmp");
        try {
            Files.write(temporary,bytes);
            // An unsupported atomic move fails closed: the event file alone never certifies success.
            Files.move(temporary,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        } finally { Files.deleteIfExists(temporary); }
    }

    public static Run restore(Path events,Run reported) throws IOException {
        try {
            Path path = receiptPath(events);
            if (!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS))
                return incomplete(reported,"process completion receipt is missing");
            if (Files.size(path) > MAX_RECEIPT_BYTES) return incomplete(reported,"completion receipt exceeds size limit");
            byte[] bytes;
            try (InputStream input = Files.newInputStream(path)) { bytes = input.readNBytes(MAX_RECEIPT_BYTES + 1); }
            if (bytes.length > MAX_RECEIPT_BYTES) return incomplete(reported,"completion receipt exceeds size limit");
            JsonObject receipt = JsonParser.parseString(new String(bytes,StandardCharsets.UTF_8)).getAsJsonObject();
            JsonElement schema = receipt.get("schemaVersion");
            if (schema == null || !schema.isJsonPrimitive() || !schema.getAsJsonPrimitive().isNumber()
                    || !schema.getAsString().equals("1")) return incomplete(reported,"unsupported completion receipt schema");
            String id = string(receipt,"runId",MAX_RUN_ID), expected = string(receipt,"eventsSHA256",64);
            if (!reported.id().equals(id)) return incomplete(reported,"completion receipt belongs to a different run");
            if (!expected.matches("[a-f0-9]{64}") || !expected.equals(hash(events)))
                return incomplete(reported,"event stream does not match its completion receipt");
            RunState state = RunState.valueOf(string(receipt,"state",32));
            if (!TERMINAL.contains(state)) return incomplete(reported,"completion receipt is not terminal");
            if (state == RunState.COMPLETED && reported.state() != RunState.COMPLETED)
                return incomplete(reported,"completion receipt contradicts the reported execution");
            return withOutcome(reported,state,string(receipt,"message",MAX_MESSAGE));
        } catch (IOException | RuntimeException invalid) {
            return incomplete(reported,"completion receipt could not be validated (" + invalid.getClass().getSimpleName() + ")");
        }
    }

    private static String string(JsonObject receipt,String field,int maximum) throws IOException {
        JsonElement value = receipt.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IOException("Invalid receipt field " + field);
        String text = value.getAsString();
        if (text.length() > maximum) throw new IOException("Receipt field exceeds limit: " + field);
        return text;
    }
    private static Path receiptPath(Path events) {
        return events.resolveSibling(events.getFileName() + ".completion.json");
    }
    private static String hash(Path events) throws IOException {
        if (!Files.isRegularFile(events,LinkOption.NOFOLLOW_LINKS) || Files.size(events) > MAX_EVENT_BYTES)
            throw new IOException("Cherry event stream is unavailable or exceeds size limit");
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(events)) {
                byte[] buffer = new byte[8192]; long total = 0;
                for (int count; (count = input.read(buffer)) >= 0;) {
                    total += count;
                    if (total > MAX_EVENT_BYTES) throw new IOException("Cherry event stream exceeds size limit");
                    digest.update(buffer,0,count);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private static Run incomplete(Run reported,String reason) {
        return withOutcome(reported,RunState.INCOMPLETE,"Historical Cherry execution is unverified: " + reason
                + ". Actual reported test and coverage evidence is retained.\n" + reported.message());
    }
    private static Run withOutcome(Run reported,RunState state,String message) {
        return new Run(reported.id(),reported.root(),state,reported.tests(),reported.coverage(),reported.sourceHashes(),
                message,reported.htmlReport(),reported.sequence(),reported.coverageAvailability());
    }
}
