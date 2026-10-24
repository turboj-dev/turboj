package dev.turboj.compiler;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.StringWriter;
import java.lang.classfile.ClassFile;
import java.lang.reflect.AccessFlag;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import javax.tools.DiagnosticCollector;
import javax.tools.FileObject;
import javax.tools.ForwardingJavaFileManager;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileManager;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

/** Single-threaded javac service. Task contexts are fresh, standard file-manager metadata is reused. */
public final class JavacCompilerBackend implements CompilerBackend {
    private static final int CACHE_ENTRIES = 16;
    private static final int CACHE_SOURCE_LIMIT = 8 * 1024 * 1024;
    private final JavaCompiler compiler;
    private final StandardJavaFileManager standard;
    private final List<String> options;
    private final Map<Key, CompilationResult> cache = new LinkedHashMap<>(16, .75f, true);
    private int cachedSourceChars;
    private boolean closed;

    public JavacCompilerBackend() {
        this(25, List.of());
    }

    /** Explicit toolchain language level and classpath; no annotation processors are executed. */
    public JavacCompilerBackend(int release, List<Path> classpath) {
        compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null)
            throw new IllegalStateException("TurboJ requires the jdk.compiler module / a full JDK");
        if (Runtime.version().feature() < release)
            throw new IllegalArgumentException(
                    "Java " + release + " requires a matching or newer JDK");
        standard = compiler.getStandardFileManager(null, Locale.ROOT, StandardCharsets.UTF_8);
        var values =
                new ArrayList<>(
                        List.of(
                                "--release",
                                Integer.toString(release),
                                "-proc:none",
                                "-g",
                                "-encoding",
                                "UTF-8"));
        // Never inherit the editor's own classpath as a user's program dependencies.
        values.add("-classpath");
        values.add(
                classpath.isEmpty()
                        ? emptyClasspath()
                        : String.join(
                                java.io.File.pathSeparator,
                                classpath.stream()
                                        .map(p -> p.toAbsolutePath().toString())
                                        .toList()));
        values.add("-sourcepath");
        values.add(emptyClasspath());
        options = List.copyOf(values);
    }

    private static String emptyClasspath() {
        return Path.of(System.getProperty("java.home"), ".turboj-empty-classpath").toString();
    }

    @Override
    public synchronized CompilationResult compile(SourceSnapshot snapshot) {
        if (closed) throw new IllegalStateException("Compiler is closed");
        long started = System.nanoTime();
        var key = new Key(snapshot.fileName(), snapshot.source());
        var previous = cache.get(key);
        if (previous != null)
            return new CompilationResult(
                    snapshot.version(),
                    previous.success(),
                    previous.diagnostics(),
                    previous.bytecode(),
                    System.nanoTime() - started,
                    true,
                    previous.mainClass());

        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var output = new MemoryFiles(standard);
        var writer = new StringWriter();
        boolean success =
                Boolean.TRUE.equals(
                        compiler.getTask(
                                        writer,
                                        output,
                                        diagnostics,
                                        options,
                                        null,
                                        List.of(
                                                new SourceFile(
                                                        snapshot.fileName(), snapshot.source())))
                                .call());
        var messages = new ArrayList<CompilerDiagnostic>();
        diagnostics
                .getDiagnostics()
                .forEach(
                        d ->
                                messages.add(
                                        new CompilerDiagnostic(
                                                d.getKind().name(),
                                                d.getMessage(Locale.ROOT),
                                                d.getLineNumber(),
                                                d.getColumnNumber(),
                                                d.getStartPosition(),
                                                d.getEndPosition())));
        if (!success && messages.stream().noneMatch(CompilerDiagnostic::isError))
            messages.add(
                    new CompilerDiagnostic(
                            "ERROR",
                            writer.toString().isBlank() ? "Compilation failed" : writer.toString(),
                            -1,
                            -1,
                            -1,
                            -1));
        Map<String, byte[]> artifacts = success ? output.bytes() : Map.of();
        String main = success ? findMain(artifacts, snapshot.fileName()) : null;
        var result =
                new CompilationResult(
                        snapshot.version(),
                        success,
                        messages,
                        artifacts,
                        System.nanoTime() - started,
                        false,
                        main);
        // Failed snapshots are deliberately not cached: no stale bytecode can masquerade as
        // current.
        if (success && snapshot.source().length() <= CACHE_SOURCE_LIMIT) {
            cache.put(key, result);
            cachedSourceChars += snapshot.source().length();
            while (cache.size() > CACHE_ENTRIES || cachedSourceChars > CACHE_SOURCE_LIMIT) {
                var oldest = cache.entrySet().iterator();
                cachedSourceChars -= oldest.next().getKey().source().length();
                oldest.remove();
            }
        }
        return result;
    }

    /** Compiles a coherent collection of unsaved buffers with this warm compiler infrastructure. */
    public synchronized WorkspaceCompilationResult compileSources(List<SourceSnapshot> sources, long workspaceVersion) {
        if (closed) throw new IllegalStateException("Compiler is closed");
        if (sources.isEmpty() || sources.size() > 4096) throw new IllegalArgumentException("Expected 1–4096 Java sources");
        long started = System.nanoTime();
        var diagnostics = new DiagnosticCollector<JavaFileObject>();
        var output = new MemoryFiles(standard);
        var writer = new StringWriter();
        var inputs = new LinkedHashMap<SourceFile, SourceSnapshot>();
        var paths = new java.util.HashSet<URI>();
        for (SourceSnapshot source : sources) {
            var file = new SourceFile(source.fileName(), source.source());
            if (!paths.add(file.toUri())) throw new IllegalArgumentException("Duplicate compilation unit: " + source.fileName());
            inputs.put(file, source);
        }
        boolean success = Boolean.TRUE.equals(compiler.getTask(writer, output, diagnostics, options, null, inputs.keySet()).call());
        var messages = new ArrayList<WorkspaceCompilationResult.SourceDiagnostic>();
        for (var diagnostic : diagnostics.getDiagnostics()) {
            SourceSnapshot source = inputs.get(diagnostic.getSource());
            messages.add(new WorkspaceCompilationResult.SourceDiagnostic(source == null ? "" : source.fileName(),
                    new CompilerDiagnostic(diagnostic.getKind().name(), diagnostic.getMessage(Locale.ROOT),
                            diagnostic.getLineNumber(), diagnostic.getColumnNumber(), diagnostic.getStartPosition(), diagnostic.getEndPosition())));
        }
        if (!success && messages.stream().noneMatch(message -> message.diagnostic().isError()))
            messages.add(new WorkspaceCompilationResult.SourceDiagnostic("", new CompilerDiagnostic("ERROR",
                    writer.toString().isBlank() ? "Project compilation failed" : writer.toString(), -1, -1, -1, -1)));
        return new WorkspaceCompilationResult(workspaceVersion, success, sources, messages,
                success ? output.bytes() : Map.of(), System.nanoTime() - started);
    }

    /** Inspects bytecode without loading or initializing user classes in the editor JVM. */
    private static String findMain(Map<String, byte[]> classes, String fileName) {
        String simpleName = Path.of(fileName).getFileName().toString().replaceFirst("\\.java$", "");
        var candidates = new ArrayList<String>();
        classes.forEach(
                (name, bytes) -> {
                    var model = ClassFile.of().parse(bytes);
                    if (model.methods().stream()
                            .anyMatch(
                                    method ->
                                            method.methodName().equalsString("main")
                                                    && !method.flags().has(AccessFlag.PRIVATE)
                                                    && (method.methodType()
                                                                    .equalsString(
                                                                            "([Ljava/lang/String;)V")
                                                            || method.methodType()
                                                                    .equalsString("()V"))))
                        candidates.add(name);
                });
        return candidates.stream()
                .filter(n -> n.equals(simpleName) || n.endsWith("." + simpleName))
                .findFirst()
                .orElse(candidates.isEmpty() ? null : candidates.getFirst());
    }

    public synchronized void invalidate() {
        cache.clear();
        cachedSourceChars = 0;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        cache.clear();
        try {
            standard.close();
        } catch (IOException ignored) {
            /* no persistent output is held */
        }
    }

    private record Key(String name, String source) {}

    private static URI sourceUri(String filename) {
        String name = Path.of(filename).getFileName().toString();
        if (!name.endsWith(".java")) name += ".java";
        try {
            return new URI("string", null, "/" + name, null);
        } catch (URISyntaxException exception) {
            throw new IllegalArgumentException("Invalid source name", exception);
        }
    }

    private static final class SourceFile extends SimpleJavaFileObject {
        private final String source;

        SourceFile(String name, String source) {
            super(sourceUri(name), Kind.SOURCE);
            this.source = source;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreErrors) {
            return source;
        }
    }

    private static final class ClassFileOutput extends SimpleJavaFileObject {
        private final ByteArrayOutputStream bytes = new ByteArrayOutputStream();

        ClassFileOutput(String name, Kind kind) {
            super(URI.create("memory:///" + name.replace('.', '/') + kind.extension), kind);
        }

        @Override
        public OutputStream openOutputStream() {
            return bytes;
        }
    }

    private static final class MemoryFiles
            extends ForwardingJavaFileManager<StandardJavaFileManager> {
        private final Map<String, ClassFileOutput> outputs = new LinkedHashMap<>();

        MemoryFiles(StandardJavaFileManager manager) {
            super(manager);
        }

        @Override
        public JavaFileObject getJavaFileForOutput(
                JavaFileManager.Location location,
                String name,
                JavaFileObject.Kind kind,
                FileObject sibling) {
            var output = new ClassFileOutput(name, kind);
            outputs.put(name, output);
            return output;
        }

        Map<String, byte[]> bytes() {
            var result = new LinkedHashMap<String, byte[]>();
            outputs.forEach((name, value) -> result.put(name, value.bytes.toByteArray()));
            return result;
        }
    }
}
