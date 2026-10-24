package dev.turboj.formatting;

import static org.junit.jupiter.api.Assertions.*;

import dev.turboj.ui.EditorComponent;

import dev.javafmt.core.FormatDiagnostic;
import dev.javafmt.core.FormatRequest;
import dev.javafmt.core.FormatResult;
import dev.javafmt.core.TextEdit;
import dev.javafmt.core.TextRange;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.SwingUtilities;

class FormattingControllerTest {
    @Test
    void formatsOffEdtAndAppliesOneUndoableTransaction() throws Exception {
        var editor = editor("class X{}");
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<FormattingController.Outcome> outcome = new AtomicReference<>();
        var controller =
                edt(
                        () ->
                                new FormattingController(
                                        editor,
                                        (request, range) -> {
                                            assertFalse(SwingUtilities.isEventDispatchThread());
                                            assertNull(range);
                                            return formatted(request);
                                        },
                                        ignored -> {},
                                        ignored ->
                                                assertTrue(
                                                        SwingUtilities.isEventDispatchThread())));
        try {
            edt(
                    () -> {
                        controller.format(
                                false,
                                value -> {
                                    outcome.set(value);
                                    finished.countDown();
                                });
                        return null;
                    });
            assertTrue(finished.await(5, TimeUnit.SECONDS));
            assertEquals(FormattingController.Outcome.APPLIED, outcome.get());
            assertEquals("class X {}", edt(editor::getText));
            edt(
                    () -> {
                        editor.undo();
                        return null;
                    });
            assertEquals("class X{}", edt(editor::getText));
        } finally {
            edt(
                    () -> {
                        controller.close();
                        return null;
                    });
        }
    }

    @Test
    void typingDuringFormattingDiscardsOldResultsWithoutBlockingEditing() throws Exception {
        var editor = editor("class X{}");
        CountDownLatch entered = new CountDownLatch(1),
                release = new CountDownLatch(1),
                finished = new CountDownLatch(1);
        AtomicReference<FormattingController.Outcome> outcome = new AtomicReference<>();
        var controller =
                edt(
                        () ->
                                new FormattingController(
                                        editor,
                                        (request, range) -> {
                                            entered.countDown();
                                            await(release);
                                            return formatted(request);
                                        },
                                        ignored -> {},
                                        ignored -> fail("Stale result applied")));
        try {
            edt(
                    () -> {
                        controller.format(
                                false,
                                value -> {
                                    outcome.set(value);
                                    finished.countDown();
                                });
                        return null;
                    });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            edt(
                    () -> {
                        editor.gotoOffset(editor.getText().length());
                        editor.replaceSelection("// typing");
                        return null;
                    });
            assertEquals("class X{}// typing", edt(editor::getText));
            release.countDown();
            assertTrue(finished.await(5, TimeUnit.SECONDS));
            assertEquals(FormattingController.Outcome.STALE, outcome.get());
            assertEquals("class X{}// typing", edt(editor::getText));
        } finally {
            release.countDown();
            edt(
                    () -> {
                        controller.close();
                        return null;
                    });
        }
    }

    @Test
    void equalVersionOnAnotherBufferCannotReceiveTheOldResult() throws Exception {
        var editor = editor("class X{}");
        CountDownLatch entered = new CountDownLatch(1),
                release = new CountDownLatch(1),
                finished = new CountDownLatch(1);
        AtomicReference<FormattingController.Outcome> outcome = new AtomicReference<>();
        var controller =
                edt(
                        () ->
                                new FormattingController(
                                        editor,
                                        (request, range) -> {
                                            entered.countDown();
                                            await(release);
                                            return formatted(request);
                                        },
                                        ignored -> {},
                                        ignored -> fail("Cross-buffer result applied")));
        try {
            long version = edt(editor::version);
            edt(
                    () -> {
                        controller.format(
                                false,
                                value -> {
                                    outcome.set(value);
                                    finished.countDown();
                                });
                        return null;
                    });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            edt(
                    () -> {
                        editor.setText("class Y{}");
                        return null;
                    });
            assertEquals(version, edt(editor::version));
            release.countDown();
            assertTrue(finished.await(5, TimeUnit.SECONDS));
            assertEquals(FormattingController.Outcome.STALE, outcome.get());
            assertEquals("class Y{}", edt(editor::getText));
        } finally {
            release.countDown();
            edt(
                    () -> {
                        controller.close();
                        return null;
                    });
        }
    }

