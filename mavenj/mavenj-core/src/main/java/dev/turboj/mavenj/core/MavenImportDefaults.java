package dev.turboj.mavenj.core;

import dev.turboj.mavenj.api.MavenModels.ImportRequest;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import org.apache.maven.settings.building.DefaultSettingsBuilderFactory;
import org.apache.maven.settings.building.DefaultSettingsBuildingRequest;
import org.apache.maven.settings.building.SettingsBuildingException;

/**
 * Opt-in desktop policy: Maven Central and repositories configured by the user.
 * This does not change the explicit allowlist policy of ordinary MavenJ requests.
 */
public final class MavenImportDefaults {
    public static final String CENTRAL = "https://repo.maven.apache.org/maven2";

    private MavenImportDefaults() {}

    /** Performs no I/O. Effective Maven settings may still require offline mode. */
    public static ImportRequest online(Path project) {
        return new ImportRequest(project, null, null, null, Set.of(), Set.of(), Map.of(),
                false, Set.of(CENTRAL), false, null, Map.of());
    }

    /**
     * Reads installed/user settings through Maven's settings builder. Call off the UI thread.
     * Project POMs and .mvn/maven.config cannot grant access to a new repository.
     * No credentials are returned or persisted by this method.
     */
    public static ImportRequest withUserRepositories(ImportRequest request) throws IOException {
        Path userSettings = request.userSettings() == null
                ? Path.of(System.getProperty("user.home"), ".m2", "settings.xml")
                : request.userSettings();
        Path globalSettings = request.globalSettings();
        if (globalSettings == null) {
            String home = System.getProperty("maven.home");
            if (home == null || home.isBlank()) home = System.getenv("MAVEN_HOME");
            if (home == null || home.isBlank()) home = System.getenv("M2_HOME");
            if (home != null && !home.isBlank()) globalSettings = Path.of(home, "conf", "settings.xml");
        }
        XmlSafety.check(userSettings);
        if (globalSettings != null) XmlSafety.check(globalSettings);
        Properties system = new Properties();
        system.putAll(System.getProperties());
        System.getenv().forEach((key, value) -> system.setProperty("env." + key, value));
        Properties user = new Properties();
        user.putAll(request.userProperties());
        var building = new DefaultSettingsBuildingRequest().setUserSettingsFile(userSettings.toFile())
                .setSystemProperties(system).setUserProperties(user);
        if (globalSettings != null) building.setGlobalSettingsFile(globalSettings.toFile());
        var permitted = new LinkedHashSet<String>();
        permitted.add(CENTRAL);
        try {
            var settings = new DefaultSettingsBuilderFactory().newInstance().build(building).getEffectiveSettings();
            settings.getMirrors().stream().filter(mirror -> !mirror.isBlocked())
                    .forEach(mirror -> addRepository(permitted, mirror.getUrl()));
            settings.getProfiles().forEach(profile -> profile.getRepositories()
                    .forEach(repository -> addRepository(permitted, repository.getUrl())));
        } catch (SettingsBuildingException failure) {
            // Maven's exception can embed configuration values; keep credentials out of diagnostics.
            throw new IOException("Cannot read Maven settings. Check user/global settings.xml in Maven Settings.");
        }
        return new ImportRequest(request.project(), request.userSettings(), request.globalSettings(),
                request.localRepository(), request.activeProfiles(), request.inactiveProfiles(), request.userProperties(),
                request.offline(), permitted, request.refreshSnapshots(), request.javaHome(), request.pomOverlays());
    }

    private static void addRepository(Set<String> repositories, String url) {
        if (url == null) return;
        try {
            URI uri = URI.create(url);
            if (uri.isAbsolute() && uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null)
                repositories.add(url);
        } catch (IllegalArgumentException ignored) {
            // Effective model diagnostics report invalid repository declarations during import.
        }
    }
}
