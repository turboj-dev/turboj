# Extensible templates, QFX-03/05 and CH-10 — 17 November 2026

## Implemented and verified

* Declarative Java live templates: `sout` + Tab inserts `System.out.println();` with the caret inside the
  parentheses, in all four keymaps. `fori` links the index occurrences. Tab/Shift+Tab traverse fields;
  the last Tab exits a snippet, Enter confirms, Escape rolls back. Confirmed snippet edits have one Undo.
* User JSON definitions are edited through the native **Live Templates…** settings form, validated, saved
  atomically and loaded after restart. Stable IDs support overrides and disabling built-ins. The keyboard
  picker filters and inserts applicable templates. All three new commands have real callback/effect tests.
* QFX-03: generated-method templates preserve their wrapping Tab model. Invalid return types, empty confirmation,
  qualified names and duplicate parameters are rejected. Save All now uses the same validation gate as Save.
  Enter in a JDT popup accepts the proposal before confirming a template. Linked-field completion preserves
  placeholders and independent import edits. Save, Format, file switching, semantic caller rename, Escape and
  workspace Undo/Redo are covered by native checks. Undo remains per semantic transaction for generated code.
* QFX-05: `System.out.print` proposals, documentation of the actual selected binding/overload, Escape and
  acceptance of `println()` work. Completion symbols are bounded and invalidated with the source context;
  documentation is not guessed from a proposal's text.
* CH-10: real Maven/JUnit/JaCoCo execution changes `Price.deliveryFee`, line 14, from **0 covered / 2 missed**
  instructions to **2 covered / 0 missed**. The driver navigates from source to its conventional test, types a
  new test and presses Save; it does not manually run Cherry after saving. The final run has three passing
  tests and current source/class/process provenance. Aggregate coverage still creates no per-test association.
* Generated reference: **312 commands, 29 aliases, zero bindings without callbacks**; four profiles have no
  bundled collisions. Four PDF cheat sheets contain 312 commands exactly once each, on four A4 landscape pages,
  with all 16 columns used and no clipped labels/keys/rows. Actual PDF page counts were checked with `pypdf`.

The [usage guide](../../live-templates.md) describes the file format, commands and compatibility boundaries.
The [project TODO](../../../TODO.md) remains the single activity register.

## Validation and reproduction

Environment: macOS 26.5.1 (25F80), arm64, Corretto 25.0.4.1+10-LTS, Maven wrapper 3.9.11.
Tests use isolated preferences/projects and real Swing events/CommandJ/JDT; setup may load fixture source
programmatically. These are automated native tests, not a human physical-keyboard walkthrough.

| Command/check | Result | Evidence |
| --- | --- | --- |
| `./mvnw -pl app -am install` | Initial app failure: duplicated settings menu entry; upstream modules passed, including QuickActionsIT 34 and JavaIntelligenceTest 24 | [Initial log](build/affected-reactor-initial.log) |
| `./mvnw -pl app install` after fixes | PASS, 330 tests; 860 tests in 123 distinct affected-reactor suites when combined with unchanged upstream results | [Final app install](build/app-install.log), [per-class counts](unit-results.json) |
| `bash scripts/verify-keyboard-effects.sh --only LiveTemplateWorkflowSmoke InlineTemplateSmoke KeyboardIntelligenceEffectsSmoke FirstLaunchKeyboardSmoke` | Four suites PASS after driver corrections below; final template rerun recorded separately | [Template](native/LiveTemplateWorkflowSmoke.txt), [generated/cross-file](native/InlineTemplateSmoke.txt), [intelligence](native/KeyboardIntelligenceEffectsSmoke.txt), [plain/Maven](native/FirstLaunchKeyboardSmoke.txt), [template effects](native/template-effects.tsv) |
| `bash scripts/verify-cherry-coverage-loop.sh /path/to/cherry-test-java.jar` | PASS using the real companion CLI and cached pinned dependencies | [Native result](cherry/native.txt), [provenance and counters](cherry/coverage-proof.json), [new test source](cherry/PriceTest.java.txt) |
| `./mvnw -pl app -Dtest=CheatSheetTest package` | PASS after shortening four clipped print labels | [Print build](build/cheatsheet-package.log) |
| `KeyboardRegistryAudit docs` | 312 commands bound; supplements resolve; zero missing callbacks | [Generated inventory](../../keyboard-command-inventory.md), [conflict audit](../../keyboard-conflicts.md) |
| `node scripts/verify-cheatsheets.mjs app/target/cheatsheet-validation` | PASS: all commands, four pages, 16 occupied columns, no clipping, all profiles | [Layout](pdf-layout.json), [PDF page counts](pdf-pages.json) |
| `node --test website/tests/keyboard-reference.test.mjs` | PASS, 6 tests; generated public reference assets refreshed locally | [Log](reference-tests.txt) |

