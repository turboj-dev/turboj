# Keyboard interaction audit

This audit covers the executable TurboJ/CommandJ changes, not the browser demo.
The four profiles remain distinct. The generated inventory currently contains
263 registered commands and 29 command-ID aliases; all bundled bindings resolve
to registered callbacks. No debugger or shell terminal was invented.

## Evidence categories

- **Working:** an implemented route exercised by an identified automated check.
- **Broken:** a reproduced failure, retained below with its fix and rerun status.
- **Missing:** functionality that is not implemented, rather than an inert menu.
- **Unverified:** an implementation or host behavior not exercised by this audit.

Command registration alone is not an accessibility result. Registry-generated
availability is a snapshot of one context, not proof that every Java input or
every physical keyboard works. See [design and eligibility](keyboard-first-design.md),
[generated command inventory](keyboard-command-inventory.md),
[shortcut reference](keyboard-shortcut-reference.md) and
[conflict report](keyboard-conflicts.md).

## Confirmed defects addressed

1. Explorer focus was classified as editor focus. Source editing commands could
   therefore match when the tree owned the keyboard. Context now follows the real
   focus owner; the explorer has its own context.
2. Source Copy/Cut were disabled without a selection. They now copy/cut the logical
   line, while selected text retains ordinary clipboard semantics.
3. Several default bindings referenced IDs without application registrations.
   Canonical implementations plus stable aliases now resolve those routes.
4. Closing Command Palette queued old-focus restoration after synchronously
   executing the selected command. The real-window test reproduced Focus Explorer
   returning to the editor. Palette execution now follows overlay restoration.
   A later OS-key walkthrough also found that native focus events could lag this
   queue: opening Theme Browser from the palette captured an intermediate explorer
   focus. `OverlayHost.closeThen` now carries the logical original focus into the
   next dialog; a separate regression test simulates that delayed native event.
5. Legacy binding migration could overwrite user bindings in iteration order.
   Migration now preserves user collisions, removes only conflicting defaults,
   persists the unresolved state and reports it; ambiguous shortcuts do not run.
6. Customizing a canonical action could leave an old alias binding active.
   Reference, palette, customization and reset now aggregate canonical/alias keys.
7. Dialog text areas and shortcut recording could consume traversal keys. The
   shared overlay policy owns plain Tab/Shift+Tab; the recorder has a keyboard-
   reachable selector for recording Tab/Enter/Escape themselves.
8. Inline completion could continue preparing proposals after adding secondary
   carets. Semantic/AI completion now stops early; stale AI requests are cancelled.
9. New File invoked from the console could leave typing focus in the console.
   It now explicitly focuses the new source buffer.
10. After preserving the correct console origin, opening a compiler diagnostic
    moved the source caret but deferred dialog restoration returned focus to the
    console. The source-opening callback now also uses `OverlayHost.closeThen`.
    The regression check verifies the exact source offset and retained editor
    focus after native events settle, rather than accepting a momentary focus.
11. The ASCII combo used a Basic delegate with inherited Aqua action names.
    The real-window profile test reached the control with Tab but could not open
    its list. Its local UI map now targets real Basic actions; application-level
    overrides remain intact. The pre-fix result is retained in
    [the profile failure report](keyboard-evidence/keyboard-profile-switch-before-combo-fix.txt).
12. Explorer Left/Right used Aqua actions after its native delegate was removed.
    A real-window check confirmed that neither expanded nor collapsed the tree.
    The ASCII tree now has compatible local navigation bindings, including
    orientation-aware Left/Right, without replacing component customizations.
    The [pre-fix tree report](keyboard-evidence/keyboard-tree-before-delegate-fix.txt)
    retains the unchanged row counts from both failed operations.
13. The same delegate mismatch affected text-area arrows/paging and List Home/End.
    Text navigation now uses standard editor-kit actions. List Home/End retain
    macOS viewport scrolling without changing the selected item. Unit checks
    exercise caret, selection and viewport effects, not only action registration.

## Automated execution

