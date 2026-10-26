package dev.turboj.project;

import dev.turboj.mavenj.workspace.PomEdits;
import dev.turboj.refactorj.api.Model.*;
import dev.turboj.refactorj.api.Operation;
import dev.turboj.turbotest.JUnitRuntime;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;
import javax.lang.model.SourceVersion;

/** Project creation and generation preparation. Invoke on a worker; preparation never edits sources. */
public final class FirstLaunchProjects {
    public static final String JUNIT_VERSION = "5.13.4";
    public static final String JUNIT_MARKER = ".turboj/junit.properties";
    private static JUnitRuntime bundledJUnit;
    private FirstLaunchProjects() {}

    public enum Kind { JAVA, MAVEN }
    /** An empty mainClass creates no source; otherwise the project opens on that class's empty main. */
    public record ProjectSpec(Path workspace, String name, Path location, Kind kind, String groupId,
                              String artifactId, String release, String mainClass) {
        public ProjectSpec(Path workspace, String name, Path location, Kind kind, String groupId, String artifactId, String release) {
            this(workspace, name, location, kind, groupId, artifactId, release, "");
        }
    }
    /** initialCaret is an offset in initialFile, or -1 to keep the editor's default position. */
    public record ProjectResult(Path root, List<Path> sourceRoots, Path initialFile, boolean maven, int initialCaret) {}

