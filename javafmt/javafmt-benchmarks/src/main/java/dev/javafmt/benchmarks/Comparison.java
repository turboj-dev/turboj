package dev.javafmt.benchmarks;

import dev.javafmt.core.*;
import java.lang.management.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import org.eclipse.jdt.core.*;
import org.eclipse.jdt.core.formatter.*;
import org.eclipse.jface.text.Document;

/** Explicit comparison; competing formatter code is never linked into javafmt-core. */
public final class Comparison {
    interface Operation {
        String format(String source) throws Exception;
    }

    static volatile Object sink;

    public static void main(String[] args) throws Exception {
        var own = new JavaFormatter();
        Map<String, String> options = new HashMap<> ();
        JavaCore.setComplianceOptions("25", options);
        options.put(DefaultCodeFormatterConstants.FORMATTER_TAB_CHAR, JavaCore.SPACE);
        options.put(DefaultCodeFormatterConstants.FORMATTER_TAB_SIZE, "4");
        options.put(DefaultCodeFormatterConstants.FORMATTER_INDENTATION_SIZE, "4");
        options.put(DefaultCodeFormatterConstants.FORMATTER_LINE_SPLIT, "100");
        var jdt = ToolFactory.createCodeFormatter(options);
        Map<String, Operation> operations = new LinkedHashMap<> ();
        operations.put("javafmt-0.1.0", own::format);
        operations.put("eclipse-jdt-3.44.0", source -> {
            var edit = jdt.format(CodeFormatter.K_COMPILATION_UNIT
                    | CodeFormatter.F_INCLUDE_COMMENTS, source, 0, source.length(), 0, "\n");

            if (edit == null)
                throw new IllegalStateException("JDT rejected fixture");
            var document = new Document(source);
            edit.apply(document);
            return document.get();
        });
        URLClassLoader googleLoader = null;

        if (args.length > 0) {
            googleLoader = new URLClassLoader(new URL[] {Path.of(args[0]).toUri().toURL()},
                    ClassLoader.getPlatformClassLoader());
            var clazz = googleLoader.loadClass("com.google.googlejavaformat.java.Formatter");
            var instance = clazz.getConstructor().newInstance();
            var method = clazz.getMethod("formatSource", String.class);
            operations.put("google-java-format-1.28.0-default", source -> (String) method
                    .invoke(instance, source));
        }
        var allocations = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();

        if (allocations.isThreadAllocatedMemorySupported())
            allocations.setThreadAllocatedMemoryEnabled(true);
        System.out.println("OS=" + System.getProperty("os.name") + " " + System
                .getProperty("os.version") + " " + System.getProperty("os.arch"));
        System.out.println("Runtime=" + System.getProperty("java.runtime.version") + " processors="
                + Runtime.getRuntime().availableProcessors());
        System.out
                .println("All samples retained. Warmup30, measured100, sequential reused instances; no source cache. Default GJF style differs.");
        System.out
                .println("backend,mode,input_utf16,p50_ms,p95_ms,p99_ms,max_ms,bytes_alloc_per_op,operations_per_second");
        try {
            for (int size : new int[] {1024, 10240, 102400}) {
                String source = FormattingBench.source(size);

                for (var entry : operations.entrySet()) {
                    String formatted = entry.getValue().format(source);
                    measure(entry.getKey(), "unformatted", source, entry.getValue(), allocations);
                    measure(entry.getKey(), "canonical-noop", formatted, entry.getValue(),
                            allocations);
                }
            }
        } finally {
            if (googleLoader != null)
                googleLoader.close();
        }
        long peak = ManagementFactory.getMemoryPoolMXBeans().stream()
                .map(MemoryPoolMXBean::getPeakUsage).filter(Objects::nonNull)
                .mapToLong(MemoryUsage::getUsed).sum();
        System.out.println("All-backends summed memory-pool peaks(bytes), not processRSS=" + peak);
    }

    private static void measure(String name, String mode, String source, Operation operation,
            com.sun.management.ThreadMXBean memory) throws Exception {
        for (int i = 0; i < 30; i++)
            sink = operation.format(source);
        long thread = Thread.currentThread().threadId();
        long allocationStart = memory.getThreadAllocatedBytes(thread), totalStart = System
                .nanoTime();
        double[] samples = new double[100];

        for (int i = 0; i < samples.length; i++) {
            long start = System.nanoTime();
            sink = operation.format(source);
            samples[i] = (System.nanoTime() - start) / 1e6;
        }
        double ops = samples.length * 1e9 / (System.nanoTime() - totalStart);
        long allocated = memory.getThreadAllocatedBytes(thread) - allocationStart;
        Path output = Path.of("target/comparison");
        Files.createDirectories(output);
        StringBuilder raw = new StringBuilder("sample,milliseconds\n");

        for (int i = 0; i < samples.length; i++)
            raw.append(i).append(',').append(samples[i]).append('\n');
        Files.writeString(output.resolve(name + "-" + mode + "-" + source.length() + ".csv"), raw);
        Arrays.sort(samples);
        System.out.printf(Locale.ROOT, "%s,%s,%d,%.3f,%.3f,%.3f,%.3f,%d,%.2f%n", name, mode, source
                .length(), samples[49], samples[94], samples[98], samples[99], allocated / 100,
                ops);
    }
}
