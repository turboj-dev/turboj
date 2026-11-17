# Keyboard audit continuation — automated scope

17 November 2026; base `df53ac8a487ffa8d6918650f7fc73cb513b44421` plus local changes.
This continues the [earlier context audit](../keyboard-contexts-2026-11-17/README.md)
without replacing its frozen evidence. The user explicitly postponed their own
physical keyboard and VoiceOver walkthrough and requested the automated checks.

## Defects found through execution

1. During IME preedit, the dispatcher correctly deferred to the input method, but
   Swing menu accelerators could still execute commands. The failing matrix
   records `save` being invoked (`ime-accelerator-before.txt`). Command availability
   now rejects composition at the shared execution boundary; editor movement,
   deletion, Tab and Enter defer to the input method during preedit too.
2. The editor exposed an accessibility context without implementing `Accessible`
   or exposing source through `AccessibleText`. It now implements both, reports
   text/caret/selection changes and supplies source geometry at current zoom.
3. The real macOS accessibility probe then found that source was still absent
   from `AXValue` (`ax-before.txt`). This JDK's `CAccessibleText` bridge reads text
   ranges through `AccessibleEditableText`. That interface now uses ordinary
   editor edits and undo, respects read-only/composition, and treats syntax
   attributes as immutable. Ghost suggestions and preedit are not committed source.

No default shortcuts, profile selection or user customization rules changed.

## Executed results

| Check | Result | Evidence |
| --- | --- | --- |
| Full `./mvnw install` | PASS: 885 tests, 129 suites, zero failures/errors/skips; 3m54s | `full-install.txt` |
| Final accessibility addition, `./mvnw install -pl app -am -DskipITs` | PASS: 699 tests, 111 suites, zero failures/errors/skips; 1m30s. Includes six editor accessibility tests. RefactorJ integration tests were not repeated in this second command. | `final-app-install.txt` |
| All-command native routing | PASS: 10,168 cases, four profiles × eight contexts, 309 descriptors | `routing-matrix.tsv`, `routing-summary.txt` |
| Production dialog entry/focus/cancellation | PASS: 22 command routes, full forward Tab cycle, reverse Tab, Escape and source preservation | `dialog-routes.txt` |
| Actual nested operations | PASS: 10 assertions across duplicate/cancel/recorder/reset/remove, folder creation, exports, overwrite cancellation, invalid import and diagnostics | `nested-dialogs.txt` |
| Live local AI precedence | PASS: 41 assertions across all four profiles; actual model output accepted/dismissed, JDT/template/modal precedence | `live-ai.txt` |
| Native accessibility | PASS: macOS AX source value and selected identifier, including Japanese/emoji source | `native-accessibility.txt` |
| Regression native harnesses | PASS: rollover, profiles/panels, workflow A–H and generated templates, rerun on final artifact | `CommandRolloverSmoke.txt`, `KeyboardProfileSmoke.txt`, `KeyboardPanelSmoke.txt`, `KeyboardWorkflowSmoke.txt`, `InlineTemplateSmoke.txt` |
| Native editing | PASS: 94 assertions, actual clipboard and multicaret edits/Undo across four profiles | `KeyboardEditingSmoke.txt`, `editing-summary.txt` |
| Registry/reference | PASS: 309 commands, 29 aliases, zero dangling bindings; six export tests pass | `registry.txt`, `reference-export.txt`, `reference-tests.txt` |

Final native artifact SHA-256:
`45f85c075c50adfd494e2e2ac05ede4af0d21a680a5c1e89e9d715ce2bd502dd`.
The final native checks all used this artifact. The first full install preceded
the `AccessibleEditableText` addition; the second build/tests and native AX probe
verify that addition. Counts above are separate runs, not additive distinct tests.

Environment: macOS 26.5.1 (25F80), arm64, Corretto 25.0.4.1. The native AX probe
had host permission; it did not inspect unrelated applications. AI diagnostics
are local observations, not a performance benchmark or latency guarantee.
`manifest.json` hashes evidence and relevant implementation/test sources. The
shortcut content and four-page PDFs are unchanged; only reference provenance was
regenerated for the new artifact. No commit, push or production deployment was
performed by this continuation.

## Scope and interpretation

