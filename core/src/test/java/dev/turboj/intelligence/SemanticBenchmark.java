package dev.turboj.intelligence;

import com.google.gson.GsonBuilder;
import dev.turboj.refactorj.api.JavaIntelligence.Context;

import java.nio.file.*;
import java.util.*;

/**
 * Reproducible end-to-end backend measurements, excluding EDT dispatch and hover delay. Run after
 * test compilation: java -cp core/target/test-classes:core/target/classes:app/target/turboj-app.jar
 * dev.turboj.intelligence.SemanticBenchmark
 */
public final class SemanticBenchmark {
    public static void main(String[] args) throws Exception {
        Path root = Files.createTempDirectory("turboj-semantic-bench-");
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("runtime", System.getProperty("java.runtime.version"));
        report.put(
                "os",
                System.getProperty("os.name")
                        + " "
                        + System.getProperty("os.version")
                        + " "
                        + System.getProperty("os.arch"));
        report.put("processors", Runtime.getRuntime().availableProcessors());
        report.put("warmupIterations", 10);
        report.put("sampleCount", 50);
        report.put(
                "conditions",
                "Serial in-process JDT semantic calls, same service; no UI dispatch or hover delay."
                    + " Changed-source samples force a fresh binding parse. Cold sample includes"
                    + " JDT initialization.");
        String source =
                "public class Main { public static void main(String[] args) { System.out.pr } }";
        int caret = source.indexOf(".pr") + 3;
        try (var service = new JdtJavaIntelligenceService()) {
            long cold = System.nanoTime();
            var initial = service.complete(context(root, source, 0), caret);
            if (initial.proposals().isEmpty())
                throw new IllegalStateException("No semantic result");
            report.put("coldCompletionMs", (System.nanoTime() - cold) / 1_000_000.0);
            for (int i = 0; i < 10; i++)
                service.complete(context(root, source + " // warm " + i, i + 1), caret);
            double[] changed = new double[50];
            for (int i = 0; i < changed.length; i++) {
                long start = System.nanoTime();
                var result =
                        service.complete(
                                context(root, source + " // measured " + i, i + 100), caret);
                if (!result.proposals().getFirst().text().equals("println()"))
                    throw new AssertionError("Incorrect proposal");
                changed[i] = (System.nanoTime() - start) / 1_000_000.0;
            }
            report.put("warmChangedSourceCompletionMs", summary(changed));
            Context same = context(root, source, 1000);
            service.complete(same, caret);
            double[] cached = new double[50];
            for (int i = 0; i < cached.length; i++) {
                long start = System.nanoTime();
                service.complete(same, caret);
                cached[i] = (System.nanoTime() - start) / 1_000_000.0;
            }
            report.put("cachedCompletionMs", summary(cached));
            String documented = "public class Main { String s=\"hello\".substring(1,3); }";
            Context docs = context(root, documented, 2000);
            var symbol = service.resolveSymbol(docs, documented.indexOf("substring"));
            if (symbol == null) throw new AssertionError("No resolved method");
            long first = System.nanoTime();
            var documentation = service.documentation(docs, symbol);
            report.put("firstJdkDocumentationMs", (System.nanoTime() - first) / 1_000_000.0);
            report.put("jdkSourceAttached", documentation.sourceAvailable());
            double[] cachedDocs = new double[50];
            for (int i = 0; i < cachedDocs.length; i++) {
                long start = System.nanoTime();
                service.documentation(docs, symbol);
                cachedDocs[i] = (System.nanoTime() - start) / 1_000_000.0;
            }
            report.put("cachedDocumentationMs", summary(cachedDocs));
            String generic = "public class Main { ArrayL list; }";
            first = System.nanoTime();
            var type =
                    service.complete(context(root, generic, 3000), generic.indexOf("ArrayL") + 6);
            if (type.proposals().stream().noneMatch(p -> p.detail().equals("java.util.ArrayList")))
                throw new AssertionError("No imported type");
            report.put("firstTypeIndexAndCompletionMs", (System.nanoTime() - first) / 1_000_000.0);
        } finally {
            Files.deleteIfExists(root);
        }
        String json = new GsonBuilder().setPrettyPrinting().create().toJson(report);
        Path output = Path.of("core/target/java-intelligence-performance.json");
        Files.createDirectories(output.getParent());
        Files.writeString(output, json + "\n");
        System.out.println(json);
    }

    private static Context context(Path root, String source, long version) {
        return new Context(
                root.resolve("Main.java"), source, version, Map.of(), List.of(root), List.of(), 1);
    }

    private static Map<String, Object> summary(double[] values) {
        double[] sorted = values.clone();
        Arrays.sort(sorted);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("p50", sorted[(int) Math.ceil(sorted.length * .50) - 1]);
        result.put("p95", sorted[(int) Math.ceil(sorted.length * .95) - 1]);
        result.put("p99", sorted[(int) Math.ceil(sorted.length * .99) - 1]);
        result.put("min", sorted[0]);
        result.put("max", sorted[sorted.length - 1]);
        result.put("samples", values);
        return result;
    }
}
