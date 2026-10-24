# TurboJ 1.0.7 — embedded ecosystem and unified window

Verification date: 2026-10-24. Local development build; independent libraries are
version 0.1.0. This report distinguishes exercised behavior from broader language,
platform and performance guarantees.

## Java namespace and compatibility

TurboJ application, compiler, editor, worker, CLI and benchmark packages now use
`dev.turboj`. Their Maven group is `dev.turboj`, the desktop entry point is
`dev.turboj.app.TurboJ`, and the macOS bundle identifier is `dev.turboj.app`.
The clean rebuilt application contains no `com/turboj` classes. RefactorJ and
CommandJ retain their existing `dev.turboj.refactorj` and `dev.turboj.commandj`
namespaces. FormatJ retains the existing `dev.javafmt` API and `javafmt` command.

The Keychain service name `com.turboj.ai.credentials` is intentionally retained as
a persisted credential identifier, not a Java package. Existing API keys and
settings do not need to be entered again. Existing keymaps remain TurboJ Classic;
new installations default to IntelliJ IDEA macOS.

## Embedded implementation

- RefactorJ prepares 33 kinds of semantic operations using the same resident JDT
  workspace as Java intelligence. Its public API does not expose mutable Eclipse
  internals. [Eligibility and safety limits](../refactorj/docs/FEATURES.md) are
  explicit for every operation.
- Untitled buffers use isolated in-memory source context and a legal private JDT
  unit alias. They do not recursively index the launch directory. Renaming an
  untitled class changes its buffer without implicitly creating or renaming a
  source file on disk.
- Refactoring previews do not mutate source. Versioned sparse edits, file
  operations, journaled disk changes and native buffer publication form one
  logical workspace transaction with guarded undo and redo. Filesystem-wide
  atomicity is not claimed.
- CommandJ supplies the registry, context-aware dispatch, four keymap profiles,
  chords, customization, conflicts, JSON persistence and local command palette.
  TurboJ calls the libraries directly, without LSP, MCP or HTTP internally.
- FormatJ formats generated regions through an optional adapter. TurboTest
  compiles a source snapshot and runs real Jupiter tests in the resident worker.
  Auto Test is optional and starts after a successful apply, never during preview.
  Affected-test selection currently conservatively runs the discovered suite.
- Dialogs, file browsing, palette, keymap, refactoring forms and previews use
  internal Swing frames in the main window. Completion, documentation, menus,
  submenus and combo popups use its layered pane. Nested panels retain their own
  focus and Escape behavior. Semantic work remains outside the EDT.
- Menu colors come from the active theme, including accelerators, disabled text,
  selection, radio/check items and submenu arrows. Automated contrast checks
  cover all 103 catalog themes; enabled text/shortcuts target 4.5:1 and disabled
  text targets 3:1.

## Executed verification

`./mvnw -q -l target/ecosystem-final-verify.log verify` completed successfully:

| Reactor area | Tests | Failures/errors/skipped |
| --- | ---: | --- |
| TurboJ app | 105 | 0 / 0 / 0 |
| Core | 81 | 0 / 0 / 0 |
| Worker | 15 | 0 / 0 / 0 |
| CLI | 5 | 0 / 0 / 0 |
| CommandJ | 28 | 0 / 0 / 0 |
| RefactorJ | 113 | 0 / 0 / 0 |
| FormatJ / javafmt | 31 | 0 / 0 / 0 |
| TurboTest | 3 | 0 / 0 / 0 |
| **Total** | **381** | **0 / 0 / 0** |

The tests include actual Eclipse transformations, compilation, selected runtime
equivalence checks, stale plans, cross-session saved plans, multi-file undo/redo,
resident JUnit execution, protocol requests and runtime shutdown/restart. Seeded
generated cases execute within some tests; they are not counted as separate JUnit
test cases. Matching syntax or compilation alone is not a universal proof of
semantic preservation.

