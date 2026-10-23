package com.turboj.intelligence;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class ProjectContextTest {
    @TempDir Path directory;

    @Test
    void packageRootWorksWithoutABuildFileAndIgnoresComments() throws Exception {
        Path pkg = Files.createDirectories(directory.resolve("com/example"));
        var config = ProjectContext.detect(pkg.resolve("Main.java"),
                "// package misleading.name;\npackage com.example; class Main {}");
        assertEquals(directory, config.projectRoot());
        assertEquals(directory, config.sourceRoots().getFirst());
        assertTrue(config.classPath().isEmpty());
    }

    @Test
    void usesResolvedLocalClasspathAndSourcesWithoutGuessingDependencies() throws Exception {
        Files.writeString(directory.resolve("pom.xml"), "<project/>");
        Path pkg = Files.createDirectories(directory.resolve("src/main/java/sample"));
        Path tests = Files.createDirectories(directory.resolve("src/test/java"));
        Path output = Files.createDirectories(directory.resolve("target/classes"));
        Path lib = Files.createDirectories(directory.resolve("lib"));
        Path jar = Files.write(lib.resolve("helper-1.jar"), new byte[0]);
        Path sources = Files.write(lib.resolve("helper-1-sources.jar"), new byte[0]);
        Files.writeString(directory.resolve("target/turboj-classpath.txt"), jar.toString());
        var config = ProjectContext.detect(pkg.resolve("Main.java"), "package sample; class Main {}");
        assertEquals(directory, config.projectRoot());
        assertTrue(config.sourceRoots().contains(tests));
        assertTrue(config.classPath().contains(jar));
        assertTrue(config.classPath().contains(output));
        assertTrue(config.sourceArchives().contains(sources));
        assertTrue(config.warnings().isEmpty());
    }

    @Test
    void explicitPathsAndConfigurationChangesHaveNewRevisions() throws Exception {
        Path configDir = Files.createDirectories(directory.resolve(".turboj"));
        Path extraRoot = Files.createDirectories(directory.resolve("extra"));
        Path first = Files.write(directory.resolve("one.jar"), new byte[0]);
        Path second = Files.write(directory.resolve("two.jar"), new byte[0]);
        Files.writeString(configDir.resolve("source-roots.txt"), "# local sources\nextra\n");
        Path classpath = configDir.resolve("classpath.txt");
        Files.writeString(classpath, "one.jar");
        var before = ProjectContext.detect(directory.resolve("Main.java"), "class Main {}");
        assertEquals(extraRoot, before.sourceRoots().getFirst());
        assertEquals(first, before.classPath().getFirst());
        Files.writeString(classpath, "two.jar");
        var after = ProjectContext.detect(directory.resolve("Main.java"), "class Main {}");
        assertEquals(second, after.classPath().getFirst());
        assertNotEquals(before.revision(), after.revision());
    }

    @Test
    void invalidAndOversizedConfigCannotDisableBasicSemanticSupport() throws Exception {
        Path configDir = Files.createDirectories(directory.resolve(".turboj"));
        Files.writeString(configDir.resolve("classpath.txt"), "https://example.invalid/library.jar");
        Files.writeString(configDir.resolve("source-archives.txt"), "x".repeat(65_537));
        Files.writeString(configDir.resolve("source-roots.txt"), "missing-directory");
        var config = ProjectContext.detect(directory.resolve("Main.java"), "class Main {}");
        assertTrue(config.sourceRoots().contains(directory));
        assertTrue(config.classPath().isEmpty());
        assertEquals(3, config.warnings().size());
    }
}
