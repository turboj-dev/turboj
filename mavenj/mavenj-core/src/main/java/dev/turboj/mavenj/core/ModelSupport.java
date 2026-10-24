package dev.turboj.mavenj.core;

import dev.turboj.mavenj.api.MavenModels.*;
import dev.turboj.mavenj.api.MavenModels.Module;
import org.apache.maven.model.*;
import org.apache.maven.model.building.*;
import org.apache.maven.model.resolution.*;
import org.apache.maven.model.io.xpp3.MavenXpp3Reader;
import org.apache.maven.model.io.xpp3.MavenXpp3Writer;
import org.codehaus.plexus.util.xml.Xpp3Dom;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.resolution.VersionRangeRequest;
import org.eclipse.aether.resolution.VersionRangeResolutionException;
import java.nio.file.*;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class ModelSupport {
    final SettingsSupport settings;
    final ResolverSupport resolver;
    final Set<Path> observed;
    final Map<Path, Model> models = new LinkedHashMap<> ();
    final Map<Path, ModelBuildingResult> results = new LinkedHashMap<> ();
    final Map<String, Path> candidates = new LinkedHashMap<> ();
    final List<Diagnostic> diagnostics = new ArrayList<> ();
    final ModelBuilder builder = new DefaultModelBuilderFactory().newInstance();
    final ImportCache cache;
    final String configuration;
    final boolean forceRefresh;
    final Path allowedProjectRoot;
    Set<Path> currentInputs;
    boolean hasVersionRanges;
    boolean currentVersionRanges;
    boolean hasSnapshotModels;
    boolean currentSnapshotModels;

    ModelSupport(SettingsSupport settings, ResolverSupport resolver, Set<Path> observed,
            ImportCache cache, boolean forceRefresh, Path allowedProjectRoot) {
        this.allowedProjectRoot = allowedProjectRoot;
        this.settings = settings;
        this.resolver = resolver;
        this.observed = observed;
        this.cache = cache;
        this.configuration = ImportCache.configuration(settings);
        this.forceRefresh = forceRefresh;
    }

    void discover(Path pom) {
        discoverCandidates(pom, new HashSet<> ());
        build(pom, new HashSet<> ());
    }

    private void discoverCandidates(Path pom, Set<Path> seen) {
        pom = pom.toAbsolutePath().normalize();
        try {
            new Source(pom).checkAccess();
        } catch (IOException e) {
            return;
        }
        if (!seen.add(pom) || !exists(pom))
            return;
        observed.add(pom);
        resolver.cancellation.check();
        try (InputStream stream = new Source(pom).getInputStream()) {
            Model raw = new MavenXpp3Reader().read(stream);
            var props = new Properties();
            props.putAll(raw.getProperties());
            props.putAll(settings.user);
            String group = raw.getGroupId() != null ? raw.getGroupId() : raw.getParent()
                    != null ? raw.getParent().getGroupId() : "";
            String version = raw.getVersion() != null ? raw.getVersion() : raw.getParent()
                    != null ? raw.getParent().getVersion() : "";
            candidates.put(interpolate(group, props) + ":" + raw.getArtifactId() + ":"
                    + interpolate(version, props), pom);
            var modules = new LinkedHashSet<> (raw.getModules());
            raw.getProfiles().forEach(p -> modules.addAll(p.getModules()));
            for (String child : modules) {
                String path = interpolate(child, props);

                if (!path.contains("${"))
                    discoverCandidates(modulePom(pom, path), seen);
            }
        } catch (Exception ignored) {
            /* The official effective-model builder reports precise diagnostics below. */
        }
    }

    private void build(Path pom, Set<Path> visiting) {
        pom = pom.toAbsolutePath().normalize();
        if (models.containsKey(pom))
            return;
        resolver.cancellation.check();
        if (!visiting.add(pom)) {
            diagnostics.add(Diagnostic.error("reactor.aggregation.cycle",
                    "Cyclic module aggregation", pom));
            return;
        }
        try {
            new Source(pom).checkAccess();
        } catch (IOException e) {
            diagnostics.add(Diagnostic.error("pom.root.denied", e.getMessage(), pom));
            visiting.remove(pom);
            return;
        }
        observed.add(pom);
        if (!exists(pom)) {
            diagnostics.add(Diagnostic.error("pom.missing", "Maven module POM does not exist",
                    pom));
            visiting.remove(pom);
            return;
        }
        var cached = cache.effective.get(pom);

        if (!forceRefresh && cached != null && inputsAllowed(cached.inputs()) && cached
                .configuration().equals(configuration) && cached.inputsHash().equals(ImportCache
                .hash("", settings.request.pomOverlays(), cached.inputs()))) {
            cache.modelHits++;
            Model model = cached.model().clone();
            models.put(pom, model);
            results.put(pom, cached.result());
            observed.addAll(cached.inputs());
            diagnostics.addAll(cached.diagnostics());
            hasVersionRanges |= cached.versionRanges();
            hasSnapshotModels |= cached.snapshotModels();
            for (String child : model.getModules())
                build(modulePom(pom, child), visiting);
            visiting.remove(pom);
            return;
        }
        currentInputs = new LinkedHashSet<> ();
        currentVersionRanges = false;
        currentSnapshotModels = false;
        currentInputs.add(pom);
        int diagnosticsStart = diagnostics.size();

        try {
            cache.modelBuilds++;
            var request = new DefaultModelBuildingRequest().setPomFile(pom.toFile())
                    .setModelSource(new Source(pom)).setSystemProperties(settings.system)
                    .setUserProperties(settings.user).setActiveProfileIds(List
                    .copyOf(settings.active)).setInactiveProfileIds(List.copyOf(settings.inactive))
                    .setProfiles(settings.profiles()).setModelResolver(new Resolver())
                    .setValidationLevel(ModelBuildingRequest.VALIDATION_LEVEL_MAVEN_3_1)
                    .setProcessPlugins(true).setLocationTracking(true);
            var result = builder.build(request);
            Model model = result.getEffectiveModel();
            model.setPomFile(pom.toFile());
            models.put(pom, model);
            results.put(pom, result);
            candidates.put(ResolverSupport.gav(model), pom);
            for (String id : result.getModelIds()) {
                Model raw = result.getRawModel(id);

                if (raw != null && raw.getPomFile() != null)
                    observed.add(raw.getPomFile().toPath().toAbsolutePath().normalize());
            }
            for (var problem : result.getProblems())
                diagnostics.add(problem(problem, pom));
            List<Path> inputFiles = List.copyOf(currentInputs);
            currentInputs = null;
            cache.effective.put(pom, new ImportCache.Effective(configuration, ImportCache.hash("",
                    settings.request.pomOverlays(), inputFiles), inputFiles, model.clone(), result,
                    List.copyOf(diagnostics.subList(diagnosticsStart, diagnostics.size())),
                    currentVersionRanges, currentSnapshotModels));
            for (String child : model.getModules())
                build(modulePom(pom, child), visiting);
            if (model.getPrerequisites() != null && model.getPrerequisites().getMaven() != null
                    && model.getPrerequisites().getMaven().startsWith("4"))
                diagnostics.add(Diagnostic.warning("maven4.prerequisite",
                        "Metadata imported with Maven 3.10.0; this project requires Maven 4 for its lifecycle",
                        pom));
        } catch (ModelBuildingException e) {
            for (var problem : e.getProblems())
                diagnostics.add(problem(problem, pom));
        } catch (Exception e) {
            resolver.cancellation.check();
            diagnostics.add(Diagnostic.error("pom.model", settings.safe(e.getMessage()), pom));
        }
        currentInputs = null;
        visiting.remove(pom);
    }

    private boolean inputsAllowed(List<Path> inputs) {
        if (allowedProjectRoot == null)
            return true;
        for (Path path : inputs)
            if (path.getFileName().toString().endsWith(".pom") || path.getFileName().toString()
                    .endsWith(".xml"))
                try {
                    new Source(path, path.startsWith(settings.localRepository)).checkAccess();
                } catch (IOException e) {
                    return false;
                }
        return true;
    }

    private Diagnostic problem(ModelProblem p, Path fallback) {
        Path file = fallback;

        try {
            if (p.getSource() != null && Files.exists(Path.of(p.getSource())))
                file = Path.of(p.getSource());
        } catch (Exception ignored) {}
        return new Diagnostic(p.getSeverity()
                == ModelProblem.Severity.WARNING ? Severity.WARNING : Severity.ERROR, "pom.model",
                settings.safe(p.getMessage()), file, p.getLineNumber(), p.getColumnNumber());
    }

    void registerReactor(Path temporaryDirectory) throws IOException {
        for (var entry : models.entrySet()) {
            Model model = entry.getValue();
            String id = ResolverSupport.gav(model);

            if (resolver.reactor.putIfAbsent(id, model) != null) {
                diagnostics.add(Diagnostic.error("reactor.coordinates.duplicate",
                        "Duplicate reactor coordinates: " + id, entry.getKey()));
                continue;
            }
            Path output = temporaryDirectory.resolve(Integer.toHexString(id.hashCode()) + "-"
                    + resolver.reactor.size() + ".pom");

            try (Writer writer = Files.newBufferedWriter(output)) {
                new MavenXpp3Writer().write(writer, model);
            }
            resolver.reactorPoms.put(id, output);
        }
    }

    Module snapshot(Path pom, Model model) {
        String signature = configuration + modelSignature(model, new HashSet<> ());
        var cached = cache.snapshots.get(pom);

        if (!forceRefresh && cached != null && cached.signature().equals(signature) && cached
                .inputsHash().equals(ImportCache.hash("", Map.of(), cached.inputs()))) {
            cache.resolutionHits++;
            observed.addAll(cached.inputs());
            return cached.module();
        }
        cache.resolutions++;
        Module module = prepareSnapshot(pom, model);
        Set<Path> inputs = new LinkedHashSet<> ();
        inputs.add(path(model.getProjectDirectory().toPath(), model.getBuild().getDirectory())
                .resolve("generated-sources"));
        inputs.add(path(model.getProjectDirectory().toPath(), model.getBuild().getDirectory())
                .resolve("generated-test-sources"));
        for (Path source : module.sourceRoots())
            inputs.add(source.resolve("module-info.java"));
        for (Path source : module.testRoots())
            inputs.add(source.resolve("module-info.java"));
        var artifacts = new ArrayList<Path> ();
        module.dependencies().forEach(d -> {
            if (d.file() != null && d.reactorModule().isEmpty())
                artifacts.add(d.file());
        });
        artifacts.addAll(module.mainJava().processors().path());
        artifacts.addAll(module.testJava().processors().path());
        for (var d : model.getDependencies()) {
            var artifact = resolver.artifact(d);
            inputs.add(settings.localRepository.resolve(resolver.session.getLocalRepositoryManager()
                    .getPathForLocalArtifact(artifact)));
            inputs.add(settings.localRepository.resolve(resolver.session.getLocalRepositoryManager()
                    .getPathForLocalArtifact(new DefaultArtifact(d.getGroupId(), d.getArtifactId(),
                    "pom", d.getVersion()))));
        }
        for (Path artifact : artifacts) {
            inputs.add(artifact);
            Path parent = artifact.getParent();

            if (Files.isDirectory(parent))
                try (var files = Files.list(parent)) {
                    files.filter(p -> p.getFileName().toString().endsWith(".pom") || p.getFileName()
                            .toString().startsWith("maven-metadata") || p.getFileName().toString()
                            .equals("_remote.repositories")).forEach(inputs::add);
                } catch (IOException ignored) {}
        }
        observed.addAll(inputs);
        List<Path> immutableInputs = List.copyOf(inputs);
        cache.snapshots.put(pom, new ImportCache.Snapshot(signature, ImportCache.hash("", Map.of(),
                inputs), immutableInputs, module));
        return module;
    }

    private String modelSignature(Model model, Set<String> seen) {
        String id = ResolverSupport.gav(model);

        if (!seen.add(id))
            return id;
        try {
            var writer = new StringWriter();
            new MavenXpp3Writer().write(writer, model);
            StringBuilder text = new StringBuilder(writer.toString());

            for (var dependency : model.getDependencies()) {
                Model target = resolver.reactor.get(dependency.getGroupId() + ":" + dependency
                        .getArtifactId() + ":" + dependency.getVersion());

                if (target != null)
                    text.append(modelSignature(target, seen));
            }
            return text.toString();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private Module prepareSnapshot(Path pom, Model model) {
        Path directory = pom.getParent();
        var issues = new ArrayList<Diagnostic> ();
        Build build = model.getBuild();
        String id = ResolverSupport.gav(model);
        var sourceRoots = new LinkedHashSet<Path> ();
        var testRoots = new LinkedHashSet<Path> ();
        sourceRoots.add(path(directory, build.getSourceDirectory()));
        testRoots.add(path(directory, build.getTestSourceDirectory()));
        Path generated = path(directory, build.getDirectory())
                .resolve("generated-sources/annotations"), generatedTest = path(directory, build
                .getDirectory()).resolve("generated-test-sources/test-annotations");
        var main = javaConfiguration(model, false, generated, issues);
        var test = javaConfiguration(model, true, generatedTest, issues);
        var generatedRoots = new LinkedHashSet<Path> ();
        var generatedTestRoots = new LinkedHashSet<Path> ();
        generatedRoots.add(main.processors().generatedSources());
        generatedTestRoots.add(test.processors().generatedSources());
        discoverGenerated(path(directory, build.getDirectory()).resolve("generated-sources"),
                generatedRoots);
        discoverGenerated(path(directory, build.getDirectory()).resolve("generated-test-sources"),
                generatedTestRoots);
        boolean pluginDependent = false;

        for (Plugin plugin : build.getPlugins()) {
            String artifact = plugin.getArtifactId();

            if (artifact.equals("build-helper-maven-plugin")) {
                for (var execution : plugin.getExecutions()) {
                    Xpp3Dom config = execution.getConfiguration() instanceof Xpp3Dom d ? d : plugin
                            .getConfiguration() instanceof Xpp3Dom d ? d : null;

                    for (String goal : execution.getGoals())
                        if (goal.equals("add-source") || goal.equals("add-test-source"))
                            for (Xpp3Dom node : children(config, "sources")) {
                                String value = node.getValue();

                                if (value != null && !value.contains("${"))
                                    (goal.equals("add-source") ? sourceRoots : testRoots)
                                            .add(path(directory, value));
                                else
                                    issues.add(Diagnostic.warning("source.dynamic",
                                            "A generated source root requires Maven execution",
                                            pom));
                            }
                }
            } else if (!Set.of("maven-compiler-plugin", "maven-resources-plugin",
                    "maven-jar-plugin", "maven-clean-plugin", "maven-install-plugin",
                    "maven-deploy-plugin", "maven-site-plugin", "maven-javadoc-plugin",
                    "maven-source-plugin", "maven-enforcer-plugin", "maven-toolchains-plugin",
                    "maven-surefire-plugin", "maven-failsafe-plugin").contains(artifact) && !plugin
                    .getExecutions().isEmpty()) {
                pluginDependent = true;
                issues.add(Diagnostic.warning("build.plugin", "Plugin " + plugin.getGroupId() + ":"
                        + artifact
                        + " participates in the build; use explicit Maven lifecycle to execute it",
                        pom));
            }
            if (plugin.isExtensions()) {
                pluginDependent = true;
                issues.add(Diagnostic.warning("build.extension",
                        "Build extension is not executed during project import: " + artifact, pom));
            }
            if (Set.of("maven-surefire-plugin", "maven-failsafe-plugin").contains(artifact)
                    && plugin.getConfiguration() != null)
                issues.add(Diagnostic.warning("test.maven.configuration", "Custom " + artifact
                        + " execution options require Maven Test for lifecycle parity", pom));
        }
        for (Path root : sourceRoots)
            observed.add(root.resolve("module-info.java"));
        for (Path root : testRoots)
            observed.add(root.resolve("module-info.java"));
        var resolved = resolver.dependencies(model, List.copyOf(sourceRoots), List
                .copyOf(testRoots), main, test);
        issues.addAll(resolved.diagnostics());
        var active = new LinkedHashSet<String> ();
        var result = results.get(pom);

        for (String modelId : result.getModelIds())
            result.getActivePomProfiles(modelId).forEach(p -> active.add(p.getId()));
        result.getActiveExternalProfiles().forEach(p -> active.add(p.getId()));
        String effective;

        try {
            var writer = new StringWriter();
            Model printable = model.clone();
            Properties properties = new Properties();
            model.getProperties().forEach((k, v) -> properties.put(k, SettingsSupport.sensitive(k
                    .toString()) ? "[redacted]" : settings.safe(v.toString())));
            printable.setProperties(properties);
            new MavenXpp3Writer().write(writer, printable);
            effective = settings.safe(writer.toString());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        var properties = new LinkedHashMap<String, String> ();
        model.getProperties().forEach((k, v) -> properties.put(k.toString(), SettingsSupport
                .sensitive(k.toString()) ? "[redacted]" : settings.safe(v.toString())));
        List<String> childIds = model.getModules().stream().map(s -> models.get(modulePom(pom, s)))
                .filter(Objects::nonNull).map(ResolverSupport::gav).toList();
        String parent = model.getParent() == null ? "" : model.getParent().getGroupId() + ":"
                + model.getParent().getArtifactId() + ":" + model.getParent().getVersion();
        if (model.getParent() != null) {
            String parentPrefix = model.getParent().getGroupId() + ":" + model.getParent()
                    .getArtifactId() + ":";
            parent = result.getModelIds().stream().filter(modelId -> modelId.startsWith(parentPrefix))
                    .findFirst().orElse(parent);
        }
        FastPath fast = !Set.of("jar", "pom").contains(model
                .getPackaging()) ? FastPath.MAVEN_REQUIRED : pluginDependent || issues.stream()
                .anyMatch(d -> d.severity()
                != Severity.INFO) ? FastPath.PARTIAL : FastPath.SUPPORTED;

        if (fast == FastPath.MAVEN_REQUIRED)
            issues.add(Diagnostic.warning("build.packaging", "Packaging " + model.getPackaging()
                    + " requires the Maven lifecycle; native Java compilation is only a partial result",
                    pom));
        return new Module(id, new Coordinates(model.getGroupId(), model.getArtifactId(), model
                .getVersion(), model.getPackaging(), ""), directory, pom, model.getPackaging(),
                parent, childIds, List.copyOf(sourceRoots), List.copyOf(testRoots), List
                .copyOf(generatedRoots), List.copyOf(generatedTestRoots), resources(build
                .getResources(), directory), resources(build.getTestResources(), directory),
                ResolverSupport.output(model, false), ResolverSupport.output(model, true), main,
                test, resolved.dependencies(), resolved.reactorDependencies(), resolved
                .classpaths(), resolver.repositories(model.getRepositories()).stream()
                .map(r -> new dev.turboj.mavenj.api.MavenModels.Repository(r.getId(), settings
                .safe(r.getUrl()), r.getPolicy(false).isEnabled(), r.getPolicy(true).isEnabled(), r
                .getPolicy(false).getUpdatePolicy(), r.getPolicy(true).getUpdatePolicy())).toList(),
                List.copyOf(active), properties, effective, fast, issues);
    }

    JavaConfiguration javaConfiguration(Model model, boolean test, Path generated,
            List<Diagnostic> issues) {
        Plugin compiler = model.getBuild().getPluginsAsMap()
                .get("org.apache.maven.plugins:maven-compiler-plugin");

        if (compiler == null && model.getBuild().getPluginManagement() != null)
            compiler = model.getBuild().getPluginManagement().getPluginsAsMap()
                    .get("org.apache.maven.plugins:maven-compiler-plugin");
        Xpp3Dom config = compiler != null && compiler
                .getConfiguration() instanceof Xpp3Dom d ? new Xpp3Dom(d) : null;

        if (compiler != null)
            for (var execution : compiler.getExecutions())
                if (execution.getGoals().contains(test ? "testCompile" : "compile") || execution
                        .getId().equals(test ? "default-testCompile" : "default-compile"))
                    if (execution.getConfiguration() instanceof Xpp3Dom d)
                        config = Xpp3Dom.mergeXpp3Dom(new Xpp3Dom(d), config);
        Properties properties = new Properties();
        properties.putAll(model.getProperties());
        properties.putAll(settings.user);
        String release = value(config, test ? "testRelease" : "release", properties,
                test ? "maven.compiler.testRelease" : "maven.compiler.release", "");
        String source = value(config, test ? "testSource" : "source", properties,
                test ? "maven.compiler.testSource" : "maven.compiler.source", "");
        String target = value(config, test ? "testTarget" : "target", properties,
                test ? "maven.compiler.testTarget" : "maven.compiler.target", "");

        if (test) {
            if (release.isEmpty())
                release = value(config, "release", properties, "maven.compiler.release", "");
            if (source.isEmpty())
                source = value(config, "source", properties, "maven.compiler.source", "");
            if (target.isEmpty())
                target = value(config, "target", properties, "maven.compiler.target", "");
        }
        String compilerVersion = compiler == null || compiler.getVersion()
                == null ? org.apache.maven.lifecycle.providers.PluginVersions.COMPILER_PLUGIN_VERSION : compiler
                .getVersion();
        String defaultLevel = compilerDefaultLevel(compilerVersion);

        if (release.isEmpty()) {
            if (source.isEmpty())
                source = defaultLevel;
            if (target.isEmpty())
                target = defaultLevel;
            if (source.isEmpty() || target.isEmpty())
                issues.add(Diagnostic.warning("compiler.language.unspecified",
                        "No explicit Java release/source/target or known compiler-plugin default for "
                        + compilerVersion, model.getPomFile().toPath()));
            else if (compiler == null || compiler.getVersion() == null)
                issues.add(new Diagnostic(Severity.INFO, "compiler.default",
                        "Java compiler defaults use embedded Maven 3.10.0 plugin " + compilerVersion
                        + "; an explicit project compiler version preserves parity with other Maven distributions",
                        model.getPomFile().toPath(), -1, -1));
        }
        String encoding = value(config, "encoding", properties, "project.build.sourceEncoding",
                "UTF-8");
        var requirements = new LinkedHashMap<String, String> ();

        for (var child : children(config, "jdkToolchain"))
            if (child.getValue() != null)
                requirements.put(child.getName(), child.getValue());
        Plugin toolchain = model.getBuild().getPluginsAsMap()
                .get("org.apache.maven.plugins:maven-toolchains-plugin");

        if (toolchain != null) {
            var configs = new ArrayList<Xpp3Dom> ();

            if (toolchain.getConfiguration() instanceof Xpp3Dom d)
                configs.add(d);
            for (var e : toolchain.getExecutions())
                if (e.getConfiguration() instanceof Xpp3Dom d)
                    configs.add(d);
            for (var c : configs) {
                Xpp3Dom tools = c.getChild("toolchains");

                if (tools != null)
                    for (var child : children(tools, "jdk"))
                        if (child.getValue() != null)
                            requirements.putIfAbsent(child.getName(), child.getValue());
            }
        }
        var arguments = new ArrayList<String> ();

        for (var arg : children(config, "compilerArgs"))
            if (arg.getValue() != null)
                arguments.add(arg.getValue());
        Xpp3Dom legacy = config == null ? null : config.getChild("compilerArguments");

        if (legacy != null)
            for (var arg : legacy.getChildren()) {
                arguments.add("-" + arg.getName());
                if (arg.getValue() != null && !arg.getValue().isBlank())
                    arguments.add(arg.getValue());
            }
        String processing = value(config, "proc", properties, "maven.compiler.proc", "");
        var processors = new ArrayList<String> ();

        for (var p : children(config, "annotationProcessors"))
            if (p.getValue() != null)
                processors.add(p.getValue());
        var dependencies = new ArrayList<Coordinates> ();
        var processorDependencies = new ArrayList<org.apache.maven.model.Dependency> ();

        for (var p : children(config, "annotationProcessorPaths")) {
            String group = text(p, "groupId", ""), artifact = text(p, "artifactId", ""),
                    version = text(p, "version", "");

            if (version.isEmpty() && model.getDependencyManagement() != null)
                version = model.getDependencyManagement().getDependencies().stream().filter(d -> d
                        .getGroupId().equals(group) && d.getArtifactId().equals(artifact))
                        .map(org.apache.maven.model.Dependency::getVersion).findFirst().orElse("");
            if (!group.isBlank() && !artifact.isBlank() && !version.isBlank()) {
                dependencies.add(new Coordinates(group, artifact, version, text(p, "type", "jar"),
                        text(p, "classifier", "")));
                var dependency = new org.apache.maven.model.Dependency();
                dependency.setGroupId(group);
                dependency.setArtifactId(artifact);
                dependency.setVersion(version);
                dependency.setType(text(p, "type", "jar"));
                dependency.setClassifier(text(p, "classifier", ""));
                dependency.setScope("runtime");
                for (var exclude : children(p, "exclusions")) {
                    var e = new org.apache.maven.model.Exclusion();
                    e.setGroupId(text(exclude, "groupId", "*"));
                    e.setArtifactId(text(exclude, "artifactId", "*"));
                    dependency.addExclusion(e);
                }
                processorDependencies.add(dependency);
            } else
                issues.add(Diagnostic.error("processor.coordinates",
                        "Annotation processor dependency needs resolved coordinates", model
                        .getPomFile().toPath()));
        }
        var processorOptions = new LinkedHashMap<String, String> ();

        for (String arg : arguments)
            if (arg.startsWith("-A")) {
                int equals = arg.indexOf('=');
                processorOptions.put(equals < 0 ? arg.substring(2) : arg.substring(2, equals),
                        equals < 0 ? "" : arg.substring(equals + 1));
            }
        String generatedDirectory = text(config,
                test ? "generatedTestSourcesDirectory" : "generatedSourcesDirectory", generated
                .toString());
        boolean requested = !processing.equals("none") && (!dependencies.isEmpty() || !processors
                .isEmpty() || processing.equals("full") || processing.equals("only"));
        var processorPath = resolver.processorPath(processorDependencies, model, issues, Boolean
                .parseBoolean(text(config, "annotationProcessorPathsUseDepMgmt", "false")));
        var pc = new ProcessorConfiguration(dependencies, processorPath, List.of(), processors,
                processorOptions, path(model.getProjectDirectory().toPath(), generatedDirectory),
                requested);
        boolean preview = Boolean.parseBoolean(value(config, "enablePreview", properties,
                "maven.compiler.enablePreview", "false"));

        if (processing.equals("none"))
            arguments.add("-proc:none");
        else if (processing.equals("only"))
            arguments.add("-proc:only");
        if (config != null)
            for (String option : List.of("includes", "excludes", "testIncludes", "testExcludes",
                    "multiReleaseOutput", "compileSourceRoots", "outputDirectory"))
                if (config.getChild(option) != null)
                    issues.add(Diagnostic.warning("compiler.configuration", "Compiler option "
                            + option + " requires an explicit Maven build for full parity", model
                            .getPomFile().toPath()));
        if (!text(config, "compilerId", "javac").equals("javac"))
            issues.add(Diagnostic.warning("compiler.implementation",
                    "Non-javac Maven compiler requires explicit Maven lifecycle", model.getPomFile()
                    .toPath()));
        if (config != null && config.getChild("executable") != null)
            issues.add(Diagnostic.warning("compiler.executable",
                    "Custom compiler executable requires explicit Maven lifecycle", model
                    .getPomFile().toPath()));
        return new JavaConfiguration(release, source, target, encoding, settings.request.javaHome(),
                requirements, arguments, pc, preview);
    }

    private static String compilerDefaultLevel(String version) {
        var match = java.util.regex.Pattern.compile("([23])\\.(\\d+)(?:\\..*)?").matcher(version);

        if (!match.matches())
            return "";
        int major = Integer.parseInt(match.group(1)), minor = Integer.parseInt(match.group(2));
        return major == 2 || minor < 8 ? "1.5" : minor < 9 ? "1.6" : minor < 11 ? "1.7" : "1.8";
    }

    private static String value(Xpp3Dom config, String name, Properties properties, String property,
            String fallback) {
        return text(config, name, properties.getProperty(property, fallback));
    }

    private static String text(Xpp3Dom node, String child, String fallback) {
        if (node == null || node.getChild(child) == null || node.getChild(child).getValue() == null)
            return fallback;
        return node.getChild(child).getValue();
    }

    private static Xpp3Dom[] children(Xpp3Dom node, String child) {
        return node == null || node.getChild(child) == null ? new Xpp3Dom[0] : node.getChild(child)
                .getChildren();
    }

    private static List<ResourceRoot> resources(List<Resource> resources, Path directory) {
        return resources.stream().map(r -> new ResourceRoot(path(directory, r.getDirectory()), r
                .getTargetPath(), r.isFiltering(), r.getIncludes(), r.getExcludes())).toList();
    }

    private void discoverGenerated(Path parent, Set<Path> roots) {
        observed.add(parent);
        if (Files.isDirectory(parent))
            try (var dirs = Files.list(parent)) {
                dirs.filter(Files::isDirectory).sorted().forEach(roots::add);
            } catch (IOException ignored) {}
    }

    static Path path(Path directory, String value) {
        return directory.resolve(value).toAbsolutePath().normalize();
    }

    static Path modulePom(Path pom, String module) {
        Path p = pom.getParent().resolve(module).toAbsolutePath().normalize();
        return p.getFileName().toString().endsWith(".xml") ? p : p.resolve("pom.xml");
    }

    private static String interpolate(String value, Properties properties) {
        for (int n = 0; n < 8 && value.contains("${"); n++) {
            String previous = value;

            for (String name : properties.stringPropertyNames())
                value = value.replace("${" + name + "}", properties.getProperty(name));
            if (previous.equals(value))
                break;
        }
        return value;
    }

    private boolean exists(Path p) {
        return settings.request.pomOverlays().containsKey(p) || Files.isRegularFile(p);
    }

    final class Source implements ModelSource2 {
        final Path file;
        final boolean repositoryMetadata;

        Source(Path file) {
            this(file, false);
        }

        Source(Path file, boolean repositoryMetadata) {
            this.file = file.toAbsolutePath().normalize();
            this.repositoryMetadata = repositoryMetadata;
        }

        private void checkAccess() throws IOException {
            if (allowedProjectRoot == null)
                return;
            Path real = Files.exists(file) ? file.toRealPath() : file.getParent().toRealPath()
                    .resolve(file.getFileName());

            if (real.startsWith(allowedProjectRoot.toRealPath()))
                return;
            if (repositoryMetadata && real.startsWith(settings.localRepository.toRealPath()))
                return;
            throw new IOException("Maven model source is outside the allowed workspace root");
        }

        @Override
        public InputStream getInputStream() throws IOException {
            resolver.cancellation.check();
            checkAccess();
            observed.add(file);
            if (currentInputs != null)
                currentInputs.add(file);
            String overlay = settings.request.pomOverlays().get(file);
            byte[] bytes = overlay == null ? Files.readAllBytes(file) : overlay
                    .replaceFirst("(?i)(<\\?xml[^>]*encoding\\s*=\\s*['\"])[^'\"]+(['\"])",
                    "$1UTF-8$2").getBytes(StandardCharsets.UTF_8);
            XmlSafety.check(new ByteArrayInputStream(bytes));
            if (allowedProjectRoot != null)
                try (Reader reader = org.codehaus.plexus.util.ReaderFactory
                        .newXmlReader(new ByteArrayInputStream(bytes))) {
                    var text = new StringWriter();
                    reader.transferTo(text);
                    var references = java.util.regex.Pattern.compile("\\$\\{([^}]+)}").matcher(text
                            .toString());

                    while (references.find())
                        if (SettingsSupport.sensitive(references.group(1)) && settings.system
                                .containsKey(references.group(1)))
                            throw new IOException("Sensitive process-property interpolation is not allowed in restricted Maven inspection");
                }
            try {
                Model raw = new MavenXpp3Reader().read(new ByteArrayInputStream(bytes));

                for (var profile : raw.getProfiles())
                    if (profile.getActivation() != null && profile.getActivation().getFile()
                            != null) {
                        var activation = profile.getActivation().getFile();
                        watchActivation(activation.getExists());
                        watchActivation(activation.getMissing());
                    }
                for (var profile : settings.settings.getProfiles())
                    if (profile.getActivation() != null && profile.getActivation().getFile()
                            != null) {
                        var activation = profile.getActivation().getFile();
                        watchActivation(activation.getExists());
                        watchActivation(activation.getMissing());
                    }
            } catch (org.codehaus.plexus.util.xml.pull.XmlPullParserException ignored) {
                /* Official model builder produces the syntax diagnostic. */
            }
            return new ByteArrayInputStream(bytes);
        }

        private void watchActivation(String value) {
            if (value == null)
                return;
            Properties props = new Properties();
            props.putAll(settings.system);
            props.putAll(settings.user);
            props.setProperty("basedir", file.getParent().toString());
            props.setProperty("project.basedir", file.getParent().toString());
            String resolved = interpolate(value, props);

            if (!resolved.contains("${")) {
                Path input = file.getParent().resolve(resolved).toAbsolutePath().normalize();
                observed.add(input);
                if (currentInputs != null)
                    currentInputs.add(input);
            }
        }

        @Override
        public String getLocation() {
            return file.toString();
        }

        @Override
        public URI getLocationURI() {
            return file.toUri();
        }

        @Override
        public ModelSource2 getRelatedSource(String relative) {
            Path p = file.getParent().resolve(relative).normalize();

            if (Files.isDirectory(p))
                p = p.resolve("pom.xml");
            return exists(p) ? new Source(p, repositoryMetadata && p
                    .startsWith(settings.localRepository)) : null;
        }
    }

    final class Resolver implements ModelResolver {
        final List<org.apache.maven.model.Repository> repositories = new ArrayList<> ();

        Resolver() {
            var central = new org.apache.maven.model.Repository();
            central.setId("central");
            central.setUrl("https://repo.maven.apache.org/maven2");
            var snapshots = new org.apache.maven.model.RepositoryPolicy();
            snapshots.setEnabled(false);
            central.setSnapshots(snapshots);
            repositories.add(central);
        }

        Resolver(Resolver source) {
            repositories.addAll(source.repositories);
        }

        @Override
        public ModelSource resolveModel(String group, String artifact,
                String version) throws UnresolvableModelException {
            resolver.cancellation.check();
            if (version.startsWith("[") || version.startsWith("("))
                version = modelVersion(group, artifact, version);
            boolean snapshot = version.endsWith("-SNAPSHOT");
            if (snapshot)
                hasSnapshotModels = currentSnapshotModels = true;
            Path candidate = candidates.get(group + ":" + artifact + ":" + version);

            if (candidate != null)
                return new Source(candidate);
            try {
                Path file = resolver.resolve(new DefaultArtifact(group, artifact, "pom",
                        version), resolver.repositories(repositories));
                if (snapshot)
                    observeVersionMetadata(group, artifact, version);
                return new Source(file, true);
            } catch (Exception e) {
                resolver.cancellation.check();
                throw new UnresolvableModelException(settings.safe(e.getMessage()), group, artifact,
                        version, e);
            }
        }

        @Override
        public ModelSource resolveModel(Parent p) throws UnresolvableModelException {
            p.setVersion(modelVersion(p.getGroupId(), p.getArtifactId(), p.getVersion()));
            return resolveModel(p.getGroupId(), p.getArtifactId(), p.getVersion());
        }

        @Override
        public ModelSource resolveModel(org.apache.maven.model.Dependency d) throws UnresolvableModelException {
            d.setVersion(modelVersion(d.getGroupId(), d.getArtifactId(), d.getVersion()));
            return resolveModel(d.getGroupId(), d.getArtifactId(), d.getVersion());
        }

        private String modelVersion(String group, String artifact, String requested)
                throws UnresolvableModelException {
            resolver.cancellation.check();
            try {
                var versions = resolver.system.resolveVersionRange(resolver.session,
                        new VersionRangeRequest(new DefaultArtifact(group, artifact, "pom",
                        requested), resolver.repositories(repositories), "mavenj-model"));
                var constraint = versions.getVersionConstraint();
                if (constraint != null && constraint.getRange() != null) {
                    hasVersionRanges = currentVersionRanges = true;
                    observeVersionMetadata(group, artifact, "");
                    if (constraint.getRange().getUpperBound() == null)
                        throw new UnresolvableModelException("Model version range requires an upper bound: "
                                + requested, group, artifact, requested);
                }
                if (versions.getHighestVersion() == null)
                    throw new UnresolvableModelException("No model version matches " + requested,
                            group, artifact, requested);
                return versions.getHighestVersion().toString();
            } catch (VersionRangeResolutionException e) {
                throw new UnresolvableModelException(settings.safe(e.getMessage()), group,
                        artifact, requested, e);
            }
        }

        private void observeVersionMetadata(String group, String artifact, String version)
                throws UnresolvableModelException {
            Path directory = settings.localRepository.resolve(group.replace('.', '/'))
                    .resolve(artifact).resolve(version);
            var inputs = new LinkedHashSet<Path>();
            inputs.add(directory);
            if (Files.isDirectory(directory))
                try (var files = Files.list(directory)) {
                    files.filter(p -> p.getFileName().toString().startsWith("maven-metadata"))
                            .forEach(inputs::add);
                } catch (IOException e) {
                    throw new UnresolvableModelException("Cannot observe Maven version metadata",
                            group, artifact, version, e);
                }
            observed.addAll(inputs);
            if (currentInputs != null)
                currentInputs.addAll(inputs);
        }

        @Override
        public void addRepository(org.apache.maven.model.Repository r) {
            addRepository(r, false);
        }

        @Override
        public void addRepository(org.apache.maven.model.Repository r, boolean replace) {
            if (replace)
                repositories.removeIf(existing -> existing.getId().equals(r.getId()));
            if (repositories.stream().noneMatch(existing -> existing.getId().equals(r.getId())))
                repositories.add(r);
        }

        @Override
        public ModelResolver newCopy() {
            return new Resolver(this);
        }
    }
}
