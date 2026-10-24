package dev.turboj.turbotest;

import com.google.gson.Gson;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** Entrypoint invoked by a host's resident execution worker. Does not start another JVM. */
public final class WorkerEntry {
    private WorkerEntry() {}
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Expected encoded request and private report path");
        Gson gson = new Gson();
        TestEngine.Request request = gson.fromJson(new String(Base64.getDecoder().decode(args[0]), StandardCharsets.UTF_8), TestEngine.Request.class);
        Path report = Path.of(args[1]).toAbsolutePath().normalize();
        if (Files.exists(report, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("Report destination must be new");
        var result = new TestEngine().execute(request, event -> {});
        Path temporary = Files.createTempFile(report.getParent(), ".turbotest-", ".json");
        try {
            Files.writeString(temporary, gson.toJson(result), StandardCharsets.UTF_8);
            Files.move(temporary, report, StandardCopyOption.ATOMIC_MOVE);
        } finally { Files.deleteIfExists(temporary); }
    }
}
