package dev.turboj.refactorj.runtime;

import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import java.util.List;
import dev.turboj.refactorj.api.Model.*;
import dev.turboj.refactorj.api.JavaIntelligenceProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class RuntimeLifecycleIT {
    @TempDir Path temporary;

    @Test void repeatedEmbeddingRestoresHostLoaderAndExitsWithoutLeakedFrameworkThreads() throws Exception {
        Path output = temporary.resolve("lifecycle.log");
        Process child = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "--enable-native-access=ALL-UNNAMED", "-cp", System.getProperty("java.class.path"),
                Probe.class.getName()).redirectErrorStream(true).redirectOutput(output.toFile()).start();
        try {
            assertTrue(child.waitFor(30, TimeUnit.SECONDS), () -> "Leaked runtime threads: " + read(output));
            assertEquals(0, child.exitValue(), () -> read(output));
            assertFalse(read(output).contains("Exception"), () -> read(output));
            assertTrue(read(output).contains("closed 3 runtimes"));
        } finally { if (child.isAlive()) child.destroyForcibly(); }
    }

    @Test void closedWorkspaceIsDetachedWhileRuntimeCanOpenTheNextProject() throws Exception {
        Path root = temporary.toRealPath(), file = root.resolve("A.java");
        var snapshot = new WorkspaceSnapshot(root, 1,
                List.of(new SourceSnapshot(file, "class A {}", 1, true)), List.of(root), List.of());
        try (var runtime = new RefactorJRuntime()) {
            var first = runtime.openWorkspace(snapshot);
            assertNotNull(((JavaIntelligenceProvider) first).intelligence());
            first.close();
            assertThrows(IllegalStateException.class, first::workspace);
            var second = runtime.openWorkspace(snapshot);
            assertEquals(snapshot, second.workspace().snapshot());
            second.close();
            var ownership = RefactorJRuntime.class.getDeclaredField("engines");
            ownership.setAccessible(true);
            assertTrue(((List<?>) ownership.get(runtime)).isEmpty(), "Closed project snapshots must be released");
        }
    }

    private static String read(Path path) {
        try { return Files.readString(path); } catch (Exception e) { return e.toString(); }
    }

    public static class Probe {
        public static void main(String[] args) throws Exception {
            ClassLoader host = Thread.currentThread().getContextClassLoader();
            for (int i = 0; i < 3; i++) {
                try (var runtime = new RefactorJRuntime()) {
                    if (Thread.currentThread().getContextClassLoader() != host)
                        throw new AssertionError("The runtime changed its host's context loader");
                }
                if (Thread.currentThread().getContextClassLoader() != host)
                    throw new AssertionError("Closing changed the host's context loader");
            }
            System.out.println("closed 3 runtimes");
            // Natural exit is part of the test: System.exit would hide leaked non-daemon threads.
        }
    }
}
