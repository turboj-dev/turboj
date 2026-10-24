# Project import

Use the headless API from a background task:

```java
try (var maven = new dev.turboj.mavenj.core.DefaultMavenJ()) {
    var request = dev.turboj.mavenj.api.MavenModels.ImportRequest.offline(project);
    var workspace = maven.open(request, cancellation);
    workspace.modules().forEach(module -> {
        System.out.println(module.id() + " Java " + module.mainJava().languageLevel());
    });
}
```

`project` may be a directory containing `pom.xml` or the POM itself. The effective
model determines source/test/resource/output roots; standard Maven directory
names are defaults, not assumptions in the editor. Module directories and custom
module POM paths are supported. Missing or malformed models return structured
file/line diagnostics.

The importer observes module and parent POMs, BOM inputs, Maven settings,
settings-security metadata, project `.mvn` configuration, selected JDK release
metadata, module descriptors, file-activated profile inputs and generated-root
configuration. `.mvn/maven.config` is located in the project or the nearest
ancestor `.mvn` directory, matching project-level Maven configuration. Current
Maven 3.9+ one-argument-per-line syntax and comments are respected. Model-relevant
`-D`, `-P`, `-s`, `-gs`, `-t`, `-gt`, `-o` and `-U` options are read; unsupported options
produce diagnostics. Environment-driven JVM options and project scripts are not
executed to inspect metadata.

Global settings can be supplied explicitly. Otherwise, an available `maven.home`,
`MAVEN_HOME` or `M2_HOME` identifies `conf/settings.xml`; user settings default to
`~/.m2/settings.xml`. The effective local repository and configured mirrors apply
to all resolutions. See [repository policy](DEPENDENCY_RESOLUTION.md) before
enabling network access.

Project trust and repository access are separate choices. Passive import can
resolve authorized dependencies without executing wrappers, build extensions,
plugins, annotation processors or tests. Lifecycle execution and trusted native
processor/test execution belong to separate APIs. Source/Javadoc attachments are
requested on demand.

`Workspace.generation` identifies a completed import; `fingerprint` identifies its
configuration inputs. Unchanged imports reuse immutable snapshots. `refresh()`
forces update checks. Unsaved POM overlays are accepted without writing disk; a
configuration change does not replace unsaved Java buffers.

[Official Maven configuration reference](https://maven.apache.org/configure.html)
explains Maven config files and version-dependent command-line file syntax.

`Workspace.userToolchains` and `globalToolchains` carry the effective toolchain
configuration paths so host discovery respects `.mvn/maven.config` selections.
