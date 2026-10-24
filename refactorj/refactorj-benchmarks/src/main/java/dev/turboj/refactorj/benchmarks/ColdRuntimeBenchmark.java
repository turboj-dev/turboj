package dev.turboj.refactorj.benchmarks;

import dev.turboj.refactorj.runtime.RefactorJRuntime;
import dev.turboj.refactorj.api.*;
import dev.turboj.refactorj.api.Model.*;
import java.nio.file.*;
import java.util.*;
import org.openjdk.jmh.annotations.*;
import java.util.concurrent.TimeUnit;

/** One measured invocation per fresh JVM fork; startup and clean shutdown are included. */
@BenchmarkMode(Mode.SingleShotTime)
@OutputTimeUnit(TimeUnit.MILLISECONDS)
@Warmup(iterations = 0)
@Measurement(iterations = 1)
@Fork(5)
public class ColdRuntimeBenchmark {
    @Benchmark public long embeddedRuntime() throws Exception {
        try (var runtime = new RefactorJRuntime()) { return runtime.initializationNanos(); }
    }
    /** Fresh framework, 20-source Java model, first symbol resolution, and complete shutdown. */
    @Benchmark public Symbol embeddedWorkspace() throws Exception {
        Path root=Files.createTempDirectory("refactorj-cold-").toRealPath();
        try {
            List<SourceSnapshot> sources=new ArrayList<>();
            for(int i=0;i<20;i++)sources.add(new SourceSnapshot(root.resolve("C"+i+".java"),"class C"+i+" { int value(){return 1;} }",1,true));
            try(var runtime=new RefactorJRuntime();var engine=runtime.openWorkspace(new WorkspaceSnapshot(root,1,sources,List.of(root),List.of()))) {
                return engine.workspace().resolve(new Location(sources.getFirst().file(),sources.getFirst().text().indexOf("value")),Cancellation.NONE);
            }
        } finally { Files.deleteIfExists(root); }
    }
}
