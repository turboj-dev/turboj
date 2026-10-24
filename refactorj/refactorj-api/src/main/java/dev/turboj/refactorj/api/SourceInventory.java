package dev.turboj.refactorj.api;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** Bounded deterministic source discovery shared by embedded and standalone workspace adapters. */
public final class SourceInventory {
    private static final Set<String> GENERATED = Set.of("target", "build", "out", "node_modules", "dist");
    private static final List<String> PROJECT_MARKERS = List.of(".turboj", "pom.xml", "build.gradle",
            "build.gradle.kts", "settings.gradle", "settings.gradle.kts", ".git");
    private SourceInventory() {}

    public record Inventory(List<Path> sources, List<Path> directories) {
        public Inventory { sources=List.copyOf(sources); directories=List.copyOf(directories); }
    }

    /** Explicit source roots can opt into generated source directories. Symbolic links are never followed. */
    public static List<Path> scan(Path workspaceRoot, List<Path> sourceRoots, int maxFiles) throws IOException {
        return scanDetailed(workspaceRoot, sourceRoots, maxFiles).sources();
    }

    /** Includes empty directories, allowing a host to invalidate inventory on directory changes. */
    public static Inventory scanDetailed(Path workspaceRoot, List<Path> sourceRoots, int maxFiles) throws IOException {
        return scanDetailed(workspaceRoot, sourceRoots, maxFiles, false);
    }

    /**
     * Discovers one editor project without absorbing nested independent projects. Explicit roots
     * inside a nested project are still scanned in their own pass, including Maven reactor roots.
     */
    public static Inventory scanProjectDetailed(Path workspaceRoot, List<Path> sourceRoots, int maxFiles) throws IOException {
        return scanDetailed(workspaceRoot, sourceRoots, maxFiles, true);
    }

    /** Includes new unsaved files under the same project boundaries used by scanProjectDetailed. */
    public static boolean isProjectSource(List<Path> sourceRoots, Path source) {
        Path file = source.toAbsolutePath().normalize();
        for (Path candidate : sourceRoots) {
            Path root = candidate.toAbsolutePath().normalize();
            if (!file.startsWith(root)) continue;
            boolean included = true;
            for (Path directory = file.getParent(); directory != null && !directory.equals(root); directory = directory.getParent()) {
                String name = directory.getFileName().toString();
                if (name.startsWith(".") || GENERATED.contains(name) || projectBoundary(directory)) {
                    included = false;
                    break;
                }
            }
            if (included) return true;
        }
        return false;
    }

    private static boolean projectBoundary(Path directory) {
        return PROJECT_MARKERS.stream().anyMatch(marker -> Files.exists(directory.resolve(marker), LinkOption.NOFOLLOW_LINKS));
    }

    private static Inventory scanDetailed(Path workspaceRoot, List<Path> sourceRoots, int maxFiles,
            boolean isolateProjects) throws IOException {
        if (maxFiles < 1) throw new IllegalArgumentException("A positive source limit is required");
        Path allowed = workspaceRoot.toAbsolutePath().normalize();
        List<Path> roots = sourceRoots.stream().map(path -> path.toAbsolutePath().normalize()).distinct().sorted().toList();
        TreeSet<Path> sources = new TreeSet<>();
        TreeSet<Path> directories = new TreeSet<>();
        for (Path root : roots) {
            if (!root.startsWith(allowed)) throw new IOException("Source root is outside the workspace");
            if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) continue;
            if (Files.isSymbolicLink(root)) throw new IOException("Symbolic source roots are not supported");
            Files.walkFileTree(root, new SimpleFileVisitor<>() {
                @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) {
                    if (directory.equals(root)) { directories.add(directory); return FileVisitResult.CONTINUE; }
                    if (isolateProjects && projectBoundary(directory)) {
                        directories.add(directory); // Removing the marker must invalidate the inventory.
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    String name = directory.getFileName().toString();
                    boolean explicitlyIncluded = roots.stream().anyMatch(explicit -> explicit.startsWith(directory));
                    if (!explicitlyIncluded && (name.startsWith(".") || GENERATED.contains(name))) return FileVisitResult.SKIP_SUBTREE;
                    directories.add(directory);
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws IOException {
                    if (attributes.isRegularFile() && file.getFileName().toString().endsWith(".java")) {
                        sources.add(file);
                        if (sources.size() > maxFiles) throw new IOException("Workspace exceeds " + maxFiles + " Java sources");
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        return new Inventory(List.copyOf(sources),List.copyOf(directories));
    }
}
