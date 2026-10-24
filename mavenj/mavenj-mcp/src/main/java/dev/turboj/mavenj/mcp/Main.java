package dev.turboj.mavenj.mcp;

import dev.turboj.mavenj.api.MavenModels.ImportRequest;
import java.nio.file.Path;
import java.util.*;

public final class Main {
    private Main() {}

    public static void main(String[] args) throws Exception {
        Path root = null, local = null, settings = null;
        boolean allowWrite = false, offline = true;
        Set<String> repositories = new LinkedHashSet<> (), profiles = new LinkedHashSet<> ();

        for (int i = 0; i < args.length; i++)
            switch (args[i]) {
                case "--root" -> root = Path.of(value(args, ++i));
                case "--local-repository" -> local = Path.of(value(args, ++i));
                case "--settings" -> settings = Path.of(value(args, ++i));
                case "--allow-repository" -> repositories.add(value(args, ++i));
                case "--profiles" -> profiles.addAll(List.of(value(args, ++i).split(",")));
                case "--allow-write" -> allowWrite = true;
                case "--online" -> offline = false;
                case "--offline" -> offline = true;
                default -> throw new IllegalArgumentException("Usage: mavenj-mcp --root DIR [--allow-write] [--settings FILE] [--local-repository DIR] [--profiles names] [--online --allow-repository URL]");
            }
        if (root == null)
            throw new IllegalArgumentException("An explicit --root is required");
        if (!offline && repositories.isEmpty())
            throw new IllegalArgumentException("--online requires --allow-repository");
        var request = new ImportRequest(root, settings, null, local, profiles, Set.of(), Map.of(),
                offline, repositories, false, null, Map.of());
        var protocol = System.out;
        System.setOut(System.err);
        try (var server = new McpServer(request, allowWrite)) {
            server.serve(System.in, protocol);
        }
    }

    private static String value(String[] args, int index) {
        if (index >= args.length)
            throw new IllegalArgumentException("Missing option value");
        return args[index];
    }
}
