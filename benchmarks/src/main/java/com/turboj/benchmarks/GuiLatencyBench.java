package com.turboj.benchmarks;

import com.google.gson.GsonBuilder;
import com.turboj.compiler.JavacCompilerBackend;
import com.turboj.compiler.SourceSnapshot;
import com.turboj.ui.EditorComponent;

import java.awt.KeyboardFocusManager;
import java.awt.event.KeyEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.JFrame;
import javax.swing.SwingUtilities;

/** Visible Swing measurement with synthetic key events. Never claims hardware-input/display latency. */
public final class GuiLatencyBench {
    public static void main(String[] args) throws Exception {
        Path output =
                args.length == 1
                        ? Path.of(args[0])
                        : Path.of("benchmarks/results/gui-latency-macos-arm64.json");
        var results = new ArrayList<Map<String, Object>>();
        for (int lines : new int[] {1000, 10000}) results.add(measure(lines));
        var report =
                Map.of(
                        "recordedAt",
                        Instant.now().toString(),
                        "os",
                        System.getProperty("os.name") + " " + System.getProperty("os.version"),
                        "runtime",
                        System.getProperty("java.runtime.version"),
                        "measurements",
                        results,
                        "limitations",
                        List.of(
                                "Synthetic KeyEvent dispatch, not physical keyboard latency",
                                "Paint completion precedes compositor/display presentation",
                                "Standalone editor component; excludes complete application chrome and live diagnostic UI updates",
                                "Input timestamps cover only the latest key when multiple edits coalesce into a repaint; event/sample counts are included"));
        Files.createDirectories(output.toAbsolutePath().getParent());
        Files.writeString(
                output, new GsonBuilder().setPrettyPrinting().create().toJson(report) + "\n");
        System.out.println("GUI raw report: " + output.toAbsolutePath());
        System.exit(0);
    }

    private static Map<String, Object> measure(int lines) throws Exception {
        var stop = new AtomicBoolean();
        var started = new CountDownLatch(1);
        var backgroundFailure = new AtomicReference<Throwable>();
        Thread compiler =
                Thread.ofPlatform()
                        .name("gui-benchmark-compiler")
                        .start(
                                () -> {
                                    try (var backend = new JavacCompilerBackend()) {
                                        for (int i = 0; !stop.get(); i++) {
                                            started.countDown();
                                            var result =
                                                    backend.compile(
                                                            new SourceSnapshot(
                                                                    "Workload.java",
                                                                    Workloads.source(10000, i),
                                                                    i));
                                            if (!result.success())
                                                throw new IllegalStateException(
                                                        result.diagnostics().toString());
                                        }
                                    } catch (Throwable failure) {
                                        backgroundFailure.set(failure);
                                    }
                                });
        var editorRef = new AtomicReference<EditorComponent>();
        var frameRef = new AtomicReference<JFrame>();
        try {
            if (!started.await(10, TimeUnit.SECONDS))
                throw new IllegalStateException("Background compiler startup failed");
            SwingUtilities.invokeAndWait(
                    () -> {
                        String source = Workloads.source(lines, 0);
                        var editor = new EditorComponent();
                        editor.setText(source);
                        var frame =
                                new JFrame(
                                        "TurboJ editor latency measurement — " + lines + " lines");
                        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
                        frame.add(editor);
                        frame.setSize(1050, 750);
                        frame.setLocationRelativeTo(null);
                        frame.setVisible(true);
                        editor.gotoOffset(source.indexOf("//") + 3);
                        editor.requestFocusInWindow();
                        editorRef.set(editor);
                        frameRef.set(frame);
                    });
            // First native window/Java2D initialization is outside the typing sample interval.
            Thread.sleep(350);
            int count = 250;
            long[] dispatchDelays = new long[count];
            for (int i = 0; i < count; i++) {
                final int index = i;
                long queued = System.nanoTime();
                SwingUtilities.invokeLater(
                        () -> {
                            dispatchDelays[index] = System.nanoTime() - queued;
                            var editor = editorRef.get();
                            KeyboardFocusManager.getCurrentKeyboardFocusManager()
                                    .redispatchEvent(
                                            editor,
                                            new KeyEvent(
                                                    editor,
                                                    KeyEvent.KEY_TYPED,
                                                    System.currentTimeMillis(),
                                                    0,
                                                    KeyEvent.VK_UNDEFINED,
                                                    'x'));
                        });
                Thread.sleep(12); // Input cadence; never a sleep on the event thread.
            }
            SwingUtilities.invokeAndWait(() -> {});
            Thread.sleep(150);
            var samples = new AtomicReference<long[]>();
            SwingUtilities.invokeAndWait(() -> samples.set(editorRef.get().inputToPaintSamples()));
            if (samples.get().length == 0)
                throw new IllegalStateException("No key events were painted");
            var paint =
                    LatencyBench.Measurement.of(
                            "EDT key-handler to paint-return, " + lines + " lines, compiler busy",
                            samples.get());
            var queue =
                    LatencyBench.Measurement.of(
                            "synthetic event queue delay, " + lines + " lines", dispatchDelays);
            System.out.printf(
                    java.util.Locale.ROOT,
                    "%d lines: %d events, %d paints; handler→paint p50 %.3f ms p95 %.3f ms p99 %.3f ms; queue p95 %.3f ms%n",
                    lines,
                    count,
                    samples.get().length,
                    paint.p50Millis(),
                    paint.p95Millis(),
                    paint.p99Millis(),
                    queue.p95Millis());
            if (backgroundFailure.get() != null)
                throw new IllegalStateException(
                        "Background compiler failed", backgroundFailure.get());
            return Map.of(
                    "sourceLines",
                    lines,
                    "typedEvents",
                    count,
                    "paintedSamples",
                    samples.get().length,
                    "handlerToPaint",
                    paint,
                    "eventQueueDelay",
                    queue);
        } finally {
            stop.set(true);
            SwingUtilities.invokeAndWait(
                    () -> {
                        if (frameRef.get() != null) frameRef.get().dispose();
                    });
            compiler.join(30_000);
        }
    }
}
