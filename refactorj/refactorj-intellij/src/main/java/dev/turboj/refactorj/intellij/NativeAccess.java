package dev.turboj.refactorj.intellij;

import com.google.gson.JsonElement;
import com.intellij.ide.plugins.PluginManagerCore;
import com.intellij.openapi.extensions.PluginId;
import com.intellij.openapi.project.Project;
import java.lang.reflect.*;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Optional Java 25 library embedding, isolated from the IDE's own dependency versions. */
final class NativeAccess implements AutoCloseable {
    private final URLClassLoader loader;
    private final Object bridge;
    private final Method execute, close;
    NativeAccess(Project project) throws Exception {
        Path directory = PluginManagerCore.getPlugin(PluginId.getId("dev.turboj.refactorj.intellij")).getPluginPath().resolve("engine/lib");
        List<Path> paths; try (var files = Files.list(directory)) { paths = files.filter(p -> p.toString().endsWith(".jar")).sorted().toList(); }
        java.net.URL[] urls = new java.net.URL[paths.size()]; for (int i = 0; i < paths.size(); i++) urls[i] = paths.get(i).toUri().toURL();
        loader = new URLClassLoader(urls, ClassLoader.getPlatformClassLoader());
        try {
            Class<?> type = loader.loadClass("dev.turboj.refactorj.cli.EmbeddedBridge"); var settings = RefactorJSettings.get(project).getState();
            bridge = type.getConstructor(String.class, String.class, String.class).newInstance(project.getBasePath(), settings.sourceRoots, settings.classpath);
            execute = type.getMethod("execute", String.class, Object.class, List.class, AtomicBoolean.class); close = type.getMethod("close");
        } catch (Exception failure) { loader.close(); throw failure; }
    }
    JsonElement execute(String command, JsonElement argument, List<RefactorJBackend.Source> sources, AtomicBoolean cancelled) throws Exception {
        Object value = argument == null ? null : RefactorJBackend.JSON.fromJson(argument, Object.class);
        List<Map<String, Object>> snapshots = new ArrayList<>();
        for (var source : sources) snapshots.add(Map.of("file", source.file(), "text", source.text(), "stamp", source.stamp()));
        try { return RefactorJBackend.JSON.toJsonTree(execute.invoke(bridge, command, value, snapshots, cancelled)); }
        catch (InvocationTargetException failure) { if (failure.getCause() instanceof Exception exception) throw exception; throw failure; }
    }
    @Override public void close() throws Exception { try { close.invoke(bridge); } finally { loader.close(); } }
}
