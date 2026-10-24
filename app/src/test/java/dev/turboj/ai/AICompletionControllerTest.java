package dev.turboj.ai;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboj.intelligence.CompletionCoordinator;
import dev.turboj.ui.EditorComponent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

import javax.swing.*;

class AICompletionControllerTest {
    @TempDir Path directory;
    private static final String SOURCE =
            "class Calculator {\n    int square(int value) {\n        \n    }\n}\n";
    private static final int CARET = SOURCE.indexOf("        \n") + 8;

    @Test
    void disabledEditingDoesNotCaptureContextConstructProvidersOrSendRequests() throws Exception {
        AtomicInteger factoryCalls = new AtomicInteger();
        try (Harness h =
                create(
                        AIConfig.defaults(),
                        c -> {
                            factoryCalls.incrementAndGet();
                            throw new AssertionError();
                        })) {
            edt(
                    () -> {
                        for (int i = 0; i < 100; i++) h.editor.replaceSelection("a");
                        h.controller.request(true, false);
                        assertFalse(h.controller.handleKey(key(h.editor, KeyEvent.VK_TAB, 0)));
                        assertEquals("", h.status.get());
                        assertEquals(0, h.snapshots.get());
                        assertTrue(h.notices.getLast().contains("disabled"));
                        return null;
                    });
            assertEquals(0, factoryCalls.get());
        }
    }

    @Test
    void fullAndPartialAcceptanceAreExplicitUndoableAndDoNotTriggerAnotherRequest()
            throws Exception {
        FakeProvider provider =
                new FakeProvider("int result = value * value;\n        return result;", false);
        try (Harness h = create(local(false), c -> provider)) {
            request(h);
            awaitAI(h);
            edt(
                    () -> {
                        assertEquals(SOURCE, h.editor.getText());
                        assertFalse(h.editor.document().canUndo());
                        assertTrue(h.controller.handleKey(key(h.editor, KeyEvent.VK_TAB, 0)));
                        assertTrue(h.editor.getText().contains(provider.text));
                        h.editor.undo();
                        assertEquals(SOURCE, h.editor.getText());
                        assertFalse(h.editor.document().canUndo());
                        return null;
                    });
            request(h);
            awaitAI(h); // same source hits the bounded cache
            assertEquals(1, provider.calls.get());
            edt(
                    () -> {
                        assertTrue(
                                h.controller.handleKey(
                                        key(
                                                h.editor,
                                                KeyEvent.VK_ENTER,
                                                InputEvent.CTRL_DOWN_MASK
                                                        | InputEvent.ALT_DOWN_MASK)));
                        assertEquals(
                                SOURCE.substring(0, CARET) + "int" + SOURCE.substring(CARET),
                                h.editor.getText());
                        assertTrue(h.editor.isAIInlineCompletion());
                        assertTrue(h.editor.inlineCompletion().insertion().startsWith(" result"));
                        assertTrue(h.controller.handleKey(key(h.editor, KeyEvent.VK_TAB, 0)));
                        h.editor.undo();
                        assertTrue(h.editor.getText().contains("        int\n"));
                        h.editor.undo();
                        assertEquals(SOURCE, h.editor.getText());
                        return null;
                    });
            assertEquals(1, provider.calls.get());
            edt(
                    () -> {
                        h.controller.requestAnother();
                        return null;
                    });
            awaitAI(h);
            assertEquals(2, provider.calls.get(), "Another suggestion must bypass the cache");
        }
    }

    @Test
    void typingAndCaretMotionCancelLateResponses() throws Exception {
        for (boolean typing : new boolean[] {true, false}) {
            FakeProvider provider = new FakeProvider("return value * value;", true);
            try (Harness h = create(local(false), c -> provider)) {
                request(h);
                assertTrue(provider.entered.await(5, TimeUnit.SECONDS));
                edt(
                        () -> {
                            if (typing) h.editor.replaceSelection("x");
                            else h.editor.gotoOffset(CARET - 1);
                            return null;
                        });
                assertTrue(provider.cancellation.get().isCancelled());
                provider.release.countDown();
                assertTrue(provider.finished.await(5, TimeUnit.SECONDS));
                edt(
                        () -> {
                            assertNull(h.editor.inlineCompletion());
                            return null;
                        });
                assertEquals(1, provider.calls.get());
            }
        }
    }

