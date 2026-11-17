# P0 release qualification — 17 November 2026

Scope: RUN-01/02, QFX-01, REL-01 and GOV-01/02. This is a qualification of the development JAR,
not a macOS package release, website deployment, physical-keyboard certification or cloud-provider test.
Baseline: `a712d51138855ec0b9ba25dc9e134414ffc536d8`, plus the source changes recorded in this delivery.
**VERIFIED:** all six P0 entries closed in their stated scope. `./mvnw clean install` passed **907 tests,
132 suites and 39 modules**, with zero failures, errors or skips. **15 native checks passed** against one JAR.
[Accepted results and timestamps](validation.json) distinguish the fourteen accepted batch checks from the
subsequent successful first-launch check; failed attempts remain intact.

Final artifact: `app/target/turboj-app.jar`

SHA-256: `bdb6e72728a30024a59931651f67b4775d6ff514c67ddfec27c32d44fee12ba2`.

The source changes and evidence are delivered together in the commit containing this report. The final first-launch archive includes
its real keyboard event trace, source snapshots, console, screenshots and report. Save automation passed
78 assertions. The reactor took 3 min 57 sec with warm dependency caches; this is a build observation,
not an editor performance claim.

## Corrections found by the audit

- Remembered main classes lacked project identity. A plain Java project with several mains could silently reuse
  the main name selected in another root. Maven AutoRun had the corresponding module/class-name ambiguity.
  Remembered choices now carry their project root. Active-source and unique-main selection remain unchanged.
- Stop was unavailable while an explicit Run compiled, before its process started. It now covers compilation,
  asynchronous worker preparation and contextual target resolution. Cancellation invalidates the launch ticket.
  Stopping automatic program execution still leaves the independently requested tests intact, and vice versa.
- The Save automation harness depended on mutable example files. It now uses frozen fixtures under
  `app/src/test/resources/save-automation/`, pinned to JUnit 5.13.4 and Java 21 for Maven.
- Two older harness assumptions were obsolete: Run/Save status belongs in the status log, not stdout;
  a key that opens a modal form must be posted asynchronously so the harness can operate that form.
  Native fixture setup is programmatic unless the harness explicitly states keyboard-only creation.

## Acceptance matrix

| Item | Executed evidence / oracle |
| --- | --- |
| RUN-01 plain targets | `RunTargetIsolationSmoke`: ambiguous and missing main refusal; active main; last main within root; identical class names in another root; Stop during compile/context resolution; edit before dispatch |
| RUN-01 Maven targets | `SaveAutomationSmoke`: identical module/class coordinates in two roots; ambiguous AutoRun refusal; explicit choice reused only in its own project. Reactor `MavenRunTargetsTest`: active source/module, sorted multiple mains, test helpers, failed compilation |
| RUN-01 Run versus tests | `OutputStatusSmoke`, `FirstLaunchKeyboardSmoke`: program Run from test source; Run Tests; Eclipse Shift+Cmd+F11; previous JUnit selection and project/module guards; plain and Maven workflows |
| RUN-02 Save | `SaveAutomationSmoke`: defaults off, toggles separate/together, no typing trigger, saved disk/source identity, compile failure, rapid-save coalescing, independent cancellation, both cancelled, edit invalidation, failed disk write, project trust, no Maven wrapper execution |
| RUN-02 focus/results | `OutputStatusSmoke`, `SaveFocusSmoke`, `BufferSaveSmoke`: automatic execution preserves Tests/caret; explicit Run selects Output; asynchronous save of an inactive buffer preserves source, fingerprint, undo and responsiveness |
| RUN-02 lifecycle | Reactor `TurboTestControllerTest`, `MavenAutoTestControllerTest`, Cherry Save/lifecycle tests: real workers, stale generations, module ordering, cancelled/incomplete execution; native `KeyboardTestEffectsSmoke` stops actual executing JUnit |
| QFX-01 | `QuickFixPopupSmoke`: real unresolved Calculator, both TurboJ/Eclipse, caret on type and after statement, classic/Monokai live theme switch, normal/selected/empty text contrast >=4.5:1, arrows/Return create actual class and restore editor focus |
| REL-01 | Complete `./mvnw clean install`; final native suite uses the same SHA-256-identified `app/target/turboj-app.jar`; all selected tests record timestamp and exit status |
| GOV-01 | All 13 pre-existing local files classified, hashed and preserved unchanged; separate local ignored backup. No personal examples promoted into release fixtures |
| GOV-02 | Eight missing historical raw directories explicitly retired as reproducible evidence; 93 retained manifest entries verified and two complete archives read successfully |

