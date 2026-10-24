# Release

Build independently using Java 25: `./mvnw clean verify install`. The repository does not need
TurboJ to compile. Publish the parent and core/keymap/palette/swing artifacts together using
one version. Benchmarks are development tools, not a runtime requirement.

Before publication verify naming/coordinates, notices, tests and Javadocs, sign artifacts with
the publisher's own credentials and configure its Maven Central deployment profile externally.
This tree intentionally contains no deployment credentials or claim of an existing publication.
Pin `0.1.0` in consumers. Command IDs are application-owned; preserve ID compatibility when
persisting keymaps. JSON schema 1 is independent from artifact version.
