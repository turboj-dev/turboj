package dev.turboj.benchmarks;

import dev.turboj.editor.document.PieceTable;
import org.openjdk.jmh.annotations.*;
import javax.swing.SwingUtilities;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.*;

/** Measures EDT queue + document edit/snapshot; excludes paint, compositor and physical keyboard. */
@State(Scope.Thread)
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations=2,time=1)
@Measurement(iterations=3,time=1)
@Fork(value=1,jvmArgsAppend={"-Xms256m","-Xmx1g"})
public class MavenEdtBenchmarks {
    @Param({"false","true"}) public boolean compiling;
    private MavenBenchmarkWorkspace workspace;
    private PieceTable document;
    private Thread background;
    private final AtomicBoolean stopped=new AtomicBoolean();
    private final AtomicReference<Throwable> failure=new AtomicReference<>();
    @Setup public void setup()throws Exception {
        document=new PieceTable(Workloads.source(10000,0));
        SwingUtilities.invokeAndWait(()->{});
        if(compiling){workspace=new MavenBenchmarkWorkspace(12);background=Thread.ofPlatform().name("maven-bench-native-compiler").start(()->{try{while(!stopped.get())if(!workspace.edit().success())throw new IllegalStateException("Compilation failed");}catch(Throwable error){failure.set(error);}});}
    }
    @Benchmark public long eventQueueAndEdit()throws Exception {
        if(failure.get()!=null)throw new IllegalStateException(failure.get());
        AtomicLong version=new AtomicLong();SwingUtilities.invokeAndWait(()->{document.insert(25,"x");version.set(document.snapshot().version());document.delete(25,26);});return version.get();
    }
    @TearDown public void close()throws Exception{stopped.set(true);if(background!=null)background.join();if(workspace!=null)workspace.close();}
}
