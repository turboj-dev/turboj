# Java versions and toolchains

The effective compiler plugin configuration supplies release, source, target, encoding, preview flags, arguments and processor paths. Main and test configuration are separate. MavenJ does not default every project to the application's Java 25 language level. When Maven itself supplies a compiler-plugin default, the importer records the effective default and its provenance in diagnostics.

`Toolchains.select(workspace, toolchainsXml)` reads Maven toolchains through Apache Maven's `DefaultToolchainsReader` and requirement matchers. The extended overload accepts global toolchains and an explicit list of installed JDK candidates. Version ranges and vendor requirements follow Maven matching semantics. Missing or invalid configured toolchains produce errors, not a silent fallback.

Discovery reads JDK `release` metadata in the current installation, `JAVA_HOME`/`JDK_HOME`, standard macOS JavaVirtualMachines locations, `/usr/lib/jvm`, `.jdks` and SDKMAN candidates. It does not execute discovered binaries. macOS installations under both the user Library and system Library are supported.

Without an explicit toolchain requirement, an installed JDK matching the project language release is preferred. If absent, a newer installed runtime may compile with an explicit `--release`; a diagnostic states that execution on the missing older JDK remains unverified. For source/target-only configurations without a required toolchain, a newer installed compiler may be used with an explicit warning: these options constrain syntax/bytecode, not the JDK API surface. No `--release` is invented. Aggregator modules with `pom` packaging do not require a Java compilation JDK of their own. Preview features require their exact JDK generation.

TurboJ's normal compiler stays resident. With `--release`, it enforces the project's public Java API/bytecode level; source/target configurations use the selected JDK's system modules where applicable. The actual compiler implementation may be TurboJ's JDK 25 compiler, which is distinguished from the selected runtime. Explicit Maven lifecycle, processor execution and test/program runtimes use the selected JDK. Older-JDK preview sources that the resident compiler cannot represent are diagnosed and require the matching explicit Maven build.

A clean Mac needs a JDK with the project release/toolchain requirements for exact runtime verification. Missing installations are shown in project diagnostics. No JDK is automatically installed into system directories. A clean-machine installation test is separate from tests on a development Mac with installed JDKs.

Official reference: https://maven.apache.org/guides/mini/guide-using-toolchains.html
