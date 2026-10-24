package dev.turboj.mavenj.workspace;

import dev.turboj.mavenj.api.MavenModels.*;
import dev.turboj.mavenj.api.MavenModels.Module;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ToolchainsTest {
    @TempDir
    Path root;

    private Workspace workspace(JavaConfiguration java) {
        return workspace(java, "jar");
    }

    private Workspace workspace(JavaConfiguration java, String packaging) {
        Path pom = root.resolve("pom.xml");
        Module module = new Module("app", new Coordinates("sample", "app", "1", "jar", ""), root,
                pom, packaging, "", List.of(), List.of(root.resolve("src/main/java")), List.of(),
                List.of(), List.of(), List.of(), List.of(), root.resolve("target/classes"), root
                .resolve("target/test-classes"), java, java, List.of(), List.of(),
                new Classpaths(Map.of(), Map.of(), true), List.of(), List.of(), Map.of(), "",
                FastPath.SUPPORTED, List.of());
        return new Workspace(root, pom, 1, List.of(module), List.of("app"), List.of(pom), "hash",
                root.resolve("repo"), List.of(), true, List.of(), 0, false);
    }

    private JavaConfiguration config(String release, Map<String, String> requirements) {
        return new JavaConfiguration(release, "", "", "UTF-8", null, requirements, List.of(),
                ProcessorConfiguration.none(root.resolve("generated")), false);
    }

    @Test
    void aggregatorWithoutLanguageDoesNotRequireAJdk() {
        var selected = Toolchains.select(workspace(config("", Map.of()), "pom"), root
                .resolve("none.xml"), null, List.of());
        assertTrue(selected.workspace().resolved());
        assertTrue(selected.diagnostics().isEmpty());
    }

    @Test
    void sourceTargetWithoutToolchainUsesInstalledJdkWithExplicitApiWarning() {
        var java = new JavaConfiguration("", "1.8", "1.8", "UTF-8", null, Map.of(), List.of(),
                ProcessorConfiguration.none(root.resolve("generated")), false);
        var selected = Toolchains.select(workspace(java), root.resolve("none.xml"), null, List
                .of());
        assertTrue(selected.workspace().resolved());
        assertNotNull(selected.workspace().modules().getFirst().mainJava().javaHome());
        assertEquals("", selected.workspace().modules().getFirst().mainJava().release());
        assertEquals("1.8", selected.workspace().modules().getFirst().mainJava().source());
        assertTrue(selected.diagnostics().stream().anyMatch(d -> d.code()
                .equals("JDK_SOURCE_TARGET_FALLBACK")));
    }

    private Path jdk(String version) throws Exception {
        Path home = Files.createDirectories(root.resolve("jdk-" + version));
        Files.createDirectories(home.resolve("bin"));
        Files.writeString(home.resolve("bin/javac"), "metadata-only test");
        Files.writeString(home.resolve("release"), "JAVA_VERSION=\"" + version
                + "\"\nIMPLEMENTOR=\"Example Vendor\"\n");
        return home;
    }

    @Test
    void officialToolchainReaderAndMatcherSelectRequestedJdkRatherThanEditorRuntime() throws Exception {
        Path jdk = jdk("21.0.11"), config = root.resolve("toolchains.xml");
        Files.writeString(config,
                "<toolchains><toolchain><type>jdk</type><provides><version>21</version><vendor>example</vendor></provides><configuration><jdkHome>"
                + jdk + "</jdkHome></configuration></toolchain></toolchains>");
        var selected = Toolchains.select(workspace(config("17", Map.of("version", "[21,22)",
                "vendor", "example"))), config, null, List.of());
        assertEquals(jdk.toRealPath(), selected.workspace().modules().getFirst().mainJava()
                .javaHome());
        assertEquals("17", selected.workspace().modules().getFirst().mainJava().release());
        assertTrue(selected.diagnostics().isEmpty());
        assertTrue(selected.workspace().observedFiles().contains(config));
    }

    @Test
    void missingRequiredToolchainNeverFallsBackSilently() {
        var selected = Toolchains.select(workspace(config("21", Map.of("version", "[21,22)",
                "vendor", "missing"))), root.resolve("absent.xml"), null, List.of());
        assertNull(selected.workspace().modules().getFirst().mainJava().javaHome());
        assertTrue(selected.diagnostics().stream().anyMatch(d -> d.code()
                .equals("TOOLCHAIN_MISSING")));
        assertFalse(selected.workspace().resolved());
    }

    @Test
    void installedExactLanguageVersionPreferredAndUnknownLevelDiagnosed() throws Exception {
        var candidate = Toolchains.inspect(jdk("21.0.11")).orElseThrow();
        var selected = Toolchains.select(workspace(config("21", Map.of())), root
                .resolve("none.xml"), null, List.of(candidate));
        assertEquals(candidate.home(), selected.workspace().modules().getFirst().mainJava()
                .javaHome());
        var unknown = Toolchains.select(workspace(config("${not.resolved}", Map.of())), root
                .resolve("none.xml"), null, List.of(candidate));
        assertTrue(unknown.diagnostics().stream().anyMatch(d -> d.code()
                .equals("JAVA_LEVEL_UNKNOWN")));
    }

    @Test
    void discoversCurrentInstalledJdkWithoutRunningIt() {
        var current = Toolchains.inspect(Path.of(System.getProperty("java.home"))).orElseThrow();
        assertEquals(Runtime.version().feature(), current.feature());
        assertTrue(Toolchains.discover().stream().anyMatch(j -> j.home().equals(current.home())));
    }
}
