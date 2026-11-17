# QFX-04 — native keyboard workflow and bottom-panel routing

**PASS — 16 November 2026.** Both native project workflows completed in one clean-profile session: **470 keyboard events and 172 assertions**, with no foreground recovery needed in the final session. The final report and manifest identify the tested artifact, commands and outcomes.

Environment: macOS 26.5.1, arm64, Corretto 25.0.4.1+10-LTS, Eclipse keymap, Monokai. Application JAR SHA-256: `27f1d89fe20f9480add69f4efaad456b91490fdb525704ea0e00aa7e357857cb`.

| Executed check | Outcome |
| --- | --- |
| `./mvnw install` | PASS — 874 tests in 126 suites; zero failures, errors or skips |
| `FirstLaunchKeyboardSmoke` | PASS — plain Java + imported Maven, real failed/green JUnit, four bottom controls, modal isolation |
| `KeyboardProfileSmoke` | PASS — 92 assertions; four profiles, empty Tests, custom binding persistence/reload |
| `QuickFixPopupSmoke` | PASS — Cmd+1 in TurboJ/Eclipse; Classic/Monokai normal, selected and empty-state contrast ≥4.5:1 |
| `ClipboardSmoke` | PASS — 11 checks, output/unsent-input/dialog clipboard routing including custom binding |
| `OutputCommandKeyboardSmoke` | PASS — 25 checks; real shell output/cancel/history and Esc-colon in all profiles; includes a separately labelled synthetic mouse probe |
| `KeyboardRegistryAudit docs` | PASS — 308 commands, 29 aliases, zero bindings without callbacks |
| `node website/scripts/export-keyboard-reference.mjs` | PASS — current source hashes and three generated references verified/copied |
| `node --test website/tests/keyboard-reference.test.mjs` | PASS — six tests |

The clipboard and output-command supplements use programmatic fixtures, and the clipboard menu probe invokes its menu item directly. They supplement, rather than stand in for, the keyboard-only first-launch session.

## Scope and fixes

The real application starts with an isolated home/preferences/workspace. The driver creates both a plain Java project and a Maven project through keyboard-accessible menus and forms. It generates and runs HelloWorld, creates Calculator through Cmd+1, generates its JUnit test and dependency, observes a genuine failed assertion, then observes a separate compilation failure for the missing `add` method. Cmd+1 generates the method; Tab/Shift+Tab edit its expression and linked parameter/method names; Enter confirms; Undo/Redo verify the final cross-file rename. Save All and Eclipse Run produce a real passing `assertEquals(42, ...)` test in each project.

Four application defects are addressed:

1. Panel cycling overlooked Tests and could target hidden Console input. It now recognizes descendants of the bottom area and focuses the selected tab.
2. JShell consumed modified arrows/Tab and could steal focus on completion. Only unmodified history/completion keys are owned locally; Shift+Tab traverses backward. Busy input remains focusable, temporarily read-only, and completion does not request focus.
3. Semantic linked rename restarted template ranges and lost the edited-expression flag, resetting the user's expression to a default such as `0`. The host retains that state across parameter and method renames. The native regression edits the expression first, then verifies bound reference updates and the final result.
4. The empty Tests tab could be clicked but its focus/toggle commands required a previous report. Those view commands are now available before the first test. Navigation to failures still requires actual results.

No competing key dispatcher or universal keymap override is introduced. CommandJ continues to resolve global shortcuts before local component handling. Modal overlays deliberately block background commands.

## Interaction checks

For **both** project types, from **Output, Tests, JShell and the output command line**:

- Cmd+S saves the edited source while preserving panel focus and any input draft.
- Cmd+3 opens the Eclipse Command Palette; Escape restores the same control and draft.
- Cmd+D does not apply the Eclipse editor-only Delete Line action to hidden source.
- Ctrl+F6 returns to source; Ctrl+Shift+F6 returns to the selected bottom panel.
- Cmd+Shift+F11 compiles and reruns the real test successfully from each panel.
- Escape returns to editing.

Further checks cover Shift+Tab in JShell, leaving a busy evaluation without focus theft, and settings-modal isolation for Save, Run and Palette. After cancellation, those global routes work again. The separate profile regression checks palette/panel/Escape routing in all four profiles, empty Tests access, profile switching, and persisted custom shortcuts. Its initial source and settings fixture are programmatic; the full first-launch driver does not create source fixtures or invoke application action callbacks.

## Evidence and limitations

- `report.txt` and `keyboard-events.tsv`: final coherent first-launch session, assertions, shortcuts and focus transitions.
- `checkpoints.json.gz`: captured compiler/JUnit/Maven state, proposals, source and open buffers; expand with `gzip -dc`.
- `fixtures/`: final Java sources, project/JUnit configuration and Maven POM created through the UI.
- `*-green.png` and `*-bottom-panels.png`: unmodified application frame captures.
- `profiles-report.txt`, `profiles.log.gz`, `popup.log.gz`, `quick-fix-monokai.png`: profile/customization and Quick Fix contrast checks.
- `install.log.gz`, `manifest.json`: full build outcomes, per-suite counts, artifact and source hashes.
- `controls.json.gz`: earlier harness failures, the reproduced expression-reset defect, modified-key unit regression and earlier passing controls. They are not substituted for the final session.

Text evidence replaces repository, home and temporary-session prefixes with `<repository>`, `<user-home>`, `<session>` and `<temporary>`. The manifest retains original and curated SHA-256 values. Screenshots are unchanged. Source fixtures retain exact source bytes; checksums cover the curated deliverable. The baseline Git revision precedes these working-tree changes; the artifact and source hashes identify what was actually executed.

This is automated **real Swing key/focus dispatch on macOS**, with no mouse events in the first-launch or profile workflow. Text is pasted through the system clipboard, which is restored afterward. Maven import uses a warm offline cache, not a cold network download or explicit lifecycle goal.

When the native window becomes inactive, the driver requests application foreground and logs the recovery; it never assigns component focus to satisfy an assertion. The origin of individual OS activation interruptions is not established. This does not certify unattended OS focus stability, human muscle-memory usability, physical US/Italian/Swiss layouts, Fn/Globe, IME/dead keys or VoiceOver. Those remain KEY-05 work. Optional `--robot` was not used.

## Reproduce

Use a logged-in macOS desktop and JDK 25. Run GUI drivers sequentially, with no competing GUI test. The build populates the repository Maven cache used by the offline fixture.

```sh
./mvnw install
java -cp app/target/test-classes:app/target/turboj-app.jar dev.turboj.app.KeyboardProfileSmoke
java -cp app/target/test-classes:app/target/turboj-app.jar dev.turboj.app.QuickFixPopupSmoke
java -Dfirstlaunch.output=app/target/qfx04-review \
  -cp app/target/test-classes:app/target/turboj-app.jar \
  dev.turboj.app.FirstLaunchKeyboardSmoke
```

The registry reference is regenerated from the final native artifact with `KeyboardRegistryAudit docs`; the website reference exporter verifies/copies that generated data. No website demo, production deployment or macOS distribution package is changed by this work.

To check this evidence bundle after checkout, run `shasum -a 256 -c SHA256SUMS` from this directory.

For a manual check, open the latest app with `./tj open <project-or-pom>`, select Eclipse, focus each bottom panel through the Command Palette, then use Cmd+S, Cmd+3, Ctrl+F6, Ctrl+Shift+F6 and Cmd+Shift+F11. In General Settings, those background commands should stay blocked until the modal closes.
