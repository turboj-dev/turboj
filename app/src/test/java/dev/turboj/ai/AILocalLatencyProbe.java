package dev.turboj.ai;

import dev.turboj.ui.EditorComponent;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.LockSupport;

import javax.swing.SwingUtilities;

/** Explicit real-model contention probe. Never run automatically as part of JUnit. */
public final class AILocalLatencyProbe {
    private static final int SAMPLES = 200;
    private static final long CADENCE = TimeUnit.MILLISECONDS.toNanos(8);
    private static final String SOURCE =
            "class Calculator {\n    int square(int value) {\n        \n    }\n}";
    private static final String PREFIX =
            "public class LocalSquare {\n    static int square(int value) {\n        ";
    private static final String SUFFIX = "\n    }\n}";

    private AILocalLatencyProbe() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2)
            throw new IllegalArgumentException(
                    "Usage: AILocalLatencyProbe <loopback URL> <FIM model>");
        var defaults = AIConfig.defaults();
        var config =
                new AIConfig(
                        AIConfig.Mode.LOCAL,
                        AIConfig.LocalBackend.OLLAMA,
                        args[0],
                        args[1],
                        defaults.cloudEndpoint(),
                        defaults.cloudModel(),
                        false,
                        false,
                        false,
                        200,
                        60,
                        64,
                        2048,
                        defaults.exclusions(),
                        false);
        config.validate();
        Path output = Path.of("app/target/ai-local-editing-latency");
        Files.createDirectories(output);
        StringBuilder report =
                new StringBuilder("Local inference contention probe\n")
                        .append("Timestamp: ")
                        .append(Instant.now())
                        .append('\n')
                        .append("OS: ")
                        .append(System.getProperty("os.name"))
                        .append(' ')
                        .append(System.getProperty("os.version"))
                        .append(' ')
                        .append(System.getProperty("os.arch"))
                        .append('\n')
                        .append("Java: ")
                        .append(System.getProperty("java.runtime.version"))
                        .append("; vendor: ")
                        .append(System.getProperty("java.vendor"))
                        .append('\n')
                        .append("Endpoint: ")
                        .append(args[0])
                        .append("; model: ")
                        .append(args[1])
                        .append('\n')
                        .append(
                                "200 samples per buffer, target cadence 8 ms, 900x500 offscreen"
                                        + " Java2D paint.\n")
                        .append(
                                "20 warmup edit/paint operations before each measured session; all"
                                        + " 200 measured samples retained.\n")
                        .append("This is not OS input-to-screen latency or a model benchmark.\n")
                        .append(
                                "Repeated fixed square-method FIM requests (64 output-token cap)"
                                        + " run sequentially outside the EDT; prompt caching may"
                                        + " apply.\n")
                        .append(
                                "The independent inference workload remains running while edits"
                                    + " occur, deliberately without controller cancellation.\n");
        try (AICompletionProvider provider = AIProviders.create(config, () -> "")) {
            var available = provider.check(new Cancellation());
            if (!available.available()) throw new IllegalStateException(available.message());
            for (int lines : new int[] {1000, 10000}) {
                System.out.println(
                        "Starting " + lines + "-line session with real local inference.");
                report.append(measure(lines, provider, output));
            }
        } finally {
            Files.writeString(output.resolve("report.txt"), report);
        }
        System.out.print(report);
    }

    private static String measure(int lines, AICompletionProvider provider, Path output)
            throws Exception {
        String source = "// contention fixture\n".repeat(lines - 5) + SOURCE;
        EditorComponent editor =
                edt(
                        () -> {
                            var result = new EditorComponent();
                            result.setSize(900, 500);
                            result.setText(source);
                            result.gotoOffset(source.indexOf("        \n") + 8);
                            return result;
                        });
        BufferedImage image = new BufferedImage(900, 500, BufferedImage.TYPE_INT_RGB);
        edt(
                () -> {
                    for (int i = 0; i < 20; i++) editAndPaint(editor, image);
                    return null;
                });
        AtomicBoolean stop = new AtomicBoolean(), active = new AtomicBoolean();
        AtomicInteger issued = new AtomicInteger(),
                completed = new AtomicInteger(),
                deltas = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        CountDownLatch firstDelta = new CountDownLatch(1);
        Cancellation cancellation = new Cancellation();
        List<Long> generationNanos = new ArrayList<>();
        Thread inference =
                Thread.ofPlatform()
                        .daemon()
                        .name("turboj-real-inference-probe")
                        .start(
                                () -> {
                                    try {
                                        while (!stop.get()) {
                                            int iteration = issued.incrementAndGet();
                                            long started = System.nanoTime();
                                            active.set(true);
                                            var request =
                                                    new AICompletionRequest(
                                                            Path.of("LocalSquare.java"),
                                                            iteration,
                                                            PREFIX.length(),
                                                            PREFIX,
                                                            SUFFIX,
                                                            "",
                                                            25,
                                                            false);
                                            provider.stream(
                                                    request,
                                                    value -> {
                                                        if (!value.isEmpty()) {
                                                            deltas.incrementAndGet();
                                                            firstDelta.countDown();
                                                        }
                                                    },
                                                    cancellation);
                                            generationNanos.add(System.nanoTime() - started);
                                            completed.incrementAndGet();
                                            active.set(false);
                                        }
                                    } catch (Throwable error) {
                                        failure.set(error);
                                        firstDelta.countDown();
                                    } finally {
                                        active.set(false);
                                    }
                                });
        double[] paint = new double[SAMPLES], dispatch = new double[SAMPLES];
        StringBuilder csv =
                new StringBuilder(
                        "sample,edit_paint_ms,dispatch_to_paint_ms,request_active,completed_requests\n");
        int overlapping = 0, completedAtStart = 0, completedAtEnd = 0;
        long measuredStart = 0, measuredEnd = 0;
        try {
            if (!firstDelta.await(65, TimeUnit.SECONDS))
                throw new AssertionError("No real model delta received");
            if (failure.get() != null)
                throw new IllegalStateException("Local inference failed", failure.get());
            completedAtStart = completed.get();
            measuredStart = System.nanoTime();
            long due = measuredStart;
            for (int i = 0; i < SAMPLES; i++) {
                long remaining;
                while ((remaining = due - System.nanoTime()) > 0) LockSupport.parkNanos(remaining);
                if (failure.get() != null)
                    throw new IllegalStateException("Local inference failed", failure.get());
                boolean running = active.get();
                if (running) overlapping++;
                long submitted = System.nanoTime();
                paint[i] = edt(() -> editAndPaint(editor, image)) / 1e6;
                dispatch[i] = (System.nanoTime() - submitted) / 1e6;
                csv.append(
                        String.format(
                                Locale.ROOT,
                                "%d,%.6f,%.6f,%s,%d%n",
                                i + 1,
                                paint[i],
                                dispatch[i],
                                running,
                                completed.get()));
                due += CADENCE;
            }
            measuredEnd = System.nanoTime();
            completedAtEnd = completed.get();
        } finally {
            stop.set(true);
            inference.join(
                    TimeUnit.SECONDS.toMillis(65)); // Allow the current generation to complete.
            if (inference.isAlive()) {
                cancellation.cancel();
                inference.join(3000);
                throw new AssertionError(
                        "Local generation did not finish within its configured deadline");
            }
            Files.writeString(output.resolve(lines + "-lines.csv"), csv);
        }
        if (failure.get() != null)
            throw new IllegalStateException("Local inference failed", failure.get());
        if (overlapping == 0)
            throw new AssertionError("No real-inference overlap; inspect raw samples");
        StringBuilder generations = new StringBuilder("request,total_ms\n");
        for (int i = 0; i < generationNanos.size(); i++)
            generations.append(
                    String.format(Locale.ROOT, "%d,%.6f%n", i + 1, generationNanos.get(i) / 1e6));
        Files.writeString(output.resolve(lines + "-inferences.csv"), generations);
        return String.format(
                Locale.ROOT,
                "%n%,d lines | n=%d | measurement span %.2f ms | request active at %d/%d sample"
                        + " starts%n  edit+paint %s%n  dispatch-to-paint %s%n  inference issued %d;"
                        + " completed %d; completed during measurements %d; nonempty deltas %d%n",
                lines,
                SAMPLES,
                (measuredEnd - measuredStart) / 1e6,
                overlapping,
                SAMPLES,
                percentiles(paint),
                percentiles(dispatch),
                issued.get(),
                completed.get(),
                completedAtEnd - completedAtStart,
                deltas.get());
    }

    private static long editAndPaint(EditorComponent editor, BufferedImage image) {
        long start = System.nanoTime();
        editor.replaceSelection(" ");
        var graphics = image.createGraphics();
        try {
            editor.paint(graphics);
        } finally {
            graphics.dispose();
        }
        return System.nanoTime() - start;
    }

    private static String percentiles(double[] samples) {
        double[] sorted = samples.clone();
        Arrays.sort(sorted);
        return String.format(
                Locale.ROOT,
                "p50 %.3f ms | p95 %.3f ms | p99 %.3f ms | max %.3f ms",
                sorted[(int) Math.ceil(sorted.length * .5) - 1],
                sorted[(int) Math.ceil(sorted.length * .95) - 1],
                sorted[(int) Math.ceil(sorted.length * .99) - 1],
                sorted[sorted.length - 1]);
    }

    private static <T> T edt(Callable<T> action) throws Exception {
        FutureTask<T> task = new FutureTask<>(action);
        SwingUtilities.invokeAndWait(task);
        return task.get();
    }
}
