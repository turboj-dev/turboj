# Licensing and release status

javafmt's original implementation is licensed under the [MIT License](../LICENSE).
That license applies to javafmt code, not to bundled third-party components. This
document records the dependency and notice audit for the 0.1.0 development build;
it is not trademark clearance or a claim of legal exclusivity.

## Runtime components

The standalone CLI runtime was resolved from the independent `javafmt/pom.xml`,
using Maven's `includeScope=runtime` dependency report. It contains javafmt's core
and parser modules and the following 21 third-party artifacts. No TurboJ modules
are dependencies of the independent project.

| Artifact | Version | Upstream declared license |
| --- | --- | --- |
| `org.eclipse.jdt:org.eclipse.jdt.core` | 3.44.0 | EPL-2.0 |
| `org.eclipse.jdt:ecj` | 3.44.0 | EPL-2.0 |
| `org.eclipse.platform:org.eclipse.core.resources` | 3.23.100 | EPL-2.0 |
| `org.eclipse.platform:org.eclipse.core.expressions` | 3.9.500 | EPL-2.0 |
| `org.eclipse.platform:org.eclipse.osgi` | 3.24.0 | EPL-2.0 |
| `org.eclipse.platform:org.eclipse.core.runtime` | 3.34.100 | EPL-2.0 |
| `org.eclipse.platform:org.eclipse.equinox.common` | 3.20.300 | EPL-2.0 |
| `org.eclipse.platform:org.eclipse.core.jobs` | 3.15.700 | EPL-2.0 |
| `org.eclipse.platform:org.eclipse.equinox.registry` | 3.12.600 | EPL-2.0 |
| `org.eclipse.platform:org.eclipse.equinox.preferences` | 3.12.100 | EPL-2.0 |
| `org.eclipse.platform:org.eclipse.core.contenttype` | 3.9.800 | EPL-2.0 |
| `org.eclipse.platform:org.eclipse.equinox.app` | 1.7.500 | EPL-2.0 |
| `org.eclipse.platform:org.eclipse.core.filesystem` | 1.11.400 | EPL-2.0 |
| `org.eclipse.platform:org.eclipse.text` | 3.14.500 | EPL-2.0 |
| `org.eclipse.platform:org.eclipse.core.commands` | 3.12.500 | EPL-2.0 |
| `net.java.dev.jna:jna` | 5.18.1 | Apache-2.0 or LGPL-2.1-or-later |
| `net.java.dev.jna:jna-platform` | 5.18.1 | Apache-2.0 or LGPL-2.1-or-later |
| `org.osgi:org.osgi.service.prefs` | 1.1.2 | Apache-2.0 |
| `org.osgi:osgi.annotation` | 8.0.1 | Apache-2.0 |
| `com.google.code.gson:gson` | 2.13.2 | Apache-2.0 |
| `com.google.errorprone:error_prone_annotations` | 2.41.0 | Apache-2.0 |

javafmt uses JNA's Apache-2.0 option. The table summarizes artifact-level POM
declarations; individual upstream notices also identify incorporated components
and must remain available. No upstream Java implementation was copied into the
original layout engine. JDT supplies parsing and tokenization. Because the full
JDT artifact is shaded without minimization, the distribution also contains JDT
classes not called by javafmt, including Eclipse's formatter implementation;
javafmt's formatting policy and layout do not delegate to that implementation.

## Preserved notices and corresponding source

The shaded CLI JAR contains `javafmt-LICENSE.txt` and a `third-party/` directory:

- Every runtime artifact has its exact original Maven POM and all original
  `LICENSE`, `NOTICE`, `about` and related legal resources, preserved separately
  by artifact/version. Shading cannot overwrite these separate copies.
- `third-party/runtime-dependencies.json` records exact coordinates, original
  artifact and POM SHA-256 checksums, per-notice checksums, declared license URLs,
  and version-specific Maven Central source JAR URLs.
- Eclipse component source is available under EPL-2.0 through the source archive
  URLs in that manifest. The full license is included at
  `third-party/licenses/EPL-2.0.txt`, copied from the
  [Eclipse Foundation's original text](https://www.eclipse.org/org/documents/epl-2.0/EPL-2.0.txt).
- Gson and Error Prone annotation JARs lack full license resources. Their exact
  version-tagged upstream licenses are additionally bundled: [Gson
  2.13.2](https://raw.githubusercontent.com/google/gson/gson-parent-2.13.2/LICENSE)
  and [Error Prone
  2.41.0](https://raw.githubusercontent.com/google/error-prone/v2.41.0/COPYING).

The same manifest is available in the source tree as
[`runtime-dependencies.json`](runtime-dependencies.json). Source archive URLs
identify the original component sources; source JARs are not embedded in the
runtime. No dependency code is relocated or intentionally modified. Shading
removes module/signature metadata and merges service-loader registrations.

## Reproduce the notice audit

From the standalone `javafmt/` directory, with JDK 25 and Python 3.9 or newer:

```sh
./mvnw -q -pl javafmt-cli -am -DskipTests package \
  org.apache.maven.plugins:maven-dependency-plugin:3.8.1:list \
  -DincludeScope=runtime \
  -DoutputFile=target/runtime-dependencies.txt \
  -DoutputAbsoluteArtifactFilename=true
python3 scripts/collect-notices.py
./mvnw -q -pl javafmt-cli -am package
python3 scripts/collect-notices.py --verify-jar javafmt-cli/target/javafmt.jar
```

The first package phase resolves reactor artifacts before Maven emits its
dependency report. The collection script reads actual resolved JAR paths, copies
legal files byte-for-byte, and runs entirely offline. The second package phase
includes the collected resources. Verification compares their exact bytes
against the shaded JAR and rejects TurboJ, JUnit, JMH, google-java-format and
fixture contents. All discovered shaded Maven metadata must be covered by the
manifest. Some Eclipse/OSGi JARs omit that metadata; the resolved dependency
report remains the complete inventory.

If dependencies change, regenerate and review notices before packaging. The
script refuses unknown no-notice artifacts and leftover directories from
removed versions, requiring explicit review instead of silently carrying stale
legal material forward. Supplemental license files are checked in under
`scripts/legal/`; their provenance and SHA-256 hashes are recorded in the
generated resource manifest.

## Development-only material

JUnit is test scope. JMH and google-java-format are confined to the benchmark
module. OpenJDK source excerpts are a separate test corpus with their original
headers, provenance and license files in
[`javafmt-test-fixtures/openjdk/`](../javafmt-test-fixtures/openjdk/README.md).
These are not bundled in the CLI runtime and are not relicensed as MIT.

The optional `jlink` distribution also contains its selected JDK runtime. Its
vendor license and module notices remain under `runtime/legal/`; those terms are
separate from javafmt's MIT license. The runtime is built from the local JDK, so
its exact vendor/version must be recorded for each published release. IDE SDKs
and their download/build tooling are development dependencies; this runtime
inventory is not a blanket license statement for an IDE distribution.

## Name and publication status

The name `javafmt` is already used by an unrelated open-source project,
[`tokuhirom/javafmt`](https://github.com/tokuhirom/javafmt), confirmed during this
implementation. This project is independent and makes no affiliation,
registration or exclusive-name claim. Product naming and trademark clearance
remain unresolved before public branding or marketplace publication. A built
JAR or extension package does not imply marketplace acceptance or a published
release.
