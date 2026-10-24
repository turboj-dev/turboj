package dev.turboj.refactorj.cli;

import com.google.gson.*;
import dev.turboj.refactorj.api.*;
import dev.turboj.refactorj.api.Model.*;
import dev.turboj.refactorj.transactions.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Preview-first independent CLI. Exit 0 success, 1 conflicts, 2 invalid input/operational error. */
public final class Main {
    private Main() {}

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    public static int run(String[] args, PrintStream out, PrintStream err) {
        try {
            if (args.length == 0 || Arrays.asList(args).contains("--help")) {
                help(out);
                return 0;
            }
            if (args[0].equals("--version")) {
                out.println("refactorj 0.1.0 — Java 25");
                return 0;
            }
            Arguments options = Arguments.parse(args);
            Path root = Path.of(options.option("root", ".")).toRealPath();
            List<Path> sources = paths(root, options.option("source-root", ""));
            List<Path> classpath = paths(root, options.option("classpath", ""));

            if (options.command().equals("recover")) {
                if (options.flag("dry-run")) {
                    try {
                        DiskWorkspace.open(root, sources, classpath);
                        out.println("No recovery required; source unchanged.");
                    } catch (TransactionException pending) {
                        if (!pending.code().equals("RECOVERY_REQUIRED"))
                            throw pending;
                        out.println("Recovery required; dry-run left source unchanged.");
                    }
                } else {
                    DiskWorkspace.openRecovering(root, sources, classpath);
                    out.println("Recovery completed.");
                }
                return 0;
            }
            DiskWorkspace store = DiskWorkspace.open(root, sources, classpath);

            if (options.command().equals("history")) {
                out.println(Json.GSON.toJson(store.history()));
                return 0;
            }
            try (WorkspaceSession session = new WorkspaceSession(store)) {
                if (options.command().equals("list")) {
                    if (options.positionals().isEmpty())
                        out.println(Json.GSON.toJson(Arrays.stream(Operation.values())
                                .map(Operation::id).toList()));
                    else
                        out.println(Json.GSON.toJson(session.engine(Cancellation.NONE)
                                .available(location(options.positionals().getFirst(), session
                                .snapshot()), Cancellation.NONE)));
                    return 0;
                }
                if (options.command().equals("inspect")) {
                    Location location = location(requiredPositional(options), session.snapshot());
                    var engine = session.engine(Cancellation.NONE);
                    Symbol symbol = engine.workspace().resolve(location, Cancellation.NONE);
                    out.println(Json.GSON.toJson(Json.object("symbol", symbol, "available", engine
                            .available(location, Cancellation.NONE))));
                    return 0;
                }
                if (options.command().equals("undo") || options.command().equals("redo")) {
                    if (options.flag("dry-run")) {
                        out.println(Json.GSON.toJson(session.history()));
                        return 0;
                    }
                    var result = options.command().equals("undo") ? session
                            .undo(requiredPositional(options), Cancellation.NONE) : session
                            .redo(requiredPositional(options), Cancellation.NONE);
                    out.println(Json.GSON.toJson(result));
                    return 0;
                }
                if (options.command().equals("apply") || options.command().equals("validate")) {
                    WorkspaceSession.Prepared plan = readPlan(Path.of(requiredPositional(options)));
                    var valid = session.validatePrepared(plan, Cancellation.NONE);

                    if (options.flag("dry-run") || options.command().equals("validate"))
                        out.println(Json.GSON.toJson(valid));
                    else
                        out.println(Json.GSON.toJson(session.apply(valid, Cancellation.NONE)));
                    return 0;
                }
                Operation operation = Json.operation(options.command());
                JsonObject request = options.options().containsKey("request") ? Json.GSON
                        .fromJson(readLimited(Path.of(options.option("request", ""))),
                        JsonObject.class) : new JsonObject();
                request.addProperty("operation", operation.id());
                if (!request.has("location")) {
                    String symbol = options.option("symbol", "");
                    Location location;

                    if (!symbol.isEmpty())
                        location = location(symbol, session.snapshot());
                    else {
                        Path file = Json.safePath(root, options.option("file",
                                requiredPositionalOrEmpty(options)));
                        var source = session.snapshot().source(file);
                        String range = options.option("range", "1:1-1:1");
                        String[] ends = range.split("-", -1);

                        if (ends.length != 2)
                            throw new IllegalArgumentException("Range must be line:column-line:column");
                        location = new Location(file, lineOffset(source.text(), ends[0]),
                                lineOffset(source.text(), ends[1]));
                    }
                    request.add("location", Json.GSON.toJsonTree(location));
                }
                if (options.options().containsKey("to"))
                    request.addProperty("name", options.option("to", ""));
                if (options.options().containsKey("name"))
                    request.addProperty("name", options.option("name", ""));
                var prepared = session.prepare(request, Cancellation.NONE);
                out.println(Json.GSON.toJson(prepared));
                return prepared.plan().valid() ? 0 : 1;
            }
        } catch (TransactionException failure) {
            err.println(failure.code() + ": " + failure.getMessage());
            return failure.code().contains("STALE") || failure.code().contains("CONFLICT") ? 1 : 2;
        } catch (Exception failure) {
            err.println(failure.getClass().getSimpleName() + ": " + failure.getMessage());
            return 2;
        }
    }