    @Test
    void retiringAnOldRequestKeepsItsReplacementCancellable() throws Exception {
        CountDownLatch oldStarted = new CountDownLatch(1), finishOld = new CountDownLatch(1);
        CountDownLatch replacementStarted = new CountDownLatch(1),
                finishReplacement = new CountDownLatch(1);
        AtomicReference<Cancellation> oldToken = new AtomicReference<>(),
                newToken = new AtomicReference<>();
        FakeProvider provider =
                new FakeProvider("return value;", false) {
                    @Override
                    public void stream(
                            AICompletionRequest request,
                            Consumer<String> output,
                            Cancellation token)
                            throws IOException {
                        int call = calls.incrementAndGet();
                        if (call == 1) {
                            oldToken.set(token);
                            oldStarted.countDown();
                        } else {
                            newToken.set(token);
                            replacementStarted.countDown();
                        }
                        try {
                            assertTrue(
                                    (call == 1 ? finishOld : finishReplacement)
                                            .await(5, TimeUnit.SECONDS));
                            output.accept(text); // Deliberately attempt a late delivery after
                            // cancellation.
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                            throw new IOException(e);
                        }
                    }
                };
        try (Harness h = create(local(false), c -> provider)) {
            request(h);
            assertTrue(oldStarted.await(5, TimeUnit.SECONDS));
            request(h); // Install the replacement while the old request is still retiring.
            assertTrue(oldToken.get().isCancelled());
            finishOld.countDown();
            assertTrue(replacementStarted.await(5, TimeUnit.SECONDS));
            assertFalse(newToken.get().isCancelled());
            edt(
                    () -> {
                        h.editor.replaceSelection(" ");
                        return null;
                    });
            assertTrue(
                    newToken.get().isCancelled(),
                    "Old cleanup must not lose the current cancellation handle");
            finishReplacement.countDown();
            edt(
                    () -> {
                        assertNull(h.editor.inlineCompletion());
                        return null;
                    });
            assertEquals(2, provider.calls.get());
        } finally {
            finishOld.countDown();
            finishReplacement.countDown();
        }
    }

    @Test
    void documentIdentityRejectsAnOldResultEvenWhenVersionAndCaretMatch() throws Exception {
        FakeProvider provider = new FakeProvider("return value * value;", true);
        try (Harness h = create(local(false), c -> provider)) {
            request(h);
            assertTrue(provider.entered.await(5, TimeUnit.SECONDS));
            edt(
                    () -> {
                        long version = h.editor.version();
                        h.editor.setOnChange(null);
                        h.editor.setOnCaretChange(null);
                        h.editor.setText(SOURCE.replace("Calculator", "OtherClass"));
                        h.editor.gotoOffset(CARET);
                        assertEquals(version, h.editor.version());
                        return null;
                    });
            provider.release.countDown();
            assertTrue(provider.finished.await(5, TimeUnit.SECONDS));
            edt(
                    () -> {
                        assertNull(h.editor.inlineCompletion());
                        return null;
                    });
        }
    }

    @Test
    void aLateAIResponseCannotReplaceSemanticOrExplicitAlternatives() throws Exception {
        FakeProvider provider = new FakeProvider("return value;", true);
        try (Harness h = create(local(false), c -> provider)) {
            edt(
                    () -> {
                        h.editor.setText("System.out.pr");
                        h.editor.gotoOffset(13);
                        return null;
                    });
            request(h);
            assertTrue(provider.entered.await(5, TimeUnit.SECONDS));
            var selected =
                    edt(
                            () -> {
                                var p =
                                        new EditorComponent.InlineCompletion(
                                                h.editor.version(), 13, 11, 13, "println()", 9);
                                assertTrue(h.coordinator.semantic(p, true));
                                h.coordinator.alternatives(true);
                                return p;
                            });
            provider.release.countDown();
            assertTrue(provider.finished.await(5, TimeUnit.SECONDS));
            edt(
                    () -> {
                        assertEquals(selected, h.editor.inlineCompletion());
                        h.controller.request(true, false);
                        return null;
                    });
            assertEquals(1, provider.calls.get());
            assertTrue(h.notices.getLast().contains("Java completion"));
        }
    }

