package dev.turboj.refactorj.runtime;

import dev.turboj.refactorj.api.*;
import dev.turboj.refactorj.api.Model.*;
import java.net.URLClassLoader;
import java.net.URL;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.*;

/**
 * Embeds headless Eclipse bundles in this JVM. No HTTP, protocol, process or SWT.
 * Construct and use on a serial semantic worker. One instance owns the workspace model.
 */
public final class RefactorJRuntime implements AutoCloseable {
    private final Path temporary;
    private FrameworkBridge framework;
    private URLClassLoader loader;
    private final List<RefactorJ> engines = new ArrayList<>();
    private boolean closed;
    private final long initializationNanos;

    public RefactorJRuntime() throws Exception {
        long started = System.nanoTime();
        ClassLoader callerLoader = Thread.currentThread().getContextClassLoader();
        temporary = Files.createTempDirectory("refactorj-runtime-");
        try {
            List<Path> bundles = materializeBundles();
            Path frameworkJar = null;
            for (Path bundle : bundles) try (JarFile jar = new JarFile(bundle.toFile())) {
                if (jar.getManifest() == null) continue;
                String symbolic = jar.getManifest().getMainAttributes().getValue("Bundle-SymbolicName");
                if (symbolic != null && symbolic.split(";")[0].equals("org.eclipse.osgi")) frameworkJar = bundle;
            }
            if (frameworkJar == null) throw new IOException("The original Equinox runtime JAR is missing");
            ClassLoader host = RefactorJRuntime.class.getClassLoader();
            ClassLoader parent = new ClassLoader(ClassLoader.getPlatformClassLoader()) {
                @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                    // PLURL owns JVM-wide URL handler hooks. Share its small JDK-only facade
                    // with the host so later frameworks never call a previously closed loader.
                    if (name.startsWith("dev.turboj.refactorj.api.")
                            || name.startsWith("org.eclipse.equinox.plurl.")
                            || name.equals(FrameworkBridge.class.getName()))
                        return host.loadClass(name);
                    return super.loadClass(name, resolve);
                }
            };
            URL origin = RefactorJRuntime.class.getProtectionDomain().getCodeSource().getLocation();
            loader = new URLClassLoader(new URL[] { frameworkJar.toUri().toURL(), origin }, parent);
            framework = (FrameworkBridge) Class.forName("dev.turboj.refactorj.runtime.EquinoxBridge", true, loader).getConstructor().newInstance();
            framework.start(temporary, bundles);
            initializationNanos = System.nanoTime() - started;
        } catch (Exception error) {
            try { if (framework == null || framework.stop()) { if (loader != null) loader.close(); eraseTemporary(); } }
            catch (Exception stop) { error.addSuppressed(stop); }
            throw error;
        } finally {
            // Equinox installs its context finder during init; never leak it into the host.
            Thread.currentThread().setContextClassLoader(callerLoader);
        }
    }

    public synchronized RefactorJ openWorkspace(WorkspaceSnapshot snapshot) throws Exception {
        if (closed) throw new IllegalStateException("RefactorJ runtime is closed");
        RefactorJ engine = new ManagedEngine(framework.open(snapshot));
        engines.add(engine);
        return engine;
    }

    public long initializationNanos() { return initializationNanos; }

    private List<Path> materializeBundles() throws Exception {
        String external = System.getProperty("refactorj.bundles", "");
        if (!external.isBlank()) {
            try (var files = Files.list(Path.of(external))) { return files.filter(p -> p.getFileName().toString().endsWith(".jar")).sorted().toList(); }
        }
        Path output = Files.createDirectories(temporary.resolve("bundles"));
        Path origin = Path.of(RefactorJRuntime.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        if (Files.isDirectory(origin)) {
            Path directory = origin.resolve("refactorj/bundles");
            if (!Files.isDirectory(directory)) throw new IOException("Semantic bundles are assembled during Maven package.");
            try (var files = Files.list(directory)) { return files.filter(p -> p.getFileName().toString().endsWith(".jar")).sorted().toList(); }
        }
        try (JarFile archive = new JarFile(origin.toFile())) {
            var entries = archive.stream().filter(e -> e.getName().startsWith("refactorj/bundles/") && e.getName().endsWith(".jar"))
                    .sorted(Comparator.comparing(JarEntry::getName)).toList();
            for (var entry : entries) {
                String name = entry.getName().substring("refactorj/bundles/".length());
                if (!name.matches("[A-Za-z0-9_.-]+\\.jar")) throw new IOException("Invalid embedded bundle path");
                try (InputStream input = archive.getInputStream(entry)) { Files.copy(input, output.resolve(name)); }
            }
        }
        try (var files = Files.list(output)) { return files.sorted().toList(); }
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        for (RefactorJ engine : List.copyOf(engines)) try { engine.close(); } catch (RuntimeException ignored) {}
        engines.clear();
        try {
            if (framework.stop()) { loader.close(); eraseTemporary(); }
        } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
        catch (Exception ignored) { /* Retain temporary state if framework shutdown fails. */ }
    }

    private void eraseTemporary() {
        try (var paths = Files.walk(temporary)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (IOException ignored) { /* Only this instance's private temporary directory is eligible. */ }
    }

    /** A closed workspace must not retain its source snapshots in the warm host runtime. */
    private final class ManagedEngine implements RefactorJ, JavaIntelligenceProvider {
        private RefactorJ delegate;
        ManagedEngine(RefactorJ delegate) { this.delegate = delegate; }
        private RefactorJ active() {
            if (delegate == null) throw new IllegalStateException("RefactorJ workspace is closed");
            return delegate;
        }
        @Override public JavaSemanticWorkspace workspace() { return active().workspace(); }
        @Override public List<Descriptor> available(Location location, Cancellation cancellation) {
            return active().available(location, cancellation);
        }
        @Override public Plan prepare(Requests.Request request, Cancellation cancellation) {
            return active().prepare(request, cancellation);
        }
        @Override public Validation validate(Plan plan, Cancellation cancellation) {
            return active().validate(plan, cancellation);
        }
        @Override public JavaIntelligence intelligence() {
            return ((JavaIntelligenceProvider) active()).intelligence();
        }
        @Override public void close() {
            synchronized (RefactorJRuntime.this) {
                if (delegate == null) return;
                try { delegate.close(); }
                finally { delegate = null; engines.remove(this); }
            }
        }
    }
}
