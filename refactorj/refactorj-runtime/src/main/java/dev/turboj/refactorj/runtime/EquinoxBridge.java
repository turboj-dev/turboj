package dev.turboj.refactorj.runtime;

import dev.turboj.refactorj.api.RefactorJ;
import dev.turboj.refactorj.api.Model.WorkspaceSnapshot;
import org.eclipse.osgi.launch.EquinoxFactory;
import org.osgi.framework.Bundle;
import org.osgi.framework.FrameworkEvent;
import org.osgi.framework.launch.Framework;
import java.io.IOException;
import java.nio.file.Path;
import java.util.*;
import java.util.jar.JarFile;

/** Loaded only beside the original Equinox JAR, preserving its system-bundle manifest. */
public final class EquinoxBridge implements FrameworkBridge {
    private Framework framework;
    private Bundle implementation;

    @Override public void start(Path directory, List<Path> bundles) throws Exception {
        framework = new EquinoxFactory().newFramework(Map.of(
                "org.osgi.framework.storage", directory.resolve("configuration").toString(),
                "osgi.instance.area", directory.resolve("workspace").toUri().toString(),
                "osgi.noShutdown", "true", "eclipse.ignoreApp", "true",
                "osgi.framework.useSystemProperties", "false",
                "osgi.contextClassLoaderParent", "fwk",
                "osgi.parentClassloader", "fwk",
                "org.osgi.framework.bootdelegation", "dev.turboj.refactorj.api,dev.turboj.refactorj.api.*"));
        framework.init();
        for (Path path : bundles) {
            try (JarFile jar = new JarFile(path.toFile())) {
                if (jar.getManifest() == null) continue;
                String symbolic = jar.getManifest().getMainAttributes().getValue("Bundle-SymbolicName");
                if (symbolic == null || symbolic.split(";")[0].equals("org.eclipse.osgi")) continue;
            }
            Bundle bundle = framework.getBundleContext().installBundle(path.toUri().toString());
            if ("dev.turboj.refactorj.jdt".equals(bundle.getSymbolicName())) implementation = bundle;
        }
        if (implementation == null) throw new IOException("The RefactorJ implementation bundle is missing");
        framework.start();
        for (Bundle bundle : framework.getBundleContext().getBundles())
            if ("org.apache.felix.scr".equals(bundle.getSymbolicName())) bundle.start();
        List<Exception> failures = new ArrayList<>();
        for (Bundle bundle : framework.getBundleContext().getBundles()) {
            if (bundle.getBundleId() == 0 || bundle == implementation || bundle.getHeaders().get("Fragment-Host") != null) continue;
            try { bundle.start(Bundle.START_ACTIVATION_POLICY); } catch (Exception error) { failures.add(error); }
        }
        try { implementation.start(); }
        catch (Exception error) { failures.forEach(error::addSuppressed); throw error; }
    }

    @Override public RefactorJ open(WorkspaceSnapshot snapshot) throws Exception {
        Class<?> type = implementation.loadClass("dev.turboj.refactorj.jdt.LtkRefactorJ");
        return (RefactorJ) type.getConstructor(WorkspaceSnapshot.class).newInstance(snapshot);
    }
    @Override public boolean stop() throws Exception {
        if (framework == null) return true;
        framework.stop();
        return framework.waitForStop(5000).getType() == FrameworkEvent.STOPPED;
    }
}
