package dev.turboj.mavenj.benchmarks;

import dev.turboj.mavenj.api.MavenModels.*;
import dev.turboj.mavenj.core.DefaultMavenJ;
import org.openjdk.jmh.annotations.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/** Offline model measurements. No remote downloads, GUI work, or source compilation is included. */
@BenchmarkMode(Mode.SampleTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@Warmup(iterations = 2, time = 1)
@Measurement(iterations = 3, time = 1)
@Fork(2)
@State(Scope.Benchmark)
public class ImportBenchmarks {
    @Param({"1", "24"})
    public int moduleCount;
    private Path root;
    private DefaultMavenJ engine;
    private ImportRequest request;
    private Workspace workspace;
    private String lastId;

    @Setup(Level.Trial)
    public void setup() throws Exception {
        root = Files.createTempDirectory("mavenj-jmh-");
        StringBuilder modules = new StringBuilder();

        for (int i = 0; i < moduleCount; i++)
            modules.append("<module>module-").append(i).append("</module>");
        Files.writeString(root.resolve("pom.xml"),
                "<project><modelVersion>4.0.0</modelVersion><groupId>benchmark</groupId><artifactId>parent</artifactId><version>1</version><packaging>pom</packaging><properties><maven.compiler.release>21</maven.compiler.release></properties><modules>"
                + modules + "</modules></project>");
        for (int i = 0; i < moduleCount; i++) {
            Path directory = Files.createDirectories(root.resolve("module-" + i));
            String dependency = i
                    == 0 ? "" : "<dependencies><dependency><groupId>benchmark</groupId><artifactId>module-"
                    + (i - 1) + "</artifactId><version>1</version></dependency></dependencies>";
            Files.writeString(directory.resolve("pom.xml"),
                    "<project><modelVersion>4.0.0</modelVersion><parent><groupId>benchmark</groupId><artifactId>parent</artifactId><version>1</version></parent><artifactId>module-"
                    + i + "</artifactId>" + dependency + "</project>");
            Path sources = Files.createDirectories(directory.resolve("src/main/java/benchmark/m"
                    + i));

            for (int j = 0; j < 10; j++)
                Files.writeString(sources.resolve("Value" + j + ".java"), "package benchmark.m" + i
                        + "; public record Value" + j + "(int value) {}\n");
        }
        request = new ImportRequest(root, null, null, root.resolve("repository"), Set.of(), Set
                .of(), Map.of(), true, Set.of(), false, null, Map.of());
        engine = new DefaultMavenJ();
        workspace = engine.open(request);
        if (!workspace.resolved())
            throw new IllegalStateException(workspace.diagnostics().toString());
        lastId = workspace.modules().getLast().id();
    }

    @Benchmark
    public Workspace warmImport() {
        return engine.open(request);
    }

    @Benchmark
    public Workspace coldImport() {
        try (var cold = new DefaultMavenJ()) {
            return cold.open(request);
        }
    }

    @Benchmark
    public Optional<dev.turboj.mavenj.api.MavenModels.Module> cachedModelLookup() {
        return workspace.module(lastId);
    }

    @Benchmark
    public List<Path> cachedClasspathLookup() {
        return workspace.modules().getLast().classpaths().forScope(ClasspathScope.TEST_COMPILE);
    }

    @Benchmark
    public List<String> cachedModuleGraph() {
        return workspace.buildOrder();
    }

    @TearDown(Level.Trial)
    public void teardown() throws Exception {
        engine.close();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList())
                Files.deleteIfExists(path);
        }
    }
}
