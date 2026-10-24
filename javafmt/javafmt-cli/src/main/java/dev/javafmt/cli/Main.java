package dev.javafmt.cli;

import dev.javafmt.core.FormatRequest;
import dev.javafmt.core.FormatResult;
import dev.javafmt.core.JavaFormatter;
import dev.javafmt.core.LineEndingPolicy;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Standalone entry point. Standard output is exclusively source, diffs, paths or JSONL. */
public final class Main {
    private enum Mode {
        PRINT,
        WRITE,
        CHECK,
        DIFF
    }

    private record Options(Mode mode, boolean stdin, boolean server, List<String> paths) {}

    private static final Set<String> SKIP_DIRECTORIES =
            Set.of("target", "build", "out", "node_modules");

    private Main() {}

    public static void main(String[] args) {
        System.exit(run(args, System.in, System.out, System.err));
    }

    /** Does not close the caller's streams. Exit 0 success, 1 check differences, 2 failure. */
    public static int run(String[] args, InputStream in, OutputStream out, OutputStream err) {
        PrintWriter stdout = new PrintWriter(out, false, StandardCharsets.UTF_8);
        PrintWriter stderr = new PrintWriter(err, false, StandardCharsets.UTF_8);
        try {
            if (args.length == 1 && args[0].equals("--help")) {
                stdout.print(help());
                return 0;
            }
            if (args.length == 1 && args[0].equals("--version")) {
                stdout.printf(
                        "javafmt %s%nStyle specification: JavaFMT %d%nJava source support: 25%n",
                        JavaFormatter.VERSION, JavaFormatter.STYLE_VERSION);
                return 0;
            }
            Options options = options(args);
            JavaFormatter formatter = new JavaFormatter();
            if (options.server) return new FormatServer(formatter).serve(in, out, stderr);
            if (options.stdin) {
                String source = SafeFiles.readUtf8(in);
                return process(formatter, options.mode, "<stdin>", source, null, stdout, stderr);
            }
            List<Path> files = collect(options.paths, options.mode == Mode.PRINT);
            if (options.mode == Mode.PRINT && files.size() != 1)
                throw new IllegalArgumentException(
                        "Printing requires exactly one Java file; use -w, --check or --diff for"
                            + " multiple files.");
            int status = 0;
            for (Path file : files) {
                try {
                    SafeFiles.Snapshot snapshot = SafeFiles.read(file);
                    status =
                            Math.max(
                                    status,
                                    process(
                                            formatter,
                                            options.mode,
                                            display(file),
                                            snapshot.source(),
                                            snapshot,
                                            stdout,
                                            stderr));
                } catch (Exception failure) {
                    stderr.println("javafmt: " + display(file) + ": " + message(failure));
                    status = 2;
                }
            }
            return status;
        } catch (Exception failure) {
            stderr.println("javafmt: " + message(failure));
            return 2;
        } finally {
            stdout.flush();
            stderr.flush();
        }
    }

    private static int process(
            JavaFormatter formatter,
            Mode mode,
            String label,
            String source,
            SafeFiles.Snapshot snapshot,
            PrintWriter out,
            PrintWriter err)
            throws Exception {
        FormatResult result = formatter.format(new FormatRequest(source, 0, LineEndingPolicy.LF));
        if (!result.success()) {
            for (var diagnostic : result.diagnostics())
                err.println(
                        "javafmt: "
                                + label
                                + ": UTF-16 "
                                + diagnostic.start()
                                + ": "
                                + diagnostic.message());
            return 2;
        }
        boolean changed = !source.equals(result.formattedText());
        switch (mode) {
            case PRINT -> out.print(result.formattedText());
            case WRITE -> {
                if (changed) SafeFiles.write(snapshot, result.formattedText());
            }
            case CHECK -> {
                if (changed) {
                    out.println(label);
                    return 1;
                }
            }
            case DIFF -> {
                if (changed) out.print(UnifiedDiff.create(label, source, result.formattedText()));
            }
        }
        return 0;
    }

