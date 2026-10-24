package dev.turboj.mavenj.core;

import dev.turboj.mavenj.api.Cancellation;
import dev.turboj.mavenj.api.MavenModels.*;
import org.apache.maven.model.Model;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.*;
import org.eclipse.aether.artifact.*;
import org.eclipse.aether.collection.*;
import org.eclipse.aether.graph.*;
import org.eclipse.aether.repository.*;
import org.eclipse.aether.resolution.*;
import org.eclipse.aether.transfer.*;
import org.eclipse.aether.util.repository.*;
import org.eclipse.aether.util.graph.transformer.ConflictResolver;
import java.io.*;
import java.net.URI;
import java.nio.file.*;
import java.util.*;

final class ResolverSupport {
    final RepositorySystem system;
    final DefaultRepositorySystemSession session;
    final SettingsSupport settings;
    final Cancellation cancellation;
    final Set<Path> observed;
    final Map<String, Model> reactor = new LinkedHashMap<> ();
    final Map<String, Path> reactorPoms = new LinkedHashMap<> ();
    final List<Diagnostic> diagnostics = new ArrayList<> ();
    private final WorkspaceRepository workspaceRepository = new WorkspaceRepository("mavenj-reactor");

    ResolverSupport(RepositorySystem system, SettingsSupport settings, Cancellation cancellation,
            Set<Path> observed) {
        this.system = system;
        this.settings = settings;
        this.cancellation = cancellation;
        this.observed = observed;
        session = MavenRepositorySystemUtils.newSession();
        session.setOffline(settings.offline());
        session.setSystemProperties(settings.system);
        session.setUserProperties(settings.user);
        session.setConfigProperty("aether.transport.apache.followRedirects", false);
        session.setConfigProperty("aether.connector.connectTimeout", 15000);
        session.setConfigProperty("aether.connector.requestTimeout", 30000);
        session.setConfigProperty("aether.artifactResolver.threads", 4);
        session.setConfigProperty("aether.metadataResolver.threads", 4);
        if (settings.request.refreshSnapshots())
            session.setUpdatePolicy(RepositoryPolicy.UPDATE_POLICY_ALWAYS);
        var mirrors = new DefaultMirrorSelector();

        for (var m : settings.settings.getMirrors())
            mirrors.add(m.getId(), m.getUrl(), m.getLayout(), false, m.isBlocked(), m.getMirrorOf(),
                    m.getMirrorOfLayouts());
        session.setMirrorSelector(repository -> {
            RemoteRepository mirror = mirrors.getMirror(repository);
            RemoteRepository selected = mirror == null ? repository : mirror;
            boolean blocked = selected.isBlocked() || !permitted(selected.getUrl());
            return new RemoteRepository.Builder(selected).setBlocked(blocked).build();
        });
        var auth = new DefaultAuthenticationSelector();

        for (var server : settings.settings.getServers()) {
            auth.add(server.getId(), new AuthenticationBuilder().addUsername(server.getUsername())
                    .addPassword(server.getPassword()).addPrivateKey(server.getPrivateKey(), server
                    .getPassphrase()).build());
            if (server
                    .getConfiguration() instanceof org.codehaus.plexus.util.xml.Xpp3Dom configuration) {
                var headers = configuration.getChild("httpHeaders");

                if (headers != null) {
                    var values = new LinkedHashMap<String, String> ();

                    for (var header : headers.getChildren("property"))
                        if (header.getChild("name") != null && header.getChild("value") != null) {
                            String name = header.getChild("name").getValue(), value = header
                                    .getChild("value").getValue();

                            if (name != null && value != null) {
                                values.put(name, value);
                                settings.addSecret(value);
                            }
                        }
                    session.setConfigProperty("aether.transport.http.headers." + server.getId(),
                            values);
                }
            }
        }
        session.setAuthenticationSelector(auth);
        var proxies = new DefaultProxySelector();

        for (var p : settings.settings.getProxies())
            if (p.isActive())
                proxies.add(new Proxy(p.getProtocol(), p.getHost(), p.getPort(),
                        new AuthenticationBuilder().addUsername(p.getUsername()).addPassword(p
                        .getPassword()).build()), p.getNonProxyHosts());
        session.setProxySelector(proxies);
        session.setLocalRepositoryManager(system.newLocalRepositoryManager(session,
                new LocalRepository(settings.localRepository.toFile())));
        session.setTransferListener(new AbstractTransferListener() {
            @Override
            public void transferInitiated(TransferEvent event) throws TransferCancelledException {
                check();
            }

            @Override
            public void transferProgressed(TransferEvent event) throws TransferCancelledException {
                check();
            }

            private void check() throws TransferCancelledException {
                if (cancellation.cancelled() || Thread.currentThread().isInterrupted())
                    throw new TransferCancelledException("MavenJ operation cancelled");
            }
        });
        session.setRepositoryListener(new AbstractRepositoryListener() {
            @Override
            public void artifactResolved(RepositoryEvent event) {
                observe(event);
            }

            @Override
            public void metadataResolved(RepositoryEvent event) {
                observe(event);
            }

            private void observe(RepositoryEvent event) {
                if (event.getFile() != null && !(event
                        .getRepository() instanceof WorkspaceRepository))
                    synchronized (observed) {
                        observed.add(event.getFile().toPath().toAbsolutePath().normalize());
                    }
            }
        });
        session.setWorkspaceReader(new WorkspaceReader() {
            @Override
            public WorkspaceRepository getRepository() {
                return workspaceRepository;
            }

            @Override
            public File findArtifact(Artifact artifact) {
                String id = gav(artifact);
                Model model = reactor.get(id);

                if (model == null)
                    return null;
                if (artifact.getExtension().equals("pom") && artifact.getClassifier().isEmpty())
                    return reactorPoms.get(id).toFile();
                if (!reactorOutput(artifact, model))
                    return null;
                Path output = output(model, artifact.getClassifier().equals("tests"));
                return Files.exists(output) ? output.toFile() : null;
            }

            @Override
            public List<String> findVersions(Artifact artifact) {
                return reactor.values().stream().filter(m -> m.getGroupId().equals(artifact
                        .getGroupId()) && m.getArtifactId().equals(artifact.getArtifactId())
                        && (reactorOutput(artifact, m) || artifact.getExtension().equals("pom")
                        && artifact.getClassifier().isEmpty()))
                        .map(Model::getVersion).toList();
            }
        });
    }