The full reactor command `./mvnw -q -l target/keyboard-diagnostic-verify.log verify`
completed successfully on 25 October 2026 (Europe/Zurich): **565 tests in 87 suites,
zero failures, errors or skipped tests**. Only reports freshly written by this run
are counted; old reports from targeted selectors are excluded. The suite totals
and paths are retained in [reactor-verification.json](keyboard-evidence/reactor-verification.json).

The full-reactor run above produced an `app/target/turboj-app.jar` with SHA-256
`b3441f5efac0aafef40d45357baf5e2bcd60b1efa29ad89099d75551b9148790`.
This is an uncommitted implementation artifact, not a newly published release.
Test sources are executable artifacts, not manual checklists:

| Area | Check | Scope |
| --- | --- | --- |
| Text / multicaret | `KeyboardEditingTest`, existing editor tests | Sparse edits, selection direction, overlapping/adjacent ranges, CRLF, Unicode, IME, clipboard metadata, undo/redo |
| Completion | `AICompletionControllerTest`, intelligence tests | Actual pending inference cancellation and no request/context capture with multiple carets |
| Java structure | `KeyboardJavaIT` | 32 unique embedded-JDT cases; candidate compilation, method-move runtime equivalence, call-site parameter reorder and workspace undo, generation, brackets, stale input |
| Commands/keymaps | `CommandRegistryTest`, `KeyboardAuditTest`, `KeymapTest`, `CommandSearchTest` | Aliases, prefix/context conflicts, migration persistence, profiles, shortcut search and generated reference |
| Registry | `KeyboardRegistryAudit` | Actual initialized TurboFrame; 259 descriptors, 29 aliases, zero unregistered bundled bindings |
| Real-window editing | `KeyboardEditingSmoke` | Four-profile clipboard and line operations; genuine multicaret typing/delete/comment/undo; console/explorer focus |
| Real-window workflows | `KeyboardWorkflowSmoke` | Prepared projects and actual key-driven application workflows; detailed results retained with the harness |

The first real-window editing run passed the editing/console checks and then
failed the palette-to-explorer focus transition. This is a reproduced defect,
not a skipped assertion or an inferred pass. Its correction is described above.
The final `KeyboardEditingSmoke` rerun on the artifact identified above passed
**94 assertions across all four profiles**, including real clipboard checks,
multicaret edits/undo and explorer/console focus restoration. This uses the actual
Swing key-dispatch pipeline in visible windows; see
[the retained final report](keyboard-evidence/keyboard-editing-final.txt).

Six alternating baseline/current input-to-paint sessions each observed all 120
injected keys and corresponding paints. The first pair and both controls are
reported in [keyboard-performance.md](keyboard-performance.md). Results vary and
do not establish a universal speedup or unchanged physical input latency.

`KeyboardPanelSmoke` additionally exercises JShell evaluation (`1+1` produces `2`),
menu navigation, actual JDK completion proposals and Escape, and opening a compiler
diagnostic from console focus with Enter. It verifies the exact source offset and
editor focus after native events settle. This is a 13-assertion supplement, not
programmatic invocation of those command callbacks; see
[the report](keyboard-evidence/keyboard-panels-final.txt).

The final `KeyboardWorkflowSmoke --robot` run on the same artifact passed **50
assertions**, using OS AWT Robot events and the system clipboard, without mouse
events or corrective programmatic focus changes. Fixture creation and observations
remain programmatic. The [report](keyboard-evidence/keyboard-workflows-final.txt)
and [artifact/harness identity](keyboard-evidence/keyboard-workflows-artifact.json)
retain the actual run; frame captures remain in `app/target/keyboard-workflow-smoke/`.

| Workflow | Verified result |
| --- | --- |
| A | Keyboard Maven import of the isolated cached fixture, open/edit/save, explicit trust and native Run; no Maven lifecycle output directory |
| B | Extract Variable parameters, inspect RefactorJ preview, apply and one workspace Undo |
| C | Two real JUnit tests, enable Auto Test on Save, introduce failure, navigate to test source, correct/save and pass |
| D | New from console, Save As, argument configuration, Tab/Shift+Tab through fields and scroll controls, interactive Scanner input, copy output, Escape |
| E | Find command, record conflicting shortcut, inspect/resolve conflict, use/undo new binding, reload persisted settings and open generated reference |
| F | Search theme, preview, apply Modern Light and retain source focus |
| G | Genuine multiple carets, simultaneous editing and Undo/Redo in all four profiles (`KeyboardEditingSmoke`) |
| H | Select complete Java method, semantic move, compile and pass both JUnit tests |

