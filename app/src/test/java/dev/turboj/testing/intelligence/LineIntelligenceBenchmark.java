package dev.turboj.testing.intelligence;

import com.google.gson.GsonBuilder;
import dev.turboj.testing.intelligence.LineIntelligence.*;
import dev.turboj.ui.EditorComponent;

import javax.swing.SwingUtilities;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Standalone, local baseline harness; not a timing-sensitive unit test.
 * Run after test compilation with app/target/test-classes:app/target/classes and
 * the reactor runtime classpath. Prints measurements, never a performance verdict.
 */
public final class LineIntelligenceBenchmark {
    private static final int WARMUP = 20, PAINT_SAMPLES = 100, GIT_SAMPLES = 15;
    private LineIntelligenceBenchmark() {}

    public static void main(String[] args) throws Exception {
        var report = new LinkedHashMap<String, Object>();
        report.put("runtime", System.getProperty("java.runtime.version"));
        report.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version") + " " + System.getProperty("os.arch"));
        report.put("processors", Runtime.getRuntime().availableProcessors());
        report.put("conditions", "Synthetic source/evidence fixture. Serial offscreen Java2D paint on the EDT; "
                + "not native input-to-display latency. No assertion that targets are attained. Git runs off the EDT "
                + "against an owned local temporary repository, no remote. All source/class evidence is unchanged by the harness.");
        report.put("warmupIterations", WARMUP);
        report.put("paintSamples", PAINT_SAMPLES);
        var painting = new ArrayList<Map<String, Object>>();
        for (int lineCount : new int[] { 10_000, 100_000 }) {
            String source = source(lineCount);
            SwingUtilities.invokeAndWait(() -> painting.add(paint(source, lineCount)));
        }
        report.put("rendering", painting);
        report.put("git", gitBaseline());
        System.out.println(new GsonBuilder().setPrettyPrinting().create().toJson(report));
    }

    private static Map<String, Object> paint(String source, int lineCount) {
        var editor = new EditorComponent();
        editor.setSize(1200, 800);
        editor.setText(source);
        editor.gotoLine(lineCount / 2);
        var image = new BufferedImage(1200, 800, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        var result = new LinkedHashMap<String, Object>();
        result.put("logicalLines", lineCount);
        result.put("viewport", "1200x800");
        try {
            for (int i = 0; i < WARMUP; i++) editor.paint(graphics);
            double[] plain = samples(PAINT_SAMPLES, () -> editor.paint(graphics));
            String hash = LineIntelligence.sha256(source);
            Path file = Path.of("benchmark", "Source.java").toAbsolutePath();
            var identity = new SourceIdentity(file.getParent(), null, "benchmark", "", file,
                    "synthetic", editor.version(), hash, "synthetic-render-only");
            var pass = new TestResult("synthetic", "synthetic", "render", "render", "benchmark", "", "", "", "", "",
                    TestStatus.PASSED, Freshness.CURRENT, 0, "", List.of());
            var covered = new Coverage(CoverageState.COVERED, Freshness.CURRENT, Availability.AVAILABLE,
                    Precision.BINARY_LINE, -1, -1, -1, -1, "synthetic-render-only", "fixture", "synthetic", "", hash, "", "");
            long preparation = System.nanoTime();
            var lines = new LinkedHashMap<Integer, Line>();
            for (int line = 1; line <= lineCount; line++) lines.put(line, new Line(true, List.of(pass), covered));
            var snapshot = new Snapshot(identity, lines, List.of(), true, true, "HEAD", "Synthetic rendering fixture");
            result.put("denseSnapshotConstructionMs", (System.nanoTime() - preparation) / 1_000_000.0);
            long install = System.nanoTime();
            if (!editor.setLineIntelligence(snapshot)) throw new AssertionError("Current-generation evidence was rejected.");
            result.put("denseSnapshotInstallMs", (System.nanoTime() - install) / 1_000_000.0);
            for (int i = 0; i < WARMUP; i++) editor.paint(graphics);
            double[] decorated = samples(PAINT_SAMPLES, () -> editor.paint(graphics));
            result.put("plainPaintMicros", summary(plain));
            result.put("threeLanePaintMicros", summary(decorated));
            result.put("medianPaintDeltaMicros", median(decorated) - median(plain));
            result.put("snapshotLookupMicros", summary(samples(10_000, () -> {
                if (!snapshot.line(lineCount / 2).added()) throw new AssertionError("Missing dense fixture line.");
            })));
        } finally { graphics.dispose(); }
        return result;
    }

    private static Map<String, Object> gitBaseline() throws Exception {
        Path root = Files.createTempDirectory("turboj-line-intelligence-bench-");
        try {
            run(root, "init", "-q");
            run(root, "config", "user.name", "TurboJ benchmark");
            run(root, "config", "user.email", "benchmark@example.test");
            String source = source(10_000);
            Path file = root.resolve("Source.java"); Files.writeString(file, source);
            run(root, "add", "Source.java"); run(root, "commit", "-qm", "local benchmark fixture");
            String changed = source.replace("value5000 = 5000", "value5000 = 5001");
            long coldStart = System.nanoTime();
            var cold = GitBufferDiff.compute(file, changed, "HEAD");
            double coldMs = (System.nanoTime() - coldStart) / 1_000_000.0;
            if (!cold.available() || cold.added().size() != 1 || cold.deleted().size() != 1)
                throw new AssertionError("Incorrect Git fixture: " + cold);
            for (int i = 0; i < 3; i++) GitBufferDiff.compute(file, changed, "HEAD");
            double[] timings = new double[GIT_SAMPLES];
            for (int i = 0; i < timings.length; i++) {
                long start = System.nanoTime();
                var diff = GitBufferDiff.compute(file, changed, "HEAD");
                timings[i] = (System.nanoTime() - start) / 1000.0;
                if (!diff.available() || diff.added().size() != 1) throw new AssertionError("Incorrect measured diff.");
            }
            return Map.of("logicalLines", 10_000, "coldMs", coldMs, "samples", GIT_SAMPLES,
                    "warmMicros", summary(timings), "includes", "process launch, Git baseline lookup, snapshot files, patch parse, cleanup");
        } finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static String source(int lines) {
        var text = new StringBuilder(lines * 28);
        for (int i = 1; i <= lines; i++) text.append("int value").append(i).append(" = ").append(i).append(";\n");
        return text.toString();
    }
    private static double[] samples(int count, Runnable action) {
        double[] values = new double[count];
        for (int i = 0; i < count; i++) { long start = System.nanoTime(); action.run(); values[i] = (System.nanoTime() - start) / 1000.0; }
        return values;
    }
    private static Map<String, Double> summary(double[] values) {
        double[] sorted = values.clone(); Arrays.sort(sorted);
        return Map.of("min", sorted[0], "median", sorted[sorted.length / 2], "p95", sorted[(int) Math.ceil(sorted.length * .95) - 1], "max", sorted[sorted.length - 1]);
    }
    private static double median(double[] values) { double[] sorted = values.clone(); Arrays.sort(sorted); return sorted[sorted.length / 2]; }
    private static void run(Path root, String... args) throws Exception {
        var command = new ArrayList<>(List.of("git", "-C", root.toString())); command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        byte[] output = process.getInputStream().readAllBytes();
        if (process.waitFor() != 0) throw new IllegalStateException("Benchmark Git fixture failed: " + new String(output, java.nio.charset.StandardCharsets.UTF_8));
    }
}
