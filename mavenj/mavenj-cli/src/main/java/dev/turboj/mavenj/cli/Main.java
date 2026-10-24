package dev.turboj.mavenj.cli;

import dev.turboj.mavenj.api.Cancellation;
import dev.turboj.mavenj.api.MavenModels.*;
import dev.turboj.mavenj.api.MavenModels.Module;
import dev.turboj.mavenj.core.DefaultMavenJ;
import dev.turboj.mavenj.workspace.Toolchains;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** Standalone inspection. Exit codes: 0 success, 2 usage, 3 model/resolution, 4 I/O, 130 cancelled. */
public final class Main {
    private Main() {}

    public static void main(String[] args) {
        System.exit(run(args, System.out, System.err));
    }

    public static int run(String[] args, PrintStream output, PrintStream errors) {
        if (args.length == 0 || Arrays.asList(args).contains("--help")) {
            output.println(usage());
            return 0;
        }
        if (args.length == 1 && args[0].equals("--version")) {
            output.println("mavenj 0.1.0");
            return 0;
        }
        boolean json = Arrays.asList(args).contains("--json");

        try {
            Options options = Options.parse(args);

            try (var engine = new DefaultMavenJ()) {
                Workspace workspace = engine.open(options.request, Cancellation.NONE);
                workspace = Toolchains.select(workspace, options.toolchains).workspace();
                Object result = switch (options.command) {
                    case "inspect", "sync" -> workspace;
                    case "modules" -> workspace.modules().stream().map(m -> Json.object("id", m
                            .id(), "coordinates", m.coordinates(), "directory", m.directory(),
                            "packaging", m.packaging(), "reactorDependencies", m
                            .reactorDependencies())).toList();
                    case "dependencies" -> workspace.modules().stream().map(m -> Json
                            .object("module", m.id(), "dependencies", m.dependencies(),
                            "diagnostics", m.diagnostics())).toList();
                    case "classpath" -> workspace.modules().stream().map(m -> Json.object("module",
                            m.id(), "scope", options.scope, "classpath", m.classpaths()
                            .forScope(options.scope), "modulePath", m.classpaths()
                            .modulesForScope(options.scope), "resolved", m.classpaths().resolved()))
                            .toList();
                    default -> throw new IllegalArgumentException("Unknown command: "
                            + options.command);
                };

                if (json)
                    output.println(Json.report(Json.object("result", result, "generation", workspace
                            .generation(), "diagnostics", workspace.diagnostics(), "resolved",
                            workspace.resolved())));
                else
                    print(workspace, options, output);
                return workspace.resolved() ? 0 : 3;
            }
        } catch (java.util.concurrent.CancellationException e) {
            return 130;
        } catch (IllegalArgumentException e) {
            reportError(json, "USAGE", e.getMessage(), output, errors);
            return 2;
        } catch (Exception e) {
            reportError(json, "IMPORT_FAILED", e.getMessage(), output, errors);
            return 4;
        }
    }

    private static void reportError(boolean json, String code, String message, PrintStream output,
            PrintStream errors) {
        if (json)
            output.println(Json.report(Json.object("error", code, "message", Objects
                    .requireNonNullElse(message, "Operation failed"))));
        else
            errors.println(code + ": " + dev.turboj.mavenj.workspace.MavenLifecycle.redact(Objects
                    .requireNonNullElse(message, "Operation failed"), Map.of()));
    }

    private static void print(Workspace workspace, Options options, PrintStream output) {
        if (options.command.equals("classpath")) {
            for (Module module : workspace.modules()) {
                output.println(module.id() + " [" + options.scope + "]");
                output.println(String.join(File.pathSeparator, module.classpaths()
                        .forScope(options.scope).stream().map(Path::toString).toList()));
                if (!module.classpaths().modulesForScope(options.scope).isEmpty())
                    output.println("module-path: " + String.join(File.pathSeparator, module
                            .classpaths().modulesForScope(options.scope).stream()
                            .map(Path::toString).toList()));
            }
        } else {
            output.println("MAVEN PROJECT — " + workspace.root().getFileName());
            output.println("Modules: " + workspace.modules().size() + "  Dependencies: "
                    + (workspace.resolved() ? "Resolved" : "Incomplete") + "  Mode: " + (workspace
                    .offline() ? "Offline" : "Configured repositories"));
            for (Module module : workspace.modules()) {
                output.println(module.coordinates().gav() + " [" + module.packaging() + "] Java "
                        + module.mainJava().languageLevel());
                if (options.command.equals("dependencies"))
                    module.dependencies().forEach(d -> output.println("  " + d.coordinates().key()
                            + " [" + d.scope() + "] " + (d.file() == null ? d.reactorModule() : d
                            .file())));
            }
        }
        for (var diagnostic : workspace.diagnostics())
            output.println(diagnostic.severity() + " " + diagnostic.code() + ": " + diagnostic
                    .message());
        for (var module : workspace.modules())
            for (var diagnostic : module.diagnostics())
                output.println(diagnostic.severity() + " " + diagnostic.code() + ": " + diagnostic
                        .message());
    }

