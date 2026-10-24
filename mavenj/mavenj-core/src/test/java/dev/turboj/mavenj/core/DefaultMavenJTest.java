package dev.turboj.mavenj.core;

import dev.turboj.mavenj.api.*;
import dev.turboj.mavenj.api.MavenModels.*;
import dev.turboj.mavenj.api.MavenModels.Module;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;
import java.io.*;
import static org.junit.jupiter.api.Assertions.*;

class DefaultMavenJTest {
    @TempDir
    Path temp;
    Path repository, settings;
    DefaultMavenJ engine;

    @BeforeEach
    void setup() throws Exception {
        repository = temp.resolve("repository");
        Files.createDirectories(repository);
        settings = write("settings.xml", "<settings/>");
        engine = new DefaultMavenJ();
    }

    @AfterEach
    void close() {
        engine.close();
    }

    Path write(String path, String content) throws IOException {
        Path file = temp.resolve(path);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
        return file;
    }

    String pom(String artifact, String body) {
        return "<project><modelVersion>4.0.0</modelVersion><groupId>test</groupId><artifactId>"
                + artifact + "</artifactId><version>1</version>" + body + "</project>";
    }

    String dep(String artifact, String version, String extra) {
        return "<dependency><groupId>test</groupId><artifactId>" + artifact
                + "</artifactId><version>" + version + "</version>" + extra + "</dependency>";
    }

