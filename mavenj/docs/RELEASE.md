# Release preparation

Build independently from the MavenJ directory with JDK 25:

```sh
./mvnw verify
./mvnw -Prelease-artifacts package
python3 scripts/package.py
```

The independent artifacts use `dev.turboj.mavenj` and version `0.1.0`. The release profile attaches source and API documentation JARs. The packaging script assembles CLI/MCP launchers with original dependency JARs, legal resources and checksums; it verifies packaged executable startup before producing the archive.

No Maven Central upload, signing, public namespace claim or repository creation is implied by local packaging. Publication requires the account that controls `dev.turboj`, signing credentials, a reviewed release version and verified namespace/name availability. Public search is not a trademark clearance. MavenJ is not an Apache project; retain the Apache attribution and all upstream licenses.

Maven 4 remains a separately evaluated compatibility boundary: wrapper lifecycle preserves the project-selected distribution, while the embedded model implementation is Maven 3.10. Do not advertise Maven 4.1-model parity or arbitrary plugin parity without matching acceptance fixtures.
