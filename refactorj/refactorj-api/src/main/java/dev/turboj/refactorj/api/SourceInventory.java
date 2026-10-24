package dev.turboj.refactorj.api;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;

/** Bounded deterministic source discovery shared by embedded and standalone workspace adapters. */
public final class SourceInventory {
    private static final Set<String> GENERATED = Set.of("target", "build", "out", "node_modules", "dist");
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