    boolean permitted(String url) {
        try {
            URI uri = URI.create(url);

            if (uri.getUserInfo() != null)
                return false;
            boolean insecure = uri.getScheme().equalsIgnoreCase("http") && !Set.of("localhost",
                    "127.0.0.1", "::1").contains(Objects.requireNonNullElse(uri.getHost(), ""));

            if (insecure)
                return false;
            return settings.request.allowedRepositoryUrls().stream()
                    .anyMatch(allowed -> normalizeUrl(allowed).equals(normalizeUrl(url)));
        } catch (RuntimeException e) {
            return false;
        }
    }

    static String normalizeUrl(String url) {
        return url.replaceAll("/+$", "");
    }

    List<RemoteRepository> repositories(List<org.apache.maven.model.Repository> input) {
        var repos = new ArrayList<RemoteRepository> ();

        for (var r : input)
            repos.add(new RemoteRepository.Builder(r.getId(), Objects.requireNonNullElse(r
                    .getLayout(), "default"), r.getUrl()).setReleasePolicy(policy(r.getReleases(),
                    true)).setSnapshotPolicy(policy(r.getSnapshots(), true)).build());
        return system.newResolutionRepositories(session, repos);
    }

    private static RepositoryPolicy policy(org.apache.maven.model.RepositoryPolicy p,
            boolean enabled) {
        return p == null ? new RepositoryPolicy(enabled, "daily", "warn") : new RepositoryPolicy(p
                .isEnabled(), Objects.requireNonNullElse(p.getUpdatePolicy(), "daily"), Objects
                .requireNonNullElse(p.getChecksumPolicy(), "warn"));
    }

    Path resolve(Artifact artifact,
            List<RemoteRepository> repositories) throws ArtifactResolutionException {
        cancellation.check();
        observed.add(settings.localRepository.resolve(session.getLocalRepositoryManager()
                .getPathForLocalArtifact(artifact)));
        var result = system.resolveArtifact(session, new ArtifactRequest(artifact, repositories,
                "mavenj"));
        cancellation.check();
        Path file = result.getArtifact().getFile().toPath().toAbsolutePath().normalize();
        observed.add(file);
        return file;
    }

    Artifact artifact(org.apache.maven.model.Dependency d) {
        ArtifactType type = session.getArtifactTypeRegistry().get(d.getType());

        if (type == null)
            type = new DefaultArtifactType(d.getType());
        Artifact a = new DefaultArtifact(d.getGroupId(), d.getArtifactId(), d.getClassifier(), null,
                d.getVersion(), Map.of(), type);

        if ("system".equals(d.getScope()) && d.getSystemPath() != null)
            a = a.setFile(new File(d.getSystemPath())).setProperties(Map
                    .of(ArtifactProperties.LOCAL_PATH, d.getSystemPath()));
        return a;
    }