    public static WorkspaceSession.Prepared readPlan(Path path) throws IOException {
        return Json.GSON.fromJson(readLimited(path), WorkspaceSession.Prepared.class);
    }

    public static String readLimited(Path path) throws IOException {
        if (Files.size(path) > 64L * 1024 * 1024)
            throw new IOException("Input exceeds 64 MiB");
        try (var input = Files.newInputStream(path)) {
            byte[] bytes = input.readNBytes(64 * 1024 * 1024 + 1);

            if (bytes.length > 64 * 1024 * 1024)
                throw new IOException("Input exceeds 64 MiB");
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        }
    }

    public static List<Path> paths(Path root, String paths) {
        if (paths.isBlank())
            return List.of();
        return Arrays.stream(paths.split(java.util.regex.Pattern.quote(File.pathSeparator)))
                .map(Path::of).map(p -> p.isAbsolute() ? p.normalize() : root.resolve(p)
                .normalize()).toList();
    }

    private static Location location(String argument, WorkspaceSnapshot workspace) {
        int column = argument.lastIndexOf(':'), line = column < 0 ? -1 : argument.lastIndexOf(':',
                column - 1);

        if (line < 0)
            throw new IllegalArgumentException("Location must be file:line:column (one-based)");
        Path file = Json.safePath(workspace.root(), argument.substring(0, line));
        return new Location(file, lineOffset(workspace.source(file).text(), argument.substring(line
                + 1)));
    }

    private static int lineOffset(String source, String position) {
        String[] parts = position.split(":", -1);

        if (parts.length != 2)
            throw new IllegalArgumentException("Expected line:column");
        return Json.offset(source, Integer.parseInt(parts[0]) - 1, Integer.parseInt(parts[1]) - 1);
    }

    private static String requiredPositional(Arguments options) {
        if (options.positionals().size() != 1)
            throw new IllegalArgumentException("Exactly one argument required");
        return options.positionals().getFirst();
    }

    private static String requiredPositionalOrEmpty(Arguments options) {
        return options.positionals().isEmpty() ? "" : requiredPositional(options);
    }

    public record Arguments(String command, Map<String, String> options, Set<String> flags,
            List<String> positionals) {
        public String option(String key, String fallback) {
            return options.getOrDefault(key, fallback);
        }

        public boolean flag(String key) {
            return flags.contains(key);
        }

        public static Arguments parse(String[] args) {
            if (args.length == 0)
                throw new IllegalArgumentException("Missing command");
            var options = new LinkedHashMap<String, String> ();
            var flags = new HashSet<String> ();
            var positionals = new ArrayList<String> ();
            Set<String> switches = Set.of("dry-run", "json", "allow-write");
            Set<String> values = Set.of("root", "source-root", "classpath", "symbol", "file",
                    "range", "to", "name", "request");

            for (int i = 1; i < args.length; i++) {
                String arg = args[i];

                if (!arg.startsWith("--")) {
                    positionals.add(arg);
                    continue;
                }
                String key = arg.substring(2);

                if (switches.contains(key)) {
                    flags.add(key);
                    continue;
                }
                if (!values.contains(key) || i + 1 == args.length || args[i + 1].startsWith("--")
                        || options.putIfAbsent(key, args[++i]) != null)
                    throw new IllegalArgumentException("Unknown, duplicate or incomplete option: "
                            + arg);
            }
            return new Arguments(args[0], Map.copyOf(options), Set.copyOf(flags), List
                    .copyOf(positionals));
        }
    }

    private static void help(PrintStream out) {
        out
                .println("""
            refactorj — semantic Java refactoring; Java 25 required
            refactorj list [file:line:column] --root PROJECT
            refactorj inspect file:line:column --root PROJECT
            refactorj rename --symbol file:line:column --to newName --root PROJECT
            refactorj extract-method --file File.java --range 2:5-4:6 --name calculate
            refactorj OPERATION --request request.json --root PROJECT
            refactorj validate plan.json | apply plan.json [--dry-run]
            refactorj history | undo TRANSACTION_ID | redo TRANSACTION_ID | recover
            Every transformation prints a JSON preview. Only apply/undo/redo/recover mutate files.
            --source-root PATHS and --classpath PATHS use the platform path separator.
            Positions are one-based lines and UTF-16 columns. Exit 0 success, 1 conflicts, 2 error.
            """);
    }
}
