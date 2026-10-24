package dev.turboj.compiler;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

class ContinuousCompilerTest {
    @Test
    void rapidRequestsRejectStaleCompilerResults() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        CompilerBackend backend =
                snapshot -> {
                    if (snapshot.version() == 1) {
                        entered.countDown();
                        try {
                            assertTrue(release.await(5, TimeUnit.SECONDS));
                        } catch (InterruptedException exception) {
                            throw new AssertionError(exception);
                        }
                    }
                    return new CompilationResult(
                            snapshot.version(), true, List.of(), Map.of(), 1, false, null);
                };
        try (var service = new ContinuousCompiler(backend)) {
            var first = service.compileNow(new SourceSnapshot("Hello.java", "class Hello {}", 1));
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            var latest =
                    service.compileNow(
                            new SourceSnapshot("Hello.java", "class Hello { int x; }", 2));
            release.countDown();
            assertThrows(CancellationException.class, first::join);
            assertEquals(2, latest.get(5, TimeUnit.SECONDS).version());
            assertEquals(1, service.supersededResults());
        } finally {
            release.countDown();
        }
    }

    @Test
    void coalescesTypingToLatestVersion() throws Exception {
        var count = new AtomicInteger();
        var published = new AtomicLong(-1);
        var done = new CountDownLatch(1);
        CompilerBackend backend =
                snapshot -> {
                    count.incrementAndGet();
                    return new CompilationResult(
                            snapshot.version(), true, List.of(), Map.of(), 1, false, null);
                };
        try (var service = new ContinuousCompiler(backend)) {
            for (int i = 0; i < 100; i++) {
                service.submit(
                        new SourceSnapshot("Hello.java", "class Hello { int x = " + i + "; }", i),
                        result -> {
                            published.set(result.version());
                            done.countDown();
                        });
            }
            assertTrue(done.await(10, TimeUnit.SECONDS));
            assertEquals(99, published.get());
            assertEquals(1, count.get());
        }
    }
}