    void artifact(String artifact, String version, String body) throws Exception {
        Path dir = repository.resolve("test/" + artifact + "/" + version);
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(artifact + "-" + version + ".pom"), pom(artifact, body)
                .replace("<version>1</version>", "<version>" + version + "</version>"));
        try (var out = new JarOutputStream(Files.newOutputStream(dir.resolve(artifact + "-"
                + version + ".jar")))) {
            out.putNextEntry(new JarEntry("META-INF/"));
            out.closeEntry();
        }
    }

    ImportRequest request(Path project) {
        return new ImportRequest(project, settings, null, repository, Set.of(), Set.of(), Map.of(),
                true, Set.of(), false, null, Map.of());
    }

    Module only(Workspace w) {
        assertTrue(w.resolved(), () -> w.diagnostics() + " " + w.modules().stream().flatMap(m -> m
                .diagnostics().stream()).toList());
        return w.modules().getFirst();
    }

    @Test
    void importsEffectiveParentPropertiesCustomRootsAndProfiles() throws Exception {
        Path parent = write("project/pom.xml", pom("parent",
                "<packaging>pom</packaging><properties><level>17</level><maven.compiler.release>${level}</maven.compiler.release></properties><modules><module>child</module></modules><dependencyManagement><dependencies>"
                + dep("managed", "2", "") + "</dependencies></dependencyManagement>"));
        artifact("managed", "2", "");
        write("project/child/pom.xml",
                "<project><modelVersion>4.0.0</modelVersion><parent><groupId>test</groupId><artifactId>parent</artifactId><version>1</version></parent><artifactId>child</artifactId><build><sourceDirectory>java</sourceDirectory><testSourceDirectory>spec</testSourceDirectory></build><dependencies><dependency><groupId>test</groupId><artifactId>managed</artifactId></dependency></dependencies><profiles><profile><id>modern</id><activation><property><name>modern</name></property></activation><properties><level>21</level></properties></profile></profiles></project>");
        var r = request(parent);
        r = new ImportRequest(r.project(), settings, null, repository, Set.of(), Set.of(), Map
                .of("modern", "true"), true, Set.of(), false, null, Map.of());
        var w = engine.open(r);
        assertEquals(2, w.modules().size());
        assertTrue(w.resolved(), () -> w.toString());
        Module child = w.module("test:child:1").orElseThrow();
        assertEquals("21", child.mainJava().release());
        assertEquals(parent.getParent().resolve("child/java"), child.sourceRoots().getFirst());
        assertEquals("test:parent:1", child.parentId());
        assertEquals(List.of("modern"), child.activeProfiles());
        assertEquals("2", child.dependencies().getFirst().coordinates().version());
        assertFalse(Files.exists(parent.getParent().resolve("target")));
        assertFalse(Files.exists(parent.getParent().resolve("child/target")));
    }

    @Test
    void bomMediationTransitiveExclusionsOptionalAndScopes() throws Exception {
        artifact("leaf", "1", "");
        artifact("leaf", "2", "");
        artifact("optional", "1", "");
        artifact("excluded", "1", "");
        artifact("lib", "1", "<dependencies>" + dep("leaf", "1", "") + dep("optional", "1",
                "<optional>true</optional>") + dep("excluded", "1", "") + "</dependencies>");
        artifact("provided", "1", "");
        artifact("runtime", "1", "");
        artifact("testonly", "1", "");
        artifact("bom", "1", "<packaging>pom</packaging><dependencyManagement><dependencies>"
                + dep("leaf", "2", "") + "</dependencies></dependencyManagement>");
        Path pom = write("app/pom.xml", pom("app",
                "<properties><maven.compiler.release>17</maven.compiler.release></properties><dependencyManagement><dependencies>"
                + dep("bom", "1", "<type>pom</type><scope>import</scope>")
                + "</dependencies></dependencyManagement><dependencies>" + dep("lib", "1",
                "<exclusions><exclusion><groupId>test</groupId><artifactId>excluded</artifactId></exclusion></exclusions>")
                + dep("provided", "1", "<scope>provided</scope>") + dep("runtime", "1",
                "<scope>runtime</scope>") + dep("testonly", "1", "<scope>test</scope>")
                + "</dependencies>"));
        Module m = only(engine.open(request(pom)));
        var artifacts = m.dependencies().stream().map(d -> d.coordinates().artifactId() + ":" + d
                .coordinates().version()).toList();
        assertTrue(artifacts.contains("leaf:2"), artifacts.toString());
        assertFalse(artifacts.contains("leaf:1"));
        assertFalse(artifacts.contains("optional:1"));
        assertFalse(artifacts.contains("excluded:1"));
        var compile = m.classpaths().forScope(ClasspathScope.MAIN_COMPILE);
        var runtime = m.classpaths().forScope(ClasspathScope.MAIN_RUNTIME);
        var tests = m.classpaths().forScope(ClasspathScope.TEST_RUNTIME);
        assertTrue(compile.stream().anyMatch(p -> p.toString().contains("provided")));
        assertFalse(runtime.stream().anyMatch(p -> p.toString().contains("provided")));
        assertFalse(compile.stream().anyMatch(p -> p.toString().contains("runtime")));
        assertTrue(runtime.stream().anyMatch(p -> p.toString().contains("runtime")));
        assertTrue(tests.stream().anyMatch(p -> p.toString().contains("testonly")));
        assertFalse(compile.stream().anyMatch(p -> p.toString().contains("testonly")));
    }

    @Test
    void reactorUsesCurrentOutputWithoutInstallAndOrdersDependencies() throws Exception {
        Path root = write("reactor/pom.xml", pom("parent",
                "<packaging>pom</packaging><modules><module>service</module><module>common</module><module>api</module></modules><properties><maven.compiler.release>21</maven.compiler.release></properties>"));
        String parent = "<parent><groupId>test</groupId><artifactId>parent</artifactId><version>1</version></parent>";
        write("reactor/common/pom.xml", "<project><modelVersion>4.0.0</modelVersion>" + parent
                + "<artifactId>common</artifactId></project>");
        write("reactor/api/pom.xml", "<project><modelVersion>4.0.0</modelVersion>" + parent
                + "<artifactId>api</artifactId><dependencies>" + dep("common", "1", "")
                + "</dependencies></project>");
        write("reactor/service/pom.xml", "<project><modelVersion>4.0.0</modelVersion>" + parent
                + "<artifactId>service</artifactId><dependencies>" + dep("api", "1", "")
                + "</dependencies></project>");
        Workspace w = engine.open(request(root));
        assertTrue(w.resolved(), () -> w.toString());
        assertEquals(List.of("test:parent:1", "test:common:1", "test:api:1", "test:service:1"), w
                .buildOrder());
        Module service = w.module("test:service:1").orElseThrow();
        assertEquals(Set.of("test:api:1", "test:common:1"), service.reactorDependencies().stream()
                .map(ReactorDependency::moduleId).collect(java.util.stream.Collectors.toSet()));
        assertTrue(service.classpaths().forScope(ClasspathScope.MAIN_COMPILE).contains(root
                .getParent().resolve("common/target/classes")));
        assertFalse(Files.exists(repository.resolve("test/common")));
    }

    @Test
    void cachedGenerationIgnoresOrdinaryJavaChangesButRejectsPomChanges() throws Exception {
        Path pom = write("cache/pom.xml", pom("cache",
                "<properties><maven.compiler.release>17</maven.compiler.release></properties>"));
        Workspace first = engine.open(request(pom));
        Workspace second = engine.open(request(pom));
        assertTrue(second.cacheHit());
        assertEquals(first.generation(), second.generation());
        write("cache/src/main/java/Example.java", "class Example {}");
        assertTrue(engine.open(request(pom)).cacheHit());
        Files.writeString(pom, Files.readString(pom).replace("17", "21"));
        Workspace changed = engine.open(request(pom));
        assertFalse(changed.cacheHit());
        assertTrue(changed.generation() > first.generation());
        assertEquals("21", changed.modules().getFirst().mainJava().release());
    }

    @Test
    void unsavedPomOverlayDoesNotWriteDisk() throws Exception {
        Path pom = write("overlay/pom.xml", pom("overlay",
                "<properties><maven.compiler.release>17</maven.compiler.release></properties>"));
        String content = Files.readString(pom);
        var r = request(pom);
        r = new ImportRequest(r.project(), settings, null, repository, Set.of(), Set.of(), Map.of(),
                true, Set.of(), false, null, Map.of(pom, content.replace("17", "21")));
        assertEquals("21", only(engine.open(r)).mainJava().release());
        assertEquals(content, Files.readString(pom));
    }

    @Test
    void missingDependencyIsStructuredAndMaven4ModelIsNotPretendedCompatible() throws Exception {
        Path pom = write("missing/pom.xml", pom("missing", "<dependencies>" + dep("absent", "999",
                "") + "</dependencies>"));
        Workspace missing = engine.open(request(pom));
        assertFalse(missing.resolved());
        assertTrue(missing.modules().getFirst().diagnostics().stream().anyMatch(d -> d.code()
                .equals("dependency.missing") || d.code().equals("dependency.collect")));
        Path four = write("four/pom.xml", pom("four", "").replace("4.0.0", "4.1.0"));
        assertFalse(engine.open(request(four)).resolved());
    }

    @Test
    void settingsProfilesLocalRepositoryAndMavenConfigAreApplied() throws Exception {
        Path alt = temp.resolve("alternate");
        Files.createDirectories(alt);
        Files.writeString(settings, "<settings><localRepository>" + alt
                + "</localRepository><profiles><profile><id>settings-profile</id><properties><maven.compiler.release>21</maven.compiler.release></properties></profile></profiles><activeProfiles><activeProfile>settings-profile</activeProfile></activeProfiles></settings>");
        Path pom = write("configured/pom.xml", pom("configured", ""));
        write("configured/.mvn/maven.config",
                "-Dmessage=\"hello world\"\n-Dmaven.compiler.release=17\n");
        var r = request(pom);
        r = new ImportRequest(pom, settings, null, null, Set.of(), Set.of(), Map.of(), true, Set
                .of(), false, null, Map.of());
        var w = engine.open(r);
        assertEquals(alt, w.localRepository());
        assertEquals("17", only(w).mainJava().release());
        assertTrue(w.activeProfiles().contains("settings-profile"));
    }

    @Test
    void processorMetadataResolvesWithoutExecutingCodeAndPluginParityIsExplicit() throws Exception {
        artifact("processor", "1", "");
        Path pom = write("processor/pom.xml", pom("processor-app",
                "<properties><maven.compiler.release>17</maven.compiler.release></properties><build><plugins><plugin><artifactId>maven-compiler-plugin</artifactId><version>3.14.1</version><configuration><annotationProcessorPaths><path><groupId>test</groupId><artifactId>processor</artifactId><version>1</version></path></annotationProcessorPaths><compilerArgs><arg>-Aanswer=42</arg></compilerArgs></configuration></plugin><plugin><groupId>org.codehaus.mojo</groupId><artifactId>exec-maven-plugin</artifactId><executions><execution><phase>compile</phase><goals><goal>exec</goal></goals><configuration><executable>never-execute-this</executable></configuration></execution></executions></plugin></plugins></build>"));
        Module m = only(engine.open(request(pom)));
        assertTrue(m.mainJava().processors().requested());
        assertEquals(1, m.mainJava().processors().path().size());
        assertEquals("42", m.mainJava().processors().options().get("answer"));
        assertEquals(FastPath.PARTIAL, m.fastPath());
        assertTrue(m.diagnostics().stream().anyMatch(d -> d.code().equals("build.plugin")));
        assertFalse(Files.exists(pom.getParent().resolve("target")));
    }

    @Test
    void cancellationAndImmutableSnapshots() throws Exception {
        Path pom = write("cancel/pom.xml", pom("cancel", ""));
        assertThrows(java.util.concurrent.CancellationException.class, () -> engine
                .open(request(pom), () -> true));
        var w = engine.open(request(pom));
        assertThrows(UnsupportedOperationException.class, () -> w.modules().clear());
        assertThrows(UnsupportedOperationException.class, () -> w.modules().getFirst().sourceRoots()
                .clear());
    }

    @Test
    void rejectsExternalEntitiesAndReportsMalformedModel() throws Exception {
        Path pom = write("xxe/pom.xml",
                "<!DOCTYPE project [<!ENTITY x SYSTEM 'file:///etc/passwd'>]><project><modelVersion>4.0.0</modelVersion><groupId>test</groupId><artifactId>&x;</artifactId><version>1</version></project>");
        assertFalse(engine.open(request(pom)).resolved());
        Path invalid = write("invalid/pom.xml", "<project><unclosed>");
        assertFalse(engine.open(request(invalid)).resolved());
    }

    @Test
    void onlyChangedEffectiveModelsAndDependentResolutionGraphsAreRebuilt() throws Exception {
        Path root = write("incremental/pom.xml", pom("parent",
                "<packaging>pom</packaging><properties><maven.compiler.release>17</maven.compiler.release></properties><modules><module>common</module><module>service</module><module>unrelated</module></modules>"));
        String parent = "<parent><groupId>test</groupId><artifactId>parent</artifactId><version>1</version></parent>";
        Path common = write("incremental/common/pom.xml",
                "<project><modelVersion>4.0.0</modelVersion>" + parent
                + "<artifactId>common</artifactId><description>before</description></project>");
        write("incremental/service/pom.xml", "<project><modelVersion>4.0.0</modelVersion>" + parent
                + "<artifactId>service</artifactId><dependencies>" + dep("common", "1", "")
                + "</dependencies></project>");
        write("incremental/unrelated/pom.xml", "<project><modelVersion>4.0.0</modelVersion>"
                + parent + "<artifactId>unrelated</artifactId></project>");
        Workspace first = engine.open(request(root));
        assertTrue(first.resolved());
        var before = engine.cacheStatistics();
        Files.writeString(common, Files.readString(common).replace("before", "after"));
        Workspace changed = engine.open(request(root));
        assertTrue(changed.resolved());
        var after = engine.cacheStatistics();
        assertEquals(1, after.modelBuilds() - before.modelBuilds());
        assertEquals(3, after.modelHits() - before.modelHits());
        assertEquals(2, after.dependencyResolutions() - before.dependencyResolutions());
        assertEquals(2, after.dependencyHits() - before.dependencyHits());
        assertSame(first.module("test:unrelated:1").orElseThrow(), changed
                .module("test:unrelated:1").orElseThrow());
        Files.writeString(root, Files.readString(root).replace(">17<", ">21<"));
        Workspace inherited = engine.open(request(root));
        assertTrue(inherited.modules().stream().allMatch(m -> m.mainJava().release().equals("21")));
        assertEquals(4, engine.cacheStatistics().modelBuilds() - after.modelBuilds());
    }

    @Test
    void compilerPluginDefaultsRespectTheirActualVersions() throws Exception {
        for (var entry : Map.of("3.1", "1.5", "3.8.1", "1.6", "3.10.1", "1.7", "3.14.1", "1.8")
                .entrySet()) {
            Path pom = write("defaults" + entry.getKey() + "/pom.xml", pom("defaults",
                    "<build><plugins><plugin><artifactId>maven-compiler-plugin</artifactId><version>"
                    + entry.getKey() + "</version></plugin></plugins></build>"));
            var model = only(engine.open(request(pom)));
            assertEquals(entry.getValue(), model.mainJava().source());
            assertEquals(entry.getValue(), model.mainJava().target());
        }
        Path pom = write("implicit/pom.xml", pom("implicit", ""));
        assertEquals("1.8", only(engine.open(request(pom))).mainJava().source());
    }

    @Test
    void snapshotAlwaysPolicyExpiresButOfflineDoesNotPerformRefresh() throws Exception {
        Path pom = write("snapshot/pom.xml", pom("snapshot",
                "<repositories><repository><id>snapshots</id><url>https://repository.invalid/maven</url><snapshots><enabled>true</enabled><updatePolicy>always</updatePolicy></snapshots></repository></repositories>")
                .replace("<version>1</version>", "<version>1-SNAPSHOT</version>"));
        var online = new ImportRequest(pom, settings, null, repository, Set.of(), Set.of(), Map
                .of(), false, Set.of(), false, null, Map.of());
        assertFalse(engine.open(online).cacheHit());
        assertFalse(engine.open(online).cacheHit());
        assertEquals(2, engine.cacheStatistics().modelBuilds());
        var offline = request(pom);
        engine.open(offline);
        assertTrue(engine.open(offline).cacheHit());
    }

    @Test
    void javaLevelsAndModulePathsAreSeparateFromClasspath() throws Exception {
        artifact("library", "1", "");
        for (String level : List.of("17", "21", "25")) {
            Path pom = write("java" + level + "/pom.xml", pom("java" + level,
                    "<properties><maven.compiler.release>" + level
                    + "</maven.compiler.release></properties><dependencies>" + dep("library", "1",
                    "") + "</dependencies>"));
            write("java" + level + "/src/main/java/module-info.java",
                    "module app { requires library; }");
            var m = only(engine.open(request(pom)));
            assertEquals(level, m.mainJava().release());
            assertTrue(m.classpaths().forScope(ClasspathScope.MAIN_COMPILE).isEmpty());
            assertEquals(1, m.classpaths().modulesForScope(ClasspathScope.MAIN_COMPILE).size());
            assertFalse(m.classpaths().forScope(ClasspathScope.TEST_COMPILE).isEmpty());
        }
    }

    @Test
    void fileActivatedProfilesInvalidateEffectiveModelCache() throws Exception {
        Path pom = write("activation/pom.xml", pom("activation",
                "<properties><maven.compiler.release>17</maven.compiler.release></properties><profiles><profile><id>file</id><activation><file><exists>${basedir}/enable-modern</exists></file></activation><properties><maven.compiler.release>21</maven.compiler.release></properties></profile></profiles>"));
        assertEquals("17", only(engine.open(request(pom))).mainJava().release());
        write("activation/enable-modern", "");
        var changed = engine.open(request(pom));
        assertFalse(changed.cacheHit());
        assertEquals("21", only(changed).mainJava().release());
    }

    @Test
    void rejectsUtf16DtdInSettingsAndPom() throws Exception {
        Files.writeString(settings,
                "<?xml version='1.0' encoding='UTF-16'?><!DOCTYPE settings [<!ENTITY secret SYSTEM 'file:///etc/passwd'>]><settings><localRepository>&secret;</localRepository></settings>",
                java.nio.charset.StandardCharsets.UTF_16);
        Path pom = write("xmlsafe/pom.xml", pom("safe", ""));
        var invalid = engine.open(request(pom));
        assertTrue(invalid.diagnostics().stream().anyMatch(d -> d.code().equals("settings.model")));
        assertFalse(invalid.toString().contains("root:"));
        Files.writeString(settings, "<settings/>");
        Files.writeString(pom,
                "<?xml version='1.0' encoding='UTF-16'?><!DOCTYPE project [<!ENTITY secret SYSTEM 'file:///etc/passwd'>]>"
                + pom("safe", ""), java.nio.charset.StandardCharsets.UTF_16);
        assertFalse(engine.open(request(pom)).resolved());
    }

    @Test
    void systemScopeAndTestJarClassifierRetainTheirCorrectPaths() throws Exception {
        artifact("classified", "1", "");
        Path jar = repository.resolve("test/classified/1/classified-1.jar"), testJar = jar
                .resolveSibling("classified-1-tests.jar");
        Files.copy(jar, testJar);
        Path local = temp.resolve("system.jar");
        Files.copy(jar, local);
        Path pom = write("types/pom.xml", pom("types",
                "<properties><maven.compiler.release>17</maven.compiler.release></properties><dependencies>"
                + dep("classified", "1", "<type>test-jar</type><scope>test</scope>") + dep("system",
                "1", "<scope>system</scope><systemPath>" + local + "</systemPath>")
                + "</dependencies>"));
        var module = only(engine.open(request(pom)));
        assertTrue(module.classpaths().forScope(ClasspathScope.MAIN_COMPILE).contains(local));
        assertFalse(module.classpaths().forScope(ClasspathScope.MAIN_RUNTIME).contains(local));
        assertTrue(module.classpaths().forScope(ClasspathScope.TEST_RUNTIME).contains(testJar));
        assertFalse(module.classpaths().forScope(ClasspathScope.MAIN_COMPILE).contains(testJar));
    }

    @Test
    void restrictedImportDeniesExternalParentModulesAndSymlinksBeforeReadingModels() throws Exception {
        Path external = write("private/pom.xml", pom("outside",
                "<packaging>pom</packaging><properties><privateValue>CONFIDENTIAL_VALUE</privateValue></properties>"));
        Path root = write("restricted/pom.xml",
                "<project><modelVersion>4.0.0</modelVersion><parent><groupId>test</groupId><artifactId>outside</artifactId><version>1</version><relativePath>../private/pom.xml</relativePath></parent><artifactId>child</artifactId></project>");

        try (var restricted = new DefaultMavenJ(root.getParent())) {
            var parent = restricted.open(request(root));
            assertFalse(parent.resolved());
            assertFalse(parent.toString().contains("CONFIDENTIAL_VALUE"));
            assertFalse(parent.observedFiles().contains(external));
            Files.writeString(root, pom("restricted",
                    "<packaging>pom</packaging><modules><module>../private</module></modules>"));
            var modules = restricted.open(request(root));
            assertFalse(modules.resolved());
            assertFalse(modules.toString().contains("CONFIDENTIAL_VALUE"));
            assertFalse(modules.observedFiles().contains(external));
            Files.createSymbolicLink(root.getParent().resolve("linked.xml"), external);
            Files.writeString(root, pom("restricted",
                    "<packaging>pom</packaging><modules><module>linked.xml</module></modules>"));
            var symlink = restricted.open(request(root));
            assertFalse(symlink.resolved());
            assertFalse(symlink.toString().contains("CONFIDENTIAL_VALUE"));
        }
    }

    @Test
    void restrictedImportStillResolvesAuthorizedLocalRepositoryParents() throws Exception {
        artifact("repository-parent", "1",
                "<packaging>pom</packaging><properties><maven.compiler.release>21</maven.compiler.release></properties>");
        Path root = write("safe/pom.xml",
                "<project><modelVersion>4.0.0</modelVersion><parent><groupId>test</groupId><artifactId>repository-parent</artifactId><version>1</version><relativePath/></parent><artifactId>safe</artifactId></project>");

        try (var restricted = new DefaultMavenJ(root.getParent())) {
            assertEquals("21", only(restricted.open(request(root))).mainJava().release());
        }
    }

    @Test
    void configuredToolchainsAreExposedAndPreservedByCachedSnapshots() throws Exception {
        Path root = write("toolchains/pom.xml", pom("toolchains", ""));
        write("toolchains/.mvn/maven.config",
                "-t\nconfig/user-toolchains.xml\n-gt\nconfig/global-toolchains.xml\n");
        var first = engine.open(request(root));
        assertEquals(root.getParent().resolve("config/user-toolchains.xml"), first
                .userToolchains());
        assertEquals(root.getParent().resolve("config/global-toolchains.xml"), first
                .globalToolchains());
        assertTrue(first.observedFiles().contains(first.userToolchains()));
        var cached = engine.open(request(root));
        assertTrue(cached.cacheHit());
        assertEquals(first.userToolchains(), cached.userToolchains());
        assertEquals(first.globalToolchains(), cached.withModules(cached.modules())
                .globalToolchains());
    }

    @Test
    void publicModelsRedactSensitiveProcessPropertiesAndRestrictedInspectionRejectsReferences() throws Exception {
        String key = "mavenj.fixture.secret", previous = System.getProperty(key);
        System.setProperty(key, "SENSITIVE_PROCESS_VALUE");
        try {
            Path pom = write("process-secret/pom.xml", pom("secret",
                    "<description>${mavenj.fixture.secret}</description><properties><innocent>${mavenj.fixture.secret}</innocent></properties>"));
            Workspace ordinary = engine.open(request(pom));
            assertFalse(ordinary.toString().contains("SENSITIVE_PROCESS_VALUE"));
            try (var restricted = new DefaultMavenJ(pom.getParent())) {
                Workspace denied = restricted.open(request(pom));
                assertFalse(denied.resolved());
                assertFalse(denied.toString().contains("SENSITIVE_PROCESS_VALUE"));
            }
        } finally {
            if (previous == null)
                System.clearProperty(key);
            else
                System.setProperty(key, previous);
        }
    }

    @Test
    void reactorClassifierUsesItsRealArtifactAndNeverSubstitutesMainClasses() throws Exception {
        Path root = write("classifiers/pom.xml", pom("parent",
                "<packaging>pom</packaging><modules><module>lib</module><module>app</module></modules>"));
        write("classifiers/lib/pom.xml", pom("lib", ""));
        write("classifiers/app/pom.xml", pom("app", "<dependencies>"
                + dep("lib", "1", "<classifier>special</classifier>") + "</dependencies>"));
        var missing = engine.open(request(root));
        assertFalse(missing.resolved());
        var missingApp = missing.module("test:app:1").orElseThrow();
        assertTrue(missingApp.classpaths().forScope(ClasspathScope.MAIN_COMPILE).isEmpty());
        assertTrue(missingApp.reactorDependencies().isEmpty());
        assertEquals("", missingApp.dependencies().getFirst().reactorModule());
        assertNull(missingApp.dependencies().getFirst().file());

        artifact("lib", "1", "");
        Path classified = repository.resolve("test/lib/1/lib-1-special.jar");
        Files.copy(repository.resolve("test/lib/1/lib-1.jar"), classified);
        var resolved = engine.open(request(root).refreshing());
        assertTrue(resolved.resolved(), resolved.toString());
        var app = resolved.module("test:app:1").orElseThrow();
        assertEquals(List.of(classified), app.classpaths().forScope(ClasspathScope.MAIN_COMPILE));
        assertTrue(app.reactorDependencies().isEmpty());
        assertEquals("special", app.dependencies().getFirst().coordinates().classifier());
    }

    @Test
    void boundedParentAndBomRangesResolveHighestRemoteVersionAndInvalidateMetadata() throws Exception {
        artifact("parent", "1", "<packaging>pom</packaging>");
        artifact("parent", "2", "<packaging>pom</packaging><properties><inherited>new</inherited></properties>");
        artifact("leaf", "1", "");
        artifact("leaf", "2", "");
        artifact("bom", "1", "<packaging>pom</packaging><dependencyManagement><dependencies>"
                + dep("leaf", "1", "") + "</dependencies></dependencyManagement>");
        artifact("bom", "2", "<packaging>pom</packaging><dependencyManagement><dependencies>"
                + dep("leaf", "2", "") + "</dependencies></dependencyManagement>");
        Path remote = temp.resolve("remote-repository");
        Files.move(repository, remote);
        Files.createDirectories(repository);
        for (String name : List.of("parent", "bom"))
            Files.writeString(remote.resolve("test/" + name + "/maven-metadata.xml"),
                    "<metadata><groupId>test</groupId><artifactId>" + name
                    + "</artifactId><versioning><versions><version>1</version><version>2</version></versions><lastUpdated>20261024000000</lastUpdated></versioning></metadata>");
        String url = remote.toUri().toString();
        Path project = write("range-model/pom.xml",
                "<project><modelVersion>4.0.0</modelVersion><parent><groupId>test</groupId><artifactId>parent</artifactId><version>[1,3)</version><relativePath/></parent><artifactId>child</artifactId><version>1</version>"
                + "<repositories><repository><id>fixture</id><url>" + url
                + "</url><releases><updatePolicy>always</updatePolicy></releases></repository></repositories>"
                + "<dependencyManagement><dependencies>" + dep("bom", "[1,3)",
                "<type>pom</type><scope>import</scope>")
                + "</dependencies></dependencyManagement><dependencies><dependency><groupId>test</groupId><artifactId>leaf</artifactId></dependency></dependencies></project>");
        var request = new ImportRequest(project, settings, null, repository, Set.of(), Set.of(),
                Map.of(), false, Set.of(url), false, null, Map.of());
        Workspace first = engine.open(request);
        Module module = only(first);
        assertEquals("test:parent:2", module.parentId());
        assertEquals("new", module.properties().get("inherited"));
        assertEquals("2", module.dependencies().getFirst().coordinates().version());
        assertTrue(module.effectivePom().contains("<inherited>new</inherited>"));
        assertTrue(first.observedFiles().stream().anyMatch(p -> p.getFileName().toString()
                .startsWith("maven-metadata")));
        assertFalse(engine.open(request).cacheHit(), "Resolved model ranges retain repository update policy");

        var empty = request(project);
        String bounded = Files.readString(project);
        Files.writeString(project, bounded.replace("[1,3)", "[5,6)"));
        assertFalse(engine.open(empty).resolved(), "An empty range must fail instead of inventing a version");
        Files.writeString(project, bounded.replace("[1,3)", "[1,)"));
        assertFalse(engine.open(empty).resolved(), "Maven rejects model version ranges without an upper bound");
    }

    @Test
    void customRepositoryDefaultsEnableSnapshotsWhileCentralStaysDisabled() throws Exception {
        Path root = write("repository-defaults/pom.xml", pom("app",
                "<repositories><repository><id>custom</id><url>https://example.invalid/maven</url></repository></repositories>"));
        var repositories = only(engine.open(request(root))).repositories();
        assertTrue(repositories.stream().filter(r -> r.id().equals("custom")).findFirst()
                .orElseThrow().snapshots());
        assertFalse(repositories.stream().filter(r -> r.id().equals("central")).findFirst()
                .orElseThrow().snapshots());
    }

    @Test
    void importedSnapshotBomHonorsAlwaysPolicyAfterItsVersionDisappearsFromEffectivePom() throws Exception {
        artifact("bom", "1-SNAPSHOT", "<packaging>pom</packaging>");
        Path project = write("snapshot-bom/pom.xml", pom("app",
                "<repositories><repository><id>snapshots</id><url>https://repository.invalid/maven</url><snapshots><enabled>true</enabled><updatePolicy>always</updatePolicy></snapshots></repository></repositories>"
                + "<dependencyManagement><dependencies>" + dep("bom", "1-SNAPSHOT",
                "<type>pom</type><scope>import</scope>") + "</dependencies></dependencyManagement>"));
        var online = new ImportRequest(project, settings, null, repository, Set.of(), Set.of(),
                Map.of(), false, Set.of(), false, null, Map.of());
        Workspace first = engine.open(online);
        assertTrue(first.resolved(), first.toString());
        assertFalse(first.modules().getFirst().effectivePom().contains("1-SNAPSHOT"));
        long before = engine.cacheStatistics().modelBuilds();
        Workspace refreshed = engine.open(online);
        assertTrue(refreshed.resolved(), refreshed.toString());
        assertFalse(refreshed.cacheHit());
        assertTrue(engine.cacheStatistics().modelBuilds() > before);
        assertTrue(refreshed.observedFiles().contains(repository.resolve("test/bom/1-SNAPSHOT")));

        Workspace offline = engine.open(request(project));
        assertTrue(offline.resolved(), offline.toString());
        assertTrue(engine.open(request(project)).cacheHit(), "Offline import does not perform remote update checks");
    }
}
