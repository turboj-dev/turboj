# KEY-04/06 — native context and precedence audit

17 November 2026. Base commit: `df53ac8a487ffa8d6918650f7fc73cb513b44421`.
This evidence concerns the native Swing application, not the website demo.

## Confirmed defect and correction

A multi-key command that opens a dialog could insert its final letter into the
new dialog's input. The dispatcher reset typed-event suppression on the focus
transition and applied its overlay guard before handling the paired typed event.
The reproduced control records `actual=d` in `rollover-before.txt`.

`CommandKeyDispatcher` now resets the pending chord on focus change without losing
the handled press's typed-event guard. It consumes that event before the new
context's overlay/menu checks, only inside its own active window. Suppression starts
**before** calling the command, since a modal callback can enter a secondary AWT
loop. It is not re-armed when that loop returns. A fresh press clears suppression,
so ordinary dialog typing, held-key rollover and local widget keys still work.
No profile binding or user customization policy changed.

## Native validation matrix

All interaction below uses real Swing key/focus dispatch in visible macOS windows.
Fixture creation, initial state and assertions are programmatic. No mouse events
are used. This is automated native testing, not a human physical-keyboard audit.

| Context | Verified behavior | Evidence |
| --- | --- | --- |
| Opening dialog / modal loop | Opening suffix suppressed; fresh input retained; Tab reaches next field; Enter confirms; source focus restored; returning from modal loop does not suppress the next typed event | `rollover-after.txt`: 13 reported checks, including focus waits |
| Four keymaps | IntelliJ/Eclipse/VS Code/TurboJ routes; isolated custom dialog chord; ordinary modal typing; background editor command blocked; Tab/Shift+Tab/Escape; profile menu/combo switching and persistence | `profiles.txt`: 120 assertions |
| Bottom panels | Output, Tests, JShell, command line: palette, panel cycling, focus editor, invalid/disabled suffix handling, Escape cancellation; editor-only commands cannot edit source from a panel | `profiles.txt` |
| Menu | F10, arrows, Escape; profile submenu Enter; focus restoration | `panels.txt`, `profiles.txt` |
| JDT completion | Real JDK proposals; Up/Down; Escape without edits; Tab accepts without extra indentation; one Undo; ordinary Tab/Shift+Tab indentation outside completion | `panels.txt` |
| Explorer | Real Maven tree; Left/Right; Down to source; Shift+F10 context menu and arrow selection; Escape returns tree; Enter opens selected file and focuses source | `panels.txt` |
| Diagnostics | Real javac errors; Home/End scrolling; Enter from a dialog opened over console navigates to exact offset and retains editor focus | `panels.txt` |
| JShell / console | Actual 1+1 → 2; console caret movement/selection; Escape; actual Scanner input and program arguments; Cmd+C copies actual output | `panels.txt`, `workflows.txt` |
| Tests | Two actual JUnit tests; Save produces one failure, correction produces two passes; Up/Down select results; Enter opens failed test source; failure navigation command remains available | `workflows.txt` |
| RefactorJ | Extract Variable parameters → inspect preview → Apply → one Undo; method movement → compile and two passing tests | `workflows.txt` |
| Chooser / settings | Open project folder, Open/Save As file, Run Configuration Tab/Shift+Tab, theme search/Apply, Keymap recorder/conflict confirmation/persistence/reference | `workflows.txt` |
| Template | Real missing-method generation; Tab cycles placeholders without indentation; Enter commits; Escape rolls back generation; linked rename and workspace Undo/Redo | `templates.txt`: five reported scenarios |

`panels.txt` contains 31 assertions. `workflows.txt` contains 59 assertions plus its
completion summary. The four-profile custom dialog chord is installed only in
isolated test preferences and restored afterward; it is not a new product default.

## Regression commands

From the repository root, after building:

