package com.turboj.benchmarks;

import com.turboj.compiler.CompilationResult;
import com.turboj.compiler.JavacCompilerBackend;
import com.turboj.compiler.SourceSnapshot;
import com.turboj.editor.document.PieceTable;
import com.turboj.language.JdtSyntaxAnalyzer;

import org.openjdk.jmh.annotations.*;

import java.util.concurrent.TimeUnit;

@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(
        value = 1,
        jvmArgsAppend = {"-Xms256m", "-Xmx1g"})
@State(Scope.Thread)
public class CoreBenchmarks {
    @Param({"100", "1000", "10000"})
    public int lines;

    private PieceTable document;
    private JavacCompilerBackend compiler;
    private JdtSyntaxAnalyzer syntax;
    private SourceSnapshot syntaxSource;
    private long revision;

    @Setup(Level.Trial)
    public void setup() {
        document = new PieceTable(Workloads.source(lines, 0));
        compiler = new JavacCompilerBackend();
        syntax = new JdtSyntaxAnalyzer();
        syntaxSource = new SourceSnapshot("Workload.java", Workloads.source(lines, 0), 0);
    }

    @TearDown(Level.Trial)
    public void tearDown() {
        compiler.close();
    }

    /** Constant-size insert/snapshot/delete cycle; not display latency. */
    @Benchmark
    public long documentEditAndSnapshot() {
        document.insert(25, "x");
        var snapshot = document.snapshot();
        document.delete(25, 26);
        return snapshot.version();
    }

    @Benchmark
    public CompilationResult warmCompileChangedSource() {
        long current = revision++;
        return compiler.compile(
                new SourceSnapshot("Workload.java", Workloads.source(lines, current), current));
    }

    @Benchmark
    public JdtSyntaxAnalyzer.SyntaxResult syntaxAnalysis() {
        return syntax.analyze(syntaxSource);
    }
}
