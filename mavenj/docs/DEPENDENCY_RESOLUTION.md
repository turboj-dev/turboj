# Dependency resolution

The backend uses Apache Maven **3.10.0**'s public
`org.apache.maven.repository.supplier.RepositorySystemSupplier` and its aligned
Apache Maven Resolver **2.0.24**. The repository system remains warm for the
`DefaultMavenJ` lifetime. Close the service to release its resources.

Resolver collects and mediates real dependency graphs. Effective-model dependency
management and BOM imports feed the graph; Resolver handles transitivity,
optional dependencies, exclusions, nearest-wins conflict resolution, scopes,
classifiers and Maven artifact types. Separate paths are produced for main
compilation, test compilation, program execution, test execution and annotation
processors. System-scoped artifacts use their declared local paths. Imported BOMs
are model inputs, not runtime JARs.

| Path | Dependency scopes |
| --- | --- |
| Main compile | compile, provided, system |
| Main runtime | compile, runtime, plus module main output |
| Test compile | compile, provided, system, runtime, test, plus main output |
| Test runtime | compile, provided, system, runtime, test, plus test/main outputs |
| Processor | Explicit processor paths and their resolved transitive dependencies |

Projects with `module-info.java` have a separate module path. Ordinary JARs on
that path become JPMS automatic modules; native compilation reports invalid
module names/resolution instead of silently falling back. Non-modular tests keep
a classpath; advanced patch-module test arrangements require an explicit Maven
build when the native compiler cannot represent them.

## Settings and repository policy

The official settings builder merges explicit/global and user settings, including
profiles, repositories, mirrors, proxies and server authentication. Maven's local
repository is used directly; there is no second downloaded-artifact cache.
`settings-security.xml` decryption uses Maven's own settings decrypter and Plexus
security dispatcher. Passwords are neither copied to project settings nor exposed
in public model diagnostics.

An empty `allowedRepositoryUrls` set permits **no remote access**, including when
`offline=false`. Authorization is checked after mirror selection and for
repositories introduced by dependency descriptors. Exact normalized repository
base URLs are required. External plain HTTP repositories are blocked; explicitly
authorized loopback HTTP is supported for local repositories/tests. HTTP redirects
are disabled so an authorized base URL cannot redirect to an unauthorized server.
Use the final HTTPS repository URL or an explicitly configured mirror. TLS
verification is never disabled. Existing cached artifacts can be used offline.

`attachment(request, moduleId, coordinates, "sources" | "javadoc", cancellation)`
resolves attachments only on demand under the same policy. Import does not download
all source/Javadoc JARs.

Repository policies follow Maven defaults: custom repositories enable releases
and snapshots unless explicitly disabled; the standard Central declaration
disables snapshots. Reactor substitution applies to ordinary Java binary outputs
and the standard `tests` classifier. Other classifiers require their actual
artifact and are never substituted with the module's main classes.

## Caching and refresh

Effective model caches track each POM's parent/BOM inputs, settings, profiles,
properties, selected JDK and overlays. Resolution snapshots track the effective
model, only its transitive reactor dependencies, local artifacts/POM metadata and
module/generated-root configuration. A changed leaf POM reuses unrelated modules.
Ordinary Java edits do not cause dependency resolution.

Snapshot and version-range cache expiry honors enabled repository `always`,
`interval:N`, `daily` and `never` policies. Offline access does not perform update
checks. Imported SNAPSHOT parent/BOM identities and their version metadata remain
cache inputs even when BOM expansion removes the original version declaration
from the effective POM. `refresh()` forces model/dependency refresh; `.mvn/maven.config` `-U` is
honored. `DefaultMavenJ.cacheStatistics()` exposes immutable counters for measuring
actual model builds, model hits, resolution work and snapshot reuse.

Downloads run on the calling background task; the API does not silently move work
onto Swing's EDT. Cancellation is checked between model/graph steps and during
transfers. Connection/request timeouts are bounded. The UI must use the supplied
asynchronous integration controller.

References: [Maven supplier API](https://maven.apache.org/ref/3-LATEST/apidocs/org/apache/maven/repository/supplier/RepositorySystemSupplier.html),
[Resolver configuration](https://maven.apache.org/resolver/configuration.html),
[Maven dependency mechanism](https://maven.apache.org/guides/introduction/introduction-to-dependency-mechanism.html),
[Maven settings](https://maven.apache.org/settings.html).
