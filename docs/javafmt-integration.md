# javafmt in TurboJ

TurboJ calls `dev.javafmt.core.JavaFormatter` directly in memory. The independent
`javafmt/` Maven project owns parsing, style, token protection and layout; it has
no dependency on Swing or TurboJ. TurboJ pins `javafmt-core` 0.1.0 and JavaFMT
Style 1. No CLI, process, IPC or HTTP request is used for native formatting.

## Commands

The **Code** menu provides:

- **Format Document** — Cmd+Option+L on macOS; Ctrl+Alt+L elsewhere.
- **Format Selection** — Cmd+Option+Shift+L on macOS; Ctrl+Alt+Shift+L elsewhere.
- **Format on Save** — optional, initially off, persisted in `config.json`.

`keymap.json` can override `format.document` and `format.selection`. These are
application bindings, not global shortcuts. The document does not need to be
saved before formatting. Theme changes do not alter formatting rules.

Selection requests pass the original UTF-16 range to javafmt; the engine may
expand to a safe syntax region. A malformed/unsupported source returns a failure
and the buffer remains unchanged. This is explicit formatting, not an automatic
rewrite on every keystroke.

## Async execution and edits

`FormattingController` has one worker and one pending slot. It captures an
immutable piece-table snapshot on the EDT, then materializes and formats the
source on the worker. New requests supersede queued requests. A result is only
applied if the same document object and version are still active. Typing or
switching buffers prevents obsolete results from replacing newer source.

`EditorComponent.applyFormatting` validates ranges and Unicode character
boundaries, then applies the ordered whitespace edits through a piece-table
transaction. A batch rebuilds piece descriptors and line starts once; it does
not replace unchanged source spans or rebuild the line index for each edit.
The document object remains the same, so retained buffers and navigation keep
their identity.

Caret, selection endpoints and the first visible line are mapped through the
edits. A formatting transaction stores pre/post viewport and selection locations
for exact one-step Undo/Redo restoration, independently of prior typing. Matching
source-version navigation-history entries are remapped. Older navigation entries
whose source version is already obsolete are retained rather than guessed at.
Diagnostics are invalidated and rebuilt by the existing versioned compiler; stale
error locations are not presented as current. TurboJ does not yet implement
breakpoints, so preservation of debugger breakpoints is not claimed.

## Save behavior

Format on Save runs after any destination selection and before capturing the
write snapshot. Formatting success and a no-op both continue to the existing safe
atomic-write path. A parser failure skips formatting, reports the diagnostic and
still saves the user's original source; incomplete Java must remain saveable.
If the source changes during formatting, that save is canceled with a message to
save again. The old snapshot is not written. Repeated Save requests are coalesced
through the existing deferred-save handling. External-file conflict checks remain
in force.

## Verification

Focused tests cover sparse batches and generated undo/redo cases, failed-batch
atomicity, all three themes, caret/selection/viewport mapping, source identity,
no-op history, stale buffers, background execution, range offsets and bounded
request supersession. `FormattingSmoke` is an explicit real-window harness for
the actual library/menu/save/compile workflow. Test outcomes and measurements
must be reported from executed runs rather than inferred from these test names.

```sh
./mvnw -q -pl app -am -Djava.awt.headless=true \
  -Dtest=PieceTableTest,EditorFormattingTest,FormattingControllerTest \
  -Dsurefire.failIfNoSpecifiedTests=false test
```

The formatter's style, supported syntax and correctness boundaries are described
in [`javafmt/docs/STYLE.md`](../javafmt/docs/STYLE.md) and the independent project's
compatibility documentation.

### Executed native checks — 24 October 2026

The focused package command passed **36 tests** (7 piece-table, 3 formatting
editor, 5 formatting-controller and 21 existing editor/semantic/AI regression
tests), with zero failures, errors or skips. The actual `FormattingSmoke` window
passed document formatting in Classic/Dark/Light, token-relative caret mapping,
exact Undo/Redo locations, range formatting without changing the second method,
and explicit compilation/execution printing `10`. Format on Save was verified
off by default, then enabled and checked against disk contents, one Undo, and a
malformed source that was safely saved unchanged after formatting was skipped.

The console recorded 86.72 ms for the first document format and 1.62 / 1.46 ms
for the next two requests in that GUI run. Those are individual cold/warm
observations, including snapshot materialization and core work, not a latency
distribution or an input-to-screen measurement. Screenshots and the complete
pass report are in ignored `app/target/javafmt-smoke/`.

After building the application and test classes, run the explicit GUI harness:

```sh
java --enable-native-access=ALL-UNNAMED \
  -cp app/target/test-classes:app/target/turboj-app.jar \
  com.turboj.app.FormattingSmoke
```
