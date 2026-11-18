package dev.turboj.testing;

import com.google.gson.Gson;
import dev.turboj.execution.WorkerClient;
import dev.turboj.mavenj.api.MavenModels.*;
import dev.turboj.mavenj.core.DefaultMavenJ;
import dev.turboj.mavenj.workspace.Toolchains;
import dev.turboj.project.MavenCompilationWorkspace;
import dev.turboj.turbotest.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Real selected-JDK qualification, with the editor/compiler JVM kept separate from the run/test JVM. */
class MavenToolchainQualificationTest {
    @TempDir Path project;

    private Path repository() {
        Path path = Path.of("").toAbsolutePath();
        while (!Files.isRegularFile(path.resolve("mvnw"))) path = path.getParent();
        return path;
    }
    private Path java17() throws Exception {
        String configured = System.getProperty("turboj.test.java17", System.getenv("TURBOJ_TEST_JDK17"));
        org.junit.jupiter.api.Assumptions.assumeTrue(configured != null, "Set turboj.test.java17 or TURBOJ_TEST_JDK17 for actual JDK17 qualification");
        Path home = Path.of(configured).toRealPath();
        assertTrue(Files.isExecutable(home.resolve("bin/java")));
        assertEquals(17, Toolchains.inspect(home).orElseThrow().feature());
        return home;
    }
    private Path write(String relative, String content) throws Exception {
        Path file = project.resolve(relative); Files.createDirectories(file.getParent()); Files.writeString(file, content); return file;
    }
    private boolean offline() { return Boolean.parseBoolean(System.getProperty("turboj.test.mavenOffline", "true")); }
    private ProcessBuilder maven(String... arguments) {
        var command = new ArrayList<String>(); command.add(repository().resolve("mvnw").toString());
        if (offline()) command.add("-o");
        command.addAll(List.of(arguments));
        return new ProcessBuilder(command);
    }
    private ImportRequest request(Path home) {
        return new ImportRequest(project, null, null, MavenTestRepository.resolve(repository()), Set.of("qualification"), Set.of(), Map.of(), offline(), Set.of(), false, home, Map.of());
    }
    private static String child(String name, String extra) {
        return "<project><modelVersion>4.0.0</modelVersion><parent><groupId>qualification</groupId><artifactId>reactor</artifactId><version>1</version></parent><artifactId>" + name + "</artifactId>" + extra + "</project>";
    }

