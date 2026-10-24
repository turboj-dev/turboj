package dev.turboj.cli;

import dev.turboj.app.TurboJ;
import dev.turboj.compiler.*;
import dev.turboj.execution.*;
import dev.turboj.repl.JavaRepl;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Standalone CLI fallback. A resident compiler daemon is deliberately not claimed. */
public final class Tj {
    private Tj() {}

    public static void main(String[] args) {
        int code = execute(args, System.in, System.out, System.err);
        if (args.length == 0 || !args[0].equals("open") || code != 0) System.exit(code);
    }

    public static int execute(
            String[] args, InputStream input, PrintStream output, PrintStream errors) {
        try {
            if (args.length == 0 || args[0].equals("--help") || args[0].equals("help")) {
                help(output);
                return 0;
            }
            return switch (args[0]) {
                case "doctor" -> {
                    requireCount(args, 1);
                    doctor(output);
                    yield 0;
                }
                case "open" -> {
                    requireCount(args, 2);
                    TurboJ.main(new String[] {args[1]});
                    yield 0;
                }
                case "check", "compile", "run" -> sourceCommand(args, input, output, errors);
                case "eval" -> {
                    requireCount(args, 2);
                    try (var repl = new JavaRepl(output, errors)) {
                        yield report(repl.submit(args[1]), output, errors);
                    }
                }
                case "repl" -> {
                    requireCount(args, 1);
                    yield repl(input, output, errors);
                }
                default ->
                        throw new IllegalArgumentException(
                                "Unknown command: " + args[0] + " (use tj --help)");
            };
        } catch (IllegalArgumentException | IOException failure) {
            errors.println("tj: " + failure.getMessage());
            return 2;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            errors.println("tj: interrupted");
            return 130;
        } catch (Exception failure) {
            Throwable cause =
                    failure instanceof ExecutionException && failure.getCause() != null
                            ? failure.getCause()
                            : failure;
            errors.println("tj: " + cause.getClass().getSimpleName() + ": " + cause.getMessage());
            return 1;
        }
    }

    private static int sourceCommand(
            String[] args, InputStream input, PrintStream output, PrintStream errors)
            throws Exception {
        if (args.length < 2)
            throw new IllegalArgumentException("Usage: tj " + args[0] + " File.java");
        Path file = Path.of(args[1]).toAbsolutePath().normalize();
        if (!file.toString().endsWith(".java"))
            throw new IllegalArgumentException("Source file must end in .java");
        String source = Files.readString(file, StandardCharsets.UTF_8);
        if (source.startsWith("\uFEFF")) source = source.substring(1);
        List<Path> classpath = new ArrayList<>();
        List<String> programArgs = new ArrayList<>();
        Path destination = Path.of("out");
        boolean isolated = false;
        for (int i = 2; i < args.length; i++) {
            switch (args[i]) {
                case "--classpath", "-cp" -> {
                    if (++i == args.length) throw new IllegalArgumentException("Missing classpath");
                    for (String part :
                            args[i].split(java.util.regex.Pattern.quote(File.pathSeparator)))
                        if (!part.isBlank()) classpath.add(Path.of(part).toAbsolutePath());
                }
                case "-d" -> {
                    if (!args[0].equals("compile") || ++i == args.length)
                        throw new IllegalArgumentException(
                                "-d requires compile and an output directory");
                    destination = Path.of(args[i]);
                }
                case "--isolated" -> {
                    if (!args[0].equals("run"))
                        throw new IllegalArgumentException("--isolated requires run");
                    isolated = true;
                }
                case "--" -> {
                    if (!args[0].equals("run"))
                        throw new IllegalArgumentException("Program arguments require run");
                    programArgs.addAll(Arrays.asList(args).subList(i + 1, args.length));
                    i = args.length;
                }
                default -> throw new IllegalArgumentException("Unknown option: " + args[i]);
            }
        }
        CompilationResult result;
        try (var compiler = new JavacCompilerBackend(25, classpath)) {
            result = compiler.compile(new SourceSnapshot(file.getFileName().toString(), source, 1));
        }
        for (CompilerDiagnostic diagnostic : result.diagnostics()) {
            errors.printf(
                    "%s:%d:%d: %s: %s%n",
                    file,
                    diagnostic.line(),
                    diagnostic.column(),
                    diagnostic.kind().toLowerCase(Locale.ROOT),
                    diagnostic.message());
        }
        if (!result.success()) return 1;
        if (args[0].equals("check")) {
            output.printf(
                    Locale.ROOT,
                    "BUILD OK | 0 errors | %.3f ms%n",
                    result.durationNanos() / 1_000_000.0);
            return 0;
        }
        if (args[0].equals("compile")) {
            Path root = destination.toAbsolutePath().normalize();
            for (var entry : result.bytecode().entrySet()) {
                Path target = root.resolve(entry.getKey().replace('.', '/') + ".class").normalize();
                if (!target.startsWith(root)) throw new IOException("Invalid output class name");
                Files.createDirectories(target.getParent());
                Path temporary = Files.createTempFile(target.getParent(), ".turboj-", ".class");
                try {
                    Files.write(temporary, entry.getValue());
                    try {
                        Files.move(
                                temporary,
                                target,
                                StandardCopyOption.ATOMIC_MOVE,
                                StandardCopyOption.REPLACE_EXISTING);
                    } catch (AtomicMoveNotSupportedException unsupported) {
                        Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
                    }
                } finally {
                    Files.deleteIfExists(temporary);
                }
            }
            output.printf(
                    Locale.ROOT,
                    "Compiled %d classes to %s | %.3f ms%n",
                    result.bytecode().size(),
                    root,
                    result.durationNanos() / 1_000_000.0);
            return 0;
        }
        if (result.mainClass() == null) {
            errors.println("tj: no runnable Java main method found");
            return 1;
        }
        try (var worker = new WorkerClient(Duration.ofSeconds(30), 1024 * 1024, 256)) {
            Thread shutdown = new Thread(worker::close, "turboj-cli-shutdown");
            Runtime.getRuntime().addShutdownHook(shutdown);
            try {
                var future =
                        worker.run(
                                result.version(),
                                result.bytecode(),
                                result.mainClass(),
                                programArgs,
                                classpath,
                                isolated,
                                event -> {
                                    PrintStream stream = event.stderr() ? errors : output;
                                    stream.print(event.text());
                                    stream.flush();
                                });
                Thread.ofPlatform()
                        .daemon()
                        .name("turboj-cli-stdin")
                        .start(() -> forwardInput(input, worker));
                RunResult execution = future.get();
                if (execution.exitCode() != 0) errors.println("tj: " + execution.detail());
                return execution.exitCode() < 0 ? 1 : execution.exitCode();
            } finally {
                Runtime.getRuntime().removeShutdownHook(shutdown);
            }
        }
    }

