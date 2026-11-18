# QFX-10 — structured live-template dialog, 18 November 2026

**VERIFIED in the automated scope below.** The first item in the agreed product sequence is implemented.
Advanced template contexts, semantic macros, imports/name shortening and automatic formatting remain separate
QFX-10 work. AI-13 completion timing/frequency is next; this delivery does not change those triggers.

Baseline: TurboJ `5db33ec`, plus the local source files identified in `source-files.sha256`.
The source changes and evidence are delivered together in this changeset. No website or installed `.app` has been changed.

## Delivered behavior

- Initial search filters name, abbreviation, description, ID and code. Arrows select a result; Enter edits its fields.
- New/Duplicate, name/abbreviation/description/code, Enabled, contexts and variable configuration require no JSON editing.
- The preview uses `EditorComponent` and the real expansion engine. Its example context is explicitly illustrative:
  `Example.example(int[] values)`, sample selection `selected();`. Boxes identify linked fields and the solid caret
  identifies END. No source or workspace changes are made by the preview.
- Native syntax colors, theme, font and tab width follow the source editor. The accessibility name/description now
  honors explicitly supplied preview metadata while retaining the main editor's default location description.
- Editing a built-in creates an override; Restore default removes it. New/duplicate entries have stable generated IDs.
- Search and selection changes retain drafts, including invalid drafts needing correction. Cancel discards the whole
  dialog transaction. Save validates everything and writes atomically off the EDT; controls are disabled during writing.
  Write failures retain the draft and re-enable controls. Invalid existing JSON cannot be replaced silently by empty defaults.
- Edit JSON is an advanced nested dialog. Apply validates into the parent draft; only the parent Save writes to disk.
- Optional `name`/`description` metadata is backward compatible with schema v1. Untouched definitions and unknown JSON
  properties survive structured changes. Disabled entries remain searchable but cannot expand.

## Validation

Environment: macOS 26.5.1 arm64, Corretto 25.0.4.1+10-LTS, repository Maven wrapper 3.9.16, warm dependencies.

| Check | Result |
| --- | --- |
| `./mvnw -pl app install` | PASS: 338 tests, zero failures/errors/skips; includes eight new settings regression tests |
| `LiveTemplateDialogSmoke` | PASS: native search/arrows/Enter, duplicate, fields/code, variables, context/abbreviation rejection, atomic persistence, expansion, javac, linked edits, Undo/Redo, Cancel and restoring a disabled built-in |
| `LiveTemplateWorkflowSmoke` | PASS: all four keymaps, advanced JSON, validation, restart persistence, linked fields/lifecycle and JDT documentation/completion |
| `LiveTemplatePrecedenceSmoke` | PASS: actual template/ghost precedence with deterministic AI fixtures; no live model claim |
| `InlineTemplateSmoke` | PASS: generated inline fields and existing keyboard behavior |
| `UsagesNavigationAcceptance` | PASS: plain/Maven references, preview navigation/focus and stale-source refusal |
| Rapid injected-key control | PASS in four additional invocations, including three consecutive runs without intermediate source-inspection barriers |
| Appearance | Native Monokai captures inspected; unit preview render checks at 10/16/48 pt and 1×/2× scale, Classic and Monokai |

The GUI fixtures prepare only isolated projects/preferences. UI interactions use actual posted Swing events and
CommandJ shortcuts; the driver may reactivate its own window after macOS activation changes, never choose an internal
focus target to manufacture an assertion. This is not physical-keyboard, IME or VoiceOver certification.

Production JAR: `app/target/turboj-app.jar`

SHA-256: `8e28b64e98bae20f94c10c5bdf9c47d0c9c1f3f55f41711dcf456049a3530f55`.

The five native suites and rapid controls used this same JAR. Standalone driver refinements were compiled separately.
The reactor was not rerun: the change is confined to the app, with the installed dependency baseline from P0 retained.
`validation.json` lists the actual app suites and counts; `native/results.tsv` and the rapid directories retain timings
and exit statuses. The repeated rapid invocation log is the last of its three runs; its TSV records all three exits.
No performance or all-103-theme certification is inferred from these tests.

## Retained failed controls

- `control/preview-description-test.txt`: the new preview description was ignored by the editor's hardcoded accessible
  description. Fixed by honoring explicitly supplied metadata; main editor location defaults remain intact.
- `control/tab-navigation.txt` / `.png`: the host LAF did not select the Configuration page on Right. The dialog now
  explicitly selects its tabs with Left/Right. The first capture also used an invalid Monokai fixture ID and showed
  Classic; the final controls use the actual bundled ID `vscode-monokai`.
- `control/source-after-save.txt` / `.png`: one injected-key control observed an unexpected source newline after Save.
  Its cause is unconfirmed. Subsequent stage-checked and rapid controls passed, with source-change tracing and an
  unchanged-source assertion. The observation is not relabeled as a passing test or a diagnosed/fixed production bug.
  `captures/source-events.txt` records the accepted control's source changes; there are none while the dialog is active.

## Review and reproduce

Quit an older development instance and run `./turboj`. Open **≡ → Live Templates…**, or use the Command Palette.
The existing supplemental shortcut in all four profiles is **Ctrl+Cmd+K, L, P** (unless customized).
Search `sout`, duplicate it, edit its name/abbreviation/code, then use Configuration for linked variable defaults.
Save, return to a Java statement and expand the new abbreviation with Tab. Cancel should leave the configuration intact.

```sh
./mvnw -pl app install
KEYBOARD_EFFECTS_OUTPUT=app/target/template-dialog-verification \
  bash scripts/verify-keyboard-effects.sh --only \
  LiveTemplateDialogSmoke LiveTemplateWorkflowSmoke LiveTemplatePrecedenceSmoke \
  InlineTemplateSmoke UsagesNavigationAcceptance
```

See [built-in preview](captures/builtin-monokai.png), [custom code and preview](captures/custom-monokai.png),
[configuration](captures/configuration-monokai.png), [native effects](captures/effects.tsv) and
[saved definitions](captures/live-templates.json). The runtime fixture sources and preferences stay isolated from personal examples.
