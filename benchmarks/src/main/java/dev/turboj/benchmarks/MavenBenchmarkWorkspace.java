package dev.turboj.benchmarks;

import dev.turboj.mavenj.api.MavenModels.*;
import dev.turboj.mavenj.core.DefaultMavenJ;
import dev.turboj.project.MavenCompilationWorkspace;
import java.nio.file.*;
import java.util.*;

/** Generated public-domain corpus: reactor chain, 20 Java source files in each module. */
final class MavenBenchmarkWorkspace implements AutoCloseable {
    final Path directory;
    final Workspace model;
    final MavenCompilationWorkspace compiler;
    final Path leaf;
    private long generation;
    MavenBenchmarkWorkspace(int modules)throws Exception {
        directory=Files.createTempDirectory("turboj-maven-benchmark-");
        String names=java.util.stream.IntStream.range(0,modules).mapToObj(i->"<module>m"+i+"</module>").collect(java.util.stream.Collectors.joining());
        Files.writeString(directory.resolve("pom.xml"),"<project><modelVersion>4.0.0</modelVersion><groupId>bench</groupId><artifactId>reactor</artifactId><version>1</version><packaging>pom</packaging><properties><maven.compiler.release>17</maven.compiler.release></properties><modules>"+names+"</modules></project>");
        for(int module=0;module<modules;module++) {
            Path folder=Files.createDirectories(directory.resolve("m"+module));Path source=Files.createDirectories(folder.resolve("src/main/java/p"+module));
            String dependency=module==0?"":"<dependencies><dependency><groupId>bench</groupId><artifactId>m"+(module-1)+"</artifactId><version>1</version></dependency></dependencies>";
            Files.writeString(folder.resolve("pom.xml"),"<project><modelVersion>4.0.0</modelVersion><parent><groupId>bench</groupId><artifactId>reactor</artifactId><version>1</version></parent><artifactId>m"+module+"</artifactId>"+dependency+"</project>");
            for(int sourceIndex=0;sourceIndex<20;sourceIndex++)Files.writeString(source.resolve("C"+sourceIndex+".java"),"package p"+module+"; public class C"+sourceIndex+" { public static int value(){return "+(module==0?"1":"p"+(module-1)+".C0.value()+1")+";} }");
        }
        try(var importer=new DefaultMavenJ()){model=importer.open(ImportRequest.offline(directory));}
        if(!model.resolved())throw new IllegalStateException(model.diagnostics().toString());
        compiler=new MavenCompilationWorkspace();leaf=directory.resolve("m"+(modules-1)+"/src/main/java/p"+(modules-1)+"/C0.java");
        if(!compiler.compile(model,Map.of(),generation++,false).success())throw new IllegalStateException("Benchmark fixture did not compile");
    }
    MavenCompilationWorkspace.Result cached(){return compiler.compile(model,Map.of(),generation++,false);}
    MavenCompilationWorkspace.Result edit(){long current=++generation;String pkg=leaf.getParent().getFileName().toString();return compiler.compile(model,Map.of(leaf,"package "+pkg+"; public class C0 { public static int value(){return "+(current%2)+";} }"),current,false);}
    @Override public void close()throws Exception{compiler.close();try(var files=Files.walk(directory)){for(Path path:files.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
}
