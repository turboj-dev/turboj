package dev.turboj.mavenj.workspace;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MavenLifecycleTest {
    @TempDir
    Path root;

    private MavenLifecycle.Request request(boolean trusted, Duration timeout) {
        return new MavenLifecycle.Request(root, List.of("verify"), List.of("ci"), Path.of(System
                .getProperty("java.home")), null, trusted, true, timeout, Map.of("api.token",
                "sensitive-value"));
    }

    private void wrapper(String script) throws Exception {
        Files.writeString(root.resolve("mvnw"), "#!/bin/sh\n" + script);
    }

    @Test
    void inspectionAndUntrustedStartCannotExecuteWrapper() throws Exception {
        wrapper("touch executed\n");
        var description = MavenLifecycle.inspect(request(false, Duration.ofSeconds(5)));
        assertTrue(description.distribution().contains("Wrapper"));
        assertFalse(Files.exists(root.resolve("executed")));
        assertThrows(SecurityException.class, () -> MavenLifecycle.start(request(false, Duration
                .ofSeconds(5)), ignored -> {}));
        assertFalse(Files.exists(root.resolve("executed")));
        assertFalse(description.command().toString().contains("sensitive-value"));
    }

    @Test
    void actualWrapperReceivesWorkingDirectoryProfilesJdkAndReturnsExitStatus() throws Exception {
        wrapper("printf '%s\\n' \"$PWD\" \"$JAVA_HOME\" \"$@\"\nexit 7\n");
        var output = new CopyOnWriteArrayList<String> ();

        try (var execution = MavenLifecycle.start(request(true, Duration.ofSeconds(5)),
                output::add)) {
            var result = execution.completion().get(8, TimeUnit.SECONDS);
            assertEquals(7, result.exitCode());
            assertFalse(result.success());
            assertFalse(result.cancelled());
            long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);

            while (!output.contains("verify") && System.nanoTime() < end)
                Thread.sleep(10);
            String canonicalRoot = root.toRealPath().toString();
            assertTrue(output.stream().anyMatch(line -> line.equals(root.toString()) || line
                    .equals(canonicalRoot)), output.toString());
            assertTrue(output.contains("--offline"));
            assertTrue(output.contains("ci"));
            assertTrue(output.contains("verify"));
            assertFalse(String.join("\n", output).contains("sensitive-value"));
        }
    }

    @Test
    void cancellationAndTimeoutTerminateRealProcessTree() throws Exception {
        wrapper("sleep 30 &\nwait\n");
        try (var execution = MavenLifecycle.start(request(true, Duration.ofSeconds(10)),
                ignored -> {})) {
            execution.cancel();
            assertTrue(execution.completion().get(5, TimeUnit.SECONDS).cancelled());
        }
        try (var execution = MavenLifecycle.start(request(true, Duration.ofMillis(100)),
                ignored -> {})) {
            assertTrue(execution.completion().get(5, TimeUnit.SECONDS).timedOut());
        }
    }

    @Test
    void checksumMismatchRejectsBeforeLaunchingProjectCode() throws Exception {
        wrapper("touch executed\n");
        Path directory = Files.createDirectories(root.resolve(".mvn/wrapper"));
        Files.writeString(directory.resolve("maven-wrapper.jar"), "wrong binary");
        Files.writeString(directory.resolve("maven-wrapper.properties"), "distributionSha256Sum="
                + "a".repeat(64) + "\nwrapperSha256Sum=" + "b".repeat(64));
        assertThrows(java.io.IOException.class, () -> MavenLifecycle.start(request(true, Duration
                .ofSeconds(5)), ignored -> {}));
        assertFalse(Files.exists(root.resolve("executed")));
    }

    @Test
    void rejectsFlagsDisguisedAsGoalsAndRedactsCredentialUrls() {
        assertThrows(IllegalArgumentException.class, () -> new MavenLifecycle.Request(root, List
                .of("--file=/tmp/other/pom.xml"), List.of(), null, null, true, false, Duration
                .ofSeconds(5), Map.of()));
        assertEquals("https://<redacted>@example.test/repo token=<redacted>", MavenLifecycle
                .redact("https://user:password@example.test/repo token=secret-value", Map.of()));
    }

    @Test
    void offlineWrapperCannotBootstrapMissingDistributionOrJar() throws Exception {
        wrapper("touch executed\n");
        Path directory = Files.createDirectories(root.resolve(".mvn/wrapper"));
        String url = "https://repository.invalid/apache-maven-99.9.9-bin.zip";
        Files.writeString(directory.resolve("maven-wrapper.properties"), "distributionUrl=" + url
                + "\ndistributionType=only-script\ndistributionBase=PROJECT\ndistributionPath=.cache/maven/wrapper/dists\n");
        assertTrue(MavenLifecycle.inspect(request(true, Duration.ofSeconds(5))).diagnostics()
                .stream().anyMatch(d -> d.code().equals("WRAPPER_OFFLINE_BOOTSTRAP")));
        assertThrows(java.io.IOException.class, () -> MavenLifecycle.start(request(true, Duration
                .ofSeconds(5)), ignored -> {}));
        assertFalse(Files.exists(root.resolve("executed")));
        Path cached = Files.createDirectories(root
                .resolve(".cache/maven/wrapper/dists/apache-maven-99.9.9/" + Integer.toHexString(url
                .hashCode()) + "/bin"));
        Files.writeString(cached.resolve("mvn"), "cached fixture launcher");
        assertFalse(MavenLifecycle.inspect(request(true, Duration.ofSeconds(5))).diagnostics()
                .stream().anyMatch(d -> d.code().equals("WRAPPER_OFFLINE_BOOTSTRAP")));
        Files.writeString(directory.resolve("maven-wrapper.properties"), "distributionUrl=" + url
                + "\nwrapperUrl=https://repository.invalid/wrapper.jar\ndistributionBase=PROJECT\ndistributionPath=.cache/maven/wrapper/dists\n");
        assertThrows(java.io.IOException.class, () -> MavenLifecycle.start(request(true, Duration
                .ofSeconds(5)), ignored -> {}));
        assertFalse(Files.exists(root.resolve("executed")));
    }
}