Earlier attempts also exposed incorrect harness assumptions: partial method-header
selection is deliberately ineligible for a complete-method move, a safe method
move need not open a warning preview, and scrollbars/close buttons are legitimate
Tab targets. These were corrected in the harness; they are not reported as product
fixes. The actual theme and diagnostic focus failures were reproduced, fixed and
rerun on the final artifact.

## Profile switching follow-up — 25 October 2026

**Options → Keyboard Profile** and four **Keyboard Profile: …** palette actions
switch between IntelliJ IDEA macOS, Eclipse macOS, VS Code macOS and TurboJ
Classic. The existing **Options → Keymap** selector also handles custom profiles.
The checked menu entry tracks the active bundled profile; a custom profile does
not falsely check one of the bundled choices. Switching uses the existing
CommandJ selection and asynchronous preferences path, without resetting bindings.

The targeted persistence run passed 30 tests, including three new
`ProfileSwitchTest` cases. The shared-control regression run subsequently passed
56 tests, including four combo, three tree and two text/list cases. Tests retain
independent profile overrides and explicit unbindings, restart selection,
inheritance and cancellation of a pending chord when the profile changes.

After these changes, `./mvnw -q -l target/keyboard-profile-final-verify.log verify`
passed **577 tests in 91 suites**, with zero failures, errors or skipped tests.
The resulting artifact SHA-256 is
`50b2c49c3b8329b4812a5dcef7d327ddfaf0845a4b7bf76e5da24ab319302f0c`.
Fresh report identities and counts are retained in
[profile-reactor-verification.json](keyboard-evidence/profile-reactor-verification.json).
The regenerated application registry contains **263 commands, 29 aliases and zero
bundled bindings without a callback**. This follow-up is separate from the
earlier 565-test/B344 artifact evidence above.

On this same final artifact, `KeyboardProfileSmoke` passed **32 assertions** in a
visible Swing window. It changes all four profiles from the palette using the
previously active palette binding, verifies the distinct Cmd+D behavior, changes
the profile with menu arrows and Enter, and operates the settings combo with
Alt+Down, Space, Home, Down, Enter and Escape. It also reloads the on-disk settings
into a fresh frame and executes a preserved custom binding. See
[the report](keyboard-evidence/keyboard-profile-switch-final.txt) and
[harness/artifact identity](keyboard-evidence/keyboard-profile-switch-final.metadata.json).

The expanded `KeyboardPanelSmoke` passed **23 assertions**: console caret movement
and selection, diagnostic-list viewport Home/End with selection preserved,
explorer collapse (9 rows to 1) and expansion (1 to 9), plus JShell, menus,
completion dismissal and diagnostic source focus. See
[the report](keyboard-evidence/keyboard-panels-profile-final.txt) and
[harness/artifact identity](keyboard-evidence/keyboard-panels-profile-final.metadata.json).
These two harnesses dispatch keyboard events through Swing in real windows;
fixture setup and state observations are programmatic. They are not physical
keyboard-layout or human usability certification.

`KeyboardWorkflowSmoke --robot` then repeated workflows A–F/H on the same artifact
and passed **50 assertions**, using actual OS AWT Robot key events without mouse
events. This includes Maven opening, native execution, JUnit failure/correction,
Auto Test on Save, semantic method movement, refactoring preview/apply/undo,
interactive stdin, keymap conflict resolution and theme selection. The
[final workflow report](keyboard-evidence/keyboard-workflows-profile-final.txt)
and [artifact/harness/runtime identity](keyboard-evidence/keyboard-workflows-profile-artifact.json)
are separate from the earlier B344 evidence. The 94-assertion multicaret editing
walkthrough remains identified with its earlier artifact; it was not relabeled
as a new run. No new latency or physical-layout result is inferred from these
functional checks.

## Local widget routes

