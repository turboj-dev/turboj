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
        this(Integer.toString(release), "", "", "UTF-8", classpath, List.of(), false);
    }

    /** Explicit project settings. Processors remain disabled, including on the project classpath. */
    public JavacCompilerBackend(String release, String source, String target, String encoding,
            List<Path> classpath, List<Path> modulePath, boolean preview) {
        this(release,source,target,encoding,classpath,modulePath,preview,List.of());
    }
    public JavacCompilerBackend(String release, String source, String target, String encoding,
            List<Path> classpath, List<Path> modulePath, boolean preview,List<String> compilerArguments) {
        this(release,source,target,encoding,classpath,modulePath,preview,compilerArguments,null);
    }
    public JavacCompilerBackend(String release, String source, String target, String encoding,
            List<Path> classpath, List<Path> modulePath, boolean preview,List<String> compilerArguments,Path systemHome) {
        compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null)
            throw new IllegalStateException("TurboJ requires the jdk.compiler module / a full JDK");
        String level = release.isBlank() ? source : release;
        if (level.isBlank()) throw new IllegalArgumentException("Project Java language level is not configured");
        int version = Integer.parseInt(level.startsWith("1.") ? level.substring(2) : level);
        if (Runtime.version().feature() < version)
            throw new IllegalArgumentException(
                    "Java " + version + " requires a matching or newer JDK");
        standard = compiler.getStandardFileManager(null, Locale.ROOT, java.nio.charset.Charset.forName(encoding));
        var values = new ArrayList<String>();
        if (!release.isBlank()) values.addAll(List.of("--release", release));
        else { values.addAll(List.of("-source", source)); if (!target.isBlank()) values.addAll(List.of("-target", target)); }
        if(release.isBlank()&&systemHome!=null) {
            if(version>=9) values.addAll(List.of("--system",systemHome.toAbsolutePath().toString()));
            else {
                // javac forbids --system with a target below 9. A selected Java 8 platform uses
                // its actual bootstrap libraries; source/target is intentionally not --release.
                Path libraries=systemHome.resolve("jre/lib");
                if(!java.nio.file.Files.isRegularFile(libraries.resolve("rt.jar")))libraries=systemHome.resolve("lib");
                if(!java.nio.file.Files.isRegularFile(libraries.resolve("rt.jar")))throw new IllegalArgumentException("Native source/target "+version+" cannot reproduce platform APIs from the selected alternate modular JDK. Use an explicit Maven build or configure --release.");
                var bootstrap=new ArrayList<String>();
                for(String name:List.of("resources.jar","rt.jar","sunrsasign.jar","jsse.jar","jce.jar","charsets.jar","jfr.jar")){Path jar=libraries.resolve(name);if(java.nio.file.Files.isRegularFile(jar))bootstrap.add(jar.toAbsolutePath().toString());}
                values.addAll(List.of("-bootclasspath",String.join(java.io.File.pathSeparator,bootstrap)));
            }
        }
        values.addAll(List.of("-proc:none", "-g", "-encoding", encoding));
        values.addAll(safeCompilerArguments(compilerArguments));
        if (preview) values.add("--enable-preview");
        if (!modulePath.isEmpty()) values.addAll(List.of("--module-path", String.join(java.io.File.pathSeparator, modulePath.stream().map(Path::toString).toList())));
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

    /** Additional compiler flags cannot enable plugins, processors or change managed paths. */
    public static List<String> safeCompilerArguments(List<String> arguments) {
        for(String option:arguments)if(!(option.equals("-parameters")||option.equals("-Werror")||option.equals("-deprecation")||option.equals("-nowarn")||option.equals("-g")||option.equals("-g:none")||option.startsWith("-Xlint")||option.startsWith("-Xdoclint")||option.matches("-g:(lines|vars|source)(,(lines|vars|source))*")))throw new IllegalArgumentException("Native compilation cannot safely reproduce compiler argument: "+option+". Use an explicit Maven build.");
        return List.copyOf(arguments);
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
    public static String findMain(Map<String, byte[]> classes, String fileName) {
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
                                                    && (model.majorVersion() >= 69 || (method.flags().has(AccessFlag.PUBLIC) && method.flags().has(AccessFlag.STATIC) && method.methodType().equalsString("([Ljava/lang/String;)V")))
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

    /** Chooses an entrypoint declared by this source file, never an unrelated reactor class. */
    public static String findMainForSource(Map<String,byte[]> classes,String fileName) {
        String name=Path.of(fileName).getFileName().toString();
        var matching=new LinkedHashMap<String,byte[]>();
        classes.forEach((binary,bytes)->{
            var model=ClassFile.of().parse(bytes);
            int separator=binary.lastIndexOf('.');String relative=(separator<0?"":binary.substring(0,separator).replace('.','/')+"/")+name;
            String normalized=Path.of(fileName).normalize().toString().replace('\\','/');
            if(Path.of(fileName).getNameCount()>1&&!normalized.equals(relative)&&!normalized.endsWith("/"+relative))return;
            if(model.findAttribute(java.lang.classfile.Attributes.sourceFile()).map(attribute->attribute.sourceFile().stringValue().equals(name)).orElse(false))matching.put(binary,bytes);
        });
        return findMain(matching,fileName);
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
        String name = Path.of(filename).normalize().toString().replace('\\', '/');
        if (name.startsWith("/")) name = name.substring(1);
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

        @Override public boolean contains(JavaFileManager.Location location,FileObject file)throws IOException {
            if(file instanceof SourceFile)return location==javax.tools.StandardLocation.SOURCE_PATH;
            if(file instanceof ClassFileOutput)return location==javax.tools.StandardLocation.CLASS_OUTPUT;
            return super.contains(location,file);
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