```sh
./mvnw test -pl app -am -Dtest=KeymapTest,KeyboardAuditTest,OverlayHostTest,PaletteDispatchTest,KeyboardDefaultsTest,CommandSettingsTest,CompletionCoordinatorTest,EditorTemplateTest,EditorAICompletionTest,AICompletionControllerTest,ReplKeyboardTest,OutputCommandGestureTest -Dsurefire.failIfNoSpecifiedTests=false
./mvnw install
java --enable-native-access=ALL-UNNAMED -cp app/target/test-classes:app/target/turboj-app.jar dev.turboj.app.CommandRolloverSmoke
java --enable-native-access=ALL-UNNAMED -cp app/target/test-classes:app/target/turboj-app.jar dev.turboj.app.KeyboardProfileSmoke
java --enable-native-access=ALL-UNNAMED -cp app/target/test-classes:app/target/turboj-app.jar dev.turboj.app.KeyboardPanelSmoke
java --enable-native-access=ALL-UNNAMED -cp app/target/test-classes:app/target/turboj-app.jar dev.turboj.app.KeyboardWorkflowSmoke
java --enable-native-access=ALL-UNNAMED -cp app/target/test-classes:app/target/turboj-app.jar dev.turboj.app.InlineTemplateSmoke
java --enable-native-access=ALL-UNNAMED -cp app/target/test-classes:app/target/turboj-app.jar dev.turboj.app.KeyboardRegistryAudit docs
node website/scripts/export-keyboard-reference.mjs
node --test website/tests/keyboard-reference.test.mjs
```

Run GUI harnesses sequentially, with desktop access. The workflow uses an isolated
canonical temporary Maven project and the repository's cached JUnit 5.13.4 artifacts.
It records any application foreground recovery; this never chooses an internal
control, so application focus-restoration assertions remain meaningful. Synthetic
Swing events are distinguished from the harness's optional `--robot` OS mode,
which was not used for this audit.

Full `./mvnw install`: **880 tests across 128 suites, zero failures/errors/skips**,
completed in 3m36s (`install.txt`). The installed artifact is byte-identical to the
one exercised by the native harnesses: SHA-256
`f060b51e66d93867412a478b522c04ad2b34a03f4f703b90e11a8a6a7c5998cc`.
Environment: macOS 26.5.1 (25F80), arm64, Corretto 25.0.4.1.

Registry regeneration: **309 commands, 29 aliases, zero dangling bindings**, no
internal binding conflicts. Export verified all source hashes and the three
reference documents; all **six reference-export tests passed**. Generated reference
content is byte-identical to the preceding delivery; only source/artifact provenance
changed. `manifest.json` records evidence and changed source hashes.

Targeted regression run: **78 tests across 12 suites, zero failures/errors/skips**.
It covers keymap contexts/conflicts/migration and controlled completion/AI/template
precedence, not a live AI provider. The first sandboxed run terminated the AWT fork
before results; the desktop-enabled rerun passed (`targeted-tests.txt`).

## Harness corrections and retained controls

The older workflow harness required maintenance before it could represent the
current product:

- Project Open now selects a folder, not a `pom.xml` file (`workflow-old-chooser.txt`).
- Tests now use a bottom panel, not a modal TurboTest report (`workflow-old-results.txt`).
- RefactorJ disk transactions reject symbolic workspace roots. The temporary fixture
  now uses `toRealPath()` rather than macOS's `/var` alias (`workflow-alias-root.txt`).
  This does **not** establish refactoring support through symbolic project aliases.
- New File uses `file.newFile`; `new` now opens the New menu.
- Reassigning Cmd+S removes that conflicting binding, but must retain other Save
  shortcuts. The assertion now compares the actual remaining bindings rather than
  requiring Save to become completely unbound.
- Key presses are posted rather than synchronously awaited across modal secondary
  loops. The harness restores application foreground after macOS deactivation.
- Panel evidence previously called its profile Classic despite selecting
  `turboj-macos`; the report now names the profile actually used.
- Invalid pending-chord suffixes are deliberately consumed under the current
  contract. The old rollover assertion incorrectly expected them to become text.

These are identified separately from the reproduced dispatcher defect. Historical
committed evidence remains unchanged.

## Boundaries

KEY-04/06 remain **PARTIAL** as umbrella requirements. This matrix verifies the
listed contexts and workflows; it does not execute all 309 command callbacks in
all possible states. Notification-specific controls, every nested dialog combination,
live AI-provider interactions, OS shortcut reservations, US/Italian/Swiss layouts,
Fn/Globe, dead keys, IME and VoiceOver are not certified by this audit. KEY-05 retains
the human/host walkthrough. Tests without instrumentation or semantic attribution
are unrelated to this shortcut audit and are not reclassified.

The generated shortcut reference retains the same bindings. Existing four-page
PDFs remain valid and were not regenerated. No production deployment, commit or
push was performed in this task. Unrelated user examples and local evidence were
left untouched.
