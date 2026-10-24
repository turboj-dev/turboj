package com.turboj.ai;

import com.google.gson.GsonBuilder;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import javax.tools.ToolProvider;

/**
 * Explicit opt-in live probe. Does not install, download, enable preferences or contact cloud APIs.
 */
public final class LocalAIProbe {
    record Case(String name, String prefix, String suffix, String expected) {}

    record Result(
            String name,
            int iteration,
            double firstUsableMillis,
            double totalMillis,
            AICompletionProvider.Metrics transport,
            String raw,
            String accepted,
            boolean compiled,
            String executionOutput,
            boolean behaviorPassed,
            String error) {}

    public static void main(String[] args) throws Exception {
        if (args.length != 2)
            throw new IllegalArgumentException(
                    "Usage: LocalAIProbe <loopback-base-url> <installed-FIM-model>");
        AIConfig defaults = AIConfig.defaults();
        AIConfig config =
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
                        45,
                        192,
                        4096,
                        "",
                        true);
        var cases =
                List.of(
                        new Case(
                                "Square",
                                "public class ProbeSquare {\n"
                                    + "    public static int square(int value) {\n"
                                    + "        ",
                                "\n"
                                    + "    }\n"
                                    + "    public static void main(String[] args) {"
                                    + " System.out.println(square(7)); }\n"
                                    + "}\n",
                                "49"),
                        new Case(
                                "Sum",
                                "public class ProbeSum {\n"
                                    + "    public static int sum(int[] numbers) {\n"
                                    + "        int total = 0;\n"
                                    + "        ",
                                "\n"
                                    + "        return total;\n"
                                    + "    }\n"
                                    + "    public static void main(String[] args) {"
                                    + " System.out.println(sum(new int[]{2,3,5})); }\n"
                                    + "}\n",
                                "10"),
                        new Case(
                                "Prime",
                                "public class ProbePrime {\n"
                                    + "    public static boolean isPrime(int number) {\n"
                                    + "        ",
                                "\n"
                                    + "    }\n"
                                    + "    public static void main(String[] args) {"
                                    + " System.out.println(isPrime(97) + \" \" + isPrime(1) + \" \""
                                    + " + isPrime(15)); }\n"
                                    + "}\n",
                                "true false false"),
                        new Case(
                                "Middle",
                                "public class ProbeMiddle {\n"
                                    + "    public static int doubleThenAdd(int value) {\n"
                                    + "        int doubled = value * 2;\n"
                                    + "        // Add three to doubled and assign it to result.\n"
                                    + "        ",
                                "\n"
                                    + "        return result;\n"
                                    + "    }\n"
                                    + "    public static void main(String[] args) {"
                                    + " System.out.println(doubleThenAdd(4)); }\n"
                                    + "}\n",
                                "11"));
        Path directory = Path.of("core/target/ai-local-validation").toAbsolutePath();
        Files.createDirectories(directory);
        List<Result> results = new ArrayList<>();
        try (AICompletionProvider provider =
                AIProviders.create(
                        config,
                        () -> {
                            throw new AssertionError("Local probe accessed cloud credentials");
                        })) {
            var health = provider.check(new Cancellation());
            if (!health.available()) throw new IOException(health.message());
            for (int iteration = 0; iteration < 3; iteration++) {
                for (Case value : cases) {
                    long start = System.nanoTime();
                    AtomicLong first = new AtomicLong();
                    StringBuilder raw = new StringBuilder();
                    String accepted = "", output = "", error = "";
                    boolean compiled = false, behavior = false;
                    AICompletionRequest request =
                            new AICompletionRequest(
                                    Path.of("Probe" + value.name + ".java"),
                                    iteration,
                                    value.prefix.length(),
                                    value.prefix,
                                    value.suffix,
                                    "",
                                    25,
                                    false);
                    try {
                        provider.stream(
                                request,
                                text -> {
                                    raw.setLength(0);
                                    raw.append(text);
                                    if (!CompletionValidation.usable(request, text, false)
                                            .isEmpty())
                                        first.compareAndSet(0, System.nanoTime() - start);
                                },
                                new Cancellation());
                        accepted = CompletionValidation.usable(request, raw.toString(), true);
                    } catch (Exception e) {
                        error = e.getClass().getSimpleName() + ": " + e.getMessage();
                    }
                    double total = (System.nanoTime() - start) / 1e6;
                    if (!accepted.isEmpty()) {
                        Path run = directory.resolve(value.name + "-" + iteration);
                        Files.createDirectories(run);
                        Path file = run.resolve("Probe" + value.name + ".java");
                        Files.writeString(file, value.prefix + accepted + value.suffix);
                        try (var compilerOutput = new ByteArrayOutputStream()) {
                            compiled =
                                    ToolProvider.getSystemJavaCompiler()
                                                    .run(
                                                            null,
                                                            compilerOutput,
                                                            compilerOutput,
                                                            "-proc:none",
                                                            "--release",
                                                            "25",
                                                            "-d",
                                                            run.toString(),
                                                            file.toString())
                                            == 0;
                            if (!compiled) error = compilerOutput.toString(StandardCharsets.UTF_8);
                        }
                        if (compiled) {
                            Path capture = run.resolve("output.txt");
                            Process process =
                                    new ProcessBuilder(
                                                    Path.of(
                                                                    System.getProperty("java.home"),
                                                                    "bin",
                                                                    "java")
                                                            .toString(),
                                                    "-Xmx128m",
                                                    "-cp",
                                                    run.toString(),
                                                    "Probe" + value.name)
                                            .redirectErrorStream(true)
                                            .redirectOutput(capture.toFile())
                                            .start();
                            if (!process.waitFor(5, TimeUnit.SECONDS)) {
                                process.destroyForcibly();
                                error = "Generated program timed out";
                            } else {
                                output = Files.readString(capture);
                                if (output.length() > 4096) output = output.substring(0, 4096);
                                behavior =
                                        process.exitValue() == 0
                                                && output.strip().equals(value.expected);
                            }
                        }
                    }
                    Result result =
                            new Result(
                                    value.name,
                                    iteration,
                                    first.get() == 0 ? -1 : first.get() / 1e6,
                                    total,
                                    provider.metrics(),
                                    raw.toString(),
                                    accepted,
                                    compiled,
                                    output,
                                    behavior,
                                    error);
                    results.add(result);
                    System.out.printf(
                            Locale.ROOT,
                            "%s iteration%d firstUsable=%.2fms total=%.2fms chars=%d compiled=%s"
                                + " behavior=%s%n",
                            value.name,
                            iteration,
                            result.firstUsableMillis,
                            total,
                            accepted.length(),
                            compiled,
                            behavior);
                    Files.writeString(
                            directory.resolve("results.json"),
                            new GsonBuilder().setPrettyPrinting().create().toJson(results));
                }
            }
        }
        long successful = results.stream().filter(Result::behaviorPassed).count();
        System.out.println(
                "Live model behavior checks: "
                        + successful
                        + "/"
                        + results.size()
                        + "; all outcomes retained.");
    }
}