    org.eclipse.aether.graph.Dependency dependency(org.apache.maven.model.Dependency d) {
        return new org.eclipse.aether.graph.Dependency(artifact(d), d.getScope(), d.isOptional(), d
                .getExclusions().stream().map(e -> new Exclusion(e.getGroupId(), e.getArtifactId(),
                "*", "*")).toList());
    }

    record Resolution(List<dev.turboj.mavenj.api.MavenModels.Dependency> dependencies,
            List<ReactorDependency> reactorDependencies, Classpaths classpaths,
            List<Diagnostic> diagnostics) {}

    Resolution dependencies(Model model, List<Path> sources, List<Path> tests,
            JavaConfiguration mainJava, JavaConfiguration testJava) {
        var issues = new ArrayList<Diagnostic> ();
        var results = new LinkedHashMap<String, dev.turboj.mavenj.api.MavenModels.Dependency> ();
        var links = new LinkedHashMap<String, ReactorDependency> ();
        var paths = new EnumMap<ClasspathScope, LinkedHashSet<Path>> (ClasspathScope.class);

        for (var s : ClasspathScope.values())
            paths.put(s, new LinkedHashSet<> ());
        List<RemoteRepository> repos = repositories(model.getRepositories());
        var collect = new CollectRequest().setDependencies(model.getDependencies().stream()
                .map(this::dependency).toList()).setRepositories(repos);

        if (model.getDependencyManagement() != null)
            collect.setManagedDependencies(model.getDependencyManagement().getDependencies()
                    .stream().map(this::dependency).toList());
        DependencyNode graph = null;

        try {
            graph = system.collectDependencies(session, collect).getRoot();
        } catch (DependencyCollectionException e) {
            graph = e.getResult().getRoot();
            issues.add(error("dependency.collect", e, model));
        }
        if (graph != null)
            visit(graph, results, links, paths, issues, model, new HashSet<> ());
        paths.get(ClasspathScope.MAIN_RUNTIME).add(output(model, false));
        paths.get(ClasspathScope.TEST_COMPILE).add(output(model, false));
        paths.get(ClasspathScope.TEST_RUNTIME).add(output(model, true));
        paths.get(ClasspathScope.TEST_RUNTIME).add(output(model, false));
        paths.get(ClasspathScope.PROCESSOR).addAll(mainJava.processors().path());
        paths.get(ClasspathScope.PROCESSOR).addAll(testJava.processors().path());
        var classpaths = new EnumMap<ClasspathScope, List<Path>> (ClasspathScope.class);
        var modulePaths = new EnumMap<ClasspathScope, List<Path>> (ClasspathScope.class);
        boolean mainModular = sources.stream().anyMatch(p -> Files.isRegularFile(p
                .resolve("module-info.java")));
        boolean testModular = tests.stream().anyMatch(p -> Files.isRegularFile(p
                .resolve("module-info.java")));

        for (var entry : paths.entrySet()) {
            boolean modular = switch (entry.getKey()) {
                case MAIN_COMPILE, MAIN_RUNTIME -> mainModular;
                case TEST_COMPILE, TEST_RUNTIME -> testModular;
                default -> false;
            };

            if (modular) {
                var cp = new ArrayList<Path> ();
                var mp = new ArrayList<Path> ();

                for (Path p : entry.getValue())
                    if (p.equals(output(model, false)) || p.equals(output(model, true)))
                        cp.add(p);
                    else
                        mp.add(p);
                classpaths.put(entry.getKey(), cp);
                modulePaths.put(entry.getKey(), mp);
            } else
                classpaths.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return new Resolution(List.copyOf(results.values()), List.copyOf(links.values()),
                new Classpaths(classpaths, modulePaths, issues.stream().noneMatch(d -> d.severity()
                == Severity.ERROR)), issues);
    }

    private void visit(DependencyNode node, Map<String,
            dev.turboj.mavenj.api.MavenModels.Dependency> results, Map<String,
            ReactorDependency> links, Map<ClasspathScope, LinkedHashSet<Path>> paths,
            List<Diagnostic> issues, Model model, Set<DependencyNode> visited) {
        cancellation.check();
        if (!visited.add(node) || node.getData().get(ConflictResolver.NODE_DATA_WINNER) != null)
            return;
        var d = node.getDependency();

        if (d != null) {
            Artifact a = node.getArtifact();
            String id = gav(a);
            Model reactorModel = reactor.get(id);
            if (reactorModel != null && !reactorOutput(a, reactorModel))
                reactorModel = null;
            Path file = null;

            if (reactorModel != null) {
                file = output(reactorModel, a.getClassifier().equals("tests"));
                links.put(id + ":" + d.getScope(), new ReactorDependency(id, d.getScope(), a
                        .getClassifier().equals("tests")));
            } else
                try {
                    file = resolve(a, node.getRepositories());
                } catch (ArtifactResolutionException e) {
                    issues.add(error("dependency.missing", e, model));
                }
            var coordinates = new Coordinates(a.getGroupId(), a.getArtifactId(), a.getVersion(), a
                    .getExtension(), a.getClassifier());
            var children = node.getChildren().stream().filter(n -> n.getArtifact() != null && n
                    .getData().get(ConflictResolver.NODE_DATA_WINNER) == null).map(n -> key(n
                    .getArtifact())).toList();
            results.putIfAbsent(coordinates.key() + ":" + d.getScope(),
                    new dev.turboj.mavenj.api.MavenModels.Dependency(coordinates, d.getScope(), d
                    .isOptional(), d.getExclusions().stream().map(e -> e.getGroupId() + ":" + e
                    .getArtifactId()).toList(), file, reactorModel == null ? "" : id, children));
            if (file != null && !a.getExtension().equals("pom")) {
                String scope = d.getScope();

                if (Set.of("compile", "provided", "system").contains(scope))
                    paths.get(ClasspathScope.MAIN_COMPILE).add(file);
                if (Set.of("compile", "runtime").contains(scope))
                    paths.get(ClasspathScope.MAIN_RUNTIME).add(file);
                if (Set.of("compile", "provided", "system", "runtime", "test").contains(scope)) {
                    paths.get(ClasspathScope.TEST_COMPILE).add(file);
                    paths.get(ClasspathScope.TEST_RUNTIME).add(file);
                }
            }
        }
        for (var child : node.getChildren())
            visit(child, results, links, paths, issues, model, visited);
    }

    List<Path> processorPath(List<org.apache.maven.model.Dependency> dependencies, Model model,
            List<Diagnostic> issues, boolean managed) {
        if (dependencies.isEmpty())
            return List.of();
        var collect = new CollectRequest().setRepositories(repositories(model.getRepositories()));

        for (var d : dependencies)
            collect.addDependency(dependency(d));
        if (managed && model.getDependencyManagement() != null)
            collect.setManagedDependencies(model.getDependencyManagement().getDependencies()
                    .stream().map(this::dependency).toList());
        try {
            var result = system.resolveDependencies(session, new DependencyRequest(collect, null));
            var paths = new LinkedHashSet<Path> ();

            for (var artifact : result.getArtifactResults())
                if (artifact.isResolved()) {
                    Path path = artifact.getArtifact().getFile().toPath();
                    paths.add(path);
                    observed.add(path);
                }
            return List.copyOf(paths);
        } catch (DependencyResolutionException e) {
            issues.add(error("processor.resolve", e, model));
            return List.of();
        }
    }

    Diagnostic error(String code, Exception e, Model model) {
        cancellation.check();
        return Diagnostic.error(code, settings.safe(e.getMessage()), model.getPomFile()
                == null ? null : model.getPomFile().toPath());
    }

    static Path output(Model m, boolean test) {
        return Path.of(test ? m.getBuild().getTestOutputDirectory() : m.getBuild()
                .getOutputDirectory()).toAbsolutePath().normalize();
    }

    private boolean reactorOutput(Artifact artifact, Model model) {
        if (!artifact.getExtension().equals("jar"))
            return false;
        if (artifact.getClassifier().equals("tests"))
            return true;
        if (!artifact.getClassifier().isEmpty())
            return false;
        ArtifactType type = session.getArtifactTypeRegistry().get(model.getPackaging());
        return type != null && type.getExtension().equals("jar");
    }

    static String gav(Artifact a) {
        return a.getGroupId() + ":" + a.getArtifactId() + ":" + a.getVersion();
    }

    static String gav(Model m) {
        return m.getGroupId() + ":" + m.getArtifactId() + ":" + m.getVersion();
    }

    static String key(Artifact a) {
        return new Coordinates(a.getGroupId(), a.getArtifactId(), a.getVersion(), a.getExtension(),
                a.getClassifier()).key();
    }
}
