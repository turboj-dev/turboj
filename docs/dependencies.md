# Runtime dependency lock and preserved notices

The application runtime resolved the following artifacts during the verified Maven build. The root POM pins all of these versions, including transitive dependencies. The machine-readable [lock and original-jar checksums](runtime-dependencies.json) records declared licenses and exact source-archive URLs. JUnit is test-only and JMH is benchmark-only, so neither appears in this desktop runtime list.

| Artifact | Version | Upstream declared license | Corresponding source |
|---|---|---|---|
| org.eclipse.jdt:org.eclipse.jdt.core | 3.44.0 | EPL-2.0 | [Source archive](https://repo.maven.apache.org/maven2/org/eclipse/jdt/org.eclipse.jdt.core/3.44.0/org.eclipse.jdt.core-3.44.0-sources.jar) |
| org.eclipse.platform:org.eclipse.core.resources | 3.23.100 | EPL-2.0 | [Source archive](https://repo.maven.apache.org/maven2/org/eclipse/platform/org.eclipse.core.resources/3.23.100/org.eclipse.core.resources-3.23.100-sources.jar) |
| org.eclipse.platform:org.eclipse.core.expressions | 3.9.500 | EPL-2.0 | [Source archive](https://repo.maven.apache.org/maven2/org/eclipse/platform/org.eclipse.core.expressions/3.9.500/org.eclipse.core.expressions-3.9.500-sources.jar) |
| net.java.dev.jna:jna | 5.18.1 | LGPL-2.1-or-later / Apache-2.0 | [Source archive](https://repo.maven.apache.org/maven2/net/java/dev/jna/jna/5.18.1/jna-5.18.1-sources.jar) |
| org.eclipse.platform:org.eclipse.osgi | 3.24.0 | EPL-2.0 | [Source archive](https://repo.maven.apache.org/maven2/org/eclipse/platform/org.eclipse.osgi/3.24.0/org.eclipse.osgi-3.24.0-sources.jar) |
| org.eclipse.platform:org.eclipse.core.runtime | 3.34.100 | EPL-2.0 | [Source archive](https://repo.maven.apache.org/maven2/org/eclipse/platform/org.eclipse.core.runtime/3.34.100/org.eclipse.core.runtime-3.34.100-sources.jar) |
| org.eclipse.platform:org.eclipse.equinox.common | 3.20.300 | EPL-2.0 | [Source archive](https://repo.maven.apache.org/maven2/org/eclipse/platform/org.eclipse.equinox.common/3.20.300/org.eclipse.equinox.common-3.20.300-sources.jar) |
| org.eclipse.platform:org.eclipse.core.jobs | 3.15.700 | EPL-2.0 | [Source archive](https://repo.maven.apache.org/maven2/org/eclipse/platform/org.eclipse.core.jobs/3.15.700/org.eclipse.core.jobs-3.15.700-sources.jar) |
| org.eclipse.platform:org.eclipse.equinox.registry | 3.12.600 | EPL-2.0 | [Source archive](https://repo.maven.apache.org/maven2/org/eclipse/platform/org.eclipse.equinox.registry/3.12.600/org.eclipse.equinox.registry-3.12.600-sources.jar) |
| org.eclipse.platform:org.eclipse.equinox.preferences | 3.12.100 | EPL-2.0 | [Source archive](https://repo.maven.apache.org/maven2/org/eclipse/platform/org.eclipse.equinox.preferences/3.12.100/org.eclipse.equinox.preferences-3.12.100-sources.jar) |
| org.osgi:org.osgi.service.prefs | 1.1.2 | Apache-2.0 | [Source archive](https://repo.maven.apache.org/maven2/org/osgi/org.osgi.service.prefs/1.1.2/org.osgi.service.prefs-1.1.2-sources.jar) |
| org.osgi:osgi.annotation | 8.0.1 | Apache-2.0 | [Source archive](https://repo.maven.apache.org/maven2/org/osgi/osgi.annotation/8.0.1/osgi.annotation-8.0.1-sources.jar) |
| org.eclipse.platform:org.eclipse.core.contenttype | 3.9.800 | EPL-2.0 | [Source archive](https://repo.maven.apache.org/maven2/org/eclipse/platform/org.eclipse.core.contenttype/3.9.800/org.eclipse.core.contenttype-3.9.800-sources.jar) |
| org.eclipse.platform:org.eclipse.equinox.app | 1.7.500 | EPL-2.0 | [Source archive](https://repo.maven.apache.org/maven2/org/eclipse/platform/org.eclipse.equinox.app/1.7.500/org.eclipse.equinox.app-1.7.500-sources.jar) |
| org.eclipse.platform:org.eclipse.core.filesystem | 1.11.400 | EPL-2.0 | [Source archive](https://repo.maven.apache.org/maven2/org/eclipse/platform/org.eclipse.core.filesystem/1.11.400/org.eclipse.core.filesystem-1.11.400-sources.jar) |
| net.java.dev.jna:jna-platform | 5.18.1 | LGPL-2.1-or-later / Apache-2.0 | [Source archive](https://repo.maven.apache.org/maven2/net/java/dev/jna/jna-platform/5.18.1/jna-platform-5.18.1-sources.jar) |
| org.eclipse.platform:org.eclipse.text | 3.14.500 | EPL-2.0 | [Source archive](https://repo.maven.apache.org/maven2/org/eclipse/platform/org.eclipse.text/3.14.500/org.eclipse.text-3.14.500-sources.jar) |
| org.eclipse.platform:org.eclipse.core.commands | 3.12.500 | EPL-2.0 | [Source archive](https://repo.maven.apache.org/maven2/org/eclipse/platform/org.eclipse.core.commands/3.12.500/org.eclipse.core.commands-3.12.500-sources.jar) |
| org.eclipse.jdt:ecj | 3.44.0 | EPL-2.0 | [Source archive](https://repo.maven.apache.org/maven2/org/eclipse/jdt/ecj/3.44.0/ecj-3.44.0-sources.jar) |
| com.google.code.gson:gson | 2.13.2 | Apache-2.0 | [Source archive](https://repo.maven.apache.org/maven2/com/google/code/gson/gson/2.13.2/gson-2.13.2-sources.jar) |
| com.google.errorprone:error_prone_annotations | 2.41.0 | Apache 2.0 | [Source archive](https://repo.maven.apache.org/maven2/com/google/errorprone/error_prone_annotations/2.41.0/error_prone_annotations-2.41.0-sources.jar) |

Original legal files and POMs are preserved by artifact under `app/src/main/resources/third-party/`; packaging includes that directory inside the application jar. This prevents the identically named Eclipse `about.html` resources from being lost when Maven Shade combines dependencies. The full EPL 2.0 text is included separately. Gson and Error Prone annotations do not carry license files in their original jars; their unmodified upstream Apache 2.0 license texts are included from the exact release sources, identified in the manifest.

The lock currently contains 21 external runtime artifacts and 26 legal files extracted from their original jars, plus the supplemental license texts. Each source link derives from its exact Maven coordinates. All original packages retain their own licenses; TurboJ's MIT license applies only to TurboJ code. JNA and JNA Platform arrive transitively through the Eclipse platform and expose their upstream dual-license terms. TurboJ does not use JNA to implement an optimized renderer or launcher.

To regenerate the artifact-specific notices after a deliberate dependency update:

```sh
./mvnw -DskipTests package
python3 scripts/collect-notices.py
./mvnw -DskipTests package
```

The script reads only local resolved artifacts (`.cache/m2` by default, or the repository path supplied as its first argument). It performs no network access. Keep the supplemental upstream license files checked in. Review and update `runtime-dependencies.tsv` and the POM together when dependencies change.
