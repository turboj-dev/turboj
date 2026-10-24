package dev.turboj.benchmarks;

import com.google.gson.GsonBuilder;
import dev.turboj.compiler.*;
import dev.turboj.editor.document.PieceTable;
import dev.turboj.execution.WorkerClient;
import dev.turboj.language.JdtSyntaxAnalyzer;

import jdk.jshell.JShell;
import jdk.jshell.Snippet;

import java.io.OutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/** End-to-end harness. Raw samples are retained, including slow samples. */
public final class LatencyBench {
    private final List<Measurement> results = new ArrayList<>();
    private final int samples;
    private final int warmups;
    private long revision;

    private LatencyBench(boolean quick) {
        samples = quick ? 15 : 40;
        warmups = quick ? 5 : 12;
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && args[0].equals("jmh")) {
            org.openjdk.jmh.Main.main(Arrays.copyOfRange(args, 1, args.length));
            return;
        }
        boolean quick = Arrays.asList(args).contains("--quick");
        Path output = null;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--output") && i + 1 < args.length) output = Path.of(args[++i]);
            else if (!args[i].equals("--quick"))
                throw new IllegalArgumentException(
                        "Usage: LatencyBench [--quick] [--output report.json] | jmh [JMH arguments]");
        }
        var bench = new LatencyBench(quick);
        var environment = environment();
        System.out.println("TurboJ measured latency — " + environment);
        System.out.println(
                "Warmups: "
                        + bench.warmups
                        + "; normal samples: "
                        + bench.samples
                        + "; all raw samples retained");
        Exception failed = null;
        try {
            bench.run();
        } catch (Exception failure) {
            failed = failure;
        }
        var report = new LinkedHashMap<String, Object>();
        report.put("environment", environment);
        report.put("warmupIterations", bench.warmups);
        report.put("measurements", bench.results);
        if (failed != null) report.put("failure", failed.toString());
        report.put(
                "limitations",
                List.of(
                        "Model edits are not keypress-to-display latency",
                        "No project incremental-build benchmark yet",
                        "No cold packaged-app startup or idle CPU measurement",
                        "Small sample counts produce coarse p95/p99 estimates",
                        "Default JShell remote execution, not an integrated console latency measurement"));
        if (output != null) {
            if (output.toAbsolutePath().getParent() != null)
                Files.createDirectories(output.toAbsolutePath().getParent());
            Files.writeString(
                    output, new GsonBuilder().setPrettyPrinting().create().toJson(report) + "\n");
            System.out.println("Raw report: " + output.toAbsolutePath());
        }
        if (failed != null) throw failed;
    }

    private void run() throws Exception {
        try (var compiler = new JavacCompilerBackend()) {
            add(
                    "compiler first Hello task",
                    new long[] {
                        compiler.compile(
                                        new SourceSnapshot(
                                                "Hello.java",
                                                Workloads.hello(revision++),
                                                revision))
                                .durationNanos()
                    });
            for (int lines : new int[] {0, 5, 100, 1000, 10000}) {
                measure(
                        "javac memory compile " + lines + " lines",
                        () -> {
                            long value = revision++;
                            // Empty source cannot vary: invalidate to measure a compile, not a
                            // cache hit.
                            if (lines == 0) compiler.invalidate();
                            var result =
                                    compiler.compile(
                                            new SourceSnapshot(
                                                    lines < 20 ? "Hello.java" : "Workload.java",
                                                    Workloads.source(lines, value),
                                                    value));
                            if (!result.success())
                                throw new IllegalStateException(result.diagnostics().toString());
                            return result.durationNanos();
                        });
            }
            String hello = Workloads.hello(0);
            var compiled = compiler.compile(new SourceSnapshot("Hello.java", hello, revision++));
            measure(
                    "identical-source cache hit",
                    () -> {
                        var cached =
                                compiler.compile(
                                        new SourceSnapshot("Hello.java", hello, revision++));
                        if (!cached.cacheHit())
                            throw new IllegalStateException("Expected cache hit");
                        return cached.durationNanos();
                    });
            var syntax = new JdtSyntaxAnalyzer();
            for (int lines : new int[] {5, 1000, 10000}) {
                var source =
                        new SourceSnapshot(
                                lines == 5 ? "Hello.java" : "Workload.java",
                                Workloads.source(lines, 1),
                                1);
                measure(
                        "JDT syntax " + lines + " lines",
                        () -> syntax.analyze(source).durationNanos());
            }
            modelEditsWhileCompiling();
            workerRuns(compiled);
            externalJavac(hello);
            jshellStatements();
            largeOutput(compiler);
        }
    }

    private void modelEditsWhileCompiling() throws Exception {
        for (int lines : new int[] {100, 1000, 10000}) {
            var done = new AtomicBoolean();
            var busy = new CountDownLatch(1);
            Thread compilation =
                    Thread.ofPlatform()
                            .name("benchmark-busy-compiler")
                            .start(
                                    () -> {
                                        try (var backend = new JavacCompilerBackend()) {
                                            for (int i = 0; !done.get(); i++) {
                                                busy.countDown();
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
                                        }
                                    });
            try {
                if (!busy.await(10, TimeUnit.SECONDS))
                    throw new IllegalStateException("Busy compiler did not start");
                var document = new PieceTable(Workloads.source(lines, 0));
                long[] durations = new long[1000];
                // Real consecutive insertion makes the piece sequence grow, unlike insert/undo
                // microbenchmarks.
                for (int i = 0; i < durations.length; i++) {
                    long start = System.nanoTime();
                    document.insert(25 + i, "x");
                    var snapshot = document.snapshot();
                    durations[i] = System.nanoTime() - start;
                    if (snapshot.version() != document.version())
                        throw new AssertionError("Snapshot version mismatch");
                }
                add("model insert + snapshot " + lines + " lines while compiling", durations);
            } finally {
                done.set(true);
                compilation.join(30_000);
            }
            if (compilation.isAlive())
                throw new IllegalStateException("Benchmark compiler did not finish");
        }
    }

    private void workerRuns(CompilationResult compiled) throws Exception {
        var bytecode = compiled.bytecode();
        try (var worker = new WorkerClient()) {
            long start = System.nanoTime();
            worker.warmup().get(15, TimeUnit.SECONDS);
            add("worker cold ready", new long[] {System.nanoTime() - start});
            for (boolean isolated : new boolean[] {false, true}) {
                int count = isolated ? Math.min(samples, 15) : samples;
                var elapsed = new long[count];
                var first = new long[count];
                var execution = new long[count];
                for (int i = -warmups; i < count; i++) {
                    var firstOutput = new AtomicLong();
                    long dispatched = System.nanoTime();
                    var result =
                            worker.run(
                                            bytecode,
                                            compiled.mainClass(),
                                            List.of(),
                                            isolated,
                                            event ->
                                                    firstOutput.compareAndSet(
                                                            0, System.nanoTime() - dispatched))
                                    .get(15, TimeUnit.SECONDS);
                    long total = System.nanoTime() - dispatched;
                    if (result.exitCode() != 0 || firstOutput.get() == 0)
                        throw new IllegalStateException("Run failed: " + result);
                    if (i >= 0) {
                        elapsed[i] = total;
                        first[i] = firstOutput.get();
                        execution[i] = result.durationNanos();
                    }
                }
                String mode = isolated ? "isolated JVM" : "warm worker";
                add(mode + " dispatch to first output", first);
                add(mode + " dispatch to completion", elapsed);
                add(mode + " execution only", execution);
            }
        }
    }

    private void externalJavac(String source) throws Exception {
        Path temporary = Files.createTempDirectory("turboj-javac-bench-");
        try {
            Path file = temporary.resolve("Hello.java");
            Files.writeString(file, source);
            String executable = Path.of(System.getProperty("java.home"), "bin", "javac").toString();
            var durations = new long[Math.min(samples, 15)];
            for (int i = -3; i < durations.length; i++) {
                long start = System.nanoTime();
                var process =
                        new ProcessBuilder(
                                        executable,
                                        "--release",
                                        "25",
                                        "-proc:none",
                                        "-g",
                                        "-d",
                                        temporary.toString(),
                                        file.toString())
                                .redirectErrorStream(true)
                                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                                .start();
                if (!process.waitFor(15, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                    throw new IllegalStateException("External javac timeout");
                }
                if (process.exitValue() != 0)
                    throw new IllegalStateException("External javac failed");
                if (i >= 0) durations[i] = System.nanoTime() - start;
            }
            add("external javac process Hello", durations);
        } finally {
            try (var paths = Files.walk(temporary)) {
                for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList())
                    Files.deleteIfExists(path);
            }
        }
    }

    private void jshellStatements() {
        try (var sink = new PrintStream(OutputStream.nullOutputStream());
                var shell = JShell.builder().out(sink).err(sink).build()) {
            for (int i = 0; i < warmups; i++) shell.eval("1 + " + i);
            long[] durations = new long[100];
            for (int i = 0; i < durations.length; i++) {
                long start = System.nanoTime();
                var events = shell.eval("int value" + i + " = " + i + ";");
                durations[i] = System.nanoTime() - start;
                if (events.stream()
                        .anyMatch(
                                event ->
                                        event.status() == Snippet.Status.REJECTED
                                                || event.exception() != null))
                    throw new IllegalStateException("JShell benchmark snippet failed");
            }
            add("JShell 100 consecutive declarations", durations);
        }
    }

    private void largeOutput(JavacCompilerBackend compiler) throws Exception {
        var compiled =
                compiler.compile(
                        new SourceSnapshot(
                                "LargeOutput.java",
                                "class LargeOutput { static void main() { System.out.print(\"x\".repeat(2 * 1024 * 1024)); } }",
                                revision++));
        if (!compiled.success()) throw new IllegalStateException(compiled.diagnostics().toString());
        try (var worker = new WorkerClient()) {
            worker.warmup().get(15, TimeUnit.SECONDS);
            long[] durations = new long[10];
            for (int i = -3; i < durations.length; i++) {
                var output = new AtomicLong();
                long start = System.nanoTime();
                var result =
                        worker.run(
                                        compiled.bytecode(),
                                        compiled.mainClass(),
                                        List.of(),
                                        false,
                                        event -> output.addAndGet(event.text().length()))
                                .get(15, TimeUnit.SECONDS);
                if (result.exitCode() != 0
                        || output.get() == 0
                        || output.get() > 1024 * 1024 + 1024)
                    throw new IllegalStateException(
                            "Output limit failed: " + result + " output=" + output.get());
                if (i >= 0) durations[i] = System.nanoTime() - start;
            }
            add("2 MiB output capped at 1 MiB, dispatch to completion", durations);
        }
    }

    private void measure(String label, LongSupplier action) {
        for (int i = 0; i < warmups; i++) action.getAsLong();
        long[] values = new long[samples];
        for (int i = 0; i < values.length; i++) values[i] = action.getAsLong();
        add(label, values);
    }

    private void add(String label, long[] values) {
        var measurement = Measurement.of(label, values);
        results.add(measurement);
        System.out.printf(
                Locale.ROOT,
                "%-66s n=%4d p50=%9.3f ms p95=%9.3f ms p99=%9.3f ms%n",
                label,
                values.length,
                measurement.p50Millis(),
                measurement.p95Millis(),
                measurement.p99Millis());
    }

    private static Map<String, Object> environment() {
        var result = new LinkedHashMap<String, Object>();
        result.put("recordedAt", Instant.now().toString());
        result.put("os", System.getProperty("os.name") + " " + System.getProperty("os.version"));
        result.put("arch", System.getProperty("os.arch"));
        result.put("runtime", System.getProperty("java.runtime.version"));
        result.put("vendor", System.getProperty("java.vendor"));
        result.put("processors", Runtime.getRuntime().availableProcessors());
        result.put("maxJvmHeapBytes", Runtime.getRuntime().maxMemory());
        if (System.getProperty("os.name").equals("Mac OS X")) {
            try {
                var process =
                        new ProcessBuilder("sysctl", "-n", "machdep.cpu.brand_string", "hw.memsize")
                                .start();
                var values =
                        new String(
                                        process.getInputStream().readAllBytes(),
                                        java.nio.charset.StandardCharsets.UTF_8)
                                .lines()
                                .toList();
                if (process.waitFor(3, TimeUnit.SECONDS) && values.size() >= 2) {
                    result.put("cpu", values.get(0));
                    result.put("physicalMemoryBytes", Long.parseLong(values.get(1)));
                }
            } catch (Exception ignored) {
                result.put("hardwareQuery", "unavailable");
            }
        }
        return result;
    }

    public record Measurement(
            String operation,
            int count,
            double p50Millis,
            double p95Millis,
            double p99Millis,
            double maxMillis,
            long[] rawNanos) {
        static Measurement of(String label, long[] values) {
            long[] sorted = values.clone();
            Arrays.sort(sorted);
            return new Measurement(
                    label,
                    values.length,
                    percentile(sorted, .5),
                    percentile(sorted, .95),
                    percentile(sorted, .99),
                    sorted[sorted.length - 1] / 1_000_000.0,
                    values.clone());
        }

        private static double percentile(long[] sorted, double quantile) {
            return sorted[Math.max(0, (int) Math.ceil(quantile * sorted.length) - 1)] / 1_000_000.0;
        }
    }
}
