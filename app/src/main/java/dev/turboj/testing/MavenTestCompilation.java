package dev.turboj.testing;

import dev.turboj.mavenj.api.MavenModels.Workspace;
import dev.turboj.project.MavenCompilationWorkspace;
import java.io.IOException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.BiPredicate;

/** Owns one test generation independently of subsequent editor compilations. */
final class MavenTestCompilation implements AutoCloseable {
    final MavenCompilationWorkspace.Result result;
    final Map<String, String> fingerprints;
    final Map<String, MavenCompilationWorkspace.ExecutionSnapshot> executions;

    private MavenTestCompilation(MavenCompilationWorkspace.Result result, Map<String, String> fingerprints,
            Map<String, MavenCompilationWorkspace.ExecutionSnapshot> executions) {
        this.result = result;
        this.fingerprints = Map.copyOf(fingerprints);
        this.executions = Map.copyOf(executions);
    }

    /** Background-only: do not wait for Swing while holding the compiler monitor. */
    static MavenTestCompilation prepare(MavenCompilationWorkspace compiler, Workspace model,
            Map<Path, String> buffers, long generation,
            BiPredicate<MavenCompilationWorkspace.ModuleResult, String> needsExecution) throws IOException {
        synchronized (compiler) {
            var result = compiler.compile(model, buffers, generation, true);
            var fingerprints = new LinkedHashMap<String, String>();
            var executions = new LinkedHashMap<String, MavenCompilationWorkspace.ExecutionSnapshot>();
            try {
                if (result.success()) for (var module : result.modules()) {
                    if (!module.tests()) continue;
                    String fingerprint = compiler.sourceFingerprint(module.moduleId(), true);
                    fingerprints.put(module.moduleId(), fingerprint);
                    if (!module.compilation().bytecode().isEmpty() && needsExecution.test(module, fingerprint))
                        executions.put(module.moduleId(), compiler.pinExecution(module.moduleId(), true,
                                model.generation(), generation));
                }
                return new MavenTestCompilation(result, fingerprints, executions);
            } catch (IOException | RuntimeException | Error failure) {
                executions.values().forEach(MavenCompilationWorkspace.ExecutionSnapshot::close);
                throw failure;
            }
        }
    }

    @Override public void close() {
        executions.values().forEach(MavenCompilationWorkspace.ExecutionSnapshot::close);
    }
}