## Governance and historical evidence

[preexisting-work.json](preexisting-work.json) records every pre-existing local file's hash and disposition.
A byte-preserving backup is kept locally at `.cache/p0-user-work-2026-11-17.tar.gz`; its hash is in the manifest.
The originals stay in place and are excluded from this changeset:

- Six Java example files are manual working material. `SampleTest.java` currently contains a public HelloWorld,
  and the Maven test example has four tests, not the earlier two. They are not release fixtures or required build inputs.
- `InlineTemplateAcceptance.java` is an untracked legacy harness tied to a mutable external JUG checkout and old
  placeholder/literal assumptions. It is not run as acceptance evidence. The tracked `InlineTemplateSmoke`,
  `InlineSemanticTemplateSmoke`, `FirstLaunchKeyboardSmoke` and real-JDT `QuickActionsIT` supersede that coverage.
- Five October GUI timing reports lack a matching build identity. Preserve them as historical observations;
  do not attribute those timings to this release or infer performance improvements.
- `real-executions.json` contains historical source/hash metadata. All eight referenced raw directories are absent.
  Those exact captures cannot be recovered from metadata, and have not been reconstructed or relabelled as current.

[historical-evidence-audit.json](historical-evidence-audit.json) records actual checks of the retained
[CH-01 delivery](../cherry-native/delivery-2026-11-16/README.md) and
[CH-02/08/09 completion](../cherry-native/completion-2026-11-16/README.md).
All 13 + 80 manifest entries match. Their readable native/raw archives contain 188 and 611 entries respectively.
These are preserved, independently identified later controls, not recovered October sessions.
The audit does not rerun the external mini/shop/JUG suites or make new performance claims.

## Failed controls retained

- `before/`: first Run harness attempt looked for an IDE status message in program stdout. Harness defect;
  not counted as an application failure or pass.
- `before-confirmed/`: corrected oracle on the previous JAR proves cross-project target reuse: Helper runs
  successfully when a fresh project with ambiguous mains should require selection.
- `preflight-native/RunTargetIsolationSmoke.txt`: project isolation fixed; Stop-during-compilation assertion fails
  on the intermediate JAR, establishing the second production regression.
- `preflight-native/QuickFixPopupSmoke.txt`: popup/contrast checks pass, then the old synchronous key driver blocks
  when Return opens a modal form. That owned harness JVM was terminated; subsequent controls in that invocation
  were not run. The corrected driver posts key events and verifies the form's actual result.
- Preflight Maven app builds are preserved separately. Only the final clean reactor/native results establish REL-01.

## Reproduction

Run from the repository root with JDK 25, the repository Maven wrapper and an unlocked macOS desktop.
Native checks require permission to launch Swing outside a restricted sandbox. They run sequentially to avoid
window-focus contention. Warm dependency caches and isolated preferences/projects are used; no user configuration
or personal project is changed.

```sh
./mvnw clean install
KEYBOARD_EFFECTS_OUTPUT=app/target/p0-native \
  bash scripts/verify-keyboard-effects.sh --only \
  RunTargetIsolationSmoke QuickFixPopupSmoke SaveAutomationSmoke OutputStatusSmoke \
  SaveFocusSmoke BufferSaveSmoke KeyboardExecutionEffectsSmoke KeyboardTestEffectsSmoke \
  KeyboardMavenBoundarySmoke KeyboardPomEffectsSmoke FirstLaunchKeyboardSmoke InlineSemanticTemplateSmoke InlineTemplateSmoke \
  CommandRolloverSmoke KeyboardPanelSmoke
```

