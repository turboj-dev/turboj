package dev.turboj.mavenj.core;

import dev.turboj.mavenj.api.*;
import dev.turboj.mavenj.api.MavenModels.*;
import dev.turboj.mavenj.api.MavenModels.Module;
import org.apache.maven.repository.supplier.RepositorySystemSupplier;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.DefaultArtifact;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.io.*;
import java.time.*;
import java.util.*;

/** Embedded, headless Maven 3.10 model builder and Resolver 2 backend. Import never executes plugins. */
public final class DefaultMavenJ implements MavenJ {
    private final RepositorySystem repositories = new RepositorySystemSupplier().get();
    private final Path allowedProjectRoot;

    public DefaultMavenJ() {
        this(null);
    }

    /** Restricts local project/parent/model reads for an agent host; repository metadata remains accessible. */
    public DefaultMavenJ(Path allowedProjectRoot) {
        this.allowedProjectRoot = allowedProjectRoot == null ? null : allowedProjectRoot
                .toAbsolutePath().normalize();
    }

    private final LinkedHashMap<ImportRequest, Cached> cache = new LinkedHashMap<> (16, 0.75f,
            true);
    private long generation;
    private boolean closed;

    private record Cached(Workspace workspace, Instant expires) {}

    private final LinkedHashMap<ImportRequest, ImportCache> modelCaches = new LinkedHashMap<> (16,
            0.75f, true);

    public record CacheStatistics(long modelBuilds, long modelHits, long dependencyResolutions,
            long dependencyHits) {}

    public synchronized CacheStatistics cacheStatistics() {
        return new CacheStatistics(modelCaches.values().stream().mapToLong(c -> c.modelBuilds)
                .sum(), modelCaches.values().stream().mapToLong(c -> c.modelHits).sum(), modelCaches
                .values().stream().mapToLong(c -> c.resolutions).sum(), modelCaches.values()
                .stream().mapToLong(c -> c.resolutionHits).sum());
    }

