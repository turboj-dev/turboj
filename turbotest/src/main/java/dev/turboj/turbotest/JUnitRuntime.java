package dev.turboj.turbotest;

import com.google.gson.Gson;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;

/** Supplies only JUnit libraries to the user's compiler, even when the host is a shaded application. */
public final class JUnitRuntime implements AutoCloseable {
    private final Path temporary;
    private final List<Path> junit;

    public JUnitRuntime() throws Exception {
        Path origin = origin(WorkerEntry.class);
        Path created = null;
        List<Path> libraries;
        if (Files.isDirectory(origin)) {
            try (var paths = Files.list(origin.resolve("turbotest/junit"))) {
                libraries = paths.filter(path -> path.getFileName().toString().endsWith(".jar")).sorted().toList();
            }
        } else {
            created = Files.createTempDirectory("turbotest-junit-");
            try (JarFile archive = new JarFile(origin.toFile())) {
                for (var entry : archive.stream().filter(entry -> entry.getName().startsWith("turbotest/junit/") && entry.getName().endsWith(".jar")).toList()) {
                    String name = entry.getName().substring("turbotest/junit/".length());
                    if (!name.matches("[A-Za-z0-9_.-]+\\.jar")) throw new IOException("Invalid embedded JUnit path");
                    try (InputStream input = archive.getInputStream(entry)) { Files.copy(input, created.resolve(name)); }
                }
            }
            try (var paths = Files.list(created)) { libraries = paths.sorted().toList(); }
        }
        temporary = created;
        if (libraries.isEmpty()) { close(); throw new IOException("JUnit runtime libraries are missing; rebuild the distribution"); }
        junit = List.copyOf(libraries);
    }

    public List<Path> compilationClasspath() { return junit; }
    public List<Path> executionClasspath() throws Exception {
        var paths = new LinkedHashSet<>(junit);
        paths.add(origin(WorkerEntry.class)); paths.add(origin(Gson.class));
        return List.copyOf(paths);
    }

    private static Path origin(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
    }

    @Override public void close() {
        if (temporary == null) return;
        try (var paths = Files.walk(temporary)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (IOException ignored) { /* Private extraction can be reclaimed by the host temporary-directory cleanup. */ }
    }
}
