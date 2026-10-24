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
}