- **All-command routing:** `KeyboardContextMatrixSmoke` uses all 309 real command
  descriptors, their availability predicates and bindings in four profiles and
  eight contexts: editor, console, Tests, JShell, Explorer, dialog, nested dialog
  and IME composition. It replaces handlers with invocation recorders. This avoids
  quitting the app, starting downloads, editing files or launching arbitrary
  processes while testing the cross-product. **It proves dispatch and blocking,
  not every command's side effects.** The TSV includes every tested binding and
  its expected/observed invocation and availability reason.
- **Actual callbacks:** dialog entry/traversal/cancellation, nested profile and
  file operations, and the earlier real workflow harnesses execute production
  callbacks. Fixture creation and observations are programmatic; interaction uses
  real Swing events in visible macOS windows. No mouse events are used.
- **Live AI:** `AILivePrecedenceSmoke` uses the already installed, externally owned
  Ollama service at `127.0.0.1:11434`, `qwen2.5-coder:1.5b-base`. The model is not a
  fixture. It exercises accept/Undo, Escape, JDT priority, template priority and
  modal cancellation in every profile. No model download or cloud request occurs.
  Model digest: `02e0f2817a890a6de385d534465c04c5d0980abddc83615c09e79cee2c094446`.
- **Native accessibility:** the C probe reads only the supplied isolated TurboJ
  process. It checks source and selected identifier through the real macOS AX
  bridge. It does not enable VoiceOver or certify audible reading/navigation.
- **Layout software checks:** `UCKeyTranslate` reads five installed Apple layout
  tables: US, Italian, Italian Pro, Swiss French and Swiss German; 765 translations
  and 25 dead-key continuations. Only US was enabled. No input source was switched.
  Swiss French is **not** claimed as a verified Swiss Italian layout. These are
  table probes, not physical keyboards or an AWT layout-switching walkthrough.

## Reproduction

Run native harnesses sequentially with desktop access, never concurrently:

```sh
./mvnw install
java --enable-native-access=ALL-UNNAMED -cp app/target/test-classes:app/target/turboj-app.jar dev.turboj.app.KeyboardContextMatrixSmoke
java --enable-native-access=ALL-UNNAMED -cp app/target/test-classes:app/target/turboj-app.jar dev.turboj.app.KeyboardNestedDialogSmoke
java --enable-native-access=ALL-UNNAMED -cp app/target/test-classes:app/target/turboj-app.jar dev.turboj.app.KeyboardDialogRoutesSmoke
java --enable-native-access=ALL-UNNAMED -cp app/target/test-classes:app/target/turboj-app.jar dev.turboj.app.AILivePrecedenceSmoke http://127.0.0.1:11434 qwen2.5-coder:1.5b-base
clang -framework Carbon -framework ApplicationServices app/src/test/native/keyboard-layout-probe.c -o /private/tmp/turboj-keyboard-layout-probe
/private/tmp/turboj-keyboard-layout-probe
clang -framework ApplicationServices app/src/test/native/editor-accessibility-probe.c -o /private/tmp/turboj-editor-accessibility-probe
java --enable-native-access=ALL-UNNAMED -cp app/target/test-classes:app/target/turboj-app.jar dev.turboj.app.NativeAccessibilitySmoke /private/tmp/turboj-editor-accessibility-probe
```

Native probes return nonzero if permission or required state is unavailable; do
not reinterpret an unavailable probe as a pass. The local AI test requires that
specific model to be installed and available; it does not provision the service.

## Limits retained

Physical US/Italian/Swiss keyboards, Fn/Globe, native IME candidate-window behavior,
and actual VoiceOver speech/navigation remain **deferred by the user (KEY-05)**.
Synthetic preedit and Apple translation tables do not substitute for those checks.

All-command routing is exhaustive for the recorded descriptors/contexts, but
callback-effect coverage and all possible application-specific dialog branches
are not exhaustive. Cloud consent, paid/provider-specific AI, system print panels,
arbitrary external project prompts and every nested refactoring/error branch are
not certified by this run. KEY-04 remains partial for those effect/branch checks;
the local live-AI precedence gap in KEY-06 is covered. No complete keyboard or
screen-reader certification is inferred. No production website deployment occurs.
