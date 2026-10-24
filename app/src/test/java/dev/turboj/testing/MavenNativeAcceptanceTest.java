package dev.turboj.testing;

import com.google.gson.Gson;
import dev.turboj.mavenj.api.MavenModels.*;
import dev.turboj.mavenj.core.DefaultMavenJ;
import dev.turboj.project.MavenCompilationWorkspace;
import dev.turboj.execution.WorkerClient;
import dev.turboj.turbotest.*;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

/** Real isolated JUnit execution and an explicitly invoked Maven lifecycle use the same fixture. */
class MavenNativeAcceptanceTest {
    @TempDir Path project;
    private Path repositoryRoot(){Path root=Path.of("").toAbsolutePath();while(!Files.isRegularFile(root.resolve("mvnw")))root=root.getParent();return root;}
    @Test void sourceTargetEightNativeCompilationMatchesExplicitCurrentJdkMavenCompile()throws Exception {
        Path repository=repositoryRoot(),home=Path.of(System.getProperty("java.home"));
        Files.writeString(project.resolve("pom.xml"),"""
                <project><modelVersion>4.0.0</modelVersion><groupId>example.acceptance</groupId><artifactId>source-target-eight</artifactId><version>1</version>
                <properties><maven.compiler.source>8</maven.compiler.source><maven.compiler.target>8</maven.compiler.target><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>
                <build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.14.1</version></plugin><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-resources-plugin</artifactId><version>3.3.1</version></plugin></plugins></build></project>
                """);
        Path sources=Files.createDirectories(project.resolve("src/main/java"));
        Files.writeString(sources.resolve("SourceEight.java"),"public class SourceEight { public static int value(){return java.util.List.of(1,2,3).size();} }");
        var request=new ImportRequest(project,null,null,repository.resolve(".cache/m2"),Set.of(),Set.of(),Map.of(),true,Set.of(),false,home,Map.of());
        try(var maven=new DefaultMavenJ();var compiler=new MavenCompilationWorkspace()){
            var model=maven.open(request);assertTrue(model.resolved(),model.diagnostics().toString());
            var module=model.modules().getFirst();assertEquals("",module.mainJava().release());assertEquals("8",module.mainJava().source());assertEquals("8",module.mainJava().target());
            var nativeResult=compiler.compile(model,Map.of(),1,false);assertTrue(nativeResult.success(),nativeResult.diagnostics().toString());
            byte[] nativeClass=compiler.runtimeBytecode(module.id(),false).get("SourceEight");assertEquals(52,java.lang.classfile.ClassFile.of().parse(nativeClass).majorVersion());
            assertFalse(Files.exists(project.resolve("target")));
            Path log=project.resolve("maven-compile.log");
            var command=new ProcessBuilder(repository.resolve("mvnw").toString(),"-o","-f",project.resolve("pom.xml").toString(),"-Dmaven.repo.local="+repository.resolve(".cache/m2"),"compile").directory(repository.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
            command.environment().put("JAVA_HOME",home.toString());Process process=command.start();assertTrue(process.waitFor(90,TimeUnit.SECONDS));assertEquals(0,process.exitValue(),Files.readString(log));
            byte[] mavenClass=Files.readAllBytes(project.resolve("target/classes/SourceEight.class"));assertEquals(52,java.lang.classfile.ClassFile.of().parse(mavenClass).majorVersion());
            for(Path classes:List.of(compiler.output(module.id(),false),project.resolve("target/classes")))try(var loader=new java.net.URLClassLoader(new java.net.URL[]{classes.toUri().toURL()},ClassLoader.getPlatformClassLoader())){assertEquals(3,loader.loadClass("SourceEight").getMethod("value").invoke(null));}
            Files.writeString(repository.resolve("target/maven-source-target8-parity.txt"),"Fixture: Maven source/target8 on current JDK "+Runtime.version()+"; List.of API deliberately used\nNative and explicit offline Maven compile: classfile52; runtime value3\nThis is source/target compatibility, not --release8 or Java8 runtime compatibility.\n");
        }
    }
    @Test void nativeCompilationAndProjectJUnitMatchExplicitMavenVerify()throws Exception {
        Path repository=repositoryRoot();Path home=Path.of("/Library/Java/JavaVirtualMachines/amazon-corretto-21.jdk/Contents/Home");
        if(!Files.isExecutable(home.resolve("bin/java")))home=Path.of(System.getProperty("java.home"));
        Files.writeString(project.resolve("pom.xml"),"""
                <project xmlns="http://maven.apache.org/POM/4.0.0"><modelVersion>4.0.0</modelVersion>
                <groupId>example.acceptance</groupId><artifactId>native-parity</artifactId><version>1</version>
                <properties><maven.compiler.release>21</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>
                <dependencies><dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><version>5.13.4</version><scope>test</scope></dependency></dependencies>
                <build><plugins><plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.14.1</version></plugin>
                <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId><version>3.5.4</version></plugin>
                <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-jar-plugin</artifactId><version>3.4.2</version></plugin>
                <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-resources-plugin</artifactId><version>3.3.1</version></plugin></plugins></build></project>
                """);
        Path main=Files.createDirectories(project.resolve("src/main/java/sample")),tests=Files.createDirectories(project.resolve("src/test/java/sample"));
        Files.writeString(main.resolve("Calculator.java"),"package sample; public class Calculator { public static int sum(int a,int b){return a+b;} }");
        Files.writeString(tests.resolve("CalculatorTest.java"),"package sample; import org.junit.jupiter.api.Test; import static org.junit.jupiter.api.Assertions.*; public class CalculatorTest { @Test void addition(){assertEquals(7,Calculator.sum(3,4));} @Test void runtime(){assertTrue(Runtime.version().feature()>=21);} }");
        var request=new ImportRequest(project,null,null,repository.resolve(".cache/m2"),Set.of(),Set.of(),Map.of(),true,Set.of(),false,home,Map.of());
        try(var maven=new DefaultMavenJ();var compiler=new MavenCompilationWorkspace();var junit=new JUnitRuntime();var worker=new WorkerClient(Duration.ofSeconds(20),1024*1024,256,home)) {
            var workspace=maven.open(request);assertTrue(workspace.resolved(),workspace.diagnostics()+" "+workspace.modules());
            var module=workspace.modules().getFirst();assertEquals("21",module.mainJava().release());
            var result=compiler.compile(workspace,Map.of(),7,true);assertTrue(result.success(),result.diagnostics().toString());assertFalse(Files.exists(project.resolve("target")),"Native compile must not run Maven or write target");
            var cp=junit.projectExecutionClasspath(compiler.runtimeClasspath(module.id(),true));
            assertEquals(module.classpaths().forScope(ClasspathScope.TEST_RUNTIME).stream().filter(p->p.getFileName().toString().startsWith("junit-jupiter-engine-")).toList(),cp.stream().filter(p->p.getFileName().toString().startsWith("junit-jupiter-engine-")).toList());
            var testRequest=new TestEngine.Request(7,List.of("sample.CalculatorTest"),List.of());Path report=project.resolve("native-report.json");String encoded=Base64.getEncoder().encodeToString(new Gson().toJson(testRequest).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var run=worker.run(7,compiler.runtimeBytecode(module.id(),true),WorkerEntry.class.getName(),List.of(encoded,report.toString()),cp,false,event->{}).get(30,TimeUnit.SECONDS);
            assertEquals(0,run.exitCode(),run.detail());var nativeReport=new Gson().fromJson(Files.readString(report),TestEngine.Report.class);assertEquals(7,nativeReport.sourceVersion());assertEquals(2,nativeReport.passed());assertTrue(nativeReport.successful());
            Path mavenLog=project.resolve("maven-verify.log");
            var process=new ProcessBuilder(repository.resolve("mvnw").toString(),"-o","-f",project.resolve("pom.xml").toString(),"-Dmaven.repo.local="+repository.resolve(".cache/m2"),"verify").directory(repository.toFile()).redirectErrorStream(true).redirectOutput(mavenLog.toFile());
            process.environment().put("JAVA_HOME",home.toString());Process execution=process.start();
            assertTrue(execution.waitFor(90,TimeUnit.SECONDS),"Explicit Maven verification timed out");assertEquals(0,execution.exitValue(),Files.readString(mavenLog));
            String xml=Files.readString(project.resolve("target/surefire-reports/TEST-sample.CalculatorTest.xml"));assertTrue(xml.contains("tests=\"2\""));assertTrue(xml.contains("failures=\"0\""));assertTrue(xml.contains("errors=\"0\""));
            Path artifact=repository.resolve("target/maven-native-lifecycle-parity.txt");Files.writeString(artifact,"Fixture: Java21 Calculator + two JUnit5.13.4 tests\nNative: sourceGeneration7, 2 passed, no target output before explicit lifecycle\nMaven: offline verify exit0, tests2 failures0 errors0\nProject JDK: "+home+"\nLimit: this fixture has no custom generators, resource filtering, Surefire provider/fork overrides or build extensions.\n");
        }
    }
}