    /** Folder for a new project: HelloWorld → hello-world, "My App" → my-app. */
    public static String directoryName(String projectName) {
        String spaced = projectName.strip().replaceAll("([a-z0-9])([A-Z])", "$1-$2").replaceAll("([A-Z]+)([A-Z][a-z])", "$1-$2");
        String folder = spaced.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]+", "-").replaceAll("-{2,}", "-")
                .replaceAll("^[-.]+|[-.]+$", "");
        return folder.isEmpty() ? projectName.strip() : folder;
    }

    /** Main class named after the project: HelloWorld → HelloWorld, hello-world → HelloWorld; empty when no Java name fits. */
    public static String mainClassName(String projectName) {
        StringBuilder name = new StringBuilder();
        for (String word : projectName.strip().split("[^\\p{javaJavaIdentifierPart}]+|_"))
            if (!word.isEmpty()) name.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        String value = name.toString();
        try { requireTypeName(value); return value; } catch (IllegalArgumentException invalid) { return ""; }
    }

    /** Maven main package from the coordinates: example + hello-world → example.helloworld. */
    public static String mavenPackage(String groupId, String artifactId) {
        String artifact = artifactId.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", "");
        String name = artifact.isEmpty() ? groupId : groupId + "." + artifact;
        return SourceVersion.isName(name) ? name : SourceVersion.isName(groupId) ? groupId : "";
    }
    public record MainSpec(Path sourceRoot, String packageName, String className, boolean mainMethod) {}
    public record TestContext(WorkspaceSnapshot snapshot, Path projectRoot, Path testRoot, Path sourceFile,
                              String packageName, String className, String qualifiedName,
                              List<String> methods, boolean maven, boolean junitAvailable, String pomText,
                              String junitDependencyArtifact, String junitDependencyVersion) {
        public TestContext { methods = List.copyOf(methods); }
    }
    public record TestSpec(Path testRoot, String packageName, String className, String methodName,
                           boolean beforeEach, boolean afterEach, boolean beforeAll, boolean afterAll,
                           boolean addJUnit) {}
    public record GeneratedSource(Plan plan, Path file, int start, int end, boolean configurationChanged) {}

    /** Creates only a new directory; existing projects, including empty directories, are never replaced. */
    public static ProjectResult createProject(ProjectSpec spec) throws IOException {
        Objects.requireNonNull(spec); requireSimpleName(spec.name(), "Project name");
        if (!spec.release().equals("25")) throw new IllegalArgumentException("This Java project template uses the TurboJ Java 25 compiler.");
        if (spec.kind() == Kind.MAVEN) { requireCoordinate(spec.groupId()); requireCoordinate(spec.artifactId()); }
        Path workspace = spec.workspace().toAbsolutePath().normalize();
        Files.createDirectories(workspace);
        Path root = spec.location().toAbsolutePath().normalize();
        Path parent = root.getParent();
        if (parent == null || !Files.isDirectory(parent)) throw new IOException("Choose an existing parent directory for the project.");
        root = parent.toRealPath().resolve(root.getFileName());
        Files.createDirectory(root); // Exclusive claim: no overwrite, even if another creator raced us.
        List<Path> created = new ArrayList<>(); created.add(root);
        try {
            for (String directory : List.of("src", "src/main", "src/main/java", "src/test", "src/test/java", ".turboj")) {
                Path path = root.resolve(directory); Files.createDirectory(path); created.add(path);
            }
            writeNew(root.resolve(".turboj/source-roots.txt"), "src/main/java\nsrc/test/java\n", created);
            writeNew(root.resolve(".turboj/project.properties"), "kind=" + spec.kind().name().toLowerCase(Locale.ROOT)
                    + "\njava.release=25\nname=" + spec.name() + "\n", created);
            if (spec.kind() == Kind.MAVEN) writeNew(root.resolve("pom.xml"), mavenPom(spec), created);
            List<Path> sourceRoots = List.of(root.resolve("src/main/java"), root.resolve("src/test/java"));
            boolean maven = spec.kind() == Kind.MAVEN;
            if (spec.mainClass().isEmpty())
                return new ProjectResult(root, sourceRoots, maven ? root.resolve("pom.xml") : root.resolve(".turboj/project.properties"), maven, -1);
            requireTypeName(spec.mainClass());
            String pkg = maven ? mavenPackage(spec.groupId(), spec.artifactId()) : "";
            Path directory = root.resolve("src/main/java");
            for (String segment : pkg.isEmpty() ? new String[0] : pkg.split("\\.")) {
                directory = directory.resolve(segment); Files.createDirectory(directory); created.add(directory);
            }
            String text = packagePrefix(pkg) + "public class " + spec.mainClass() + " {\n\n"
                    + "    public static void main(String[] args) {\n        \n    }\n}\n";
            Path main = directory.resolve(spec.mainClass() + ".java");
            writeNew(main, text, created);
            return new ProjectResult(root, sourceRoots, main, maven, text.indexOf("{\n        \n") + 10);
        } catch (IOException | RuntimeException error) {
            // Delete only paths that this invocation created. Unrelated files inserted concurrently survive.
            Collections.reverse(created);
            for (Path path : created) try { Files.deleteIfExists(path); } catch (IOException rollback) { error.addSuppressed(rollback); }
            throw error;
        }
    }

    public static GeneratedSource mainPlan(WorkspaceSnapshot snapshot, MainSpec spec) {
        requireTypeName(spec.className()); requirePackage(spec.packageName());
        Path configuredRoot = spec.sourceRoot().toAbsolutePath().normalize();
        if (snapshot.sourceRoots().stream().map(path -> path.toAbsolutePath().normalize()).noneMatch(configuredRoot::equals))
            throw new IllegalArgumentException("Choose one of this project's configured Java source folders.");
        Path file = sourcePath(snapshot.root(), spec.sourceRoot(), spec.packageName(), spec.className());
        String declaration = "public class " + spec.className() + " {\n";
        String text = packagePrefix(spec.packageName()) + declaration;
        if (spec.mainMethod()) text += "    public static void main(String[] args) {\n        System.out.println(\"Hello World!\");\n    }\n";
        text += "}\n";
        int start = spec.mainMethod() ? text.indexOf("Hello World!") : text.indexOf('{') + 1;
        return generated(snapshot, file, text, start, spec.mainMethod() ? start + "Hello World!".length() : start,
                List.of(), List.of(), false, "Create Java class " + spec.className());
    }

    /** Uses semantic proposal identities and actual module configuration supplied by the host. */
    public static TestContext testContext(WorkspaceSnapshot snapshot, Map<String, String> parameters,
            Path projectRoot, Path testRoot, String currentPomText) throws Exception {
        Path root = projectRoot.toAbsolutePath().normalize();
        snapshot = projectSnapshot(snapshot, root);
        Path file = Path.of(required(parameters, "sourceFile")).toAbsolutePath().normalize();
        snapshot.source(file); // The proposal must still refer to this captured workspace.
        String className = required(parameters, "className"); requireTypeName(className);
        String packageName = parameters.getOrDefault("packageName", ""); requirePackage(packageName);
        var library = junitLibrary(snapshot, root, testRoot, currentPomText);
        List<String> methods = parameters.getOrDefault("methods", "").lines().filter(s -> !s.isBlank()).toList();
        return new TestContext(snapshot, root, testRoot.toAbsolutePath().normalize(), file, packageName, className,
                parameters.getOrDefault("qualifiedName", packageName.isEmpty() ? className : packageName + "." + className),
                methods, library.maven(), library.available(), currentPomText, library.artifact(), library.version());
    }

    /** How this project gets JUnit Jupiter: a POM test dependency for Maven, TurboJ's bundled library otherwise. */
    public record JUnitLibrary(Path projectRoot, boolean maven, boolean available, String pomText, String artifact, String version) {}

    public static JUnitLibrary junitLibrary(WorkspaceSnapshot snapshot, Path projectRoot, Path testRoot, String currentPomText) throws IOException {
        boolean maven = currentPomText != null;
        List<Path> classpath = new ArrayList<>(snapshot.classpath());
        // Select the actual test boundary, not a sibling module or the main-only classpath.
        snapshot.projects().stream().filter(project -> project.sourceRoots().stream().anyMatch(testRoot::startsWith))
                .forEach(project -> classpath.addAll(project.classpath()));
        var libraries = jupiterLibraries(classpath);
        boolean available = libraries.api() && libraries.engine();
        String dependencyArtifact = libraries.api() ? "junit-jupiter-engine" : "junit-jupiter";
        String dependencyVersion = libraries.apiVersion().isEmpty() ? JUNIT_VERSION : libraries.apiVersion();
        if (!available && libraries.api() && libraries.apiVersion().isEmpty())
            throw new IllegalStateException("The project has a JUnit Jupiter API without an engine, but its version cannot be established. Configure a matching JUnit engine before creating the test.");
        if (!available && !maven && libraries.api() && !JUNIT_VERSION.equals(libraries.apiVersion()))
            throw new IllegalStateException("The project uses JUnit Jupiter " + libraries.apiVersion()
                    + " without its engine. Configure the matching engine; TurboJ's bundled library is " + JUNIT_VERSION + ".");
        if (maven && !available && (declaresJupiter(currentPomText, dependencyArtifact) || declaresJupiter(currentPomText, "junit-jupiter")))
            throw new IllegalStateException("JUnit Jupiter is already declared, but its test runtime is unresolved. Reload Maven Project before creating the test.");
        return new JUnitLibrary(projectRoot.toAbsolutePath().normalize(), maven, available, currentPomText, dependencyArtifact, dependencyVersion);
    }

    /** Adds JUnit Jupiter to the project, together with the Java edits (such as the import) that need it. */
    public static GeneratedSource junitLibraryPlan(WorkspaceSnapshot snapshot, JUnitLibrary library, Path file,
            List<FileEdits> javaEdits) throws Exception {
        if (library.available()) throw new IllegalStateException("JUnit Jupiter is already on this project's build path.");
        snapshot = projectSnapshot(snapshot, library.projectRoot());
        List<FileEdits> edits = new ArrayList<>(javaEdits); List<FileOperation> additional = new ArrayList<>();
        snapshot = addJUnit(library, snapshot, edits, additional);
        Plan plan = new Plan(UUID.randomUUID().toString(), Operation.ADD_IMPORT, snapshot, List.of(), edits, additional,
                List.of(), ValidationStatus.VALID, "Add JUnit 5 library to the build path");
        return new GeneratedSource(plan, file, -1, -1, true);
    }

    // The Java model may use the common source ancestor (<project>/src). Library/POM edits
    // belong to the enclosing project transaction, without widening source discovery.
    private static WorkspaceSnapshot projectSnapshot(WorkspaceSnapshot snapshot, Path root) {
        if (!snapshot.root().startsWith(root) || snapshot.root().equals(root)) return snapshot;
        return new WorkspaceSnapshot(root, snapshot.version(), snapshot.sources(), snapshot.sourceRoots(), snapshot.classpath(), snapshot.projects());
    }

    private static WorkspaceSnapshot addJUnit(JUnitLibrary library, WorkspaceSnapshot snapshot, List<FileEdits> edits,
            List<FileOperation> additional) throws Exception {
        if (library.maven()) {
            Path pom = library.projectRoot().resolve("pom.xml");
            var update = PomEdits.addDependency(library.pomText(), "org.junit.jupiter", library.artifact(), library.version(), "test");
            var sources = new ArrayList<>(snapshot.sources());
            var previous = sources.stream().filter(source -> source.file().equals(pom)).findFirst();
            if (previous.isPresent() && !previous.get().text().equals(library.pomText()))
                throw new IllegalStateException("POM changed while the JUnit dialog was open.");
            if (previous.isEmpty()) sources.add(new SourceSnapshot(pom, library.pomText(), 0, false));
            edits.add(new FileEdits(pom, update.edits().stream().map(edit -> new TextEdit(edit.start(), edit.end(), edit.text())).toList()));
            return new WorkspaceSnapshot(snapshot.root(), snapshot.version(), sources, snapshot.sourceRoots(), snapshot.classpath(), snapshot.projects());
        }
        bundledJUnitClasspath(); // Verify the installed distribution before preparing a dependency marker.
        Path marker = library.projectRoot().resolve(JUNIT_MARKER);
        if (Files.exists(marker, LinkOption.NOFOLLOW_LINKS))
            throw new IllegalStateException("The JUnit configuration changed; refresh the project and try again.");
        additional.add(new FileOperation(FileOperationKind.CREATE, marker, null, "runtime=bundled-jupiter-" + JUNIT_VERSION + "\n"));
        return snapshot;
    }

    public static GeneratedSource testPlan(TestContext context, TestSpec spec) throws Exception {
        requireTypeName(spec.className()); requirePackage(spec.packageName()); requireIdentifier(spec.methodName(), "Test method");
        if (!context.junitAvailable() && !spec.addJUnit())
            throw new IllegalArgumentException("JUnit Jupiter is missing. Add the JUnit library before creating this test.");
        Path file = sourcePath(context.snapshot().root(), spec.testRoot(), spec.packageName(), spec.className());
        if (!spec.testRoot().toAbsolutePath().normalize().equals(context.testRoot()))
            throw new IllegalArgumentException("Use this project's configured test source root.");
        List<FileEdits> edits = new ArrayList<>(); List<FileOperation> additional = new ArrayList<>();
        WorkspaceSnapshot snapshot = context.snapshot(); boolean changed = false;
        if (!context.junitAvailable()) {
            changed = true;
            snapshot = addJUnit(new JUnitLibrary(context.projectRoot(), context.maven(), false, context.pomText(),
                    context.junitDependencyArtifact(), context.junitDependencyVersion()), snapshot, edits, additional);
        }
        StringBuilder text = new StringBuilder(packagePrefix(spec.packageName()));
        text.append("import static org.junit.jupiter.api.Assertions.*;\n\nimport org.junit.jupiter.api.Test;\n");
        if (spec.beforeEach()) text.append("import org.junit.jupiter.api.BeforeEach;\n");
        if (spec.afterEach()) text.append("import org.junit.jupiter.api.AfterEach;\n");
        if (spec.beforeAll()) text.append("import org.junit.jupiter.api.BeforeAll;\n");
        if (spec.afterAll()) text.append("import org.junit.jupiter.api.AfterAll;\n");
        text.append("\nclass ").append(spec.className()).append(" {\n");
        if (spec.beforeAll()) lifecycle(text, "BeforeAll", "static void setUpBeforeClass");
        if (spec.afterAll()) lifecycle(text, "AfterAll", "static void tearDownAfterClass");
        if (spec.beforeEach()) lifecycle(text, "BeforeEach", "void setUp");
        if (spec.afterEach()) lifecycle(text, "AfterEach", "void tearDown");
        text.append("    @Test\n    void ").append(spec.methodName()).append("() {\n        fail(\"Not yet implemented\");\n    }\n}\n");
        int start = text.indexOf("fail(\"Not yet implemented\");");
        return generated(snapshot, file, text.toString(), start, start + "fail(\"Not yet implemented\");".length(),
                edits, additional, changed, "Create JUnit test for " + context.qualifiedName());
    }

    /** Stable process-owned paths. Persist the intention, never temporary extraction paths. */
    public static synchronized List<Path> bundledJUnitClasspath() throws Exception {
        if (bundledJUnit == null) {
            bundledJUnit = new JUnitRuntime();
            Runtime.getRuntime().addShutdownHook(new Thread(() -> { synchronized (FirstLaunchProjects.class) {
                if (bundledJUnit != null) bundledJUnit.close();
            } }, "turboj-project-junit-cleanup"));
        }
        return bundledJUnit.compilationClasspath();
    }

    public static boolean hasJupiter(Collection<Path> classpath) throws IOException {
        return jupiterLibraries(classpath).api();
    }

    private record JupiterLibraries(boolean api, boolean engine, String apiVersion) {}
    private static JupiterLibraries jupiterLibraries(Collection<Path> classpath) throws IOException {
        boolean api = false, engine = false; String version = "";
        for (Path path : new LinkedHashSet<>(classpath)) {
            if (Files.isDirectory(path)) {
                api |= Files.isRegularFile(path.resolve("org/junit/jupiter/api/Test.class"));
                engine |= Files.isRegularFile(path.resolve("org/junit/jupiter/engine/JupiterTestEngine.class"));
            } else if (Files.isRegularFile(path) && path.getFileName().toString().endsWith(".jar")) {
                try (JarFile jar = new JarFile(path.toFile())) {
                    boolean containsApi = jar.getEntry("org/junit/jupiter/api/Test.class") != null;
                    api |= containsApi; engine |= jar.getEntry("org/junit/jupiter/engine/JupiterTestEngine.class") != null;
                    if (containsApi && version.isEmpty()) {
                        var manifest = jar.getManifest();
                        if (manifest != null) {
                            String declared = manifest.getMainAttributes().getValue("Bundle-Version");
                            if (declared == null) declared = manifest.getMainAttributes().getValue("Implementation-Version");
                            if (declared != null && declared.matches("[0-9]+(?:[.][0-9A-Za-z_-]+)*")) version = declared;
                        }
                    }
                }
            }
        }
        return new JupiterLibraries(api, engine, version);
    }

    private static GeneratedSource generated(WorkspaceSnapshot snapshot, Path file, String text, int start, int end,
            List<FileEdits> edits, List<FileOperation> additional, boolean configurationChanged, String description) {
        if (snapshot.sources().stream().anyMatch(source -> source.file().equals(file)) || Files.exists(file, LinkOption.NOFOLLOW_LINKS))
            throw new IllegalArgumentException("A source file with this name already exists.");
        List<FileOperation> operations = new ArrayList<>(additional);
        operations.add(new FileOperation(FileOperationKind.CREATE, file, null, text));
        Plan plan = new Plan(UUID.randomUUID().toString(), Operation.CREATE_CLASS, snapshot, List.of(), edits, operations,
                List.of(), ValidationStatus.VALID, description);
        return new GeneratedSource(plan, file, start, end, configurationChanged);
    }

    private static Path sourcePath(Path projectRoot, Path sourceRoot, String packageName, String type) {
        Path root = sourceRoot.toAbsolutePath().normalize();
        if (!root.startsWith(projectRoot))
            throw new IllegalArgumentException("Choose a source root inside this project.");
        return root.resolve(packageName.replace('.', '/')).resolve(type + ".java");
    }
    private static String packagePrefix(String name) { return name.isEmpty() ? "" : "package " + name + ";\n\n"; }
    private static boolean declaresJupiter(String pom, String artifact) {
        var elements = PomEdits.elements(pom);
        return elements.stream().filter(element -> element.path().equals(List.of("project", "dependencies", "dependency")))
                .anyMatch(dependency -> elements.stream().anyMatch(element -> element.start() > dependency.start() && element.end() < dependency.end()
                        && element.path().equals(List.of("project", "dependencies", "dependency", "groupId")) && element.text().equals("org.junit.jupiter"))
                    && elements.stream().anyMatch(element -> element.start() > dependency.start() && element.end() < dependency.end()
                        && element.path().equals(List.of("project", "dependencies", "dependency", "artifactId")) && element.text().equals(artifact)));
    }
    private static void lifecycle(StringBuilder text, String annotation, String declaration) {
        text.append("    @").append(annotation).append("\n    ").append(declaration).append("() {\n    }\n\n");
    }
    private static String required(Map<String, String> map, String key) {
        String value = map.get(key); if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing semantic context: " + key); return value;
    }
    private static void requireSimpleName(String name, String label) {
        if (name == null || name.isBlank() || name.equals(".") || name.equals("..") || !name.equals(name.strip())
                || name.chars().anyMatch(c -> c < 32 || c == '/' || c == '\\' || c == ':' || c == '='))
            throw new IllegalArgumentException(label + " must be a single folder name.");
    }
    private static void requireCoordinate(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_][A-Za-z0-9_.-]*")) throw new IllegalArgumentException("Invalid Maven coordinate.");
    }
    private static void requireTypeName(String name) {
        requireIdentifier(name, "Class name");
        if (Set.of("var", "yield", "record", "sealed", "permits").contains(name)) throw new IllegalArgumentException("This Java name cannot identify a class.");
    }
    private static void requireIdentifier(String name, String label) {
        if (name == null || !SourceVersion.isIdentifier(name) || SourceVersion.isKeyword(name)) throw new IllegalArgumentException(label + " must be a Java identifier.");
    }
    private static void requirePackage(String name) {
        if (name == null || !name.isEmpty() && !SourceVersion.isName(name)) throw new IllegalArgumentException("Invalid Java package.");
    }
    private static void writeNew(Path file, String text, List<Path> created) throws IOException {
        Files.writeString(file, text, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE); created.add(file);
    }
    private static String mavenPom(ProjectSpec spec) {
        return """
                <project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
                         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
                    <modelVersion>4.0.0</modelVersion>
                    <groupId>%s</groupId>
                    <artifactId>%s</artifactId>
                    <version>1.0-SNAPSHOT</version>
                    <properties>
                        <maven.compiler.release>25</maven.compiler.release>
                        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
                    </properties>
                    <build><plugins>
                        <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId><version>3.14.1</version></plugin>
                        <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-surefire-plugin</artifactId><version>3.5.4</version></plugin>
                    </plugins></build>
                </project>
                """.formatted(spec.groupId(), spec.artifactId());
    }
}
