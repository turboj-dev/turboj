package dev.turboj.mavenj.core;

import dev.turboj.mavenj.api.MavenModels.*;
import dev.turboj.mavenj.api.MavenModels.Module;
import org.apache.maven.model.Model;
import org.apache.maven.model.building.ModelBuildingResult;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.io.*;
import java.util.*;

/** Session-private immutable-result caches; neither Maven internals nor credential-bearing keys escape. */
final class ImportCache {
    record Effective(String configuration, String inputsHash, List<Path> inputs, Model model,
            ModelBuildingResult result, List<Diagnostic> diagnostics, boolean versionRanges,
            boolean snapshotModels) {}

    record Snapshot(String signature, String inputsHash, List<Path> inputs, Module module) {}

    final Map<Path, Effective> effective = new HashMap<> ();
    final Map<Path, Snapshot> snapshots = new HashMap<> ();
    long modelBuilds, modelHits, resolutions, resolutionHits;

    static String hash(String prefix, Map<Path, String> overlays, Collection<Path> paths) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(prefix.getBytes(StandardCharsets.UTF_8));
            for (Path path : paths.stream().sorted().toList()) {
                digest.update(path.toString().getBytes(StandardCharsets.UTF_8));
                String overlay = overlays.get(path);

                if (overlay != null) {
                    digest.update(overlay.getBytes(StandardCharsets.UTF_8));
                    continue;
                }
                try {
                    BasicFileAttributes attributes = Files.readAttributes(path,
                            BasicFileAttributes.class);
                    digest.update((attributes.size() + ":" + attributes.lastModifiedTime() + ":"
                            + attributes.isDirectory()).getBytes(StandardCharsets.UTF_8));
                    if (attributes.isRegularFile() && !path.toString().endsWith(".jar")
                            && attributes.size() < 16_000_000)
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

    static String configuration(SettingsSupport settings) {
        var sortedSystem = new TreeMap<String, String> ();
        settings.system.forEach((k, v) -> sortedSystem.put(k.toString(), v.toString()));
        return hash(settings.request.userProperties() + ":" + settings.request.activeProfiles()
                + ":" + settings.request.inactiveProfiles() + ":" + settings.localRepository + ":"
                + settings.request.allowedRepositoryUrls() + ":" + settings.offline() + ":"
                + sortedSystem, Map.of(), settings.observed);
    }
}