    @Test
    void disableDuringFactoryCreationClosesUnpublishedProviderWithoutBlockingTheEDT()
            throws Exception {
        CountDownLatch creating = new CountDownLatch(1), releaseFactory = new CountDownLatch(1);
        FakeProvider provider = new FakeProvider("return 1;", false);
        try (Harness h =
                create(
                        local(false),
                        c -> {
                            assertFalse(SwingUtilities.isEventDispatchThread());
                            creating.countDown();
                            try {
                                assertTrue(releaseFactory.await(5, TimeUnit.SECONDS));
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            return provider;
                        })) {
            request(h);
            assertTrue(creating.await(5, TimeUnit.SECONDS));
            edt(
                    () -> {
                        h.controller.configure(AIConfig.defaults());
                        assertEquals("", h.status.get());
                        return null;
                    });
            releaseFactory.countDown();
            assertTrue(provider.closed.await(5, TimeUnit.SECONDS));
            assertEquals(0, provider.calls.get());
            edt(
                    () -> {
                        assertNull(h.editor.inlineCompletion());
                        return null;
                    });
        }
    }

    @Test
    void providerFailureLeavesSemanticEditingAvailableAndNeverLeaksProviderDetails()
            throws Exception {
        FakeProvider provider =
                new FakeProvider("", false) {
                    @Override
                    public void stream(
                            AICompletionRequest request,
                            Consumer<String> output,
                            Cancellation cancellation)
                            throws IOException {
                        calls.incrementAndGet();
                        throw new IOException(
                                "private source and credential should never be shown");
                    }
                };
        try (Harness h = create(local(false), c -> provider)) {
            request(h);
            waitFor(() -> !h.notices.isEmpty());
            edt(
                    () -> {
                        assertFalse(h.notices.getLast().contains("private source"));
                        h.editor.setText("System.out.pr");
                        h.editor.gotoOffset(13);
                        assertTrue(
                                h.coordinator.semantic(
                                        new EditorComponent.InlineCompletion(
                                                h.editor.version(), 13, 11, 13, "println()", 9),
                                        false));
                        assertTrue(h.editor.acceptInlineCompletion());
                        assertEquals("System.out.println()", h.editor.getText());
                        return null;
                    });
        }
    }

    @Test
    void unavailableAutomaticProviderBacksOffButAnExplicitRequestCanRetry() throws Exception {
        FakeProvider provider =
                new FakeProvider("", false) {
                    @Override
                    public void stream(
                            AICompletionRequest request,
                            Consumer<String> output,
                            Cancellation cancellation)
                            throws IOException {
                        calls.incrementAndGet();
                        throw new IOException("offline fixture");
                    }
                };
        try (Harness h = create(local(true), c -> provider)) {
            request(h);
            waitFor(() -> h.status.get().contains("unavailable"));
            edt(
                    () -> {
                        h.editor.replaceSelection(" ");
                        return null;
                    });
            Thread.sleep(350);
            assertEquals(
                    1,
                    provider.calls.get(),
                    "Typing should not repeatedly hit an unavailable server");
            request(h);
            waitFor(() -> provider.calls.get() == 2);
        }
    }

    @Test
    void automaticRequestsCoalesceAndEscapeStopsPendingAndActiveGeneration() throws Exception {
        FakeProvider provider = new FakeProvider("return value;", true);
        try (Harness h = create(local(true), c -> provider)) {
            edt(
                    () -> {
                        for (int i = 0; i < 30; i++) h.editor.replaceSelection(" ");
                        assertEquals(0, h.snapshots.get());
                        return null;
                    });
            assertTrue(provider.entered.await(5, TimeUnit.SECONDS));
            assertEquals(1, provider.calls.get());
            assertEquals(1, h.snapshots.get());
            edt(
                    () -> {
                        assertFalse(h.controller.handleKey(key(h.editor, KeyEvent.VK_ESCAPE, 0)));
                        h.coordinator.clear();
                        return null;
                    });
            assertTrue(provider.cancellation.get().isCancelled());
            provider.release.countDown();
            assertTrue(provider.finished.await(5, TimeUnit.SECONDS));
            edt(
                    () -> {
                        assertNull(h.editor.inlineCompletion());
                        return null;
                    });
        }
    }

    @Test
    void slowInferenceDoesNotWaitOnEditsOrPaintingInLargeBuffers() throws Exception {
        StringBuilder report =
                new StringBuilder(
                        "Synthetic blocked-provider fixture; editing and offscreen Java2D paint"
                                + " only, not model latency.\n");
        for (int lines : new int[] {1_000, 10_000}) {
            FakeProvider provider = new FakeProvider("return value;", true);
            try (Harness h = create(local(false), c -> provider)) {
                edt(
                        () -> {
                            h.editor.setText("// fixture\n".repeat(lines) + SOURCE);
                            h.editor.gotoOffset(h.editor.getText().indexOf("        \n") + 8);
                            return null;
                        });
                request(h);
                assertTrue(provider.entered.await(8, TimeUnit.SECONDS));
                double[] samples =
                        edt(
                                () -> {
                                    double[] values = new double[100];
                                    BufferedImage image =
                                            new BufferedImage(900, 500, BufferedImage.TYPE_INT_RGB);
                                    for (int i = 0; i < values.length; i++) {
                                        long start = System.nanoTime();
                                        h.editor.replaceSelection(" ");
                                        var graphics = image.createGraphics();
                                        h.editor.paint(graphics);
                                        graphics.dispose();
                                        values[i] = (System.nanoTime() - start) / 1e6;
                                    }
                                    return values;
                                });
                Arrays.sort(samples);
                report.append(
                        String.format(
                                Locale.ROOT,
                                "%,d lines: n=100 edit+paint p50 %.2f ms p95 %.2f ms max %.2f ms%n",
                                lines,
                                samples[49],
                                samples[94],
                                samples[99]));
                assertTrue(
                        samples[94] < 100, "EDT unexpectedly waited on inference: " + samples[94]);
                assertEquals(1, provider.calls.get());
                assertTrue(provider.cancellation.get().isCancelled());
                provider.release.countDown();
            }
        }
        Files.createDirectories(Path.of("target"));
        Files.writeString(Path.of("target/ai-controller-latency.txt"), report);
        System.out.print(report);
    }

    private Harness create(AIConfig config, AICompletionController.ProviderFactory factory)
            throws Exception {
        return edt(() -> new Harness(directory.resolve("Calculator.java"), config, factory));
    }

    private static AIConfig local(boolean automatic) {
        AIConfig d = AIConfig.defaults();
        return new AIConfig(
                AIConfig.Mode.LOCAL,
                d.localBackend(),
                d.localEndpoint(),
                d.localModel(),
                d.cloudEndpoint(),
                d.cloudModel(),
                automatic,
                false,
                false,
                200,
                3,
                256,
                d.contextChars(),
                d.exclusions(),
                false);
    }

    private static void request(Harness h) throws Exception {
        edt(
                () -> {
                    h.controller.request(true, false);
                    return null;
                });
    }

    private static void awaitAI(Harness h) throws Exception {
        waitFor(() -> h.editor.isAIInlineCompletion());
    }

    private static void waitFor(Callable<Boolean> predicate) throws Exception {
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < end) {
            if (edt(predicate)) return;
            Thread.sleep(5);
        }
        fail("Timed out waiting for controller state");
    }