    private static void forwardInput(InputStream input, WorkerClient worker) {
        try {
            byte[] buffer = new byte[4096];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) worker.sendInput(Arrays.copyOf(buffer, count));
            }
            worker.closeInput();
        } catch (IOException | IllegalStateException ignored) {
            /* completion closes the worker before stdin may finish */
        }
    }

    private static int repl(InputStream input, PrintStream output, PrintStream errors)
            throws IOException {
        boolean interactive = input == System.in && System.console() != null;
        try (var repl = new JavaRepl(output, errors)) {
            BufferedReader reader =
                    new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8));
            if (interactive)
                output.println("TurboJ Java 25 REPL — /help for commands; /exit to leave");
            StringBuilder pending = new StringBuilder();
            String line;
            while (true) {
                if (interactive) {
                    output.print(pending.isEmpty() ? "tj> " : "... ");
                    output.flush();
                }
                if ((line = reader.readLine()) == null) break;
                if (pending.isEmpty() && line.startsWith("/")) {
                    if (line.equals("/exit") || line.equals("/quit")) return 0;
                    if (line.equals("/help")) {
                        output.println(
                                "/exit  /reset  /history  /again N  /complete PREFIX  /clear");
                        continue;
                    }
                    if (line.equals("/reset")) {
                        repl.reset();
                        output.println("Session reset");
                        continue;
                    }
                    if (line.equals("/history")) {
                        List<String> history = repl.history();
                        for (int i = 0; i < history.size(); i++)
                            output.println((i + 1) + "  " + history.get(i));
                        continue;
                    }
                    if (line.startsWith("/complete ")) {
                        repl.complete(line.substring(10)).forEach(output::println);
                        continue;
                    }
                    if (line.equals("/clear")) {
                        if (interactive) output.print("\033[H\033[2J");
                        output.flush();
                        continue;
                    }
                    if (line.startsWith("/again ")) {
                        try {
                            line =
                                    repl.history()
                                            .get(Integer.parseInt(line.substring(7).trim()) - 1);
                        } catch (RuntimeException invalid) {
                            errors.println("Invalid history number");
                            continue;
                        }
                    } else {
                        errors.println("Unknown REPL command; use /help");
                        continue;
                    }
                }
                pending.append(line).append('\n');
                var result = repl.submit(pending.toString());
                if (result.complete()) {
                    report(result, output, errors);
                    pending.setLength(0);
                }
            }
            if (!pending.isEmpty()) {
                errors.println("Incomplete Java snippet at end of input");
                return 1;
            }
            return 0;
        }
    }

    private static int report(JavaRepl.Evaluation result, PrintStream output, PrintStream errors) {
        if (!result.complete()) {
            errors.println("Incomplete Java snippet");
            return 1;
        }
        result.values().forEach(output::println);
        result.diagnostics().forEach(errors::println);
        return result.success() ? 0 : 1;
    }

    private static void doctor(PrintStream output) {
        output.println("TurboJ 0.1.0");
        output.println(
                "Java: " + Runtime.version() + " (" + System.getProperty("java.vendor") + ")");
        output.println("Java home: " + System.getProperty("java.home"));
        output.println(
                "Platform: "
                        + System.getProperty("os.name")
                        + " "
                        + System.getProperty("os.version")
                        + " "
                        + System.getProperty("os.arch"));
        output.println(
                "JavaCompiler: "
                        + (javax.tools.ToolProvider.getSystemJavaCompiler() != null
                                ? "available"
                                : "MISSING"));
        output.println("Execution: separate JVM, 256 MiB heap, 30 s timeout, 1 MiB output cap");
        output.println("CLI backend: standalone (resident daemon not implemented)");
    }

    private static void requireCount(String[] args, int count) {
        if (args.length != count)
            throw new IllegalArgumentException(
                    "Incorrect arguments for tj " + args[0] + "; use tj --help");
    }

    private static void help(PrintStream output) {
        output.println(
                """
                TurboJ — Java editor and execution environment
                Usage:
                  tj open File.java
                  tj check File.java [--classpath PATH]
                  tj compile File.java [-d DIR] [--classpath PATH]
                  tj run File.java [--isolated] [--classpath PATH] [-- ARGS...]
                  tj eval "1 + 2"
                  tj repl
                  tj doctor

                The CLI currently starts its own backend for each invocation.
                Java 25 is required. Program execution remains explicit.
                """);
    }
}