Quit any older development instance, then use `./turboj` to launch the verified development JAR.
`./mvnw install` does not replace an installed `TurboJ.app`; packaging/signing remains REL-02.
Physical layouts, Fn/Globe, real IME candidate interaction and spoken VoiceOver remain the explicitly postponed
KEY-05 walkthrough. Live paid-cloud inference remains AI-05.

## Additional controls and fixes during qualification

The first clean candidate passed all **907 tests / 132 suites / 39 modules**. Its identity and reactor
results remain in `candidate-1-environment.json` and `candidate-1-reactor-tests.json`; it is not the final
candidate because native Save qualification exposed an additional race:

- Merely opening/restoring a POM scheduled a 550 ms metadata reload. That redundant publication invalidated a
  subsequent saved-source request. `changed()` now distinguishes an actual edit from a document switch before
  notifying Maven configuration changes. The frozen cross-project Maven Save control reproduces the failure
  on candidate 1. Actual POM editing is separately checked by `KeyboardPomEffectsSmoke`.
- Some historical native drivers assumed the app stayed foreground after a worker JVM exited. They now use the
  existing `NativeWindowTestSupport` to reactivate only their owned window. No editor/dialog focus is assigned
  to manufacture a pass; the application must restore the expected component itself. First-launch and output
  checks pass after that driver correction. The failed `focus=null` observations remain recorded.
- The standalone popup fixture used macOS's symbolic `/var` path. Return correctly exposed RefactorJ's existing
  `Workspace root must be a real directory, not a symbolic alias` guard; no class was created. The fixture now
  uses `toRealPath()`, matching first-launch setup, and the same cross-platform look-and-feel as `TurboJ.main`.
  This does not claim that aliased/symlink workspace roots are supported for disk refactoring.
- The unsuccessful `jstack` attachment targeted a test JVM that had already finished; its empty dump is not
  diagnostic evidence. The popup thread dump records an idle EDT, distinguishing a rejected operation from
  an EDT deadlock. The first synchronous-driver deadlock and subsequent rejected-path control are separate.

The final native batch passed 14 checks and encountered one macOS menu-grab cancellation during first launch.
The retained failure has no open dialog and editor focus after a recorded foreground recovery. The driver now
observes actual window-deactivation events and retries the F10/arrow sequence only if a deactivation occurred
and the menu path was cleared before any form opened (maximum two retries). Wrong selected items with a live
menu, ordinary application failures and opened dialogs are not retried. The follow-up uses the **same JAR**;
only this standalone GUI harness is recompiled. Its source hash and result are recorded separately in
`native-final-menu-followup/`. This is not a retry of a failing compiler/test outcome.

A further first-launch control recorded Cmd+A/Cmd+V targeting `JTextPane` (Output) after Run, not the editor.
When the OS deactivates the app at worker completion, TurboJ does not steal focus from another application;
on reactivation Output can therefore remain the remembered control. The workflow now explicitly presses
Escape and waits for source focus before typing the unresolved Calculator reference. This is a real keyboard
step, not programmatic focus assignment. The failed source snapshot and key-event trace are retained in
`captures/first-launch-focus-failure.tar.gz`. The next control, `native-first-launch-final/`, reached the
final Maven JShell focus assertion but recorded `Focus: none` after worker completion. Its report and event
trace are retained in `captures/first-launch-jshell-focus-failure.tar.gz`. The same owned-window recovery
now precedes that assertion; it still fails if the active component is anything other than the editor.
The subsequent control is recorded under `native-first-launch-verified/`; the other fourteen final checks
did not require rerunning. These corrections affect the standalone driver, not the verified production JAR.

Final first-launch result: **PASS**, plain Java and Maven, 176 assertions and 494 recorded keyboard events. See `native-first-launch-verified/results.tsv` and `captures/first-launch-verified.tar.gz`.
