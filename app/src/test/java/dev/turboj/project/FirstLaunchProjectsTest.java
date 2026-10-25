package dev.turboj.project;

import dev.turboj.intelligence.ProjectContext;
import dev.turboj.refactorj.api.Model.*;
import dev.turboj.refactorj.transactions.ChangeSet;
import java.nio.file.*;
import java.util.*;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class FirstLaunchProjectsTest {
    @TempDir Path temporary;

    @Test void createsExplicitPlainSourceRootsAndNeverReplacesExistingProject() throws Exception {
        var spec = project("plain", false);
        var result = FirstLaunchProjects.createProject(spec);
        assertFalse(result.maven());
        assertEquals(List.of(result.root().resolve("src/main/java"), result.root().resolve("src/test/java")), result.sourceRoots());
        assertEquals("src/main/java\nsrc/test/java\n", Files.readString(result.root().resolve(".turboj/source-roots.txt")));
        assertFalse(Files.exists(result.root().resolve("pom.xml")));
        Files.writeString(result.root().resolve("owned.java"), "do not touch");
        assertThrows(FileAlreadyExistsException.class, () -> FirstLaunchProjects.createProject(spec));
        assertEquals("do not touch", Files.readString(result.root().resolve("owned.java")));
    }

    @Test void mavenProjectPinsCompilerAndTestPluginWithoutInventingJUnitDependency() throws Exception {
        var result = FirstLaunchProjects.createProject(project("maven", true));
        String pom = Files.readString(result.root().resolve("pom.xml"));
        assertTrue(pom.contains("<maven.compiler.release>25</maven.compiler.release>"));
        assertTrue(pom.contains("<artifactId>maven-compiler-plugin</artifactId><version>3.14.1</version>"));
        assertTrue(pom.contains("<artifactId>maven-surefire-plugin</artifactId><version>3.5.4</version>"));
        assertFalse(pom.contains("junit"));
        assertEquals("project", dev.turboj.mavenj.workspace.PomEdits.elements(pom).getFirst().path().getFirst());
    }

    @Test void rejectsEscapesAndInvalidMetadataBeforeCreatingProject() {
        var bad = new FirstLaunchProjects.ProjectSpec(temporary, "../outside", temporary.resolve("no"), FirstLaunchProjects.Kind.JAVA, "", "", "25");
        assertThrows(IllegalArgumentException.class, () -> FirstLaunchProjects.createProject(bad));
        assertFalse(Files.exists(temporary.resolve("no")));
        var coordinates = new FirstLaunchProjects.ProjectSpec(temporary, "safe", temporary.resolve("no"), FirstLaunchProjects.Kind.MAVEN, "<injection>", "ok", "25");
        assertThrows(IllegalArgumentException.class, () -> FirstLaunchProjects.createProject(coordinates));
        assertFalse(Files.exists(temporary.resolve("no")));
    }

    @Test void preparesPackagedUnicodeMainWithoutTouchingDiskAndRejectsDuplicates() throws Exception {
        var project = FirstLaunchProjects.createProject(project("unicode", false));
        var snapshot = empty(project);
        var generated = FirstLaunchProjects.mainPlan(snapshot, new FirstLaunchProjects.MainSpec(project.sourceRoots().getFirst(), "example.tools", "Salùt", true));
        assertEquals(project.root().resolve("src/main/java/example/tools/Salùt.java"), generated.file());
        String text = generated.plan().fileOperations().getFirst().content();
        assertEquals("Hello World!", text.substring(generated.start(), generated.end()));
        assertTrue(text.startsWith("package example.tools;\n\npublic class Salùt"));
        assertFalse(Files.exists(generated.file()));
        materialize(generated.plan());
        assertThrows(IllegalArgumentException.class, () -> FirstLaunchProjects.mainPlan(snapshot,
                new FirstLaunchProjects.MainSpec(project.sourceRoots().getFirst(), "example.tools", "Salùt", true)));
    }

    @Test void mainClassCannotBeCreatedOutsideConfiguredSourceRoots() throws Exception {
        var project = FirstLaunchProjects.createProject(project("configured-main", true));
        var request = new FirstLaunchProjects.MainSpec(project.root().resolve("unindexed/java"), "example", "Main", true);
        var failure = assertThrows(IllegalArgumentException.class, () -> FirstLaunchProjects.mainPlan(empty(project), request));
        assertTrue(failure.getMessage().contains("configured Java source"));
        assertFalse(Files.exists(project.root().resolve("unindexed")));
    }

    @Test void junitTransactionWidensJavaModelRootWithoutWideningSourceDiscovery() throws Exception {
        var context = context("narrow-model", false);
        var before = context.snapshot();
        var narrow = new WorkspaceSnapshot(before.root().resolve("src"), before.version(), before.sources(), before.sourceRoots(), before.classpath());
        var inferred = FirstLaunchProjects.testContext(narrow, parameters(context.sourceFile(), "Adder", "example"),
                context.projectRoot(), context.testRoot(), null);
        assertEquals(context.projectRoot(), inferred.snapshot().root());
        assertEquals(narrow.sourceRoots(), inferred.snapshot().sourceRoots());
        var generated = FirstLaunchProjects.testPlan(inferred, test(inferred, true));
        assertTrue(generated.plan().fileOperations().stream().anyMatch(operation -> operation.path().equals(context.projectRoot().resolve(FirstLaunchProjects.JUNIT_MARKER))));
        assertDoesNotThrow(() -> ChangeSet.prepare(generated.plan()));
    }

    @Test void mavenTestUsesActualCustomRootAndTestCompilationClasspath() throws Exception {
        var context = context("custom-tests", true);
        Path customTestRoot = context.projectRoot().resolve("tests/java");
        var main = new JavaProjectConfiguration("module-main", List.of(context.snapshot().sourceRoots().getFirst()), List.of(), List.of(), List.of(), "25", "", "", null, "UTF-8", false);
        var test = new JavaProjectConfiguration("module-test", List.of(customTestRoot), FirstLaunchProjects.bundledJUnitClasspath(), List.of(), List.of("module-main"), "25", "", "", null, "UTF-8", false);
        var snapshot = new WorkspaceSnapshot(context.snapshot().root(), 2, context.snapshot().sources(), List.of(main.sourceRoots().getFirst(), customTestRoot), List.of(), List.of(main, test));
        var inferred = FirstLaunchProjects.testContext(snapshot, parameters(context.sourceFile(), "Adder", "example"),
                context.projectRoot(), customTestRoot, context.pomText());
        assertTrue(inferred.junitAvailable()); assertEquals(customTestRoot, inferred.testRoot());
        var generated = FirstLaunchProjects.testPlan(inferred, test(inferred, false));
        assertEquals(customTestRoot.resolve("example/AdderTest.java"), generated.file());
        assertTrue(generated.plan().edits().isEmpty());
        assertEquals(1, generated.plan().fileOperations().size());
    }

    @Test void generatedJUnitLifecycleTestReallyCompilesAndClasspathPersistsAsIntent() throws Exception {
        var project = FirstLaunchProjects.createProject(project("plain-test", false));
        Path calculator = project.root().resolve("src/main/java/example/Adder.java");
        Files.createDirectories(calculator.getParent());
        String original = "package example; public class Adder {}"; Files.writeString(calculator, original);
        var before = new WorkspaceSnapshot(project.root(), 2, List.of(new SourceSnapshot(calculator, original, 1, false)), project.sourceRoots(), List.of());
        var context = FirstLaunchProjects.testContext(before, parameters(calculator, "Adder", "example"), project.root(), project.sourceRoots().get(1), null);
        assertFalse(context.junitAvailable());
        var request = new FirstLaunchProjects.TestSpec(context.testRoot(), "example", "AdderTest", "test", true, true, true, true, true);
        var generated = FirstLaunchProjects.testPlan(context, request);
        assertEquals(2, generated.plan().fileOperations().size());
        assertFalse(Files.exists(generated.file()));
        materialize(generated.plan());
        String test = Files.readString(generated.file());
        assertEquals("fail(\"Not yet implemented\");", test.substring(generated.start(), generated.end()));
        assertTrue(test.contains("static void setUpBeforeClass()"));
        var detected = ProjectContext.detect(generated.file(), test);
        assertTrue(FirstLaunchProjects.hasJupiter(detected.classPath()));
        Path output = Files.createDirectory(temporary.resolve("compiled"));
        String cp = String.join(java.io.File.pathSeparator, detected.classPath().stream().map(Path::toString).toList());
        assertEquals(0, ToolProvider.getSystemJavaCompiler().run(null, null, null, "-classpath", cp, "-d", output.toString(), calculator.toString(), generated.file().toString()));
        assertEquals("runtime=bundled-jupiter-5.13.4\n", Files.readString(project.root().resolve(FirstLaunchProjects.JUNIT_MARKER)));
        var refreshed = new WorkspaceSnapshot(before.root(), 3, before.sources(), before.sourceRoots(), detected.classPath());
        assertTrue(FirstLaunchProjects.testContext(refreshed, parameters(calculator, "Adder", "example"), project.root(), context.testRoot(), null).junitAvailable());
    }

    @Test void missingJUnitRequiresAnExplicitSelection() throws Exception {
        var context = context("requires-consent", false);
        assertThrows(IllegalArgumentException.class, () -> FirstLaunchProjects.testPlan(context, test(context, false)));
        assertFalse(Files.exists(context.projectRoot().resolve(FirstLaunchProjects.JUNIT_MARKER)));
    }

    @Test void mavenJUnitPlanIncludesExactPomVersionAndLosslessDependencyEdit() throws Exception {
        var context = context("maven-test", true);
        var generated = FirstLaunchProjects.testPlan(context, test(context, true));
        assertEquals(1, generated.plan().fileOperations().size());
        Path pom = context.projectRoot().resolve("pom.xml");
        assertEquals(context.pomText(), generated.plan().before().source(pom).text());
        var edit = generated.plan().edits().getFirst(); assertEquals(pom, edit.file());
        String updated = TextEdit.apply(context.pomText(), edit.edits());
        assertTrue(updated.contains("<artifactId>junit-jupiter</artifactId>"));
        assertTrue(updated.contains("<version>5.13.4</version>"));
        assertTrue(updated.contains("<scope>test</scope>"));
        assertEquals(context.pomText(), Files.readString(pom), "Preparation must not save a POM");
        assertFalse(Files.exists(generated.file()));
    }

    @Test void refusesDuplicateJUnitDeclarationUntilMavenClasspathHasResolved() throws Exception {
        var context = context("declared", true);
        String declared = dev.turboj.mavenj.workspace.PomEdits.addDependency(context.pomText(), "org.junit.jupiter", "junit-jupiter", "5.13.4", "test").result();
        var error = assertThrows(IllegalStateException.class, () -> FirstLaunchProjects.testContext(context.snapshot(),
                parameters(context.sourceFile(), context.className(), context.packageName()), context.projectRoot(), context.testRoot(), declared));
        assertTrue(error.getMessage().contains("already declared"));
    }

    @Test void apiOnlyMavenProjectAddsMatchingEngineWithoutChangingApiVersion() throws Exception {
        var context = context("api-only", true);
        String apiVersion = "5.12.2";
        Path api = syntheticApi(apiVersion);
        var snapshot = new WorkspaceSnapshot(context.snapshot().root(), 2, context.snapshot().sources(), context.snapshot().sourceRoots(), List.of(api));
        String pom = dev.turboj.mavenj.workspace.PomEdits.addDependency(context.pomText(), "org.junit.jupiter", "junit-jupiter-api", apiVersion, "test").result();
        var configured = FirstLaunchProjects.testContext(snapshot, parameters(context.sourceFile(), context.className(), context.packageName()),
                context.projectRoot(), context.testRoot(), pom);
        assertFalse(configured.junitAvailable());
        assertEquals("junit-jupiter-engine", configured.junitDependencyArtifact()); assertEquals(apiVersion, configured.junitDependencyVersion());
        var generated = FirstLaunchProjects.testPlan(configured, test(configured, true));
        String updated = TextEdit.apply(pom, generated.plan().edits().getFirst().edits());
        assertTrue(updated.contains("<artifactId>junit-jupiter-api</artifactId>"));
        assertTrue(updated.contains("<artifactId>junit-jupiter-engine</artifactId>"));
        assertFalse(updated.contains("<artifactId>junit-jupiter</artifactId>"));
        assertFalse(updated.contains("5.13.4"), "Existing project versions must not be upgraded");
    }

    @Test void plainApiOnlyProjectCannotMixInDifferentBundledEngineVersion() throws Exception {
        var context = context("plain-api-only", false);
        Path api = syntheticApi("5.12.2");
        var snapshot = new WorkspaceSnapshot(context.snapshot().root(), 2, context.snapshot().sources(), context.snapshot().sourceRoots(), List.of(api));
        var failure = assertThrows(IllegalStateException.class, () -> FirstLaunchProjects.testContext(snapshot,
                parameters(context.sourceFile(), context.className(), context.packageName()), context.projectRoot(), context.testRoot(), null));
        assertTrue(failure.getMessage().contains("matching engine"));
        assertFalse(Files.exists(context.projectRoot().resolve(FirstLaunchProjects.JUNIT_MARKER)));
    }

    @Test void testTargetsCannotEscapeTheProjectOrChangeConfiguredRoot() throws Exception {
        var context = context("bounded", false);
        var request = new FirstLaunchProjects.TestSpec(temporary.resolve("outside"), "example", "AdderTest", "test", false, false, false, false, true);
        assertThrows(IllegalArgumentException.class, () -> FirstLaunchProjects.testPlan(context, request));
        var invalidPackage = new FirstLaunchProjects.TestSpec(context.testRoot(), "../bad", "AdderTest", "test", false, false, false, false, true);
        assertThrows(IllegalArgumentException.class, () -> FirstLaunchProjects.testPlan(context, invalidPackage));
    }

    private FirstLaunchProjects.ProjectSpec project(String name, boolean maven) {
        return new FirstLaunchProjects.ProjectSpec(temporary, name, temporary.resolve(name), maven ? FirstLaunchProjects.Kind.MAVEN : FirstLaunchProjects.Kind.JAVA,
                maven ? "example" : "", maven ? name : "", "25");
    }
    private WorkspaceSnapshot empty(FirstLaunchProjects.ProjectResult result) {
        return new WorkspaceSnapshot(result.root(), 1, List.of(), result.sourceRoots(), List.of());
    }
    private FirstLaunchProjects.TestContext context(String name, boolean maven) throws Exception {
        var result = FirstLaunchProjects.createProject(project(name, maven));
        Path source = result.root().resolve("src/main/java/example/Adder.java");
        Files.createDirectories(source.getParent()); Files.writeString(source, "package example; public class Adder {}\n");
        var snapshot = new WorkspaceSnapshot(result.root(), 1, List.of(new SourceSnapshot(source, Files.readString(source), 1, false)), result.sourceRoots(), List.of());
        return FirstLaunchProjects.testContext(snapshot, parameters(source, "Adder", "example"), result.root(), result.sourceRoots().get(1),
                maven ? Files.readString(result.root().resolve("pom.xml")) : null);
    }
    private static Map<String, String> parameters(Path file, String name, String pkg) {
        return Map.of("sourceFile", file.toString(), "className", name, "packageName", pkg, "qualifiedName", pkg + "." + name);
    }
    private static FirstLaunchProjects.TestSpec test(FirstLaunchProjects.TestContext context, boolean add) {
        return new FirstLaunchProjects.TestSpec(context.testRoot(), context.packageName(), context.className() + "Test", "test", false, false, false, false, add);
    }
    private static void materialize(Plan plan) throws Exception {
        for (var transition : ChangeSet.prepare(plan).transitions()) {
            Files.createDirectories(transition.path().getParent());
            Files.writeString(transition.path(), transition.after().text());
        }
    }
    private Path syntheticApi(String version) throws Exception {
        Path jar = Files.createTempFile(temporary, "synthetic-jupiter-api-", ".jar");
        var manifest = new java.util.jar.Manifest(); manifest.getMainAttributes().putValue("Manifest-Version", "1.0");
        manifest.getMainAttributes().putValue("Bundle-Version", version);
        try (var output = new java.util.jar.JarOutputStream(Files.newOutputStream(jar), manifest)) {
            output.putNextEntry(new java.util.jar.JarEntry("org/junit/jupiter/api/Test.class")); output.write(0); output.closeEntry();
        }
        return jar;
    }
}
