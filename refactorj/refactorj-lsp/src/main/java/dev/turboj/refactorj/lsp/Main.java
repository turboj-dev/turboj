package dev.turboj.refactorj.lsp;

import dev.turboj.refactorj.cli.*;
import java.nio.file.Path;

public final class Main {
    public static void main(String[] arguments) throws Exception {
        var protocolOutput = System.out;
        System.setOut(System.err);
        String[] args = new String[arguments.length + 1];
        args[0] = "lsp";
        System.arraycopy(arguments, 0, args, 1, arguments.length);
        var options = dev.turboj.refactorj.cli.Main.Arguments.parse(args);

        if (!options.options().containsKey("root"))
            throw new IllegalArgumentException("--root is required");
        Path root = Path.of(options.option("root", "")).toRealPath();

        try (var handler = new LspHandler(root, dev.turboj.refactorj.cli.Main.paths(root, options
                .option("source-root", "")), dev.turboj.refactorj.cli.Main.paths(root, options
                .option("classpath", "")))) {
            new RpcServer(System.in, protocolOutput, RpcServer.Framing.CONTENT_LENGTH, handler).run();
        }
    }
}
