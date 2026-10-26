package dev.turboj.app;

import dev.turboj.config.SafeFiles;
import dev.turboj.config.Settings;

import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;

/** Real-window regression for saving an inactive unsaved buffer during source navigation. */
public final class BufferSaveSmoke {
    private static final StringBuilder REPORT = new StringBuilder();
    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    public static void main(String[] args) throws Exception {
        Path target = Path.of("app", "target").toAbsolutePath();
        Files.createDirectories(target);
        Path fixtures = Files.createTempDirectory(target, "buffer-save-smoke-");
        System.setProperty("turboj.config", fixtures.resolve("config").toString());
        System.setProperty("apple.laf.useScreenMenuBar", "false");
        Path first = fixtures.resolve("A.java");
        Path second = fixtures.resolve("B.java");
        String original = "public class A { int value = 1; }\n";
        String otherSource = "public class B { int value = 2; }\n";
        Files.writeString(first, original);
        Files.writeString(second, otherSource);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger savedMessages = new AtomicInteger();
        TurboFrame frame =
                edt(
                        () -> {
                            UIManager.setLookAndFeel(
                                    UIManager.getCrossPlatformLookAndFeelClassName());
                            TurboFrame value = new TurboFrame(new Settings(), Map.of());
                            value.console()
                                    .inputComponent()
                                    .getDocument()
                                    .addDocumentListener(
                                            new DocumentListener() {
                                                public void insertUpdate(DocumentEvent event) {
                                                    try {
                                                        String inserted =
                                                                event.getDocument()
                                                                        .getText(
                                                                                event.getOffset(),
                                                                                event.getLength());
                                                        if (inserted.contains("Saved A.java"))
                                                            savedMessages.incrementAndGet();
                                                    } catch (
                                                            javax.swing.text.BadLocationException
                                                                    e) {
                                                        throw new AssertionError(e);
                                                    }
                                                }

                                                public void removeUpdate(DocumentEvent event) {}

                                                public void changedUpdate(DocumentEvent event) {}
                                            });
                            value.setVisible(true);
                            value.toFront();
                            value.editor().requestFocusInWindow();
                            return value;
                        });
        long started = System.nanoTime();
        try {
            open(frame, first);
            open(frame, second);
            open(frame, first);
            String edited = original + "// first unsaved edit\n";
            edt(
                    () -> {
                        frame.editor().gotoOffset(frame.editor().getText().length());
                        frame.editor().replaceSelection("// first unsaved edit\n");
                        return null;
                    });

            ScheduledExecutorService background =
                    field(frame, "background", ScheduledExecutorService.class);
            CountDownLatch blocked = new CountDownLatch(1);
            background.execute(
                    () -> {
                        blocked.countDown();
                        try {
                            if (!release.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS))
                                throw new IllegalStateException(
                                        "Save regression blocker timed out");
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        }
                    });
            check(
                    blocked.await(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS),
                    "background did not reach blocker");
            long switchNanos =
                    edt(
                            () -> {
                                saveFromMenu(frame);
                                check(
                                        field(frame, "saving", Boolean.class),
                                        "Save did not queue an asynchronous write");
                                long before = System.nanoTime();
                                frame.openPath(second);
                                check(
                                        second.equals(frame.currentFile()),
                                        "cached navigation waited for the blocked filesystem"
                                                + " executor");
                                check(
                                        otherSource.equals(frame.editor().getText()),
                                        "navigation did not restore B.java");
                                return System.nanoTime() - before;
                            });
            check(
                    original.equals(Files.readString(first)),
                    "Save ran before the intentional blocker was released");
            release.countDown();
            waitFor(
                    () -> !field(frame, "saving", Boolean.class),
                    "inactive A.java save completion");
            check(
                    edited.equals(Files.readString(first)),
                    "inactive A.java was not saved with its captured source");
            check(
                    second.equals(edt(frame::currentFile)),
                    "finishing inactive save switched the active editor");
            open(frame, first);
            byte[] savedFingerprint = SafeFiles.fingerprint(first);
            edt(
                    () -> {
                        check(
                                edited.equals(frame.editor().getText()),
                                "returning to A.java lost its unsaved edits");
                        check(
                                frame.editor().version()
                                        == field(frame, "savedVersion", Long.class),
                                "inactive save did not update savedVersion");
                        check(
                                Arrays.equals(
                                        savedFingerprint,
                                        field(frame, "diskFingerprint", byte[].class)),
                                "inactive save did not update its disk fingerprint");
                        check(
                                !frame.getTitle().contains(" *"),
                                "successfully saved inactive buffer is still marked dirty");
                        frame.editor().undo();
                        check(
                                original.equals(frame.editor().getText()),
                                "navigation discarded A.java's undo history");
                        frame.editor().redo();
                        check(
                                edited.equals(frame.editor().getText()),
                                "navigation discarded A.java's redo history");
                        return null;
                    });
            note(
                    String.format(
                            java.util.Locale.ROOT,
                            "PASS blocked asynchronous Save + cached navigation: %.2f ms local"
                                    + " switch; inactive source, savedVersion, fingerprint and"
                                    + " undo/redo preserved",
                            switchNanos / 1e6));

            String editedAgain = edited + "// second edit after return\n";
            int beforeSecondSave = savedMessages.get();
            edt(
                    () -> {
                        frame.editor().gotoOffset(frame.editor().getText().length());
                        frame.editor().replaceSelection("// second edit after return\n");
                        saveFromMenu(frame);
                        return null;
                    });
            waitFor(
                    () ->
                            !field(frame, "saving", Boolean.class)
                                    && savedMessages.get() > beforeSecondSave,
                    "second save completion and console acknowledgment");
            check(
                    editedAgain.equals(Files.readString(first)),
                    "second save rejected the internally updated fingerprint");
            edt(
                    () -> {
                        check(
                                !"ERROR".equals(frame.buildStatus()),
                                "second save reported an unexpected error");
                        check(
                                !frame.statusMessages().contains("File changed outside TurboJ"),
                                "second save incorrectly detected an external change");
                        check(
                                savedMessages.get() > beforeSecondSave,
                                "Save command did not report success");
                        check(
                                frame.editor().version()
                                        == field(frame, "savedVersion", Long.class),
                                "second save left the buffer dirty");
                        return null;
                    });
            note(
                    "PASS return to saved buffer, edit and save again: disk updated and Saved"
                            + " A.java reported without a false external-change error");

            String external = "public class A { int value = 99; }\n// external edit\n";
            Files.writeString(first, external);
            String unsavedThird = editedAgain + "// third local edit\n";
            edt(
                    () -> {
                        frame.editor().gotoOffset(frame.editor().getText().length());
                        frame.editor().replaceSelection("// third local edit\n");
                        saveFromMenu(frame);
                        return null;
                    });
            waitFor(() -> !field(frame, "saving", Boolean.class), "external-change rejection");
            check(
                    external.equals(Files.readString(first)),
                    "save overwrote a real external file change");
            edt(
                    () -> {
                        check(
                                unsavedThird.equals(frame.editor().getText()),
                                "rejected save discarded the local buffer");
                        check(
                                frame.statusMessages().contains("File changed outside TurboJ"),
                                "external-change protection did not report the conflict");
                        check(
                                frame.editor().version()
                                        != field(frame, "savedVersion", Long.class),
                                "rejected save incorrectly marked local changes saved");
                        frame.openPath(second);
                        check(
                                second.equals(frame.currentFile()),
                                "editor stopped responding after rejected save");
                        check(
                                otherSource.equals(frame.editor().getText()),
                                "external-change failure corrupted another buffer");
                        return null;
                    });
            note(
                    "PASS real external file changes are rejected; disk and local unsaved source"
                            + " remain intact, navigation remains responsive");
            note(
                    String.format(
                            java.util.Locale.ROOT,
                            "PASS BufferSaveSmoke completed in %.2f s",
                            (System.nanoTime() - started) / 1e9));
        } finally {
            release.countDown();
            Files.writeString(target.resolve("buffer-save-smoke.txt"), REPORT.toString());
            edt(
                    () -> {
                        frame.shutdown();
                        return null;
                    });
            waitFor(() -> !frame.isDisplayable(), "smoke window shutdown");
        }
    }

    private static void open(TurboFrame frame, Path path) throws Exception {
        edt(
                () -> {
                    frame.openPath(path);
                    return null;
                });
        waitFor(() -> path.equals(frame.currentFile()), "open " + path.getFileName());
    }

    private static void saveFromMenu(TurboFrame frame) {
        JMenu file = java.util.stream.IntStream.range(0, frame.getJMenuBar().getMenuCount()).mapToObj(frame.getJMenuBar()::getMenu)
                .filter(menu -> menu.getText().equals("File")).findFirst().orElseThrow();
        for (int i = 0; i < file.getItemCount(); i++) {
            JMenuItem item = file.getItem(i);
            if (item != null && "Save".equals(item.getText())) {
                item.doClick(0);
                return;
            }
        }
        throw new AssertionError("Functional File → Save menu action is missing");
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object instance, String name, Class<T> type) throws Exception {
        Field field = instance.getClass().getDeclaredField(name);
        field.setAccessible(true);
        return (T) field.get(instance);
    }

    private static void waitFor(Callable<Boolean> condition, String description) throws Exception {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (edt(condition)) return;
            Thread.sleep(20);
        }
        throw new AssertionError("Timed out: " + description);
    }

    private static <T> T edt(Callable<T> action) throws Exception {
        CompletableFuture<T> future = new CompletableFuture<>();
        SwingUtilities.invokeLater(
                () -> {
                    try {
                        future.complete(action.call());
                    } catch (Throwable error) {
                        future.completeExceptionally(error);
                    }
                });
        return future.get(TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
    }

    private static void check(boolean condition, String description) {
        if (!condition) throw new AssertionError(description);
    }

    private static void note(String message) {
        System.out.println(message);
        REPORT.append(message).append('\n');
    }
}
