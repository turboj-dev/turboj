package dev.turboj.ai.local;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;

/**
 * Finds inference runtimes without a login shell. A macOS app started from Finder has only
 * {@code /usr/bin:/bin:/usr/sbin:/sbin} on PATH, so the usual install locations are searched first.
 */
public final class RuntimeLocator {
    private final List<Path> directories;
    private final List<Path> ollamaApps;

    public RuntimeLocator(List<Path> directories, List<Path> ollamaApps) {
        this.directories = List.copyOf(directories);
        this.ollamaApps = List.copyOf(ollamaApps);
    }

    /** Homebrew (Apple Silicon and Intel), user bins, the Ollama app bundle, then PATH. */
    public static RuntimeLocator system() {
        String home = System.getProperty("user.home");
        var directories = new LinkedHashSet<Path>(List.of(Path.of("/opt/homebrew/bin"), Path.of("/usr/local/bin"),
                Path.of(home, ".local", "bin"), Path.of(home, "bin")));
        String path = System.getenv("PATH");
        if (path != null)
            for (String entry : path.split(File.pathSeparator))
                if (!entry.isBlank()) directories.add(Path.of(entry));
        return new RuntimeLocator(new ArrayList<>(directories), List.of(Path.of("/Applications/Ollama.app"),
                Path.of(home, "Applications", "Ollama.app")));
    }

    public Optional<Path> ollama() {
        for (Path app : ollamaApps) {
            Path cli = app.resolve("Contents/Resources/ollama");
            if (executable(cli)) return Optional.of(cli);
        }
        return find("ollama");
    }

    public Optional<Path> llamaServer() {
        return find("llama-server");
    }

    public Optional<Path> find(String name) {
        for (Path directory : directories) {
            Path candidate = directory.resolve(name);
            if (executable(candidate)) return Optional.of(candidate.toAbsolutePath().normalize());
        }
        return Optional.empty();
    }

    private static boolean executable(Path candidate) {
        return Files.isRegularFile(candidate) && Files.isExecutable(candidate);
    }
}
