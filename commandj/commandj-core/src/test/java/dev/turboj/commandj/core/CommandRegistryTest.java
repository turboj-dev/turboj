package dev.turboj.commandj.core;

import org.junit.jupiter.api.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class CommandRegistryTest {
    @Test void aliasesPreserveExecutionWithoutDuplicatePaletteRows() throws Exception {
        try (var registry = new CommandRegistry()) {
            var count = new java.util.concurrent.atomic.AtomicInteger();
            registry.register(Command.action("canonical", "Canonical", "Test", count::incrementAndGet));
            registry.alias("old-id", "canonical");
            assertEquals("canonical", registry.find("old-id").orElseThrow().id());
            registry.execute("old-id", CommandContext.of()).stage().toCompletableFuture().get();
            assertEquals(1, count.get());
            assertEquals(1, registry.commands().size());
            assertEquals(java.util.List.of("canonical"), registry.recentCommands());
            assertThrows(IllegalArgumentException.class, () -> registry.alias("old-id", "canonical"));
            registry.unregister("canonical");
            assertTrue(registry.find("old-id").isEmpty());
        }
    }
    @Test
    void registrationLookupAndDuplicateProtection() {
        try (var registry = new CommandRegistry()) {
            registry.register(Command.action("save", "Save", "File", () -> {}));
            assertEquals("Save", registry.find("save").orElseThrow().title());
            assertThrows(IllegalArgumentException.class, () -> registry.register(Command
                    .action("save", "Other", "File", () -> {})));
            registry.unregister("save");
            assertTrue(registry.commands().isEmpty());
        }
    }

    @Test
    void contextRequirementsAndReasonsPreventExecution() {
        try (var registry = new CommandRegistry()) {
            var count = new AtomicInteger();
            registry.register(Command.action("edit", "Edit", "Code", count::incrementAndGet)
                    .requiring("editor").when(c -> c.has("readOnly") ? Availability
                    .disabled("Read only source") : Availability.available()));
            assertEquals(CommandResult.Status.DISABLED, registry.execute("edit", CommandContext
                    .of()).stage().toCompletableFuture().join().status());
            assertEquals("Read only source", registry.availability("edit", CommandContext
                    .of("editor", "readOnly")).reason());
            assertEquals(0, count.get());
            assertEquals(CommandResult.Status.SUCCEEDED, registry.execute("edit", CommandContext
                    .of("editor")).stage().toCompletableFuture().join().status());
            assertEquals(1, count.get());
        }
    }

    @Test
    void callerAndAsynchronousExecutionDoNotWaitForCompletion() throws Exception {
        try (var registry = new CommandRegistry()) {
            String calling = Thread.currentThread().getName();
            var future = new CompletableFuture<CommandResult> ();
            var entered = new CountDownLatch(1);
            var executing = new AtomicReference<String> ();
            registry.register(Command.of("async", "Async", "Test", (c, t) -> {
                executing.set(Thread.currentThread().getName());
                entered.countDown();
                return future;
            }).inBackground(true));
            var execution = registry.execute("async", CommandContext.of());
            assertFalse(execution.isDone());
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertNotEquals(calling, executing.get());
            future.complete(CommandResult.success());
            assertEquals(CommandResult.Status.SUCCEEDED, execution.stage().toCompletableFuture()
                    .get(2, TimeUnit.SECONDS).status());
        }
    }

    @Test
    void cooperativeCancellationPublishesImmediatelyAndNotifiesProvider() throws Exception {
        try (var registry = new CommandRegistry()) {
            var entered = new CountDownLatch(1);
            var cancelled = new AtomicBoolean();
            var upstream = new CompletableFuture<CommandResult> ();
            registry.register(Command.of("slow", "Slow", "Test", (c, t) -> {
                t.onCancel(() -> cancelled.set(true));
                entered.countDown();
                return upstream;
            }).inBackground(true));
            var execution = registry.execute("slow", CommandContext.of());
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertTrue(execution.cancel());
            assertTrue(cancelled.get());
            assertEquals(CommandResult.Status.CANCELLED, execution.stage().toCompletableFuture()
                    .get(2, TimeUnit.SECONDS).status());
            upstream.complete(CommandResult.success());
            assertTrue(registry.recentCommands().isEmpty());
        }
    }

    @Test
    void closeCompletesQueuedCommandsAndBlocksNewRequests() throws Exception {
        var registry = new CommandRegistry();
        var release = new CountDownLatch(1);
        registry.register(Command.of("wait", "Wait", "Test", (c, t) -> {
            release.await();
            return CompletableFuture.completedFuture(CommandResult.success());
        }).inBackground(true));
        var a = registry.execute("wait", CommandContext.of());
        var b = registry.execute("wait", CommandContext.of());
        registry.close();
        release.countDown();
        assertEquals(CommandResult.Status.CANCELLED, a.stage().toCompletableFuture().get(2,
                TimeUnit.SECONDS).status());
        assertEquals(CommandResult.Status.CANCELLED, b.stage().toCompletableFuture().get(2,
                TimeUnit.SECONDS).status());
        assertEquals(CommandResult.Status.FAILED, registry.execute("wait", CommandContext.of())
                .stage().toCompletableFuture().join().status());
    }

    @Test
    void errorsAndRecentCommandsAreDeterministic() {
        try (var registry = new CommandRegistry()) {
            registry.register(Command.action("fail", "Fail", "Test", () -> {
                throw new IllegalStateException("Expected failure");
            }));
            assertEquals("Expected failure", registry.execute("fail", CommandContext.of()).stage()
                    .toCompletableFuture().join().message());
            for (String id : List.of("a", "b", "c"))
                registry.register(Command.action(id, id, "Test", () -> {}));
            for (String id : List.of("a", "b", "a", "c"))
                registry.execute(id, CommandContext.of()).stage().toCompletableFuture().join();
            assertEquals(List.of("c", "a", "b"), registry.recentCommands());
            registry.restoreRecent(List.of("missing", "a", "a", "b"));
            assertEquals(List.of("a", "b"), registry.recentCommands());
        }
    }

    @Test
    void requestsAreBounded() {
        try (var registry = new CommandRegistry()) {
            var upstream = new CompletableFuture<CommandResult> ();
            registry.register(Command.of("pending", "Pending", "Test", (c, t) -> upstream));
            for (int i = 0; i < 128; i++)
                assertFalse(registry.execute("pending", CommandContext.of()).isDone());
            assertEquals(CommandResult.Status.FAILED, registry.execute("pending", CommandContext
                    .of()).stage().toCompletableFuture().join().status());
            upstream.complete(CommandResult.success());
        }
    }

    @Test
    void cancellationListenerRegistrationRaceCallsOnce() throws Exception {
        for (int i = 0; i < 100; i++) {
            var token = new CancellationToken();
            var count = new AtomicInteger();
            var thread = new Thread(() -> token.onCancel(count::incrementAndGet));
            thread.start();
            token.cancel();
            thread.join();
            assertEquals(1, count.get());
        }
    }
}
