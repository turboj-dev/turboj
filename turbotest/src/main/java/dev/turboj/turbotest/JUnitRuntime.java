package dev.turboj.turbotest;

import com.google.gson.Gson;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.jar.JarFile;

/** Supplies only JUnit libraries to the user's compiler, even when the host is a shaded application. */
public final class JUnitRuntime implements AutoCloseable {
    private final Path temporary;
    private final List<Path> junit;
    private Path projectHelpers;

    public JUnitRuntime() throws Exception {
        Path origin = origin(WorkerEntry.class);
        Path created = null;
        List<Path> libraries;
        if (Files.isDirectory(origin)) {
            try (var paths = Files.list(origin.resolve("turbotest/junit"))) {
                libraries = paths.filter(path -> path.getFileName().toString().endsWith(".jar")).sorted().toList();
            }
        } else {
            created = Files.createTempDirectory("turbotest-junit-");
            try (JarFile archive = new JarFile(origin.toFile())) {
                for (var entry : archive.stream().filter(entry -> entry.getName().startsWith("turbotest/junit/") && entry.getName().endsWith(".jar")).toList()) {
                    String name = entry.getName().substring("turbotest/junit/".length());
                    if (!name.matches("[A-Za-z0-9_.-]+\\.jar")) throw new IOException("Invalid embedded JUnit path");
                    try (InputStream input = archive.getInputStream(entry)) { Files.copy(input, created.resolve(name)); }
                }
            }
            try (var paths = Files.list(created)) { libraries = paths.sorted().toList(); }
        }
        temporary = created;
        if (libraries.isEmpty()) { close(); throw new IOException("JUnit runtime libraries are missing; rebuild the distribution"); }
        junit = List.copyOf(libraries);
    }

    public List<Path> compilationClasspath() { return junit; }
    public List<Path> executionClasspath() throws Exception {
        var paths = new LinkedHashSet<>(junit);
        paths.add(origin(WorkerEntry.class)); paths.add(origin(Gson.class));
        return List.copyOf(paths);
    }

    /**
     * Keeps the project's JUnit engines and versions authoritative. Only the host launcher and
     * TurboTest transport are supplied; bundled Jupiter APIs/engines are never added to this path.
     */
    public synchronized List<Path> projectExecutionClasspath(List<Path> projectClasspath) throws Exception {
        boolean engine=false,launcher=false;
        for(Path path:projectClasspath)if(Files.isRegularFile(path)&&path.toString().endsWith(".jar"))try(JarFile jar=new JarFile(path.toFile())) {
            engine|=jar.getEntry("META-INF/services/org.junit.platform.engine.TestEngine")!=null;
            launcher|=jar.getEntry("org/junit/platform/launcher/Launcher.class")!=null;
        }
        if(!engine)throw new IllegalArgumentException("The Maven test runtime has no JUnit Platform test engine; declare the appropriate project test dependency");
        var paths=new LinkedHashSet<>(projectClasspath);
        if(!launcher)paths.add(junit.stream().filter(p->p.getFileName().toString().startsWith("junit-platform-launcher-")).findFirst().orElseThrow());
        if(projectHelpers==null) {
            projectHelpers=Files.createTempFile("turbotest-transport-",".jar");
            Set<String> copied=new HashSet<>();
            try(var out=new java.util.jar.JarOutputStream(Files.newOutputStream(projectHelpers))) {
                copyPackage(origin(WorkerEntry.class),"dev/turboj/turbotest/",out,copied);
                copyPackage(origin(Gson.class),"com/google/gson/",out,copied);
            }
        }
        paths.add(projectHelpers);return List.copyOf(paths);
    }

    private static void copyPackage(Path origin,String prefix,java.util.jar.JarOutputStream out,Set<String> copied)throws IOException {
        if(Files.isDirectory(origin)) {
            try(var files=Files.walk(origin.resolve(prefix))){for(Path file:files.filter(p->p.toString().endsWith(".class")).toList()){
                String name=origin.relativize(file).toString().replace(java.io.File.separatorChar,'/');if(!copied.add(name))continue;
                out.putNextEntry(new java.util.jar.JarEntry(name));Files.copy(file,out);out.closeEntry();
            }}
        } else try(JarFile jar=new JarFile(origin.toFile())) {
            for(var entry:jar.stream().filter(e->e.getName().startsWith(prefix)&&e.getName().endsWith(".class")).toList()) {
                if(!copied.add(entry.getName()))continue;out.putNextEntry(new java.util.jar.JarEntry(entry.getName()));try(var input=jar.getInputStream(entry)){input.transferTo(out);}out.closeEntry();
            }
        }
    }

    private static Path origin(Class<?> type) throws Exception {
        return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toAbsolutePath().normalize();
    }

    @Override public void close() {
        if(projectHelpers!=null)try{Files.deleteIfExists(projectHelpers);}catch(IOException ignored){}
        if (temporary == null) return;
        try (var paths = Files.walk(temporary)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (IOException ignored) { /* Private extraction can be reclaimed by the host temporary-directory cleanup. */ }
    }
}
