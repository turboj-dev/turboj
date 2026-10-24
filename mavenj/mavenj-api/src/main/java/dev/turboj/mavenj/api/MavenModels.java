package dev.turboj.mavenj.api;

import java.nio.file.Path;
import java.util.*;

/** Immutable Maven project data. Paths are absolute; no compiler or Maven internals escape. */
public final class MavenModels {
    private MavenModels() {}

    public enum Severity {
        INFO,
        WARNING,
        ERROR
    }

    public enum ClasspathScope {
        MAIN_COMPILE,
        TEST_COMPILE,
        MAIN_RUNTIME,
        TEST_RUNTIME,
        PROCESSOR
    }

    public enum FastPath {
        SUPPORTED,
        PARTIAL,
        MAVEN_REQUIRED
    }

    public record Diagnostic(Severity severity, String code, String message, Path file, int line,
            int column) {
        public Diagnostic {
            Objects.requireNonNull(severity);
            Objects.requireNonNull(code);
            Objects.requireNonNull(message);
        }

        public static Diagnostic error(String code, String message, Path file) {
            return new Diagnostic(Severity.ERROR, code, message, file, -1, -1);
        }

        public static Diagnostic warning(String code, String message, Path file) {
            return new Diagnostic(Severity.WARNING, code, message, file, -1, -1);
        }
    }

    public record Coordinates(String groupId, String artifactId, String version, String type,
            String classifier) {
        public Coordinates {
            groupId = Objects.requireNonNull(groupId);
            artifactId = Objects.requireNonNull(artifactId);
            version = Objects.requireNonNullElse(version, "");
            type = type == null || type.isBlank() ? "jar" : type;
            classifier = Objects.requireNonNullElse(classifier, "");
        }

        public String gav() {
            return groupId + ":" + artifactId + ":" + version;
        }

        public String key() {
            return groupId + ":" + artifactId + ":" + type + ":" + classifier + ":" + version;
        }
    }

    public record Repository(String id, String url, boolean releases, boolean snapshots,
            String releaseUpdatePolicy, String snapshotUpdatePolicy) {}

    public record ResourceRoot(Path directory, String targetPath, boolean filtering,
            List<String> includes, List<String> excludes) {
        public ResourceRoot {
            includes = List.copyOf(includes);
            excludes = List.copyOf(excludes);
            targetPath = Objects.requireNonNullElse(targetPath, "");
        }
    }

    public record ProcessorConfiguration(List<Coordinates> dependencies, List<Path> path,
            List<Path> modulePath, List<String> processors, Map<String, String> options,
            Path generatedSources, boolean requested) {
        public ProcessorConfiguration {
            dependencies = List.copyOf(dependencies);
            path = List.copyOf(path);
            modulePath = List.copyOf(modulePath);
            processors = List.copyOf(processors);
            options = Map.copyOf(options);
        }

        public static ProcessorConfiguration none(Path generated) {
            return new ProcessorConfiguration(List.of(), List.of(), List.of(), List.of(), Map.of(),
                    generated, false);
        }
    }

    public record JavaConfiguration(String release, String source, String target, String encoding,
            Path javaHome, Map<String, String> toolchainRequirements,
            List<String> compilerArguments, ProcessorConfiguration processors, boolean preview) {
        public JavaConfiguration {
            release = Objects.requireNonNullElse(release, "");
            source = Objects.requireNonNullElse(source, "");
            target = Objects.requireNonNullElse(target, "");
            encoding = Objects.requireNonNullElse(encoding, "UTF-8");
            toolchainRequirements = Map.copyOf(toolchainRequirements);
            compilerArguments = List.copyOf(compilerArguments);
            Objects.requireNonNull(processors);
        }

        public String languageLevel() {
            return !release.isBlank() ? release : source;
        }

        public JavaConfiguration withJavaHome(Path home) {
            return new JavaConfiguration(release, source, target, encoding, home,
                    toolchainRequirements, compilerArguments, processors, preview);
        }
    }

