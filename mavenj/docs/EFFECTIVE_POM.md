# Effective Maven models

MavenJ uses Apache Maven **3.10.0** `DefaultModelBuilderFactory` and its official
`ModelBuilder`. Maven parses and evaluates inheritance, relative parents,
properties, interpolation, dependency management, imported BOMs, activation and
explicit profiles, repository declarations, plugin management, plugin
configuration and model validation. MavenJ does not emulate these rules with an
XML DOM or run `mvn help:effective-pom` to import a project.

The public API returns immutable `MavenModels` records. Mandatory public types do
not expose Maven classes. `Module.effectivePom()` is a serialized effective model;
credential-like properties and credential values are redacted. This representation
is intended for inspection, not as a replacement for the original POM. It does not
include execution of plugin code or extension-provided lifecycle contributions.

## Inputs and profiles

`ImportRequest` accepts active/inactive profile IDs, user properties, user/global
settings, local repository override, selected Java home, offline state, allowed
repository URLs and unsaved POM overlays. The official model builder evaluates
profile activation. A selected JDK's `release` file supplies `java.version` and
`java.home` for JDK-sensitive metadata. POM and settings file-activation inputs are
watched and participate in effective-model invalidation.

Unsaved overlays use `ModelSource2`, including relative parent resolution. They do
not write project files. XML-declared encodings are honored for disk files;
overlay strings are encoded as UTF-8 with a corresponding in-memory declaration.
DTD-bearing POMs and settings are rejected before official Maven readers consume
them, including UTF-16 documents.

Bounded parent and imported-BOM version ranges use Resolver's version-range API
and select the highest matching version. Empty ranges and model ranges without an
upper bound produce errors. `Module.parentId()` identifies the concrete resolved
parent; the serialized effective POM may retain its declared parent range. Version
metadata participates in cache invalidation and repository update policies.

## Compiler configuration

Main and test configurations are separate. The model reads release/source/target,
encoding, compiler arguments, preview mode, generated-source directories,
processor coordinates/options, compiler `jdkToolchain` and toolchains-plugin
requirements. Processor dependencies are resolved but never executed by import.

Explicit compiler plugin defaults use Maven's documented version-specific rules:
prior to 3.8 the source/target default is 1.5; 3.8 uses 1.6; 3.9–3.10 use 1.7;
3.11 and later 3.x use 1.8. When the project does not pin a plugin version, the
embedded Maven 3.10.0 public `PluginVersions.COMPILER_PLUGIN_VERSION` supplies its
version and an informational diagnostic explains this assumption. An older
project wrapper can select different defaults; pinning the plugin and language
level avoids that ambiguity. TurboJ's runtime version never becomes the implicit
project language level.

Custom compiler implementations/executables, include/exclude filtering,
multirelease outputs and other unrepresented compiler behavior produce explicit
parity diagnostics. The native compiler can reject options it cannot faithfully
implement. An explicit Maven build remains authoritative for such configurations.

## Maven 4 boundary

At implementation time Maven 4 is **4.0.0-rc-7**, not a GA release. Maven 4 projects
using compatible POM model 4.0.0 can be inspected with the Maven 3.10 builder.
Maven 4 model 4.1.0 is rejected with official structured model diagnostics. A Maven
4 prerequisite is reported. MavenJ does not claim a binary-compatible Maven 4
model adapter or full Maven 4 build semantics. Explicit lifecycle execution uses
the project-selected distribution/wrapper, independently of the metadata adapter.

References: [Maven model builder](https://maven.apache.org/ref/3-LATEST/maven-model-builder/),
[Maven release history](https://maven.apache.org/docs/history.html),
[compiler parameters](https://maven.apache.org/plugins/maven-compiler-plugin/compile-mojo.html),
[default lifecycle bindings](https://maven.apache.org/ref/3-LATEST/maven-core/default-bindings.html).