Plain editing, source navigation, refactoring, formatting, build/run/test, Maven,
AI settings, themes, panel focus and file operations are registry actions. Dialog
buttons, table/list navigation, tree expansion, text selection, JShell history and
ordinary field editing are local Swing interactions. They are intentionally not
duplicated as hundreds of global actions that would intercept text entry.

Enter does not globally run a program. In a list it opens the selected result;
in the console it sends input; in a dialog it confirms the focused action; in a
multiline field it inserts a newline. Tab follows editor/completion context in
source and traverses controls in dialogs. Escape closes a popup/dialog first,
then dismisses suggestions or secondary carets and returns from a tool area.

Run Configuration exposes arguments (one argument per line) and optional initial
stdin. Both are session-local and passed directly to the existing worker; there
is no shell parsing. Further stdin remains interactive. Source/Test navigation
uses a documented filename convention. Call hierarchy reports static source
bindings, not a complete dynamic execution graph.

## Conflicts and platform limits

The fresh four-profile registry audit reports no exact/prefix binding collisions
and no binding without a callback. It retains the macOS **Ctrl+Space** input-source
advisory in every profile. Users can change that binding; a matching OS shortcut
can prevent Java from receiving the event.

**Unverified:** physical Italian, Swiss Italian, Swiss German and US keyboards,
dead-key behavior across all input sources, Globe/Fn modes, system-reserved-key
delivery, VoiceOver and third-party accessibility services. Unit input-method and
Unicode tests do not certify those host combinations.

The desktop harnesses use synthetic keyboard events in real Swing windows on
macOS, with isolated settings and fixtures. Programmatic setup or observation is
identified in each harness. They do not use mouse events for the tested actions.
This is not a human walkthrough, a physical keyboard timing measurement or a claim
that all registered operations have been manually exercised. A human-only end-to-end
usability audit remains unverified.

**Missing by existing product scope:** integrated debugger, general shell terminal,
Gradle import and a persistent rectangular arrow-navigation mode. Rectangular
selection itself is implemented through real per-line selections. Java structure
actions require one source range and reject unsupported or unsafe transformations;
they are not universally applicable to arbitrary Java syntax.

## Website boundary

Only verified keyboard behavior may be described publicly. The generated reference
is the source of shortcut documentation; Classic keys must not be advertised as
universal defaults. This task does not change the hero replay or deploy production.
The focused website copy and generated download passed Astro checking, production
build and 39 tests. The existing local preview served matching homepage/download
bytes over HTTP. Fresh browser/layout verification remains unverified: sandbox
launch failed and the escalated preview/browser requests remained awaiting
approval until aborted. Prior production browser reports were not relabeled as
new results. See [website QA](../website/QA.md).

## Repeating the checks

Use JDK 25 and a logged-in macOS desktop. Run the desktop harnesses sequentially;
the Robot harness needs host accessibility permission and sends actual keys to the
focused TurboJ test window. It refuses to send keys to another application. All
fixtures and preferences are isolated; prior clipboard contents are restored when available.
Do not type into or change focus away from the audit window during a run.

```sh
./mvnw -q -l target/keyboard-diagnostic-verify.log verify
java -cp app/target/test-classes:app/target/turboj-app.jar \
  dev.turboj.app.KeyboardRegistryAudit docs
java -cp app/target/test-classes:app/target/turboj-app.jar \
  dev.turboj.app.KeyboardWorkflowSmoke --robot
java -cp app/target/test-classes:app/target/turboj-app.jar \
  dev.turboj.app.KeyboardEditingSmoke
java -cp app/target/test-classes:app/target/turboj-app.jar \
  dev.turboj.app.KeyboardPanelSmoke
java -cp app/target/test-classes:app/target/turboj-app.jar \
  dev.turboj.app.KeyboardProfileSmoke
```

Reports and screenshots are written under `app/target/keyboard-workflow-smoke/`
and `app/target/keyboard-editing-smoke/`; panel evidence is in
`app/target/keyboard-panel-smoke/`. Regeneration of the reference also records
the application artifact and source hashes in `keyboard-reference-sources.json`.
Website asset preparation checks these hashes before copying three Markdown
downloads, so changing a native command requires regenerating the reference.
The website does not launch Java or execute any IDE command.
