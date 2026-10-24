package dev.turboj.refactorj.benchmarks;

import dev.turboj.refactorj.api.*;
import dev.turboj.refactorj.api.Model.*;
import dev.turboj.refactorj.api.Requests.Rename;
import dev.turboj.refactorj.runtime.RefactorJRuntime;
import dev.turboj.refactorj.transactions.*;
import org.openjdk.jmh.annotations.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

@State(Scope.Thread)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(2)
public class SemanticBenchmark {
    @Param({"1", "20"}) public int files;
    private RefactorJRuntime runtime;
    private RefactorJ engine;
    private Path directory;
    private Location local, method;
    private Symbol symbol;
    private Plan plan;
    private long validationIdentity;

    @Setup(Level.Trial) public void setup() throws Exception {
        directory = Files.createTempDirectory("refactorj-jmh-").toRealPath();
        List<SourceSnapshot> sources = new ArrayList<>();
        for (int index = 0; index < files; index++) {
            Path file = directory.resolve("C" + index + ".java");
            String source = "class C" + index + " { int calculate(int value) { int result = value + 1; return result; } }";
            Files.writeString(file, source);
            sources.add(new SourceSnapshot(file, source, 1, true));
            if (index == 0) { local = new Location(file, source.indexOf("result")); method = new Location(file, source.indexOf("calculate")); }
        }
        runtime = new RefactorJRuntime();
        engine = runtime.openWorkspace(new WorkspaceSnapshot(directory, 1, sources, List.of(directory), List.of()));
        symbol = engine.workspace().resolve(local, Cancellation.NONE);
        plan = engine.prepare(new Rename(local, "total"), Cancellation.NONE);
        if (!plan.valid()) throw new IllegalStateException(plan.diagnostics().toString());
    }

    @Benchmark public Symbol cachedSymbol() { return engine.workspace().resolve(local, Cancellation.NONE); }
    @Benchmark public List<Location> references() { return engine.workspace().references(symbol.id(), Cancellation.NONE); }
    @Benchmark public Plan localRename() { return engine.prepare(new Rename(local, "total"), Cancellation.NONE); }
    @Benchmark public Plan methodRename() { return engine.prepare(new Rename(method, "compute"), Cancellation.NONE); }
    @Benchmark public Validation validatePreview() { return engine.validate(plan, Cancellation.NONE); }
    /** Same edits, unique plan identity: bypass the exact-plan validation cache each invocation. */
    @Benchmark public Validation validateUncached() {
        var fresh = new Plan("uncached-" + ++validationIdentity, plan.operation(), plan.before(),
                plan.affectedSymbols(), plan.edits(), plan.fileOperations(), plan.diagnostics(),
                plan.status(), plan.description());
        return engine.validate(fresh, Cancellation.NONE);
    }
    @Benchmark public WorkspaceSnapshot applyAndUndo() {
        var transactions = new WorkspaceTransactions(new InMemoryWorkspace(plan.before()));
        var result = transactions.apply(plan, Cancellation.NONE);
        return transactions.undo(result.transactionId(), Cancellation.NONE).snapshot();
    }

    @TearDown(Level.Trial) public void close() throws Exception {
        if (runtime != null) runtime.close();
        if (directory != null) try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
