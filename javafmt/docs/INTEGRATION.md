# Integration

## In-process Java

Depend on `dev.javafmt:javafmt-core:0.1.0` from a local reactor or locally installed
artifact (`./mvnw install`). No public Maven repository publication is implied.
Core depends on the parser module; neither depends on an editor or UI toolkit.

Capture an immutable source snapshot/version, invoke `JavaFormatter` on a worker,
then verify document identity and version before applying the result. Apply
`TextEdit` values backwards to a mutable editor, or use `TextEdit.apply` for an
immutable string. All ranges use original-source UTF-16 coordinates, half open.
`TextEdit.mapOffset` maps caret/marker positions with right affinity at an edit's
end. Selection anchors must be mapped independently.

Group edits in one undo transaction. Never apply stale results or replace the
entire buffer merely because `formattedText` is available. `FormatResult` includes
the expanded region for range requests; callers must allow safe expansion or
decline the operation. It does not imply an incremental parser.

`LF` canonicalizes newly generated whitespace. `PRESERVE` uses the first physical
source line ending, while keeping literals/comments verbatim under either mode.

## TurboJ

Code → Format Document (`Cmd+Option+L`), Format Selection and optional Format on
Save use the API directly. The bounded background queue supersedes old requests.
Sparse edits preserve caret, selection, viewport and one-step undo. This behavior
does not depend on the active theme. The containing checkout documents native GUI
tests and save-failure policy in `docs/javafmt-integration.md`.

## External IDEs

[IntelliJ adapter](../javafmt-intellij/README.md) and
[VS Code adapter](../javafmt-vscode/README.md) have independent packaging and
host-test instructions. They use the same engine through the bounded resident
JSONL service described in [CLI.md](CLI.md). There is no HTTP listener and no
TurboJ installation dependency. An adapter supplies its own request identity,
document version and cancellation policy; the formatter never executes source.

Format-on-save behavior is opt-in through each editor's own settings. A formatter
must not override unrelated Java tooling or assume it owns the project classpath.

## API documentation

The public immutable records document the data contract in source. Generate HTML
API documentation after a build with Maven's Javadoc plugin, or browse
`javafmt-core/src/main/java/dev/javafmt/core`. Use the exact product version in
automation to avoid unreviewed formatting changes.
