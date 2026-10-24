# Runtime licenses and provenance

RefactorJ's original code uses the MIT license in `../LICENSE`. Third-party
components keep their own terms. This audit covers the exact 0.1.0 development
runtime artifacts; it is not a trademark opinion or a claim of name exclusivity.
The RefactorJ and CommandJ names require review before public publication.

The runtime contains 37 third-party artifacts, copied byte-for-byte from Maven
artifacts. Most are nested intact inside `refactorj-runtime-0.1.0.jar` for the
embedded Equinox loader; Gson and the framework are also supplied as standalone
JARs. Duplicate framework copies have identical SHA-256 hashes. No upstream
implementation source is copied into RefactorJ and no dependency is relocated.
Eclipse's original processors are called through their pinned binary APIs.

| Artifact | Version | Upstream declared license |
| --- | --- | --- |
| `org.codehaus.mojo:animal-sniffer-annotations` | 1.9 | MIT license |
| `org.commonmark:commonmark` | 0.27.0 | BSD-2-Clause |
| `org.commonmark:commonmark-ext-gfm-tables` | 0.27.0 | BSD-2-Clause |
| `org.eclipse.jdt:ecj` | 3.44.0 | EPL-2.0 |
| `com.google.code.gson:gson` | 2.13.2 | Apache-2.0 |
| `net.java.dev.jna:jna` | 5.18.1 | LGPL-2.1-or-later, Apache-2.0 |
| `net.java.dev.jna:jna-platform` | 5.18.1 | LGPL-2.1-or-later, Apache-2.0 |
| `org.apache.felix:org.apache.felix.scr` | 2.2.18 | Apache-2.0 |
| `org.eclipse.platform:org.eclipse.core.commands` | 3.12.500 | EPL-2.0 |
| `org.eclipse.platform:org.eclipse.core.contenttype` | 3.9.800 | EPL-2.0 |
| `org.eclipse.platform:org.eclipse.core.expressions` | 3.9.500 | EPL-2.0 |
| `org.eclipse.platform:org.eclipse.core.filebuffers` | 3.8.500 | EPL-2.0 |
| `org.eclipse.platform:org.eclipse.core.filesystem` | 1.11.400 | EPL-2.0 |
| `org.eclipse.platform:org.eclipse.core.jobs` | 3.15.700 | EPL-2.0 |
| `org.eclipse.platform:org.eclipse.core.resources` | 3.23.100 | EPL-2.0 |
| `org.eclipse.platform:org.eclipse.core.runtime` | 3.34.100 | EPL-2.0 |
| `org.eclipse.platform:org.eclipse.core.variables` | 3.6.700 | EPL-2.0 |
| `org.eclipse.platform:org.eclipse.debug.core` | 3.23.200 | EPL-2.0 |
| `org.eclipse.platform:org.eclipse.equinox.app` | 1.7.500 | EPL-2.0 |
| `org.eclipse.platform:org.eclipse.equinox.common` | 3.20.300 | EPL-2.0 |
| `org.eclipse.platform:org.eclipse.equinox.preferences` | 3.12.100 | EPL-2.0 |
| `org.eclipse.platform:org.eclipse.equinox.registry` | 3.12.600 | EPL-2.0 |
| `org.eclipse.jdt:org.eclipse.jdt.core` | 3.44.0 | EPL-2.0 |
| `org.eclipse.jdt:org.eclipse.jdt.core.manipulation` | 1.23.200 | EPL-2.0 |
| `org.eclipse.jdt:org.eclipse.jdt.debug` | 3.25.0 | EPL-2.0 |
| `org.eclipse.jdt:org.eclipse.jdt.launching` | 3.24.0 | EPL-2.0 |
| `org.eclipse.jdt:org.eclipse.jdt.launching.macosx` | 3.6.300 | Eclipse Public License - v 2.0 |
| `org.eclipse.platform:org.eclipse.ltk.core.refactoring` | 3.15.100 | EPL-2.0 |
| `org.eclipse.platform:org.eclipse.osgi` | 3.24.0 | EPL-2.0 |
| `org.eclipse.platform:org.eclipse.search.core` | 3.16.600 | EPL-2.0 |
| `org.eclipse.platform:org.eclipse.text` | 3.14.500 | EPL-2.0 |
| `org.osgi:org.osgi.namespace.extender` | 1.0.1 | Apache License, Version 2.0 |
| `org.osgi:org.osgi.service.component` | 1.5.1 | Apache-2.0 |
| `org.osgi:org.osgi.service.prefs` | 1.1.2 | Apache-2.0 |
| `org.osgi:org.osgi.util.function` | 1.2.0 | Apache-2.0 |
| `org.osgi:org.osgi.util.promise` | 1.3.0 | Apache-2.0 |
| `org.osgi:osgi.annotation` | 8.0.1 | Apache-2.0 |

The authoritative original license/notice/about resources remain in each JAR.
`runtime-dependencies.json` records their hashes and paths, exact artifact and POM
checksums, inherited license declarations, and version-specific Maven Central
source archive URLs. `third-party/` preserves original POMs and the parent POM
that supplies a license when necessary. Eclipse component sources can be
obtained from the version-pinned source archive URLs in that inventory. All 37
source archive URLs returned HTTP 200 in a HEAD availability check on 2026-10-24.

Full EPL-2.0 text is included in `licenses/EPL-2.0.txt`, obtained from the Eclipse
Foundation's original https://www.eclipse.org/org/documents/epl-2.0/EPL-2.0.txt.
Gson's JAR does not include its full license; `licenses/gson-2.13.2-LICENSE` is the
unmodified version-tagged upstream file from
https://raw.githubusercontent.com/google/gson/gson-parent-2.13.2/LICENSE.
Animal Sniffer 1.9's original POM includes its full MIT license and copyright;
that POM is preserved. JNA is distributed using its Apache-2.0 option, retaining
all original JAR notices. Artifact-level declarations do not override notices
for code incorporated inside an upstream dependency.

Run `python3 scripts/audit-runtime.py --repository /path/to/maven/repository`
after Maven package. This offline audit matches every shipped JAR by exact hash,
reads licenses from POMs, records embedded legal resources, and rejects unknown
artifacts with no notice. `scripts/package.py` invokes it before assembling a
release. Run `python3 scripts/verify-distribution.py path/to/archive.zip` (also
accepts VSIX and multiple paths) to verify exact shipped bytes, preserved legal
resources, POMs and exclusion of test-only plugin code. The CLI ZIP and both IDE
plugins include this inventory, POM provenance,
supplemental licenses, and the unchanged runtime JARs. Remove stale dependency
provenance only after an explicit dependency review.

JUnit/JMH and IntelliJ/VS Code SDKs are build/test dependencies, not redistributed
as part of the semantic runtime. The IDE plugin code retains RefactorJ's MIT
license. Marketplace publication, trademark review, source release hosting and
signing have not been performed. A locally built ZIP/VSIX is not a public release.
