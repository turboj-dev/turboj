package dev.turboj.mavenj.core;

import dev.turboj.mavenj.api.MavenModels.*;
import org.apache.maven.settings.Settings;
import org.apache.maven.settings.building.*;
import org.apache.maven.settings.crypto.*;
import org.sonatype.plexus.components.cipher.DefaultPlexusCipher;
import org.sonatype.plexus.components.sec.dispatcher.DefaultSecDispatcher;
import java.nio.file.*;
import java.io.*;
import java.util.*;

final class SettingsSupport {
    final ImportRequest request;
    final Settings settings;
    final Properties system = new Properties(), user = new Properties();
    final List<Diagnostic> diagnostics = new ArrayList<> ();
    final Set<Path> observed;
    final List<String> secrets = new ArrayList<> ();
    final Path localRepository, userToolchains, globalToolchains;
    final Set<String> active = new LinkedHashSet<> (), inactive = new LinkedHashSet<> ();

    SettingsSupport(ImportRequest original, Path root, Set<Path> observed) {
        this.observed = observed;
        request = configuration(original, root);
        java.time.ZoneId
                .systemDefault(); // Stabilize the JVM's lazily initialized user.timezone before fingerprinting.
        system.putAll(System.getProperties());
        System.getenv().forEach((k, v) -> system.setProperty("env." + k, v));
        system.forEach((key, value) -> {
            if (sensitive(key.toString()))
                addSecret(value.toString());
        });
        if (request.javaHome() != null) {
            Path release = request.javaHome().resolve("release");
            observed.add(release);
            Properties jdk = new Properties();

            try (Reader r = Files.newBufferedReader(release)) {
                jdk.load(r);
                String version = jdk.getProperty("JAVA_VERSION", "").replace("\"", "");

                if (!version.isBlank())
                    system.setProperty("java.version", version);
                system.setProperty("java.home", request.javaHome().toString());
            } catch (IOException e) {
                diagnostics.add(Diagnostic.error("jdk.release",
                        "Cannot read selected JDK release metadata", release));
            }
        }
        user.putAll(request.userProperties());
        active.addAll(request.activeProfiles());
        inactive.addAll(request.inactiveProfiles());
        Path userSettings = request.userSettings() != null ? absolute(request.userSettings()) : Path
                .of(System.getProperty("user.home"), ".m2", "settings.xml");
        Path globalSettings = request.globalSettings();

        if (globalSettings == null) {
            String home = System.getProperty("maven.home");

            if (home == null || home.isBlank())
                home = System.getenv("MAVEN_HOME");
            if (home == null || home.isBlank())
                home = System.getenv("M2_HOME");
            if (home != null && !home.isBlank())
                globalSettings = Path.of(home).resolve("conf/settings.xml");
        }
        observed.add(userSettings);
        if (globalSettings != null)
            observed.add(absolute(globalSettings));
        Settings built = new Settings();

        try {
            XmlSafety.check(userSettings);
            if (globalSettings != null)
                XmlSafety.check(globalSettings);
            var r = new DefaultSettingsBuildingRequest().setUserSettingsFile(userSettings.toFile())
                    .setSystemProperties(system).setUserProperties(user);

            if (globalSettings != null)
                r.setGlobalSettingsFile(globalSettings.toFile());
            var result = new DefaultSettingsBuilderFactory().newInstance().build(r);
            built = result.getEffectiveSettings();
            result.getProblems().forEach(p -> diagnostics.add(new Diagnostic(p.getSeverity()
                    == SettingsProblem.Severity.WARNING ? Severity.WARNING : Severity.ERROR,
                    "settings.model", p.getMessage(), userSettings, p.getLineNumber(), p
                    .getColumnNumber())));
            var dispatcher = new DefaultSecDispatcher(new DefaultPlexusCipher());
            Path security = Path.of(System.getProperty("user.home"), ".m2",
                    "settings-security.xml");
            dispatcher.setConfigurationFile(security.toString());
            observed.add(security);
            XmlSafety.check(security);
            var decrypted = new DefaultSettingsDecrypter(dispatcher)
                    .decrypt(new DefaultSettingsDecryptionRequest(built));
            built.setServers(decrypted.getServers());
            built.setProxies(decrypted.getProxies());
            for (var server : built.getServers()) {
                addSecret(server.getPassword());
                addSecret(server.getPassphrase());
            }
            for (var proxy : built.getProxies())
                addSecret(proxy.getPassword());
            decrypted.getProblems().forEach(p -> diagnostics.add(Diagnostic
                    .error("settings.credentials", "Unable to decrypt configured Maven credentials",
                    userSettings)));
        } catch (Exception e) {
            diagnostics.add(Diagnostic.error("settings.model",
                    "Cannot load effective Maven settings: " + safe(e.getMessage()), userSettings));
        }
        String configuredToolchains = request.userProperties().get("maven.toolchains.file"),
                configuredGlobalToolchains = request.userProperties()
                .get("maven.globalToolchains.file");
        userToolchains = configuredToolchains == null ? Path.of(System.getProperty("user.home"),
                ".m2/toolchains.xml") : absolute(root.resolve(configuredToolchains));
        globalToolchains = configuredGlobalToolchains != null ? absolute(root
                .resolve(configuredGlobalToolchains)) : globalSettings
                != null ? absolute(globalSettings).resolveSibling("toolchains.xml") : null;
        observed.add(userToolchains);
        if (globalToolchains != null)
            observed.add(globalToolchains);
        settings = built;
        active.addAll(settings.getActiveProfiles());
        active.removeAll(inactive);
        String configured = request.userProperties().get("maven.repo.local");
        localRepository = absolute(root.resolve(request.localRepository() != null ? request
                .localRepository() : configured != null ? Path.of(configured) : settings
                .getLocalRepository() != null ? Path.of(settings.getLocalRepository()) : Path
                .of(System.getProperty("user.home"), ".m2", "repository")));
    }

