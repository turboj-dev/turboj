package dev.turboj.commandj.benchmarks;

import dev.turboj.commandj.core.*;
import dev.turboj.commandj.keymap.*;
import dev.turboj.commandj.palette.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.*;

@State(Scope.Thread)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class CommandBenchmarks {
    @Param({"100", "1000"})
    public int count;
    private CommandRegistry registry;
    private KeymapManager maps;
    private CommandSearch search;
    private ShortcutResolver resolver;
    private CommandContext context;
    private KeyStrokeSpec save;

    @Setup
    public void setup() {
        registry = new CommandRegistry();
        context = CommandContext.of("editor", "editable");
        for (int i = 0; i < count; i++)
            registry.register(Command.action("command." + i, "Format document " + i, "Formatting",
                    () -> {}).withAliases("source action " + i));
        registry.register(Command.action("save", "Save", "File", () -> {}));
        maps = new KeymapManager();
        search = new CommandSearch(registry);
        search.search("format", context, 50);
        resolver = new ShortcutResolver(maps, registry);
        save = KeyStrokeSpec.parse("META+S");
    }

    @TearDown
    public void close() {
        registry.close();
    }

    @Benchmark
    public Object lookup() {
        return registry.find("command.50");
    }

    @Benchmark
    public Object dispatch() {
        return registry.execute("command.50", context).stage();
    }

    @Benchmark
    public Object shortcut() {
        return resolver.accept(save, context, System.nanoTime());
    }

    @Benchmark
    public Object palette() {
        return search.search("fmt doc", context, 50);
    }

    @Benchmark
    public Object conflicts() {
        return maps.conflicts();
    }

    @Benchmark
    public Object switchProfile() {
        maps.select(maps.selectedId().equals("intellij-macos") ? "vscode-macos" : "intellij-macos");
        return maps.bindings();
    }

    @Benchmark
    public int registration() {
        try (var r = new CommandRegistry()) {
            for (int i = 0; i < 100; i++)
                r.register(Command.action("new." + i, "Command " + i, "Tests", () -> {}));
            return r.commands().size();
        }
    }
}
