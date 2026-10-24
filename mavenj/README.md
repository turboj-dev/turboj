# MavenJ

Reusable Maven project models and dependency resolution, embedded as ordinary Java calls.

MavenJ uses Apache Maven 3.10.0 Model Builder and Maven Resolver 2.0.24. It is independent of TurboJ, Swing, CommandJ, RefactorJ, FormatJ and TurboTest. TurboJ consumes `mavenj-core` and `mavenj-workspace`; its editing path never requires the CLI or MCP adapter.

## Build and use

Requires a JDK 25 to run MavenJ itself. Imported projects retain their own Java release and toolchain configuration.

```sh
./mvnw verify
java -jar mavenj-cli/target/mavenj-cli-0.1.0-all.jar inspect examples/reactor --json
java -jar mavenj-cli/target/mavenj-cli-0.1.0-all.jar classpath examples/reactor --scope test
```

The commands default to offline import. Enable only explicitly permitted repository URLs with `--online --allow-repository https://repo.maven.apache.org/maven2`. Existing local Maven repositories are reused. No plugin, wrapper, test or annotation processor executes during import.

```java
import dev.turboj.mavenj.api.MavenModels.ImportRequest;
import dev.turboj.mavenj.core.DefaultMavenJ;
import dev.turboj.mavenj.workspace.Toolchains;

try (var maven = new DefaultMavenJ()) {
    var imported = maven.open(ImportRequest.offline(projectDirectory));
    var workspace = Toolchains.select(imported, null).workspace();
    workspace.modules().forEach(module ->
        System.out.println(module.coordinates().gav() + " Java " + module.mainJava().languageLevel()));
}
```

Import and resolution are blocking headless operations: GUI hosts schedule them on bounded background workers. Published snapshots are immutable and carry a generation. Cached snapshots and classpaths are safe for immediate reads.

## Modules

| Artifact | Responsibility |
|---|---|
| `mavenj-api` | Immutable model, scoped classpaths, diagnostics, cancellation and facade |
| `mavenj-core` | Official effective model builder, settings, Resolver, incremental caches and reactor graph |
| `mavenj-workspace` | JDK/toolchain selection, metadata watcher, lossless POM previews, explicit Maven lifecycle |
| `mavenj-cli` | Standalone inspection and JSON reports |
| `mavenj-mcp` | Optional root-restricted MCP inspection and explicitly authorized POM edits |
| `mavenj-benchmarks` | JMH offline import and cached lookup measurements |

Model/resolver packages share one core artifact to avoid an artificial dependency chain. Tests live alongside the modules they exercise. Maven coordinates are `dev.turboj.mavenj:*:0.1.0`; Maven Central publication has not been performed.

## References

Start with [architecture](docs/ARCHITECTURE.md), [project import](docs/PROJECT_IMPORT.md), [effective POM](docs/EFFECTIVE_POM.md), [dependency resolution](docs/DEPENDENCY_RESOLUTION.md), [multi-module workspaces](docs/MULTI_MODULE.md), [toolchains](docs/TOOLCHAINS.md), [security](docs/SECURITY.md), [CLI](docs/CLI.md), [MCP](docs/MCP.md), and [TurboJ integration](docs/TURBOJ.md).

Maven 4.1 model semantics, arbitrary plugins/extensions, custom Surefire providers and older-JDK preview compilation are not silently emulated. Diagnostics distinguish the native development path from an explicit Maven lifecycle. See the detailed compatibility boundaries in the integration documentation.

MavenJ source is MIT licensed. Apache Maven, Maven Resolver and their dependencies retain their original licenses and notices; see `NOTICE` and the distribution inventory.
