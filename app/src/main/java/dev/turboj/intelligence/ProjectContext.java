package dev.turboj.intelligence;

import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.CompilationUnit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import dev.turboj.mavenj.api.MavenModels.Workspace;
import dev.turboj.project.MavenSemanticAdapter;
import dev.turboj.refactorj.api.Model.JavaProjectConfiguration;

/** Local, bounded project discovery. Call on the semantic worker, never the EDT. */
public final class ProjectContext {
    private static final long MAX_CONFIG_BYTES = 65_536;
    private static final int MAX_ENTRIES = 4_096;

    private ProjectContext() {}

    public record Config(Path projectRoot, List<Path> sourceRoots, List<Path> classPath,
                         List<Path> sourceArchives, long revision, List<String> warnings,
                         List<JavaProjectConfiguration> projects) {
        public Config(Path root, List<Path> roots, List<Path> cp, List<Path> archives, long revision, List<String> warnings) {
            this(root, roots, cp, archives, revision, warnings, List.of());
        }
        public Config {
            sourceRoots = List.copyOf(sourceRoots);
            classPath = List.copyOf(classPath);
            sourceArchives = List.copyOf(sourceArchives);
            warnings = List.copyOf(warnings);
            projects = List.copyOf(projects);
        }
    }

    public static Config fromMaven(Workspace workspace) {
        return fromMaven(workspace, null, 0);
    }

    public static Config fromMaven(Workspace workspace, dev.turboj.project.MavenCompilationWorkspace compiler, long generatedRevision) {
        var archives = new LinkedHashSet<Path>();
        for (var module : workspace.modules()) {
            if (module.mainJava().javaHome() != null) addFile(archives, module.mainJava().javaHome().resolve("lib/src.zip"));
            if (compiler != null) {
                addFile(archives, compiler.generatedSourceArchive(module.id(), false));
                addFile(archives, compiler.generatedSourceArchive(module.id(), true));
            }
            for (Path artifact : module.classpaths().forScope(dev.turboj.mavenj.api.MavenModels.ClasspathScope.TEST_COMPILE)) {
                String name = artifact.getFileName().toString();
                if (name.endsWith(".jar")) addFile(archives, artifact.resolveSibling(name.substring(0, name.length() - 4) + "-sources.jar"));
            }
        }
        return new Config(workspace.root(), MavenSemanticAdapter.sourceRoots(workspace), List.of(), new ArrayList<>(archives),
                workspace.generation() * 31 + generatedRevision, workspace.diagnostics().stream().map(d -> d.message()).toList(), MavenSemanticAdapter.configurations(workspace, compiler));
    }

    public static Config detect(Path activeFile, String activeSource) throws IOException {
        Path file = activeFile.toAbsolutePath().normalize();
        Path directory = file.getParent();
        if (file.getFileName().toString().startsWith(".turboj-untitled-")
                && Files.notExists(file)) {
            // An unsaved editor buffer has no project membership yet. Its synthetic path
            // must not turn the process working directory into a recursive source root.
            var archives = new LinkedHashSet<Path>();
            discoverJdkSources(archives);
            return new Config(directory, List.of(), List.of(), new ArrayList<>(archives),
                    17, List.of());
        }
        Path packageRoot = packageRoot(directory, activeSource);
        Path root = findProjectRoot(directory, packageRoot);
        var warnings = new ArrayList<String>();
        var roots = new LinkedHashSet<Path>();
        var classpath = new LinkedHashSet<Path>();
        var archives = new LinkedHashSet<Path>();
        long revision = 17;

        revision = readPaths(root.resolve(".turboj/source-roots.txt"), root, roots, true,
                warnings, revision);
        addDirectory(roots, packageRoot);
        for (String relative : List.of("src/main/java", "src/test/java", "src"))
            addDirectory(roots, root.resolve(relative));
        // packageRoot already covers loose root-level sources. Recursively adding the whole
        // project would also index sibling Maven modules, caches and intentionally invalid fixtures.

        revision = readPaths(root.resolve(".turboj/classpath.txt"), root, classpath, false,
                warnings, revision);
        revision = readPaths(root.resolve("target/turboj-classpath.txt"), root, classpath, false,
                warnings, revision);
        revision = readPaths(root.resolve("build/turboj-classpath.txt"), root, classpath, false,
                warnings, revision);
        for (String relative : List.of("target/classes", "target/test-classes",
                "build/classes/java/main", "build/classes/java/test"))
            addDirectory(classpath, root.resolve(relative));

        revision = readPaths(root.resolve(".turboj/source-archives.txt"), root, archives, false,
                warnings, revision);
        for (Path entry : classpath) {
            String name = entry.getFileName().toString();
            if (name.endsWith(".jar") && !name.endsWith("-sources.jar"))
                addFile(archives, entry.resolveSibling(name.substring(0, name.length() - 4)
                        + "-sources.jar"));
        }
        discoverJdkSources(archives);
        for (Path p : roots) revision = fingerprint(revision, p);
        for (Path p : classpath) revision = fingerprint(revision, p);
        for (Path p : archives) revision = fingerprint(revision, p);
        for (String name : List.of("pom.xml", "build.gradle", "build.gradle.kts", "settings.gradle",
                "settings.gradle.kts")) revision = fingerprint(revision, root.resolve(name));
        return new Config(root, new ArrayList<>(roots), new ArrayList<>(classpath),
                new ArrayList<>(archives), revision, warnings);
    }