After the last keyboard rollover correction, the affected Swing/editor and shared
semantic tests were rerun successfully. `CommandRolloverSmoke` reproduced the lost
character before the fix and passed against the final JAR: a held command key or
cancelled chord no longer consumes the next ordinary character, while the command's
own typed event remains suppressed.

Real-window acceptance runs also passed: `OverlaySmoke`, `CommandJSmoke`,
`RefactoringSmoke`, `TurboTestSmoke`, `AISettingsSmoke`, full `ThemeSmoke`,
`MenuSmoke` under Aqua, and `IntelligenceSmoke`. The latter verified completion,
acceptance/undo, cross-file navigation, Javadoc, signature help and 120 complete
synthetic key cycles with all characters preserved. Its observed edit-to-paint
p50/p95/p99 was 0.224/0.915/1.327 ms on the 1,000-line fixture; this excludes the OS
event queue, compositor and display scanout and is not hardware input latency.

AI settings explanations, status and tab text passed 4.5:1 contrast checks in the
three primary themes. Internal file browsing, nested confirmations, menu/combo
popups, focus return and Escape were exercised without an extra native window.

The independent RefactorJ adapters were also exercised in actual hosts:
IntelliJ IDEA 2026.1.3 with JBR 25 (20 assertions), and VS Code 1.138.0
(19 assertions). CLI ZIP, IntelliJ ZIP and VSIX dependency audits passed against
their 37 bundled third-party JARs and legal metadata. The plugins are locally
packaged, not published to marketplaces. See [host limitations and installation](../refactorj/docs/ADAPTERS.md).
IntelliJ file undo requires Local History. VS Code move/delete operations require
saved affected files; undo restores their paths and buffers, and a subsequent Save
persists the restored text to disk.

Website build and 27 source tests passed. Browser verification passed 40 checks
at six widths (320–1920 pixels), including two automated WCAG 2.2 AA audits with
no reported violations. Automated audits do not establish complete accessibility.

## Measured performance and remaining boundaries

Hardware: Apple M5, 16 GiB RAM, macOS 26.5.1, aarch64, Corretto 25.0.4.1.
Short JMH runs are development measurements, not advertised guarantees.
RefactorJ measurements identify the tested artifact hash and precede the final
public-symbol-ID and runtime-lifecycle hardening; they are not final-bundle timings.

- Command palette filtering over 1,000 generated commands: p95 99.840 µs.
- Local rename preparation over the measured 20-file fixture: p95 28.423 ms.
- Method rename preparation over that fixture: p95 54.113 ms, exceeding the
  initial 50 ms small-preview objective.
- The measured 20-file rename paths still allocate tens of megabytes per
  operation. Cached symbol results are reported separately from uncached work.

Raw data, workload sizes, cold runs and reproduction commands are in
[RefactorJ performance](../refactorj/docs/PERFORMANCE.md) and
[CommandJ performance](../commandj/docs/PERFORMANCE.md). There is no claim of
outperforming Eclipse or IntelliJ.

Automatic Maven/Gradle dependency import, a JDI debugger, dependency-aware test
selection, complete physical keyboard-layout coverage and signed/notarized
distribution remain outside the verified implementation. See
[current limitations](limitations.md). Live paid-cloud AI inference is not
included in these checks; prior local-model results have their own report.

## Local artifacts

The final macOS app and DMG are in `target/release-ecosystem-1.0.7-final/`.
`TurboTestSmoke` also passed using that app's own runtime and bundled JAR,
including compilation, real JUnit execution and Auto Test after an applied
refactoring. The DMG was generated successfully. This is a local unsigned,
unnotarized development build.

The bundled application JAR SHA-256 is
`37985c9565ed0f64254352ae1dacb14e7081158b6e708c628b715b7a8f7e42a5`.
Independent RefactorJ packages:

- `refactorj/target/refactorj-0.1.0.zip`
- `refactorj/refactorj-intellij/target/refactorj-intellij-0.1.0.zip`
- `refactorj/refactorj-vscode/refactorj-0.1.0.vsix`

No Maven Central or marketplace publication is implied. Original third-party
license/POM bytes, including upstream whitespace, are retained for auditability.