    @Override
    public synchronized Workspace open(ImportRequest request, Cancellation cancellation) {
        requireOpen();
        Objects.requireNonNull(request);
        Objects.requireNonNull(cancellation);
        cancellation.check();
        long start = System.nanoTime();
        Path pom = Files.isDirectory(request.project()) ? request.project()
                .resolve("pom.xml") : request.project();
        Path root = pom.getParent();
        ImportRequest key = new ImportRequest(request.project(), request.userSettings(), request
                .globalSettings(), request.localRepository(), request.activeProfiles(), request
                .inactiveProfiles(), request.userProperties(), request.offline(), request
                .allowedRepositoryUrls(), false, request.javaHome(), request.pomOverlays());
        Cached cached = cache.get(key);

        if (cached != null && !request.refreshSnapshots() && Instant.now().isBefore(cached
                .expires()) && fingerprint(key, cached.workspace().observedFiles()).equals(cached
                .workspace().fingerprint())) {
            Workspace w = cached.workspace();
            return new Workspace(w.root(), w.rootPom(), w.generation(), w.modules(), w.buildOrder(),
                    w.observedFiles(), w.fingerprint(), w.localRepository(), w.activeProfiles(), w
                    .offline(), w.diagnostics(), System.nanoTime() - start, true, w
                    .userToolchains(), w.globalToolchains());
        }
        var observed = new LinkedHashSet<Path> ();
        var settings = new SettingsSupport(request, root, observed);
        var resolver = new ResolverSupport(repositories, settings, cancellation, observed);
        ImportRequest modelKey = new ImportRequest(key.project(), key.userSettings(), key
                .globalSettings(), key.localRepository(), key.activeProfiles(), key
                .inactiveProfiles(), key.userProperties(), key.offline(), key
                .allowedRepositoryUrls(), false, key.javaHome(), Map.of());
        ImportCache modelCache = modelCaches.computeIfAbsent(modelKey, unused -> new ImportCache());

        if (modelCaches.size() > 8)
            modelCaches.remove(modelCaches.keySet().iterator().next());
        boolean forceRefresh = settings.request.refreshSnapshots() || cached != null && !Instant
                .now().isBefore(cached.expires());
        var models = new ModelSupport(settings, resolver, observed, modelCache, forceRefresh,
                allowedProjectRoot);
        models.discover(pom);
        var issues = new ArrayList<> (settings.diagnostics);
        issues.addAll(models.diagnostics);
        Path extensions = root.resolve(".mvn/extensions.xml");

        if (Files.isRegularFile(extensions))
            issues.add(Diagnostic.warning("build.core.extensions",
                    "Maven core extensions are not loaded during passive import; use the project Maven lifecycle for extension-dependent behavior",
                    extensions));
        var modules = new ArrayList<Module> ();
        Path temporary = null;

        try {
            temporary = Files.createTempDirectory("mavenj-reactor-");
            models.registerReactor(temporary);
            for (var entry : models.models.entrySet()) {
                cancellation.check();
                modules.add(models.snapshot(entry.getKey(), entry.getValue()));
            }
        } catch (IOException e) {
            issues.add(Diagnostic.error("reactor.workspace", settings.safe(e.getMessage()), pom));
        } finally {
            if (temporary != null)
                deleteTemporary(temporary);
        }
        // Include errors raised while registering reactor coordinates, but do not duplicate model diagnostics.
        for (Diagnostic d : models.diagnostics)
            if (!issues.contains(d))
                issues.add(d);
        List<String> order = buildOrder(modules, issues);
        cancellation.check();
        String fingerprint = fingerprint(key, observed);
        var active = new LinkedHashSet<String> ();
        modules.forEach(m -> active.addAll(m.activeProfiles()));
        Workspace workspace = new Workspace(root, pom, ++generation, modules, order, List
                .copyOf(observed), fingerprint, settings.localRepository, List.copyOf(active),
                settings.offline(), issues, System.nanoTime() - start, false,
                settings.userToolchains, settings.globalToolchains);
        cache.put(key, new Cached(workspace, settings.request.refreshSnapshots() && !request
                .refreshSnapshots() ? Instant.now() : expires(workspace, models.hasVersionRanges,
                models.hasSnapshotModels)));
        if (cache.size() > 8)
            cache.remove(cache.keySet().iterator().next());
        return workspace;
    }

    @Override
    public synchronized Attachment attachment(ImportRequest request, String moduleId,
            Coordinates artifact, String classifier, Cancellation cancellation) {
        requireOpen();
        if (!Set.of("sources", "javadoc").contains(classifier))
            throw new IllegalArgumentException("Only sources and javadoc attachments are supported");
        Workspace workspace = open(request, cancellation);
        Module module = workspace.module(moduleId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown Maven module: "
                + moduleId));
        var settings = new SettingsSupport(request, workspace.root(), new LinkedHashSet<> ());
        var resolver = new ResolverSupport(repositories, settings, cancellation,
                new LinkedHashSet<> ());
        var modelRepos = new ArrayList<org.apache.maven.model.Repository> ();

