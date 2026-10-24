package dev.turboj.mavenj.workspace;

import dev.turboj.mavenj.api.MavenModels.*;
import dev.turboj.mavenj.api.MavenModels.Module;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.apache.maven.toolchain.RequirementMatcherFactory;
import org.apache.maven.toolchain.io.DefaultToolchainsReader;
import org.codehaus.plexus.util.xml.Xpp3Dom;

/** JDK discovery reads installation metadata; it never executes a project or a discovered binary. */
public final class Toolchains {
    private Toolchains() {}

    public record Jdk(Path home, String version, String vendor) {
        public int feature() {
            return featureOf(version);
        }
    }

    public record Selection(Workspace workspace, List<Diagnostic> diagnostics,
            List<Jdk> installed) {
        public Selection {
            diagnostics = List.copyOf(diagnostics);
            installed = List.copyOf(installed);
        }
    }

    private record Configured(Jdk jdk, Map<String, String> provides) {}

    public static List<Jdk> discover() {
        var homes = new LinkedHashSet<Path> ();
        homes.add(Path.of(System.getProperty("java.home")));
        for (String key : List.of("JAVA_HOME", "JDK_HOME")) {
            String value = System.getenv(key);

            if (value != null && !value.isBlank())
                homes.add(Path.of(value));
        }
        Path user = Path.of(System.getProperty("user.home"));

        for (Path directory : List.of(user.resolve("Library/Java/JavaVirtualMachines"), Path
                .of("/Library/Java/JavaVirtualMachines"), Path
                .of("/System/Library/Java/JavaVirtualMachines"), Path.of("/usr/lib/jvm"), user
                .resolve(".jdks"), user.resolve(".sdkman/candidates/java"))) {
            if (Files.isDirectory(directory))
                try (var children = Files.list(directory)) {
                    children.sorted().forEach(path -> homes.add(Files.isDirectory(path
                            .resolve("Contents/Home")) ? path.resolve("Contents/Home") : path));
                } catch (IOException ignored) {
                    /* Inaccessible installations are not candidates. */
                }
        }
        return homes.stream().map(Toolchains::inspect).flatMap(Optional::stream)
                .collect(java.util.stream.Collectors.toMap(Jdk::home, j -> j, (a, b) -> a,
                LinkedHashMap::new)).values().stream().sorted(Comparator.comparingInt(Jdk::feature)
                .reversed().thenComparing(j -> j.home.toString())).toList();
    }