    public record Dependency(Coordinates coordinates, String scope, boolean optional,
            List<String> exclusions, Path file, String reactorModule, List<String> children) {
        public Dependency {
            exclusions = List.copyOf(exclusions);
            children = List.copyOf(children);
            scope = Objects.requireNonNullElse(scope, "compile");
            reactorModule = Objects.requireNonNullElse(reactorModule, "");
        }
    }

    public record ReactorDependency(String moduleId, String scope, boolean testArtifact) {}

    public record Classpaths(Map<ClasspathScope, List<Path>> classpaths, Map<ClasspathScope,
            List<Path>> modulePaths, boolean resolved) {
        public Classpaths {
            classpaths = immutablePaths(classpaths);
            modulePaths = immutablePaths(modulePaths);
        }

        public List<Path> forScope(ClasspathScope scope) {
            return classpaths.getOrDefault(scope, List.of());
        }

        public List<Path> modulesForScope(ClasspathScope scope) {
            return modulePaths.getOrDefault(scope, List.of());
        }

        private static Map<ClasspathScope, List<Path>> immutablePaths(Map<ClasspathScope,
                List<Path>> input) {
            var result = new EnumMap<ClasspathScope, List<Path>> (ClasspathScope.class);
            input.forEach((k, v) -> result.put(k, List.copyOf(v)));
            return Collections.unmodifiableMap(result);
        }
    }

    public record Module(String id, Coordinates coordinates, Path directory, Path pom,
            String packaging, String parentId, List<String> childModuleIds, List<Path> sourceRoots,
            List<Path> testRoots, List<Path> generatedSourceRoots, List<Path> generatedTestRoots,
            List<ResourceRoot> resources, List<ResourceRoot> testResources, Path outputDirectory,
            Path testOutputDirectory, JavaConfiguration mainJava, JavaConfiguration testJava,
            List<Dependency> dependencies, List<ReactorDependency> reactorDependencies,
            Classpaths classpaths, List<Repository> repositories, List<String> activeProfiles,
            Map<String, String> properties, String effectivePom, FastPath fastPath,
            List<Diagnostic> diagnostics) {
        public Module {
            Objects.requireNonNull(id);
            Objects.requireNonNull(coordinates);
            Objects.requireNonNull(directory);
            Objects.requireNonNull(pom);
            parentId = Objects.requireNonNullElse(parentId, "");
            childModuleIds = List.copyOf(childModuleIds);
            sourceRoots = List.copyOf(sourceRoots);
            testRoots = List.copyOf(testRoots);
            generatedSourceRoots = List.copyOf(generatedSourceRoots);
            generatedTestRoots = List.copyOf(generatedTestRoots);
            resources = List.copyOf(resources);
            testResources = List.copyOf(testResources);
            dependencies = List.copyOf(dependencies);
            reactorDependencies = List.copyOf(reactorDependencies);
            repositories = List.copyOf(repositories);
            activeProfiles = List.copyOf(activeProfiles);
            properties = Map.copyOf(properties);
            diagnostics = List.copyOf(diagnostics);
        }

        public Module withJavaConfigurations(JavaConfiguration main, JavaConfiguration test) {
            return new Module(id, coordinates, directory, pom, packaging, parentId, childModuleIds,
                    sourceRoots, testRoots, generatedSourceRoots, generatedTestRoots, resources,
                    testResources, outputDirectory, testOutputDirectory, main, test, dependencies,
                    reactorDependencies, classpaths, repositories, activeProfiles, properties,
                    effectivePom, fastPath, diagnostics);
        }

        public boolean contains(Path file) {
            Path path = file.toAbsolutePath().normalize();
            return java.util.stream.Stream.of(sourceRoots, testRoots, generatedSourceRoots,
                    generatedTestRoots).flatMap(Collection::stream).anyMatch(path::startsWith);
        }

        public boolean isTest(Path file) {
            Path path = file.toAbsolutePath().normalize();
            return java.util.stream.Stream.concat(testRoots.stream(), generatedTestRoots.stream())
                    .anyMatch(path::startsWith);
        }
    }

