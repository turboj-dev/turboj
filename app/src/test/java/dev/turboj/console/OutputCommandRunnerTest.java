package dev.turboj.console;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

@Timeout(15)
class OutputCommandRunnerTest {
    @TempDir Path directory;

    @Test void capturesRealStdoutStderrAndNonzeroExit() throws Exception {
        StringBuffer out = new StringBuffer(), err = new StringBuffer();
        try (var runner = new OutputCommandRunner()) {
            var result = runner.start("printf 'hello'; printf 'problem' >&2; exit 7", directory,
                    (text, error) -> (error ? err : out).append(text)).get(5, TimeUnit.SECONDS);
            assertEquals("hello", out.toString());
            assertEquals("problem", err.toString());
            assertEquals(7, result.exitCode());
            assertFalse(result.succeeded());
            assertFalse(result.cancelled());
            assertNull(result.error());
            assertTrue(result.elapsedNanos() > 0);
        }
    }

    @Test void usesRequestedDirectoryAndShellQuotingWithoutAffectingParentDirectory() throws Exception {
        Files.writeString(directory.resolve("name with spaces.txt"), "caffè 😀");
        StringBuffer out = new StringBuffer();
        try (var runner = new OutputCommandRunner()) {
            var result = runner.start("cat 'name with spaces.txt' | cat", directory,
                    (text, error) -> out.append(text)).get(5, TimeUnit.SECONDS);
            assertTrue(result.succeeded(), result.toString());
            assertEquals("caffè 😀", out.toString());
            assertEquals(directory.toAbsolutePath().normalize(), result.directory());
        }
    }

    @Test void providesEofInsteadOfTakingJavaProgramStdin() throws Exception {
        StringBuffer out = new StringBuffer();
        try (var runner = new OutputCommandRunner()) {
            var result = runner.start("if read line; then printf WRONG; else printf EOF; fi", directory,
                    (text, error) -> out.append(text)).get(5, TimeUnit.SECONDS);
            assertTrue(result.succeeded());
            assertEquals("EOF", out.toString());
        }
    }

    @Test void missingDirectoryIsAnActualLaunchFailure() throws Exception {
        try (var runner = new OutputCommandRunner()) {
            var result = runner.start("printf should-not-run", directory.resolve("missing"),
                    (text, error) -> fail("Command must not launch")).get(5, TimeUnit.SECONDS);
            assertEquals(-1, result.exitCode());
            assertFalse(result.succeeded());
            assertTrue(result.error().contains("does not exist"));
        }
    }

    @Test void rejectsConcurrentSubmissionAndCanRestartAfterCancellation() throws Exception {
        try (var runner = new OutputCommandRunner()) {
            var first = runner.start("sleep 30", directory, (text, error) -> {});
            assertThrows(IllegalStateException.class, () -> runner.start("printf duplicate", directory, (text, error) -> {}));
            assertTrue(runner.cancel());
            assertTrue(first.get(5, TimeUnit.SECONDS).cancelled());
            assertFalse(runner.isRunning());
            assertTrue(runner.start("printf done", directory, (text, error) -> {}).get(5, TimeUnit.SECONDS).succeeded());
        }
    }

    @Test void cancelsOwnedChildButLeavesUnrelatedServerProcessAlive() throws Exception {
        Process unrelated = new ProcessBuilder("/bin/sh", "-c", "exec sleep 30").start();
        AtomicLong childPid = new AtomicLong();
        CountDownLatch started = new CountDownLatch(1);
        StringBuffer pidOutput = new StringBuffer();
        try (var runner = new OutputCommandRunner()) {
            var completion = runner.start("sleep 30 & child=$!; printf '%s\\n' \"$child\"; wait \"$child\"", directory,
                    (text, error) -> {
                        if (!error) {
                            pidOutput.append(text);
                            if (pidOutput.toString().contains("\n")) {
                                childPid.set(Long.parseLong(pidOutput.toString().strip()));
                                started.countDown();
                            }
                        }
                    });
            assertTrue(started.await(3, TimeUnit.SECONDS));
            assertTrue(runner.cancel());
            assertTrue(completion.get(5, TimeUnit.SECONDS).cancelled());
            assertFalse(ProcessHandle.of(childPid.get()).map(ProcessHandle::isAlive).orElse(false), "Owned sleep must stop");
            assertTrue(unrelated.isAlive(), "Cancelling curl must not stop the independent Maven/server process");
        } finally {
            unrelated.destroyForcibly();
            unrelated.waitFor(3, TimeUnit.SECONDS);
        }
    }

    @Test void timeoutIsBoundedAndReportedSeparatelyFromUserCancellation() throws Exception {
        try (var runner = new OutputCommandRunner(Duration.ofMillis(100))) {
            var result = runner.start("sleep 30", directory, (text, error) -> {}).get(5, TimeUnit.SECONDS);
            assertTrue(result.timedOut());
            assertFalse(result.cancelled());
            assertFalse(result.succeeded());
            assertFalse(runner.isRunning());
        }
    }

    @Test void closeCancelsJobAndPreventsNewLaunches() throws Exception {
        var runner = new OutputCommandRunner();
        var result = runner.start("sleep 30", directory, (text, error) -> {});
        runner.close();
        assertTrue(result.get(5, TimeUnit.SECONDS).cancelled());
        assertThrows(IllegalStateException.class, () -> runner.start("true", directory, (text, error) -> {}));
    }

    @Test void rejectsEmptyMultilineAndExcessiveCommandsWithoutLaunchingAnything() {
        try (var runner = new OutputCommandRunner()) {
            for (String command : new String[]{" ", "true\nfalse", "true\rfalse", "true\0", "x".repeat(16385)})
                assertThrows(IllegalArgumentException.class, () -> runner.start(command, directory, (text, error) -> {}));
            assertFalse(runner.isRunning());
        }
    }

    @Test void largeOutputIsBoundedAndProcessStillCompletes() throws Exception {
        AtomicLong received = new AtomicLong();
        StringBuffer notices = new StringBuffer();
        try (var runner = new OutputCommandRunner()) {
            var result = runner.start("dd if=/dev/zero bs=65536 count=20 2>/dev/null", directory,
                    (text, error) -> {
                        received.addAndGet(text.length());
                        if (error) notices.append(text);
                    }).get(5, TimeUnit.SECONDS);
            assertTrue(result.succeeded());
            assertTrue(received.get() < 1_049_000);
            assertTrue(notices.toString().contains("output truncated"));
        }
    }
}