    public static Optional<Jdk> inspect(Path home) {
        if (home == null)
            return Optional.empty();
        try {
            Path path = home.toRealPath();

            if (!Files.isRegularFile(path.resolve("bin/javac")) && !Files.isRegularFile(path
                    .resolve("bin/javac.exe")))
                return Optional.empty();
            Properties metadata = new Properties();

            try (var reader = Files.newBufferedReader(path.resolve("release"))) {
                metadata.load(reader);
            }
            String version = unquote(metadata.getProperty("JAVA_VERSION", ""));

            if (featureOf(version) == 0)
                return Optional.empty();
            return Optional.of(new Jdk(path, version, unquote(metadata.getProperty("IMPLEMENTOR",
                    "unknown"))));
        } catch (IOException | IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public static Selection select(Workspace workspace, Path toolchainsXml) {
        return select(workspace, toolchainsXml == null ? workspace.userToolchains() : toolchainsXml,
                workspace.globalToolchains(), discover());
    }

    /** Explicit installed candidates make discovery testable and allow embedders to add managed JDKs. */
    public static Selection select(Workspace workspace, Path userToolchains, Path globalToolchains,
            List<Jdk> installed) {
        var diagnostics = new ArrayList<Diagnostic> ();
        var configured = new ArrayList<Configured> ();
        Path user = userToolchains == null ? Path.of(System.getProperty("user.home"), ".m2",
                "toolchains.xml") : userToolchains;
        configured.addAll(read(user, diagnostics));
        if (globalToolchains != null)
            configured.addAll(read(globalToolchains, diagnostics));
        var all = new LinkedHashMap<Path, Jdk> ();
        installed.forEach(j -> all.put(j.home, j));
        configured.forEach(c -> all.put(c.jdk.home, c.jdk));
        var updated = new ArrayList<Module> ();

        for (Module module : workspace.modules()) {
            if (module.packaging().equals("pom")) {
                updated.add(module);
                continue;
            }
            JavaConfiguration main = select(module.mainJava(), configured, List.copyOf(all
                    .values()), module.pom(), diagnostics);
            JavaConfiguration test = module.testJava().equals(module
                    .mainJava()) ? main : select(module.testJava(), configured, List.copyOf(all
                    .values()), module.pom(), diagnostics);
            updated.add(module.withJavaConfigurations(main, test));
        }
        var combined = new ArrayList<> (workspace.diagnostics());
        combined.addAll(diagnostics);
        var observed = new LinkedHashSet<> (workspace.observedFiles());
        observed.add(user.toAbsolutePath().normalize());
        if (globalToolchains != null)
            observed.add(globalToolchains.toAbsolutePath().normalize());
        Workspace selected = new Workspace(workspace.root(), workspace.rootPom(), workspace
                .generation(), updated, workspace.buildOrder(), List.copyOf(observed), workspace
                .fingerprint(), workspace.localRepository(), workspace.activeProfiles(), workspace
                .offline(), combined, workspace.durationNanos(), workspace.cacheHit(), user,
                globalToolchains);
        return new Selection(selected, diagnostics, List.copyOf(all.values()));
    }

    private static JavaConfiguration select(JavaConfiguration config, List<Configured> configured,
            List<Jdk> installed, Path pom, List<Diagnostic> diagnostics) {
        int feature = featureOf(config.languageLevel());

        if (feature == 0) {
            diagnostics.add(Diagnostic.error("JAVA_LEVEL_UNKNOWN",
                    "Effective Maven compiler source/release is unspecified or unresolved", pom));
            return config.withJavaHome(null);
        }
        Jdk selected = null;

        if (config.javaHome() != null) {
            selected = inspect(config.javaHome()).orElse(null);
            if (selected == null)
                diagnostics.add(Diagnostic.error("JDK_MISSING", "Configured JDK is not installed: "
                        + config.javaHome(), pom));
            else if (!config.toolchainRequirements().isEmpty()) {
                Jdk explicit = selected;
                boolean match = configured.stream().anyMatch(c -> c.jdk.home.equals(explicit.home)
                        && matches(c.provides, config.toolchainRequirements()));

                if (!match) {
                    diagnostics.add(Diagnostic.error("TOOLCHAIN_MISMATCH",
                            "Selected JDK does not satisfy the configured Maven toolchain", pom));
                    selected = null;
                }
            }
        } else if (!config.toolchainRequirements().isEmpty()) {
            selected = configured.stream().filter(c -> matches(c.provides, config
                    .toolchainRequirements())).map(Configured::jdk).findFirst().orElse(null);
            if (selected == null)
                diagnostics.add(Diagnostic.error("TOOLCHAIN_MISSING",
                        "No configured JDK matches toolchain requirements " + config
                        .toolchainRequirements(), pom));
        } else {
            selected = installed.stream().filter(j -> j.feature() == feature).findFirst()
                    .orElse(null);
            if (selected == null && !config.preview()) {
                Jdk current = inspect(Path.of(System.getProperty("java.home"))).orElse(null);

                if (current != null && current.feature() >= feature) {
                    selected = current;
                    if (config.release().isBlank())
                        diagnostics.add(Diagnostic.warning("JDK_SOURCE_TARGET_FALLBACK",
                                "Using installed JDK " + current.feature() + " with source "
                                + config.source() + " and target " + config.target()
                                + ". Source/target controls syntax and bytecode, not the available JDK APIs; configure release or a toolchain for an older API/runtime contract",
                                pom));
                    else
                        diagnostics.add(Diagnostic.warning("JDK_RELEASE_FALLBACK", "JDK " + feature
                                + " is not installed; compilation uses JDK " + current.feature()
                                + " with --release " + config.release()
                                + ". Runtime compatibility on JDK " + feature
                                + " remains unverified", pom));
                }
            }
            if (selected == null)
                diagnostics.add(Diagnostic.error("JDK_MISSING", "Install JDK " + feature
                        + " or configure a compatible Maven toolchain", pom));
        }
        if (selected != null && (selected.feature() < feature || config.preview() && selected
                .feature() != feature)) {
            diagnostics.add(Diagnostic.error("JDK_LANGUAGE_MISMATCH", "Selected JDK "
                    + selected.version + " cannot compile Java " + feature + (config
                    .preview() ? " preview" : ""), pom));
            selected = null;
        }
        return config.withJavaHome(selected == null ? null : selected.home);
    }

    private static List<Configured> read(Path path, List<Diagnostic> diagnostics) {
        if (!Files.exists(path))
            return List.of();
        try {
            if (Files.size(path) > 1_000_000)
                throw new IOException("Toolchains XML exceeds 1 MB");
            var factory = DocumentBuilderFactory.newInstance();
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            try (var input = Files.newInputStream(path)) {
                factory.newDocumentBuilder().parse(input);
            }
            var model = new DefaultToolchainsReader().read(path.toFile(), Map.of());
            var result = new ArrayList<Configured> ();

            for (var toolchain : model.getToolchains()) {
                if (!"jdk".equals(toolchain.getType()))
                    continue;
                if (!(toolchain.getConfiguration() instanceof Xpp3Dom xml) || xml
                        .getChild("jdkHome") == null) {
                    diagnostics.add(Diagnostic.error("TOOLCHAIN_HOME",
                            "JDK toolchain has no jdkHome", path));
                    continue;
                }
                String home = xml.getChild("jdkHome").getValue();

                if (home == null || home.isBlank()) {
                    diagnostics.add(Diagnostic.error("TOOLCHAIN_HOME",
                            "JDK toolchain has an empty jdkHome", path));
                    continue;
                }
                var jdk = inspect(Path.of(home));

                if (jdk.isEmpty()) {
                    diagnostics.add(Diagnostic.error("TOOLCHAIN_HOME",
                            "Toolchain JDK is unavailable: " + home, path));
                    continue;
                }
                var provides = new LinkedHashMap<String, String> ();
                toolchain.getProvides().forEach((k, v) -> provides.put(k.toString(), v.toString()));
                result.add(new Configured(jdk.get(), Map.copyOf(provides)));
            }
            return result;
        } catch (Exception failure) {
            diagnostics.add(Diagnostic.error("TOOLCHAIN_XML", "Cannot read toolchains: " + failure
                    .getMessage(), path));
            return List.of();
        }
    }

    private static boolean matches(Map<String, String> provides, Map<String, String> requirements) {
        for (var requirement : requirements.entrySet()) {
            String value = provides.get(requirement.getKey());

            if (value == null)
                return false;
            try {
                var matcher = requirement.getKey().equals("version") ? RequirementMatcherFactory
                        .createVersionMatcher(value) : RequirementMatcherFactory
                        .createExactMatcher(value);

                if (!matcher.matches(requirement.getValue()))
                    return false;
            } catch (IllegalArgumentException invalid) {
                return false;
            }
        }
        return true;
    }

    private static String unquote(String value) {
        return value.length() >= 2 && value.startsWith("\"") && value.endsWith("\"") ? value
                .substring(1, value.length() - 1) : value;
    }

    private static int featureOf(String version) {
        try {
            String value = version.startsWith("1.") ? version.substring(2) : version;
            return Integer.parseInt(value.split("[.\\-+_]", 2)[0]);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