    boolean offline() {
        return request.offline() || settings.isOffline();
    }

    void addSecret(String value) {
        if (value != null && !value.isEmpty())
            secrets.add(value);
    }

    String safe(String value) {
        String text = Objects.requireNonNullElse(value, "Unspecified Maven error");

        for (String secret : secrets)
            text = text.replace(secret, "[redacted]");
        return text
                .replaceAll("(?is)(<(?:password|passphrase|secret|token|credential)[^>]*>).*?(</(?:password|passphrase|secret|token|credential)>)",
                "$1[redacted]$2").replaceAll("(?i)(https?://)[^/@\\s]+:[^/@\\s]+@", "$1[redacted]@")
                .replaceAll("(?i)(password|token|secret|access_key)=([^&\\s]+)", "$1=[redacted]");
    }

    static boolean sensitive(String name) {
        return name.toLowerCase(Locale.ROOT)
                .matches(".*(password|passwd|passphrase|secret|token|credential|private.key|api[._-]?key|access[._-]?key|authorization).*");
    }

    List<org.apache.maven.model.Profile> profiles() {
        var result = new ArrayList<org.apache.maven.model.Profile> ();

        for (var source : settings.getProfiles()) {
            var p = new org.apache.maven.model.Profile();
            p.setId(source.getId());
            p.setSource("settings.xml");
            p.setProperties(source.getProperties());
            if (source.getActivation() != null) {
                var a = new org.apache.maven.model.Activation();
                var s = source.getActivation();
                a.setActiveByDefault(s.isActiveByDefault());
                a.setJdk(s.getJdk());
                if (s.getProperty() != null) {
                    var v = new org.apache.maven.model.ActivationProperty();
                    v.setName(s.getProperty().getName());
                    v.setValue(s.getProperty().getValue());
                    a.setProperty(v);
                }
                if (s.getFile() != null) {
                    var v = new org.apache.maven.model.ActivationFile();
                    v.setExists(s.getFile().getExists());
                    v.setMissing(s.getFile().getMissing());
                    a.setFile(v);
                }
                if (s.getOs() != null) {
                    var v = new org.apache.maven.model.ActivationOS();
                    v.setName(s.getOs().getName());
                    v.setFamily(s.getOs().getFamily());
                    v.setArch(s.getOs().getArch());
                    v.setVersion(s.getOs().getVersion());
                    a.setOs(v);
                }
                p.setActivation(a);
            }
            source.getRepositories().forEach(r -> p.addRepository(repository(r)));
            source.getPluginRepositories().forEach(r -> p.addPluginRepository(repository(r)));
            result.add(p);
        }
        return result;
    }