    @Test
    void newestRequestSupersedesPendingWorkAndSettlesAllCallbacks() throws Exception {
        var editor = editor("class X{}");
        AtomicInteger calls = new AtomicInteger(), stale = new AtomicInteger();
        CountDownLatch entered = new CountDownLatch(1),
                release = new CountDownLatch(1),
                finished = new CountDownLatch(1);
        var controller =
                edt(
                        () ->
                                new FormattingController(
                                        editor,
                                        (request, range) -> {
                                            if (calls.incrementAndGet() == 1) {
                                                entered.countDown();
                                                await(release);
                                            }
                                            return formatted(request);
                                        },
                                        ignored -> {},
                                        ignored -> {}));
        try {
            edt(
                    () -> {
                        controller.format(
                                false,
                                value -> {
                                    if (value == FormattingController.Outcome.STALE)
                                        stale.incrementAndGet();
                                });
                        return null;
                    });
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            edt(
                    () -> {
                        controller.format(
                                false,
                                value -> {
                                    if (value == FormattingController.Outcome.STALE)
                                        stale.incrementAndGet();
                                });
                        controller.format(
                                false,
                                value -> {
                                    assertEquals(FormattingController.Outcome.APPLIED, value);
                                    finished.countDown();
                                });
                        return null;
                    });
            assertEquals(2, stale.get());
            release.countDown();
            assertTrue(finished.await(5, TimeUnit.SECONDS));
            assertEquals(2, calls.get(), "obsolete queued request must not run");
        } finally {
            release.countDown();
            edt(
                    () -> {
                        controller.close();
                        return null;
                    });
        }
    }

    @Test
    void invalidSourceIsUnchangedAndSelectionUsesOriginalOffsets() throws Exception {
        var editor = editor("class X{bad}");
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<TextRange> requested = new AtomicReference<>();
        AtomicReference<FormattingController.Outcome> outcome = new AtomicReference<>();
        var controller =
                edt(
                        () ->
                                new FormattingController(
                                        editor,
                                        (request, range) -> {
                                            requested.set(range);
                                            return new FormatResult(
                                                    request.source(),
                                                    List.of(),
                                                    request.sourceVersion(),
                                                    "0.1.0",
                                                    1,
                                                    List.of(
                                                            new FormatDiagnostic(
                                                                    8, 11, "Syntax error")),
                                                    false,
                                                    range);
                                        },
                                        ignored -> {},
                                        ignored -> fail("Invalid format result applied")));
        try {
            edt(
                    () -> {
                        editor.restoreLocation(new EditorComponent.Location(11, 8, 0, 0));
                        controller.format(
                                true,
                                value -> {
                                    outcome.set(value);
                                    finished.countDown();
                                });
                        return null;
                    });
            assertTrue(finished.await(5, TimeUnit.SECONDS));
            assertEquals(new TextRange(8, 11), requested.get());
            assertEquals(FormattingController.Outcome.FAILED, outcome.get());
            assertEquals("class X{bad}", edt(editor::getText));
            assertFalse(edt(() -> editor.document().canUndo()));
        } finally {
            edt(
                    () -> {
                        controller.close();
                        return null;
                    });
        }
    }

    private static FormatResult formatted(FormatRequest request) {
        int at = request.source().indexOf('{');
        var edits = List.of(new TextEdit(at, at, " "));
        return new FormatResult(
                TextEdit.apply(request.source(), edits),
                edits,
                request.sourceVersion(),
                "0.1.0",
                1,
                List.of(),
                true,
                new TextRange(0, request.source().length()));
    }

    private static EditorComponent editor(String text) throws Exception {
        return edt(
                () -> {
                    var editor = new EditorComponent();
                    editor.setSize(900, 500);
                    editor.setText(text);
                    return editor;
                });
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) throw new AssertionError("Timed out");
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
    }

    private static <T> T edt(Callable<T> action) throws Exception {
        var task = new FutureTask<T>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }
}
