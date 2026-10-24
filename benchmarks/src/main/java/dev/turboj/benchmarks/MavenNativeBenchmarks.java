package dev.turboj.benchmarks;

import dev.turboj.project.MavenCompilationWorkspace;
import org.openjdk.jmh.annotations.*;
import java.util.concurrent.TimeUnit;

@State(Scope.Thread)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations=2,time=1)
@Measurement(iterations=3,time=1)
@Fork(value=1,jvmArgsAppend={"-Xms256m","-Xmx1g"})
public class MavenNativeBenchmarks {
    @Param({"12"}) public int modules;
    private MavenBenchmarkWorkspace workspace;
    @Setup public void setup()throws Exception{workspace=new MavenBenchmarkWorkspace(modules);}
    @TearDown public void close()throws Exception{workspace.close();}
    @Benchmark public MavenCompilationWorkspace.Result unchangedReactor(){return workspace.cached();}
    @Benchmark public MavenCompilationWorkspace.Result editLeafModule(){return workspace.edit();}
}