    private static KeyEvent key(EditorComponent editor, int code, int modifiers) {
        return new KeyEvent(
                editor,
                KeyEvent.KEY_PRESSED,
                System.currentTimeMillis(),
                modifiers,
                code,
                KeyEvent.CHAR_UNDEFINED);
    }

    private static <T> T edt(Callable<T> action) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) return action.call();
        FutureTask<T> result = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(result);
        return result.get();
    }

    private static final class Harness implements AutoCloseable {
        final EditorComponent editor = new EditorComponent();
        final CompletionCoordinator coordinator = new CompletionCoordinator(editor);
        final AtomicInteger snapshots = new AtomicInteger();
        final AtomicReference<String> status = new AtomicReference<>("");
        final List<String> notices = new CopyOnWriteArrayList<>();
        final AICompletionController controller;
        final KeyboardFocusManager previous = KeyboardFocusManager.getCurrentKeyboardFocusManager();

        Harness(Path file, AIConfig config, AICompletionController.ProviderFactory factory) {
            editor.setSize(900, 500);
            editor.setText(SOURCE);
            editor.gotoOffset(CARET);
            KeyboardFocusManager.setCurrentKeyboardFocusManager(
                    new DefaultKeyboardFocusManager() {
                        @Override
                        public Component getFocusOwner() {
                            return editor;
                        }
                    });
            assertTrue(
                    editor.hasFocus(),
                    "Controlled focus is needed for headless controller delivery");
            controller =
                    new AICompletionController(
                            editor,
                            coordinator,
                            () -> {
                                snapshots.incrementAndGet();
                                return new AICompletionController.Snapshot(file, editor.snapshot());
                            },
                            config,
                            factory,
                            notices::add,
                            status::set);
            Runnable changed =
                    () -> {
                        coordinator.clear();
                        controller.changed();
                    };
            editor.setOnChange(changed);
            editor.setOnCaretChange(changed);
        }

        public void close() throws Exception {
            edt(
                    () -> {
                        controller.close();
                        KeyboardFocusManager.setCurrentKeyboardFocusManager(previous);
                        return null;
                    });
        }
    }

    private static class FakeProvider implements AICompletionProvider {
        final String text;
        final CountDownLatch entered = new CountDownLatch(1),
                release,
                finished = new CountDownLatch(1),
                closed = new CountDownLatch(1);
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<Cancellation> cancellation = new AtomicReference<>();

        FakeProvider(String text, boolean block) {
            this.text = text;
            release = new CountDownLatch(block ? 1 : 0);
        }

        public void stream(AICompletionRequest request, Consumer<String> output, Cancellation token)
                throws IOException {
            assertFalse(SwingUtilities.isEventDispatchThread());
            calls.incrementAndGet();
            cancellation.set(token);
            entered.countDown();
            try {
                if (!release.await(10, TimeUnit.SECONDS)) throw new IOException("Fixture timeout");
                output.accept(text);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException(e);
            } finally {
                finished.countDown();
            }
        }

        public Availability check(Cancellation token) {
            return new Availability(true, "fixture");
        }

        public void close() {
            closed.countDown();
            release.countDown();
        }
    }
}