    static org.apache.maven.model.Repository repository(org.apache.maven.settings.Repository r) {
        var m = new org.apache.maven.model.Repository();
        m.setId(r.getId());
        m.setUrl(r.getUrl());
        m.setLayout(r.getLayout());
        if (r.getReleases() != null) {
            var p = new org.apache.maven.model.RepositoryPolicy();
            p.setEnabled(r.getReleases().isEnabled());
            p.setUpdatePolicy(r.getReleases().getUpdatePolicy());
            p.setChecksumPolicy(r.getReleases().getChecksumPolicy());
            m.setReleases(p);
        }
        if (r.getSnapshots() != null) {
            var p = new org.apache.maven.model.RepositoryPolicy();
            p.setEnabled(r.getSnapshots().isEnabled());
            p.setUpdatePolicy(r.getSnapshots().getUpdatePolicy());
            p.setChecksumPolicy(r.getSnapshots().getChecksumPolicy());
            m.setSnapshots(p);
        }
        return m;
    }

    private ImportRequest configuration(ImportRequest r, Path root) {
        for (Path ancestor = root; ancestor != null; ancestor = ancestor.getParent())
            if (Files.isDirectory(ancestor.resolve(".mvn"))) {
                root = ancestor;
                break;
            }
        Path config = root.resolve(".mvn/maven.config");
        observed.add(config);
        observed.add(root.resolve(".mvn/extensions.xml"));
        observed.add(root.resolve(".mvn/jvm.config"));
        var properties = new LinkedHashMap<String, String> ();
        var profiles = new LinkedHashSet<String> ();
        var disabled = new LinkedHashSet<String> ();
        Path userSettings = r.userSettings(), globalSettings = r.globalSettings();
        boolean offline = r.offline(), refresh = r.refreshSnapshots();

        if (Files.isRegularFile(config))
            try {
                String[] args = Files.readAllLines(config).stream().map(String::strip)
                        .filter(l -> !l.isEmpty() && !l.startsWith("#")).toArray(String[]::new);

                for (int i = 0; i < args.length; i++) {
                    String arg = args[i];

                    if (arg.startsWith("-D")) {
                        String value = arg.length() > 2 ? arg.substring(2) : args[++i];
                        int eq = value.indexOf('=');
                        properties.put(eq < 0 ? value : value.substring(0, eq), eq
                                < 0 ? "true" : value.substring(eq + 1));
                    } else if (arg.startsWith("-P") || arg.equals("--activate-profiles")) {
                        String value = arg.startsWith("-P") && arg.length() > 2 ? arg
                                .substring(2) : args[++i];

                        for (String profile : value.split(","))
                            if (profile.startsWith("!") || profile.startsWith("-"))
                                disabled.add(profile.substring(1));
                            else
                                profiles.add(profile.startsWith("+") ? profile
                                        .substring(1) : profile);
                    } else if (arg.equals("-o") || arg.equals("--offline"))
                        offline = true;
                    else if (arg.equals("-U") || arg.equals("--update-snapshots"))
                        refresh = true;
                    else if (arg.equals("-s") || arg.equals("--settings")) {
                        Path p = root.resolve(args[++i]).normalize();

                        if (userSettings == null)
                            userSettings = p;
                    } else if (arg.equals("-gs") || arg.equals("--global-settings")) {
                        Path p = root.resolve(args[++i]).normalize();

                        if (globalSettings == null)
                            globalSettings = p;
                    } else if (arg.equals("-t") || arg.equals("--toolchains")) {
                        Path p = root.resolve(args[++i]).normalize();
                        observed.add(p);
                        properties.put("maven.toolchains.file", p.toString());
                    } else if (arg.equals("-gt") || arg.equals("--global-toolchains")) {
                        Path p = root.resolve(args[++i]).normalize();
                        observed.add(p);
                        properties.put("maven.globalToolchains.file", p.toString());
                    } else if (!Set.of("-B", "--batch-mode", "--no-transfer-progress", "-ntp", "-q",
                            "--quiet", "-e", "--errors", "-V", "--show-version").contains(arg))
                        diagnostics.add(Diagnostic.warning("maven.config.option",
                                "Maven model import does not interpret option: " + arg, config));
                }
            } catch (Exception e) {
                diagnostics.add(Diagnostic.error("maven.config.invalid",
                        "Cannot read Maven configuration: " + e.getMessage(), config));
            }
        properties.putAll(r.userProperties());
        profiles.addAll(r.activeProfiles());
        disabled.addAll(r.inactiveProfiles());
        return new ImportRequest(r.project(), userSettings, globalSettings, r.localRepository(),
                profiles, disabled, properties, offline, r.allowedRepositoryUrls(), refresh, r
                .javaHome(), r.pomOverlays());
    }

    static Path absolute(Path p) {
        return p.toAbsolutePath().normalize();
    }
}