    private static Path packageRoot(Path directory, String source) {
        // This is structural package extraction, not a second semantic engine.
        ASTParser parser = ASTParser.newParser(AST.JLS25);
        parser.setSource(source.toCharArray());
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        CompilationUnit unit = (CompilationUnit) parser.createAST(null);
        if (unit.getPackage() == null) return directory;
        String[] parts = unit.getPackage().getName().getFullyQualifiedName().split("\\.");
        Path candidate = directory;
        for (int i = parts.length - 1; i >= 0; i--) {
            if (candidate == null || candidate.getFileName() == null
                    || !candidate.getFileName().toString().equals(parts[i])) return directory;
            candidate = candidate.getParent();
        }
        return candidate == null ? directory : candidate;
    }

    private static Path findProjectRoot(Path directory, Path fallback) {
        Path candidate = directory;
        for (int depth = 0; candidate != null && depth < 32; depth++, candidate = candidate.getParent()) {
            for (String marker : List.of(".turboj", "pom.xml", "build.gradle", "build.gradle.kts",
                    "settings.gradle", "settings.gradle.kts", ".git"))
                if (Files.exists(candidate.resolve(marker))) return candidate;
        }
        return fallback;
    }

    private static long readPaths(Path config, Path base, Set<Path> entries, boolean directories,
                                  List<String> warnings, long revision) {
        revision = fingerprint(revision, config);
        if (!Files.exists(config)) return revision;
        try {
            if (Files.size(config) > MAX_CONFIG_BYTES) throw new IOException("exceeds 64 KiB");
            String text = Files.readString(config);
            revision = revision * 31 + text.hashCode();
            int count = 0;
            for (String line : text.split("\\R")) {
                if (line.isBlank() || line.stripLeading().startsWith("#")) continue;
                if (line.contains("://")) throw new IOException("only local paths are supported");
                // Maven's dependency:build-classpath writes the platform-separated format.
                for (String part : line.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
                    if (++count > MAX_ENTRIES) throw new IOException("exceeds 4096 entries");
                    String value = part.strip();
                    if (value.isEmpty()) continue;
                    if (value.indexOf('\0') >= 0 || value.indexOf('*') >= 0
                            || value.contains("://")) throw new IOException("invalid local path");
                    Path path = base.resolve(value).toAbsolutePath().normalize();
                    if ((directories && Files.isDirectory(path))
                            || (!directories && (Files.isDirectory(path) || Files.isRegularFile(path)))) {
                        entries.add(path);
                    } else warnings.add("Missing local path in " + config.getFileName() + ": " + value);
                }
            }
        } catch (IOException | RuntimeException e) {
            warnings.add("Cannot read " + config.getFileName() + ": " + e.getMessage());
        }
        return revision;
    }

    private static void discoverJdkSources(Set<Path> archives) {
        Path runningHome = Path.of(System.getProperty("java.home"));
        addFile(archives, runningHome.resolve("lib/src.zip"));
        addFile(archives, runningHome.resolve("src.zip"));
        String configured = System.getProperty("turboj.javaHome", "");
        if (!configured.isBlank()) addJdkSources(archives, Path.of(configured));
        String environment = System.getenv("JAVA_HOME");
        if (environment != null && !environment.isBlank()) addJdkSources(archives, Path.of(environment));
        // Bundled jlink runtimes omit src.zip. Inspect installed matching JDKs without launching tools.
        for (Path location : List.of(Path.of(System.getProperty("user.home"), "Library", "Java",
                "JavaVirtualMachines"), Path.of("/Library/Java/JavaVirtualMachines"))) {
            if (!Files.isDirectory(location)) continue;
            try (var children = Files.list(location)) {
                children.filter(Files::isDirectory).sorted().limit(32)
                        .forEach(p -> addJdkSources(archives, p.resolve("Contents/Home")));
            } catch (IOException ignored) {
                // Missing source is an explicit declaration fallback, not an editor failure.
            }
        }
    }

    private static void addJdkSources(Set<Path> archives, Path home) {
        try {
            Path release = home.resolve("release");
            if (!Files.isRegularFile(release) || Files.size(release) > MAX_CONFIG_BYTES) return;
            String releaseText = Files.readString(release);
            String feature = Integer.toString(Runtime.version().feature());
            if (!java.util.regex.Pattern.compile("(?m)^JAVA_VERSION=\"" + feature + "(?:[.\"+\\-])")
                    .matcher(releaseText).find()) return;
            addFile(archives, home.resolve("lib/src.zip"));
        } catch (IOException | RuntimeException ignored) {
            // An inaccessible installation does not disable semantic assistance.
        }
    }

    private static void addDirectory(Set<Path> paths, Path path) {
        if (Files.isDirectory(path)) paths.add(path.toAbsolutePath().normalize());
    }

    private static void addFile(Set<Path> paths, Path path) {
        if (Files.isRegularFile(path)) paths.add(path.toAbsolutePath().normalize());
    }

    private static long fingerprint(long hash, Path path) {
        hash = hash * 31 + path.toString().hashCode();
        try {
            BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class);
            hash = hash * 31 + attributes.lastModifiedTime().toMillis();
            hash = hash * 31 + attributes.size();
        } catch (IOException ignored) {
            hash *= 31;
        }
        return hash;
    }
}