    private record Options(String command, ImportRequest request, Path toolchains,
            ClasspathScope scope) {
        static Options parse(String[] args) {
            String command = args[0];

            if (!Set.of("inspect", "modules", "dependencies", "classpath", "sync")
                    .contains(command))
                throw new IllegalArgumentException("Unknown command: " + command);
            Path project = Path.of("."), settings = null, global = null, local = null, home = null,
                    toolchains = null;
            Set<String> profiles = new LinkedHashSet<> (), inactive = new LinkedHashSet<> (),
                    repositories = new LinkedHashSet<> ();
            Map<String, String> properties = new LinkedHashMap<> ();
            boolean offline = true, refresh = command.equals("sync"), projectSet = false;
            ClasspathScope scope = ClasspathScope.MAIN_COMPILE;

            for (int i = 1; i < args.length; i++) {
                String arg = args[i];

                switch (arg) {
                    case "--json" -> {}
                    case "--offline" -> offline = true;
                    case "--online" -> offline = false;
                    case "--refresh-snapshots" -> refresh = true;
                    case "--settings", "--global-settings", "--local-repository", "--java-home",
                            "--toolchains", "--allow-repository", "--profiles",
                            "--inactive-profiles", "--property", "--scope" -> {
                        if (++i >= args.length)
                            throw new IllegalArgumentException("Missing value for " + arg);
                        String value = args[i];

                        switch (arg) {
                            case "--settings" -> settings = Path.of(value);
                            case "--global-settings" -> global = Path.of(value);
                            case "--local-repository" -> local = Path.of(value);
                            case "--java-home" -> home = Path.of(value);
                            case "--toolchains" -> toolchains = Path.of(value);
                            case "--allow-repository" -> repositories.add(value);
                            case "--profiles" -> profiles.addAll(List.of(value.split(",")));
                            case "--inactive-profiles" -> inactive.addAll(List.of(value
                                    .split(",")));
                            case "--property" -> {
                                int equals = value.indexOf('=');

                                if (equals < 1)
                                    throw new IllegalArgumentException("Property requires key=value");
                                properties.put(value.substring(0, equals), value.substring(equals
                                        + 1));
                            }
                            case "--scope" -> scope = Main.scope(value);
                        }
                    }
                    default -> {
                        if (arg.startsWith("-") || projectSet)
                            throw new IllegalArgumentException("Unexpected argument: " + arg);
                        project = Path.of(arg);
                        projectSet = true;
                    }
                }
            }
            if (!offline && repositories.isEmpty())
                throw new IllegalArgumentException("--online requires one or more --allow-repository URL values");
            return new Options(command, new ImportRequest(project, settings, global, local,
                    profiles, inactive, properties, offline, repositories, refresh, home, Map.of()),
                    toolchains, scope);
        }
    }

    public static ClasspathScope scope(String value) {
        return switch (value.toLowerCase(Locale.ROOT).replace('-', '_')) {
            case "compile", "main", "main_compile" -> ClasspathScope.MAIN_COMPILE;
            case "test", "test_compile" -> ClasspathScope.TEST_COMPILE;
            case "runtime", "main_runtime" -> ClasspathScope.MAIN_RUNTIME;
            case "test_runtime" -> ClasspathScope.TEST_RUNTIME;
            case "processor" -> ClasspathScope.PROCESSOR;
            default -> throw new IllegalArgumentException("Unknown classpath scope: " + value);
        };
    }

    private static String usage() {
        return """
            mavenj 0.1.0 — Native Maven project inspection
            Usage: mavenj <inspect|modules|dependencies|classpath|sync> [path] [options]
              --json                         Machine-readable result and diagnostics
              --scope compile|test|runtime|test-runtime|processor
              --profiles name[,name]         Activate Maven profiles
              --inactive-profiles name[,name]
              --settings FILE  --global-settings FILE  --local-repository DIR
              --java-home DIR  --toolchains FILE  --property key=value
              --offline                      Default: cached artifacts, no network
              --online --allow-repository URL  Explicit repository permission (repeatable)
              --refresh-snapshots             Apply Maven repository update policies
              --version
            Exit codes: 0 success; 2 usage; 3 unresolved model/dependencies; 4 import failure; 130 cancellation.
            No Maven lifecycle, plugin or annotation processor is executed by these commands.
            """;
    }
}