    private static Options options(String[] args) {
        Mode mode = Mode.PRINT;
        boolean selectedMode = false, stdin = false, server = false, literal = false;
        var paths = new ArrayList<String>();
        for (String arg : args) {
            if (literal) {
                paths.add(arg);
                continue;
            }
            if (arg.equals("--")) {
                literal = true;
                continue;
            }
            Mode selection =
                    switch (arg) {
                        case "-w" -> Mode.WRITE;
                        case "--check" -> Mode.CHECK;
                        case "--diff" -> Mode.DIFF;
                        default -> null;
                    };
            if (selection != null) {
                if (selectedMode)
                    throw new IllegalArgumentException("Choose only one of -w, --check or --diff.");
                mode = selection;
                selectedMode = true;
            } else if (arg.equals("--stdin")) stdin = true;
            else if (arg.equals("--server")) server = true;
            else if (arg.startsWith("-"))
                throw new IllegalArgumentException(
                        "Unknown or misplaced option: " + arg + ". Use --help.");
            else paths.add(arg);
        }
        if (server && (args.length != 1))
            throw new IllegalArgumentException("--server cannot be combined with other arguments.");
        if (stdin && (!paths.isEmpty() || mode == Mode.WRITE))
            throw new IllegalArgumentException(
                    "--stdin accepts no paths and cannot write in place.");
        if (!server && !stdin && paths.isEmpty())
            throw new IllegalArgumentException(
                    "Supply a Java file/directory or --stdin. Use --help.");
        return new Options(mode, stdin, server, List.copyOf(paths));
    }

    private static List<Path> collect(List<String> names, boolean printing) throws Exception {
        Set<Path> found = new LinkedHashSet<>();
        for (String name : names) {
            Path path = Path.of(name).toAbsolutePath().normalize();
            if (Files.isSymbolicLink(path))
                throw new IllegalArgumentException(
                        "Explicit symbolic links are not processed: " + display(path));
            if (Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
                if (!path.getFileName().toString().endsWith(".java"))
                    throw new IllegalArgumentException("Not a .java file: " + display(path));
                found.add(path);
            } else if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                if (printing)
                    throw new IllegalArgumentException(
                            "Printing a directory is ambiguous; use -w, --check or --diff.");
                Files.walkFileTree(
                        path,
                        new SimpleFileVisitor<>() {
                            @Override
                            public FileVisitResult preVisitDirectory(
                                    Path dir, BasicFileAttributes attrs) {
                                String leaf = dir.getFileName().toString();
                                return !dir.equals(path)
                                                && (leaf.startsWith(".")
                                                        || SKIP_DIRECTORIES.contains(leaf))
                                        ? FileVisitResult.SKIP_SUBTREE
                                        : FileVisitResult.CONTINUE;
                            }

                            @Override
                            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                                String leaf = file.getFileName().toString();
                                if (attrs.isRegularFile()
                                        && !leaf.startsWith(".")
                                        && leaf.endsWith(".java")) found.add(file);
                                return FileVisitResult.CONTINUE;
                            }
                        });
            } else
                throw new IllegalArgumentException(
                        "Not a readable regular file or directory: " + display(path));
        }
        return found.stream().sorted(Comparator.comparing(Path::toString)).toList();
    }

    static String display(Path path) {
        return path.toString()
                .replace("\\", "\\\\")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }

    private static String message(Exception failure) {
        return failure.getMessage() == null
                ? failure.getClass().getSimpleName()
                : failure.getMessage();
    }

    private static String help() {
        return """
        javafmt — JavaFMT Style 1, zero style configuration
        Usage:
          javafmt File.java          Print formatted UTF-8 source
          javafmt -w PATH...         Write changed Java files safely
          javafmt --check PATH...    Check files/directories without writing
          javafmt --diff PATH...     Print unified diffs without writing
          javafmt --stdin            Read UTF-8 source from stdin
          javafmt --server           Resident JSONL formatter for IDE clients
          javafmt --version          Show product, style and Java versions
          javafmt --help             Show this help

        --stdin also supports --check and --diff. Use -- before paths beginning with -.
        Directory modes recurse deterministically, skipping symbolic links, hidden
        entries, target, build, out and node_modules. Imports are never removed.
        Exit codes: 0 success; 1 --check found differences; 2 invalid input or failure.
        Output whitespace uses LF; comments and literal contents remain protected.
        """;
    }
}
