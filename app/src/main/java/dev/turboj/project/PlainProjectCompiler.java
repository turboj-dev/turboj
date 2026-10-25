package dev.turboj.project;

import dev.turboj.compiler.*;
import dev.turboj.config.SafeFiles;
import dev.turboj.editor.document.PieceTable;
import dev.turboj.intelligence.ProjectContext;
import dev.turboj.refactorj.api.SourceInventory;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;

/** Coherent plain-project compilation. One reusable javac lane, no filesystem or compiler work on EDT. */
public final class PlainProjectCompiler implements AutoCloseable {
    public record Result(CompilationResult active, WorkspaceCompilationResult workspace) {}
    private JavacCompilerBackend backend;
    private List<Path> classpath = List.of();
    private long projectRevision = Long.MIN_VALUE;
    private Path projectRoot;
    private boolean closed;

    public synchronized Result compile(Path activeFile, PieceTable.Snapshot source,
            Map<Path, PieceTable.Snapshot> openSources, ProjectContext.Config config) throws IOException {
        if (javax.swing.SwingUtilities.isEventDispatchThread()) throw new IllegalStateException("Compile plain projects on a worker");
        if (closed) throw new IllegalStateException("Compiler is closed");
        if (!config.projects().isEmpty()) throw new IllegalArgumentException("Use Maven compilation for configured modules");
        Path active = activeFile.toAbsolutePath().normalize();
        if (!active.startsWith(config.projectRoot())) throw new IllegalArgumentException("Active source is outside this project");
        if (backend == null || !classpath.equals(config.classPath()) || projectRevision != config.revision()
                || !Objects.equals(projectRoot, config.projectRoot())) {
            if (backend != null) backend.close();
            classpath = config.classPath(); projectRevision = config.revision(); projectRoot = config.projectRoot();
            backend = new JavacCompilerBackend(25, classpath);
        }
        boolean includeTests = isTest(active, config.projectRoot());
        TreeMap<Path, SourceSnapshot> units = new TreeMap<>();
        int total = 0;
        for (Path file : SourceInventory.scanProjectDetailed(config.projectRoot(), config.sourceRoots(), 4096).sources()) {
            if (!includeTests && isTest(file, config.projectRoot())) continue;
            PieceTable.Snapshot open = openSources.get(file);
            String text = open == null ? SafeFiles.readUtf8(file) : open.toString();
            if ((total += text.length()) > 32 * 1024 * 1024) throw new IOException("Plain project exceeds the 32 MiB source limit");
            units.put(file, new SourceSnapshot(file.toString(), text, open == null ? 0 : open.version()));
        }
        for (var entry : openSources.entrySet()) {
            Path file = entry.getKey().toAbsolutePath().normalize();
            if (!file.getFileName().toString().endsWith(".java") || !SourceInventory.isProjectSource(config.sourceRoots(), file)
                    || !includeTests && isTest(file, config.projectRoot())) continue;
            units.put(file, new SourceSnapshot(file.toString(), entry.getValue().toString(), entry.getValue().version()));
        }
        units.put(active, new SourceSnapshot(active.toString(), source.toString(), source.version()));
        if (units.size() > 4096 || units.values().stream().mapToLong(unit -> unit.source().length()).sum() > 32 * 1024 * 1024)
            throw new IOException("Plain project exceeds compilation size limits");
        var compiled = backend.compileSources(List.copyOf(units.values()), source.version());
        List<CompilerDiagnostic> diagnostics = new ArrayList<>();
        for (var diagnostic : compiled.diagnostics()) {
            if (diagnostic.fileName().isEmpty() || Path.of(diagnostic.fileName()).toAbsolutePath().normalize().equals(active))
                diagnostics.add(diagnostic.diagnostic());
        }
        if (!compiled.success() && diagnostics.stream().noneMatch(CompilerDiagnostic::isError)) {
            var error = compiled.diagnostics().stream().filter(value -> value.diagnostic().isError()).findFirst();
            String explanation = error.map(value -> Path.of(value.fileName()).getFileName() + ": " + value.diagnostic().message())
                    .orElse("Another project source failed to compile.");
            diagnostics.add(new CompilerDiagnostic("ERROR", explanation, -1, -1, -1, -1));
        }
        Map<String, byte[]> bytes = compiled.bytecode();
        return new Result(new CompilationResult(source.version(), compiled.success(), diagnostics, bytes,
                compiled.durationNanos(), false, compiled.success() ? JavacCompilerBackend.findMainForSource(bytes, active.toString()) : null), compiled);
    }

    private static boolean isTest(Path file, Path root) { return file.startsWith(root.resolve("src/test/java")); }

    @Override public synchronized void close() {
        closed = true; if (backend != null) backend.close(); backend = null;
    }
}