    public record Workspace(Path root, Path rootPom, long generation, List<Module> modules,
            List<String> buildOrder, List<Path> observedFiles, String fingerprint,
            Path localRepository, List<String> activeProfiles, boolean offline,
            List<Diagnostic> diagnostics, long durationNanos, boolean cacheHit, Path userToolchains,
            Path globalToolchains) {
        public Workspace {
            modules = List.copyOf(modules);
            buildOrder = List.copyOf(buildOrder);
            observedFiles = List.copyOf(observedFiles);
            activeProfiles = List.copyOf(activeProfiles);
            diagnostics = List.copyOf(diagnostics);
        }

        public Workspace(Path root, Path rootPom, long generation, List<Module> modules,
                List<String> buildOrder, List<Path> observedFiles, String fingerprint,
                Path localRepository, List<String> activeProfiles, boolean offline,
                List<Diagnostic> diagnostics, long durationNanos, boolean cacheHit) {
            this(root, rootPom, generation, modules, buildOrder, observedFiles, fingerprint,
                    localRepository, activeProfiles, offline, diagnostics, durationNanos, cacheHit,
                    null, null);
        }

        public Optional<Module> module(String id) {
            return modules.stream().filter(m -> m.id().equals(id)).findFirst();
        }

        public Optional<Module> moduleFor(Path file) {
            return modules.stream().filter(m -> m.contains(file) || m.pom().equals(file
                    .toAbsolutePath().normalize())).max(Comparator.comparingInt(m -> m.directory()
                    .getNameCount()));
        }

        public boolean resolved() {
            return diagnostics.stream().noneMatch(d -> d.severity() == Severity.ERROR) && modules
                    .stream().allMatch(m -> m.classpaths().resolved() && m.diagnostics().stream()
                    .noneMatch(d -> d.severity() == Severity.ERROR));
        }

        public Workspace withModules(List<Module> updated) {
            return new Workspace(root, rootPom, generation, updated, buildOrder, observedFiles,
                    fingerprint, localRepository, activeProfiles, offline, diagnostics,
                    durationNanos, cacheHit, userToolchains, globalToolchains);
        }
    }

    /** Empty repository permission set means no remote repository access, even if offline=false. */
    public record ImportRequest(Path project, Path userSettings, Path globalSettings,
            Path localRepository, Set<String> activeProfiles, Set<String> inactiveProfiles,
            Map<String, String> userProperties, boolean offline, Set<String> allowedRepositoryUrls,
            boolean refreshSnapshots, Path javaHome, Map<Path, String> pomOverlays) {
        public ImportRequest {
            project = project.toAbsolutePath().normalize();
            activeProfiles = Set.copyOf(activeProfiles);
            inactiveProfiles = Set.copyOf(inactiveProfiles);
            userProperties = Map.copyOf(userProperties);
            allowedRepositoryUrls = Set.copyOf(allowedRepositoryUrls);
            pomOverlays = Map.copyOf(pomOverlays);
        }

        public static ImportRequest offline(Path project) {
            return new ImportRequest(project, null, null, null, Set.of(), Set.of(), Map.of(), true,
                    Set.of(), false, null, Map.of());
        }

        public ImportRequest refreshing() {
            return new ImportRequest(project, userSettings, globalSettings, localRepository,
                    activeProfiles, inactiveProfiles, userProperties, offline,
                    allowedRepositoryUrls, true, javaHome, pomOverlays);
        }
    }

    public record Attachment(Coordinates coordinates, String classifier, Path file,
            List<Diagnostic> diagnostics) {
        public Attachment {
            diagnostics = List.copyOf(diagnostics);
        }
    }
}
