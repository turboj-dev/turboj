package dev.turboj.refactorj.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SourceInventoryTest {
    @TempDir Path root;
    @Test void excludesCachesAndBuildOutputsButAllowsExplicitGeneratedRoots() throws Exception {
        Path source = file("src/C.java"), generated = file("build/generated/G.java");
        file(".cache/Dependency.java"); file("target/Copy.java"); file("node_modules/Other.java");
        assertEquals(List.of(source), SourceInventory.scan(root, List.of(root), 10));
        assertEquals(List.of(generated, source), SourceInventory.scan(root, List.of(root, generated.getParent()), 10));
        assertThrows(java.io.IOException.class, () -> SourceInventory.scan(root, List.of(root, generated.getParent()), 1));
    }
    @Test void doesNotFollowSymbolicDirectories() throws Exception {
        Path external = Files.createTempDirectory(root.getParent(), "inventory-external-");
        try {
            Files.writeString(external.resolve("External.java"), "class External {}");
            Files.createSymbolicLink(root.resolve("linked"), external);
            assertTrue(SourceInventory.scan(root, List.of(root), 10).isEmpty());
        } finally { Files.deleteIfExists(external.resolve("External.java")); Files.deleteIfExists(external); }
    }
    private Path file(String name) throws Exception {
        Path path = root.resolve(name); Files.createDirectories(path.getParent()); Files.writeString(path, "class C {}"); return path;
    }
    @Test void detailedInventoryIncludesEmptyDirectories() throws Exception {
        Path nested = Files.createDirectories(root.resolve("source/empty"));
        var inventory = SourceInventory.scanDetailed(root, List.of(root), 10);
        assertTrue(inventory.sources().isEmpty());
        assertTrue(inventory.directories().contains(nested));
    }

    @Test void projectScanExcludesNestedBuildsButKeepsExplicitModuleSourceRoots() throws Exception {
        Path loose = file("Main.java"), ordinary = file("example/Helper.java");
        Path maven = file("nested-maven/src/main/java/Main.java");
        Path test = file("nested-maven/src/test/java/MainTest.java");
        Path plain = file("nested-plain/src/Main.java");
        Files.writeString(root.resolve("nested-maven/pom.xml"), "<project/>");
        Files.createDirectory(root.resolve("nested-plain/.turboj"));

        var isolated = SourceInventory.scanProjectDetailed(root, List.of(root), 20);
        assertEquals(List.of(loose, ordinary), isolated.sources());
        assertTrue(isolated.directories().contains(root.resolve("nested-maven")));
        assertEquals(List.of(loose, ordinary, maven), SourceInventory.scanProjectDetailed(root,
                List.of(root, maven.getParent()), 20).sources());
        assertTrue(SourceInventory.scanDetailed(root, List.of(root), 20).sources().containsAll(List.of(maven, test, plain)),
                "The broad inventory API retains its previous semantics");
        assertFalse(SourceInventory.isProjectSource(List.of(root), maven));
        assertFalse(SourceInventory.isProjectSource(List.of(root), plain));
        assertTrue(SourceInventory.isProjectSource(List.of(root, maven.getParent()), maven));
        assertFalse(SourceInventory.isProjectSource(List.of(root, maven.getParent()), test));
        assertTrue(SourceInventory.isProjectSource(List.of(root), root.resolve("example/new/NeverSaved.java")));
        assertFalse(SourceInventory.isProjectSource(List.of(root), root.resolve("target/Generated.java")));
    }
}
