package dev.turboj.testing;

import java.nio.file.Path;

final class MavenTestRepository {
    private MavenTestRepository() { }

    static Path resolve(Path checkout) {
        Path configured = Path.of(System.getProperty("maven.repo.local", ".cache/m2"));
        return (configured.isAbsolute() ? configured : checkout.resolve(configured)).normalize();
    }
}
