# Runnable public examples

`java-17`, `java-21` and `java-25` declare explicit releases. `reactor` contains three dependent Java modules and an aggregator, with no installation requirement for the native path. `profiles-custom-roots` declares custom source/test roots and a Java 21 profile. `junit` uses real JUnit Jupiter and requires cached dependencies or explicitly allowed Maven Central access.

Additional generated acceptance fixtures live in `mavenj-core/src/test`, `mavenj-workspace/src/test`, TurboJ core/app/worker tests and the JMH corpus. They include parent/BOM dependency management, optional/excluded/transitive/classified artifacts, local authenticated HTTP mirrors, blocked redirects, snapshots, annotation processing, toolchains, malformed POMs, wrappers and 24-module import/12-module compilation workloads. No proprietary source is used.

Example: `mavenj inspect examples/reactor --json`. For the JUnit example: `mavenj inspect examples/junit --online --allow-repository https://repo.maven.apache.org/maven2`. Inspection never invokes the example's lifecycle plugins.