        for (var repo : module.repositories()) {
            var r = new org.apache.maven.model.Repository();
            r.setId(repo.id());
            r.setUrl(repo.url());
            modelRepos.add(r);
        }
        try {
            Path file = resolver.resolve(new DefaultArtifact(artifact.groupId(), artifact
                    .artifactId(), classifier, "jar", artifact.version()), resolver
                    .repositories(modelRepos));
            return new Attachment(artifact, classifier, file, List.of());
        } catch (Exception e) {
            cancellation.check();
            return new Attachment(artifact, classifier, null, List.of(Diagnostic
                    .error("attachment.missing", settings.safe(e.getMessage()), module.pom())));
        }
    }

    private static Instant expires(Workspace workspace, boolean modelVersionRanges,
            boolean snapshotModels) {
        if (workspace.offline())
            return Instant.MAX;
        Instant expiry = Instant.MAX, now = Instant.now();

        for (Module module : workspace.modules()) {
            boolean snapshot = snapshotModels || module.coordinates().version().endsWith("-SNAPSHOT") || module
                    .dependencies().stream().anyMatch(d -> d.coordinates().version()
                    .endsWith("-SNAPSHOT")) || module.effectivePom()
                    .contains("-SNAPSHOT</version>");
            boolean range = modelVersionRanges || module.effectivePom().matches("(?s).*<version>\\s*[\\[(].*");

            if (snapshot || range)
                for (var repository : module.repositories()) {
                    if (snapshot && !repository.snapshots() || !snapshot && !repository.releases())
                        continue;
                    String policy = snapshot ? repository.snapshotUpdatePolicy() : repository
                            .releaseUpdatePolicy();
                    Instant policyExpiry = Instant.MAX;

                    if ("always".equals(policy))
                        policyExpiry = now;
                    else if ("daily".equals(policy))
                        policyExpiry = LocalDate.now().plusDays(1).atStartOfDay(ZoneId
                                .systemDefault()).toInstant();
                    else if (policy != null && policy.startsWith("interval:"))
                        try {
                            policyExpiry = now.plusSeconds(Math.max(0, Long.parseLong(policy
                                    .substring(9))) * 60);
                        } catch (NumberFormatException e) {
                            policyExpiry = now;
                        }
                    if (policyExpiry.isBefore(expiry))
                        expiry = policyExpiry;
                }
        }
        return expiry;
    }

    private static List<String> buildOrder(List<Module> modules, List<Diagnostic> issues) {
        Map<String, Module> byId = new LinkedHashMap<> ();
        modules.forEach(m -> byId.put(m.id(), m));
        var order = new ArrayList<String> ();
        var visited = new HashSet<String> ();
        var visiting = new HashSet<String> ();

        for (Module module : modules)
            order(module, byId, visited, visiting, order, issues);
        return order;
    }

    private static void order(Module module, Map<String, Module> modules, Set<String> visited,
            Set<String> visiting, List<String> order, List<Diagnostic> issues) {
        if (visited.contains(module.id()))
            return;
        if (!visiting.add(module.id())) {
            issues.add(Diagnostic.error("reactor.dependency.cycle",
                    "Cyclic reactor dependency involving " + module.id(), module.pom()));
            return;
        }
        Module parent = modules.get(module.parentId());

        if (parent != null)
            order(parent, modules, visited, visiting, order, issues);
        for (var dependency : module.reactorDependencies()) {
            Module other = modules.get(dependency.moduleId());

            if (other != null)
                order(other, modules, visited, visiting, order, issues);
        }
        visiting.remove(module.id());
        if (visited.add(module.id()))
            order.add(module.id());
    }

    private String fingerprint(ImportRequest request, Collection<Path> observed) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(request.toString().getBytes(StandardCharsets.UTF_8));
            for (Path path : observed.stream().sorted().toList()) {
                digest.update(path.toString().getBytes(StandardCharsets.UTF_8));
                try {
                    BasicFileAttributes attrs = Files.readAttributes(path,
                            BasicFileAttributes.class);
                    digest.update((attrs.size() + ":" + attrs.lastModifiedTime() + ":" + attrs
                            .isDirectory()).getBytes(StandardCharsets.UTF_8));
                    if (attrs.isRegularFile() && !path.toString().endsWith(".jar") && attrs.size()
                            < 16_000_000 && (allowedProjectRoot == null || path.toRealPath()
                            .startsWith(allowedProjectRoot.toRealPath())))
                        digest.update(Files.readAllBytes(path));
                } catch (IOException e) {
                    digest.update((byte) 0);
                }
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void deleteTemporary(Path directory) {
        try (var files = Files.walk(directory)) {
            for (Path path : files.sorted(Comparator.reverseOrder()).toList())
                Files.deleteIfExists(path);
        } catch (IOException ignored) {
            directory.toFile().deleteOnExit();
        }
    }

    private void requireOpen() {
        if (closed)
            throw new IllegalStateException("MavenJ is closed");
    }

    @Override
    public synchronized void close() {
        if (!closed) {
            closed = true;
            cache.clear();
            modelCaches.clear();
            repositories.shutdown();
        }
    }
}