    @Test void actual17ToolchainRunsMainJUnitAndIncrementalMultiModuleGenerations() throws Exception {
        Path home = java17();
        String rootPom = """
                <project><modelVersion>4.0.0</modelVersion><groupId>qualification</groupId><artifactId>reactor</artifactId><version>1</version><packaging>pom</packaging>
                <modules><module>library</module><module>application</module><module>independent</module></modules>
                <properties><maven.compiler.release>17</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding></properties>
                <profiles><profile><id>qualification</id><properties><qualification.marker>active</qualification.marker></properties></profile></profiles>
                <dependencyManagement><dependencies><dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><version>5.13.4</version><scope>test</scope></dependency></dependencies></dependencyManagement>
                <build><plugins>
                <plugin><artifactId>maven-compiler-plugin</artifactId><version>3.14.1</version><configuration><jdkToolchain><version>17</version><vendor>amazon</vendor></jdkToolchain></configuration></plugin>
                <plugin><artifactId>maven-surefire-plugin</artifactId><version>3.5.4</version><configuration><jdkToolchain><version>17</version><vendor>amazon</vendor></jdkToolchain></configuration></plugin>
                <plugin><artifactId>maven-resources-plugin</artifactId><version>3.3.1</version></plugin>
                <plugin><artifactId>maven-jar-plugin</artifactId><version>3.4.2</version></plugin>
                </plugins></build></project>
                """;
        write("pom.xml", rootPom);
        write("library/pom.xml", child("library", ""));
        write("independent/pom.xml", child("independent", ""));
        write("application/pom.xml", child("application", "<dependencies><dependency><groupId>qualification</groupId><artifactId>library</artifactId><version>1</version></dependency><dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><scope>test</scope></dependency></dependencies>"));
        Path library = write("library/src/main/java/sample/Value.java", "package sample; public class Value { public static int value(){return 42;} }");
        write("independent/src/main/java/other/Independent.java", "package other; public class Independent { public static int value(){return 7;} }");
        write("application/src/main/resources/message.txt", "resource-ok");
        write("application/src/main/java/sample/Main.java", "package sample; public class Main { public static void main(String[] args) throws Exception { System.out.print(Runtime.version().feature()+\":\"+Value.value()+\":\"+new String(Main.class.getResourceAsStream(\"/message.txt\").readAllBytes(),java.nio.charset.StandardCharsets.UTF_8)); } }");
        write("application/src/test/java/sample/MainTest.java", "package sample; import org.junit.jupiter.api.Test; import static org.junit.jupiter.api.Assertions.*; public class MainTest { @Test void actualRuntime(){assertEquals(17,Runtime.version().feature());} @Test void dependency(){assertEquals(42,Value.value());} }");
        Path chains = write("toolchains.xml", "<toolchains><toolchain><type>jdk</type><provides><version>17</version><vendor>amazon</vendor></provides><configuration><jdkHome>" + home + "</jdkHome></configuration></toolchain></toolchains>");
        StringBuilder evidence = new StringBuilder("Editor/compiler JVM: " + Runtime.version() + "\nSelected project/run/test JVM: " + home + "\nDependency mode: " + (offline() ? "warm cache, offline" : "online permitted; cache state not asserted") + "; model session first/warm measured separately\n");
        try (var maven = new DefaultMavenJ(); var compiler = new MavenCompilationWorkspace(); var junit = new JUnitRuntime(); var worker = new WorkerClient(Duration.ofSeconds(20), 1024 * 1024, 128, home)) {
            long started = System.nanoTime(); var first = maven.open(request(home)); long firstNanos = System.nanoTime()-started;
            assertTrue(first.resolved(), first.diagnostics().toString());
            started = System.nanoTime(); var warm = maven.open(request(home)); long warmNanos = System.nanoTime()-started;
            assertTrue(warm.cacheHit()); assertTrue(warm.activeProfiles().contains("qualification"));
            var selected = Toolchains.select(warm, chains, null, List.of(Toolchains.inspect(home).orElseThrow()));
            assertTrue(selected.diagnostics().isEmpty(), selected.diagnostics().toString());
            var model = selected.workspace();
            for (var module : model.modules()) if (!module.packaging().equals("pom")) {
                assertEquals(home, module.mainJava().javaHome()); assertEquals("17", module.mainJava().release());
                assertEquals("active", module.properties().get("qualification.marker"));
            }
            var app = model.modules().stream().filter(m -> m.coordinates().artifactId().equals("application")).findFirst().orElseThrow();
            var independent = model.modules().stream().filter(m -> m.coordinates().artifactId().equals("independent")).findFirst().orElseThrow();
            var compiled = compiler.compile(model, Map.of(), 1, true);
            assertTrue(compiled.success(), compiled.diagnostics().toString());
            assertEquals(61, java.lang.classfile.ClassFile.of().parse(compiler.runtimeBytecode(app.id(), false).get("sample.Main")).majorVersion());
            assertFalse(Files.exists(project.resolve("application/target")), "Import/native compile must not invoke lifecycle");
            StringBuffer output = new StringBuffer();
            assertEquals(0, worker.run(1, compiler.runtimeBytecode(app.id(), false), "sample.Main", List.of(), compiler.runtimeClasspath(app.id(), false), false, e -> output.append(e.text())).get(30, TimeUnit.SECONDS).exitCode());
            assertEquals("17:42:resource-ok", output.toString());
            var request = new TestEngine.Request(1, List.of("sample.MainTest"), List.of());
            Path report = project.resolve("native-tests.json");
            String encoded = Base64.getEncoder().encodeToString(new Gson().toJson(request).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var result = worker.run(1, compiler.runtimeBytecode(app.id(), true), WorkerEntry.class.getName(), List.of(encoded, report.toString()), junit.projectExecutionClasspath(compiler.runtimeClasspath(app.id(), true)), false, event -> {}).get(30, TimeUnit.SECONDS);
            assertEquals(0, result.exitCode(), result.detail());
            var tests = new Gson().fromJson(Files.readString(report), TestEngine.Report.class);
            assertEquals(2, tests.passed()); assertTrue(tests.successful());
            var edited = compiler.compile(model, Map.of(library, Files.readString(library).replace("42", "43")), 2, false);
            assertTrue(edited.success(), edited.diagnostics().toString());
            assertTrue(edited.modules().stream().filter(m -> m.moduleId().equals(independent.id())).allMatch(MavenCompilationWorkspace.ModuleResult::cacheHit));
            output.setLength(0);
            assertEquals(0, worker.run(2, compiler.runtimeBytecode(app.id(), false), "sample.Main", List.of(), compiler.runtimeClasspath(app.id(), false), false, e -> output.append(e.text())).get(30, TimeUnit.SECONDS).exitCode());
            assertEquals("17:43:resource-ok", output.toString());
            assertTrue(Files.readString(library).contains("42"), "Unsaved overlay must not write source");
            var restored = compiler.compile(model, Map.of(), 3, true); assertTrue(restored.success());
            Path log = project.resolve("maven-verify.log");
            var process = maven("-f", project.resolve("pom.xml").toString(), "--toolchains", chains.toString(), "-Pqualification", "-Dmaven.repo.local=" + MavenTestRepository.resolve(repository()), "verify")
                    .directory(repository().toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
            process.environment().put("JAVA_HOME", System.getProperty("java.home"));
            Process execution = process.start();
            assertTrue(execution.waitFor(120, TimeUnit.SECONDS)); assertEquals(0, execution.exitValue(), Files.readString(log));
            assertTrue(Files.readString(log).contains("Toolchain in maven-compiler-plugin"), Files.readString(log));
            assertTrue(Files.readString(log).contains(home.toString()), Files.readString(log));
            String xml = Files.readString(project.resolve("application/target/surefire-reports/TEST-sample.MainTest.xml"));
            assertTrue(xml.contains("tests=\"2\"")); assertTrue(xml.contains("failures=\"0\""));
            assertTrue(xml.contains("value=\"17\""), "Surefire must use selected17 toolchain");
            write("pom.xml", rootPom.replace("<module>independent</module>", ""));
            var pruned = Toolchains.select(maven.open(request(home)), chains, null, List.of(Toolchains.inspect(home).orElseThrow())).workspace();
            assertTrue(pruned.modules().stream().noneMatch(m -> m.id().equals(independent.id())));
            assertTrue(compiler.compile(pruned, Map.of(), 4, true).success());
            assertThrows(NoSuchElementException.class, () -> compiler.runtimeClasspath(independent.id(), false));
            evidence.append("Native: main17:42:resource-ok; JUnit2 PASS; unsaved dependent output43; independent cache retained; removed module pruned\nMaven: explicit verify via real compiler/run toolchain17; two tests PASS\nModel first ns: ").append(firstNanos).append("\nModel warm ns: ").append(warmNanos).append('\n');
            Files.createDirectories(repository().resolve("target")); Files.writeString(repository().resolve("target/maven-toolchain17-qualification.txt"), evidence);
        }
    }
    @Test void generatorsFilteredResourcesAndFailsafeRequireExplicitLifecycle() throws Exception {
        Path home = java17();
        write("pom.xml", """
                <project><modelVersion>4.0.0</modelVersion><groupId>qualification</groupId><artifactId>lifecycle</artifactId><version>1</version>
                <properties><maven.compiler.release>17</maven.compiler.release><project.build.sourceEncoding>UTF-8</project.build.sourceEncoding><qualification.message>filtered-value</qualification.message></properties>
                <dependencies><dependency><groupId>org.junit.jupiter</groupId><artifactId>junit-jupiter</artifactId><version>5.13.4</version><scope>test</scope></dependency></dependencies>
                <build><resources><resource><directory>src/main/resources</directory><filtering>true</filtering></resource></resources><plugins>
                <plugin><artifactId>maven-compiler-plugin</artifactId><version>3.14.1</version></plugin>
                <plugin><artifactId>maven-resources-plugin</artifactId><version>3.3.1</version></plugin>
                <plugin><artifactId>maven-jar-plugin</artifactId><version>3.4.2</version></plugin>
                <plugin><artifactId>maven-surefire-plugin</artifactId><version>3.5.4</version></plugin>
                <plugin><artifactId>maven-failsafe-plugin</artifactId><version>3.5.4</version><executions><execution><goals><goal>integration-test</goal><goal>verify</goal></goals></execution></executions></plugin>
                <plugin><artifactId>maven-antrun-plugin</artifactId><version>3.1.0</version><executions><execution><phase>generate-sources</phase><goals><goal>run</goal></goals><configuration><target><mkdir dir="${project.build.directory}/generated-sources/qualification/sample"/><echo file="${project.build.directory}/generated-sources/qualification/sample/Generated.java">package sample; public class Generated { public static int value(){ return 42; } }</echo><echo file="${project.build.directory}/plugin-executed.txt">explicit-lifecycle</echo></target></configuration></execution></executions></plugin>
                <plugin><groupId>org.codehaus.mojo</groupId><artifactId>build-helper-maven-plugin</artifactId><version>3.6.1</version><executions><execution><phase>generate-sources</phase><goals><goal>add-source</goal></goals><configuration><sources><source>${project.build.directory}/generated-sources/qualification</source></sources></configuration></execution></executions></plugin>
                </plugins></build></project>
                """);
        write("src/main/resources/message.txt", "${qualification.message}");
        write("src/main/java/sample/Main.java", "package sample; public class Main { public static int value(){return Generated.value();} }");
        write("src/test/java/sample/MainTest.java", "package sample; import org.junit.jupiter.api.Test; import static org.junit.jupiter.api.Assertions.*; public class MainTest { @Test void generated(){assertEquals(42,Main.value());} }");
        write("src/test/java/sample/MainIT.java", "package sample; import org.junit.jupiter.api.Test; import static org.junit.jupiter.api.Assertions.*; public class MainIT { @Test void resourceAndRuntime() throws Exception {assertEquals(17,Runtime.version().feature()); assertEquals(\"filtered-value\",new String(Main.class.getResourceAsStream(\"/message.txt\").readAllBytes(),java.nio.charset.StandardCharsets.UTF_8));} }");
        try (var maven = new DefaultMavenJ(); var compiler = new MavenCompilationWorkspace()) {
            var model = maven.open(request(home)); assertTrue(model.resolved(), model.diagnostics().toString());
            var module = model.modules().getFirst();
            assertNotEquals(FastPath.SUPPORTED, module.fastPath());
            assertTrue(module.diagnostics().stream().anyMatch(d -> d.code().equals("build.plugin")));
            assertTrue(module.resources().stream().anyMatch(ResourceRoot::filtering));
            assertFalse(Files.exists(project.resolve("target/plugin-executed.txt")));
            assertFalse(compiler.compile(model, Map.of(), 1, false).success());
            assertFalse(Files.exists(project.resolve("target/plugin-executed.txt")), "Native compilation must not execute project plugins implicitly");
            Path log = project.resolve("explicit-lifecycle.log");
            var builder = maven("-f", project.resolve("pom.xml").toString(), "-Dmaven.repo.local=" + MavenTestRepository.resolve(repository()), "verify")
                    .directory(repository().toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
            builder.environment().put("JAVA_HOME", home.toString());
            Process execution = builder.start(); assertTrue(execution.waitFor(120, TimeUnit.SECONDS)); assertEquals(0, execution.exitValue(), Files.readString(log));
            assertEquals("explicit-lifecycle", Files.readString(project.resolve("target/plugin-executed.txt")));
            assertEquals("filtered-value", Files.readString(project.resolve("target/classes/message.txt")));
            for (String report : List.of("surefire-reports/TEST-sample.MainTest.xml", "failsafe-reports/TEST-sample.MainIT.xml")) {
                String xml = Files.readString(project.resolve("target").resolve(report));
                assertTrue(xml.contains("tests=\"1\"")); assertTrue(xml.contains("failures=\"0\"")); assertTrue(xml.contains("errors=\"0\""));
            }
            Files.createDirectories(repository().resolve("target"));
            Files.writeString(repository().resolve("target/maven-explicit-lifecycle-qualification.txt"),
                    "Import/native compile do not execute antrun/build-helper; generated type unavailable until explicit lifecycle.\nExplicit Maven verify under actual JDK17: generated source, filtered resource, Surefire1 PASS and Failsafe1 PASS.\nPlugin versions: compiler3.14.1 resources3.3.1 jar3.4.2 surefire3.5.4 failsafe3.5.4 antrun3.1.0 build-helper3.6.1.\n");
        }
    }

}
