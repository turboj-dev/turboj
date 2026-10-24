# RefactorJ

Semantic Java refactoring through a headless Java 25 API. TurboJ embeds the engine in its own JVM. CLI, LSP, MCP, IntelliJ and VS Code adapters use that same engine.

Version: **0.1.0**. The 33 typed operations cover rename, extraction, inlining, signatures, moves, hierarchy transformations, modernization, imports and quick fixes. Each operation has semantic eligibility checks; unsupported or unsafe cases return diagnostics rather than speculative edits. See [the exact feature boundaries](docs/FEATURES.md).

Preparation returns a read-only preview. Plans carry source and workspace versions, UTF-16 text edits, file operations and diagnostics. A separate transaction layer validates current content before application and supports undo/redo. No generated code executes during preparation or preview.

## Build

Use JDK 25 and Maven 3.9.11 or the included wrapper. From this checkout:

```
./mvnw -f javafmt/pom.xml install -DskipTests
./mvnw -f refactorj/pom.xml verify
python3 refactorj/scripts/package.py
```

The first command installs the optional FormatJ adapter's dependency, retaining the existing `dev.javafmt:javafmt-core:0.1.0` coordinates and `javafmt` command. The semantic API does not depend on FormatJ. A standalone checkout can build the API/runtime without that adapter with `./mvnw -pl refactorj-runtime -am verify`.

The distribution contains independent `refactorj`, `refactorj-lsp` and `refactorj-mcp` launchers. [CLI examples and protocols](docs/ADAPTERS.md) include preview-first usage, allowed roots and mutation authorization. External editor packages and actual host-test instructions live in `refactorj-intellij/` and `refactorj-vscode/`; they do not require TurboJ.

## Java API

```java
try (var runtime = new RefactorJRuntime()) {
    RefactorJ engine = runtime.openWorkspace(snapshot);
    Plan plan = engine.prepare(new Requests.Rename(location, "calculateTotal"), Cancellation.NONE);
    Validation validation = engine.validate(plan, Cancellation.NONE);
    // Present the plan. After explicit acceptance, publish via a WorkspaceTransactions store.
}
```

Imports: `dev.turboj.refactorj.api.*`, `dev.turboj.refactorj.api.Model.*`, and `dev.turboj.refactorj.runtime.RefactorJRuntime`. Construct and use the semantic runtime on a serial background worker. The public API exposes immutable values, not Eclipse objects.

[Architecture](docs/ARCHITECTURE.md) · [Integration](docs/INTEGRATION.md) · [Transactions](docs/TRANSACTIONS.md) · [Performance](docs/PERFORMANCE.md) · [Dependencies and licenses](docs/LICENSES.md) · [Release instructions](docs/RELEASE.md)

The implementation uses Eclipse JDT/LTK processors and original adapters. RefactorJ source is MIT; the bundled dependencies retain their own licenses and notices. The name has prior independent usage and is not claimed to be legally exclusive. No Maven Central, Marketplace or VS Code Marketplace publication is implied by the local release artifacts.
