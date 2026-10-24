package dev.turboj.mavenj.workspace;

import java.nio.file.*;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MetadataWatcherTest {
    @TempDir
    Path root;

    @Test
    void coalescesPomChangesAndIgnoresJavaEditsAndStopsAfterClose() throws Exception {
        Path pom = root.resolve("pom.xml");
        Files.writeString(pom, "one");
        var count = new AtomicInteger();
        var change = new CountDownLatch(1);

        try (var watcher = new MetadataWatcher(List.of(pom), Duration.ofMillis(200), () -> {
            count.incrementAndGet();
            change.countDown();
        })) {
            Files.writeString(root.resolve("Main.java"), "class Main {}");
            Thread.sleep(400);
            assertEquals(0, count.get());
            Files.writeString(pom, "two");
            Thread.sleep(40);
            Files.writeString(pom, "three");
            assertTrue(change.await(3, TimeUnit.SECONDS));
            assertEquals(1, count.get());
            assertTrue(watcher.lastFailure().isEmpty());
        }
        Files.writeString(pom, "after close");
        Thread.sleep(400);
        assertEquals(1, count.get());
    }

    @Test
    void watchesCreationOfPreviouslyMissingSettings() throws Exception {
        Path settings = root.resolve("settings.xml");
        var changed = new CountDownLatch(1);

        try (var watcher = new MetadataWatcher(List.of(settings), Duration.ofMillis(50),
                changed::countDown)) {
            Files.writeString(settings, "<settings/>");
            assertTrue(changed.await(3, TimeUnit.SECONDS));
        }
    }
}
