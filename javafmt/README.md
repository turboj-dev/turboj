# javafmt

**One Java style. Zero configuration.**

An independent Java 25 formatter with an original AST-guided layout engine,
Java API, CLI and editor adapters. Product version **0.1.0**, **JavaFMT Style 1**.
The name is provisional: prior projects use `javafmt`; no exclusive name or
trademark rights are claimed.

Four spaces, K&R braces, a 100-column target and conservative comments/literals.
There are no style switches. Formatting changes whitespace; it does not repair
code, remove imports, introduce braces or reorder declarations. Imports retain
their order. See the authoritative [style specification](docs/STYLE.md).

## Build and use

Requires JDK 25, including `javac`. From this directory:

```sh
./mvnw verify
./javafmt --version
./javafmt Hello.java
./javafmt -w Hello.java
./javafmt --check path/to/sources
./javafmt --diff Hello.java
printf 'class Hello{void main(){System.out.println("Hello");}}' | ./javafmt --stdin
```

The wrapper resolves pinned Maven dependencies on the first build. Runtime
formatting makes no network requests. Exit codes: 0 success, 1 differences in
check/diff mode, 2 invalid input or operational failure. See [CLI details](docs/CLI.md).

Create a host-specific distribution with its own Java runtime:

```sh
./scripts/package-cli.sh
```

The output is under `target/distribution/`; its `javafmt` launcher needs neither
TurboJ nor a separately installed JDK. Build separately for each OS/architecture.
This is a JVM distribution, not a GraalVM native executable.

## Java API

```java
import dev.javafmt.core.*;

var formatter = new JavaFormatter();
var request = new FormatRequest(source, documentVersion, LineEndingPolicy.PRESERVE);
FormatResult result = formatter.format(request);
if (result.success() && result.sourceVersion() == currentDocumentVersion) {
    // Apply ordered, nonoverlapping edits in one editor transaction.
    String updated = TextEdit.apply(source, result.edits());
    int updatedCaret = TextEdit.mapOffset(caret, result.edits());
}
```

`format(String)` returns canonical text or throws `FormatException`.
`format(FormatRequest)` and `formatRange(request, new TextRange(start, end))`
return explicit success/failure, original-version edits, diagnostics, formatted
text and the expanded range. Offsets are UTF-16, ends exclusive. Failures retain
the input and return no edits. The default formatter is thread-safe; custom
parser adapters must honor that contract. See [integration](docs/INTEGRATION.md).

## Modules and adapters

- `javafmt-parser`: JDT scanner/AST adapter, no Eclipse workspace.
- `javafmt-core`: original formatting policy, layout and validation; no UI dependency.
- `javafmt-cli`: CLI, safe writes and bounded resident JSONL service.
- `javafmt-benchmarks`: JMH and separate comparative measurements.
- `javafmt-test-fixtures`: original examples and attributed OpenJDK test inputs.
- `javafmt-intellij`: independently packaged IntelliJ formatting service.
- `javafmt-vscode`: independently packaged VS Code formatting provider.

The containing TurboJ checkout aggregates this project for development. This
directory also builds independently and can be extracted without TurboJ sources.
TurboJ uses the core directly in memory; external IDE adapters use one resident
Java process rather than starting a JVM for every formatting operation.

## Correctness and current scope

Changed output is reparsed and checked against the original raw token sequence
and structural AST. Tests cover idempotence, generated whitespace variations,
modern Java syntax, compile/runtime equivalence, comments, Unicode, text blocks,
range edits and editor undo. These checks are evidence, not a universal proof of
semantic equivalence. Unsupported or malformed source is rejected intact.

Range formatting limits edits, but still parses the complete file. True cached
incremental parsing is future work. Read [compatibility and limitations](docs/COMPATIBILITY.md),
[measured performance](docs/PERFORMANCE.md), [architecture](docs/ARCHITECTURE.md),
and [licenses](docs/LICENSES.md).

## CI and contribution

Build the pinned release, then run `./javafmt --check src` in CI. Check mode never
writes files. Maven/Gradle projects can invoke that CLI as a verification task;
dedicated build plugins are not included. A pre-commit hook may use the same
command and abort on a nonzero result. Automatic source rewriting is opt-in.

See [CONTRIBUTING.md](CONTRIBUTING.md) and [CHANGELOG.md](CHANGELOG.md).
