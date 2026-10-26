package dev.turboj.ai.local;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/** Starts a server process that cannot outlive its owner. */
@FunctionalInterface
public interface ProcessLauncher {
    /** A process this application started and therefore may stop. */
    interface ManagedProcess {
        long pid();

        /** The server itself, below any watchdog; empty until known. */
        Optional<Long> serverPid();

        boolean alive();

        /** Asks the server to exit, then forces it after {@code grace}. */
        void stop(Duration grace);
    }

    ManagedProcess start(List<String> command, Map<String, String> environment, Path log) throws IOException;

    /**
     * The command runs under a small {@code /bin/sh} watchdog that polls this JVM's pid every second and
     * stops the server when the JVM is gone, even after {@code kill -9}; a TERM to the watchdog is
     * forwarded. Output goes to {@code log}, replaced at each start.
     */
    static ProcessLauncher watchdog() {
        return (command, environment, log) -> {
            String script = WatchdogScript.TEXT;
            var full = new ArrayList<String>(List.of("/bin/sh", "-c", script, "turbo-ai-watchdog",
                    Long.toString(ProcessHandle.current().pid())));
            full.addAll(command);
            Files.createDirectories(log.getParent());
            var builder = new ProcessBuilder(full).redirectErrorStream(true).redirectOutput(log.toFile());
            builder.environment().putAll(environment);
            Process process = builder.start();
            return new ManagedProcess() {
                private volatile ProcessHandle server;

                @Override
                public long pid() {
                    return process.pid();
                }

                @Override
                public Optional<Long> serverPid() {
                    if (server == null) server = process.toHandle().children().findFirst().orElse(null);
                    return server == null ? Optional.empty() : Optional.of(server.pid());
                }

                @Override
                public boolean alive() {
                    return process.isAlive();
                }

                @Override
                public void stop(Duration grace) {
                    serverPid();
                    process.destroy();
                    try {
                        if (!process.waitFor(grace.toMillis(), TimeUnit.MILLISECONDS)) process.destroyForcibly();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        process.destroyForcibly();
                    }
                    if (server != null && server.isAlive()) server.destroyForcibly();
                }
            };
        };
    }

    /** The {@code /bin/sh} program behind {@link #watchdog()}: arguments are the owner pid, then the command. */
    final class WatchdogScript {
        static final String TEXT = """
                parent="$1"; shift
                "$@" &
                child=$!
                trap 'kill -TERM "$child" 2>/dev/null; wait "$child"; exit 0' TERM INT HUP
                while kill -0 "$parent" 2>/dev/null && kill -0 "$child" 2>/dev/null; do
                  sleep 1 & wait $!
                done
                kill -TERM "$child" 2>/dev/null
                wait "$child"
                """;

        private WatchdogScript() {}
    }
}
