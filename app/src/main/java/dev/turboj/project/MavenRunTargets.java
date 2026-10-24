package dev.turboj.project;

import dev.turboj.compiler.JavacCompilerBackend;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Discovers runnable classes from native compilation results without loading project code. */
public final class MavenRunTargets {
    private MavenRunTargets() {}

    public record Target(String moduleId, String mainClass, boolean tests) {
        public Target {
            Objects.requireNonNull(moduleId);
            Objects.requireNonNull(mainClass);
        }

        @Override public String toString() {
            return moduleId + " · " + mainClass + (tests ? " [tests]" : "");
        }
    }

    /**
     * A main declared by the active source wins. Otherwise the caller gets every main-source
     * entrypoint, in a stable order, and must let the user choose when there is more than one.
     * Test helpers are candidates only when their own source is explicitly active.
     */
    public static List<Target> candidates(MavenCompilationWorkspace.Result result, Path activeFile) {
        Objects.requireNonNull(result);
        if (!result.success()) return List.of();
        if (activeFile != null) {
            Path selected = activeFile.toAbsolutePath().normalize();
            for (var module : result.modules()) {
                if (!module.success() || !module.compilation().success()) continue;
                boolean ownsSource = module.compilation().sources().stream().anyMatch(source ->
                        Path.of(source.fileName()).toAbsolutePath().normalize().equals(selected));
                if (!ownsSource) continue;
                String main = JavacCompilerBackend.findMainForSource(module.compilation().bytecode(), selected.toString());
                if (main != null) return List.of(new Target(module.moduleId(), main, module.tests()));
            }
        }
        return result.modules().stream()
                .filter(module -> !module.tests() && module.success() && module.compilation().success())
                .flatMap(module -> module.compilation().bytecode().entrySet().stream()
                        .filter(entry -> JavacCompilerBackend.findMain(Map.of(entry.getKey(), entry.getValue()),
                                entry.getKey() + ".java") != null)
                        .map(entry -> new Target(module.moduleId(), entry.getKey(), false)))
                .distinct()
                .sorted(Comparator.comparing(Target::moduleId).thenComparing(Target::mainClass))
                .toList();
    }
}
