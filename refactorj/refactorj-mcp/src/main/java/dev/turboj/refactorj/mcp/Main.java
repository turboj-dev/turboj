package dev.turboj.refactorj.mcp;

import dev.turboj.refactorj.cli.*;
import dev.turboj.refactorj.transactions.DiskWorkspace;
import java.nio.file.Path;

public final class Main {
    public static void main(String[] arguments) throws Exception {
        var protocolOutput = System.out;
        System.setOut(System.err);
        String[] args = new String[arguments.length + 1];
        args[0] = "mcp";
        System.arraycopy(arguments, 0, args, 1, arguments.length);
        var options = dev.turboj.refactorj.cli.Main.Arguments.parse(args);

        if (!options.options().containsKey("root"))
            throw new IllegalArgumentException("--root is required; the server never grants access to arbitrary paths");
        Path root = Path.of(options.option("root", "")).toRealPath();
        var store = DiskWorkspace.open(root, dev.turboj.refactorj.cli.Main.paths(root, options
                .option("source-root", "")), dev.turboj.refactorj.cli.Main.paths(root, options
                .option("classpath", "")));

        try (var session = new WorkspaceSession(store)) {
            new RpcServer(System.in, protocolOutput, RpcServer.Framing.JSON_LINES,
                    new McpHandler(session, options.flag("allow-write"))).run();
        }
    }
}