The native suites ran sequentially because macOS window activation is shared. Worker JVM startup/exit can activate
the host terminal; the helper restores only its owned window, never the expected component or result. Recovery is
logged. The first-launch regression includes global shortcuts from Tests, Output, JShell and output command input.

The four keyboard suites used JAR SHA-256 `ee03014a53d14068378064e3c095cc5c1526021e5cb1f56d6efe0e28c69b0423`.
The subsequent print-label-only change produced the delivered JAR recorded in [validation.json](validation.json);
the print checks and final Cherry loop ran on that JAR. There were no intervening editor/template/intelligence changes.
The 860 total is not a newly executed full-root `install` and does not double-count repeated app runs.

Cherry fixture: [`integration-tests/fixtures/cherry-native`](../../../integration-tests/fixtures/cherry-native),
JUnit 6.1.3, JaCoCo 0.8.15, provenance plugin 0.3.16, Java release 21 compiled with the above JDK. Its configured
category is **unit**. This run does not claim additional integration/functional/browser suites. Before and after
adapter results, precise native coverage sidecars, JaCoCo XML and Surefire XML are retained in
[before](cherry/before/result.json) and [after](cherry/after/result.json). Production source is unchanged;
the test source adds `coversDeliveryFee()`. Coverage is verified against the instrumented class and current source,
not inferred from the new test's name or success.

To regenerate the reference/PDFs after building:

```sh
javac -cp app/target/test-classes:app/target/turboj-app.jar -d app/target/test-classes app/src/test/java/dev/turboj/app/KeyboardRegistryAudit.java
java --enable-native-access=ALL-UNNAMED -cp app/target/test-classes:app/target/turboj-app.jar dev.turboj.app.KeyboardRegistryAudit docs
java --enable-native-access=ALL-UNNAMED -cp app/target/turboj-app.jar dev.turboj.help.CheatSheetExport app/target/cheatsheet-validation --keep-html
node scripts/verify-cheatsheets.mjs app/target/cheatsheet-validation
node website/scripts/export-keyboard-reference.mjs
```

## Failed controls and corrections

* A duplicate Live Templates settings menu entry failed `MenuLayoutTest`. Settings now has one menu location;
  Code → Live Templates provides the two editing actions.
* The first repeated Quick Fix driver found a retained hidden list before the new popup owned focus. It now waits
  for the actual filter focus. No popup outcome is set by the test.
* [A later template run](control-save-publication-race.txt) replaced the fixture document after a Save disk write
  but before the UI published its saved fingerprint. The driver now waits for both disk bytes and the saved title;
  it also waits for individual async Undo/Redo steps. Save's external-change protection was not weakened.
  [Initial run statuses](native-first-pass.tsv) retain the failure; [final template rerun](native/template-final-results.tsv) passes.
* The first CH-10 driver dereferenced decorations before asynchronous buffer loading finished. Its wait now checks
  snapshot availability. [Failed control](cherry/control-decoration-wait.txt); final native run passes.
* PDF validation caught wrapped labels after the three new commands changed packing. Four print labels were
  shortened; commands were not removed. [Failed layout control](control-pdf-label-clipping.txt).
* An early sandboxed Swing test fork exited 134; its unrestricted rerun passed. Native window focus transients
  also affected early completion/JShell checks; final recorded checks pass with owned-window recovery.

## Explicit remaining limits

All **51 definitions from the three core IntelliJ Java XML groups** are included, with upstream blobs, Apache
license/notice and a deterministic offline generator. This is not every language/framework plugin's catalog,
and it is not complete IntelliJ macro parity. Five context-gated entries (`soutc`, `serrc`, `iop`, `ior`, `sts`)
remain catalogued but are not offered without supported context proof. Advanced expected-type/subtype/cast macros
remain editable fallback fields; auto-import, shortening and automatic formatting are not implemented. These
remaining items are QFX-10, not hidden successes. Parameter-type fields and return-expression type compatibility
remain QFX-08; the compiler still diagnoses body type errors after confirmation.

Physical layouts, IME/dead keys, Fn/Globe and VoiceOver remain postponed under KEY-05. Paid-cloud inference remains
AI-05. No new performance, full 312-command effect re-audit, website deployment or macOS package-release claim.
The old 309-command ledger remains dated evidence; the three additions have their own actual native effects.

Home/repository/temporary-directory prefixes in archived text were replaced with placeholders. Test results,
source/class hashes and counters were preserved; [validation.json](validation.json) retains raw-file hashes before
redaction, final source hashes and CLI/JAR identities. Existing user examples and unrelated untracked files were
preserved. No commit, push or deployment was performed.
