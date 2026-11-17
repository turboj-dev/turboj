# KEY-04: actual command outcomes and dialog branches

**Follow-up complete:** the [remaining automated boundary checks](boundary-followup/README.md) passed. The current ledger has **309 witnessed command outcomes**, with no branch-only or unverified commands. Historical limitations below describe the earlier run; physical KEY-05 checks remain postponed.

This continues the 17 November routing/context audit. The new native harnesses keep
production CommandJ handlers installed. They post Swing keyboard events, inspect
real editor/file/settings/process effects, and use isolated projects/preferences.
Fixture creation, source selection and some tree selection are programmatic;
these checks are not a physical-keyboard walkthrough. GUI harnesses run sequentially.

## Defects found and corrected

1. **Remove Import:** a caret on the `java` package qualifier was rejected by generic
   symbol resolution before the import-specific operation could run. Import edits
   now use their own AST validation. A runtime regression also confirms that removing
   a used import is still refused. `remove-import-before.txt` retains the failure.
2. **Workspace Redo:** undoing a cross-file refactoring and opening a previously closed
   affected source lost the document identity required by Redo. Existing affected Java
   files are now materialized before capturing transaction history. The native checks
   inspect both files, compile the result, undo/redo the entire map, and verify that a
   real intervening edit still prevents Redo. `multifile-redo-before.txt` is the control.
3. **Go to Implementation:** JDT bindings from separate compilation units could not be
   compared using resolver-local subtype/override methods. Navigation now finds the
   target declaration within the candidate's hierarchy and compares overrides there.
   Two runtime regressions cover a concrete subtype, cross-file method overrides and
   an unrelated overload. `implementation-navigation-before.txt` retains the failure.
4. **Evaluate Current Statement:** explicit compilation could supersede continuous
   syntax analysis, leaving the command indefinitely at “analysis pending”. It now
   obtains the statement through the existing asynchronous semantic service and rejects
   responses after source, document, caret, composition or dialog state changes.
   `evaluate-statement-before.txt` retains the original failure.

5. **Maven Run Tests versus editor compilation:** background compilation could replace
   the shared reactor outputs after tests compiled but before their worker pinned the
   classpath. Test compilation now atomically captures selected execution snapshots
   and all test fingerprints under the compiler monitor, entirely off the EDT.
   Workers and aggregation consume that captured generation; source/model validity
   checks still reject genuinely stale runs. Pins close on success, cancellation and
   failure. A deterministic regression replaces compiler outputs before executing the
   prepared class and verifies original bytecode, fingerprints and cleanup.
   `maven-test-generation-before.txt` records the native failure.

6. **New Java Class from Explorer:** the generated class could open while focus
   remained on the tree. Source focus is deferred until queued workspace publication
   observers finish, and guarded against a subsequent document/dialog change.
   `explorer-class-focus-before.txt` is the original actual-source/focus failure.
7. **New Folder during directory loading:** the chooser displayed a new directory
   path but kept New Folder enabled while its asynchronous listing still represented
   the previous directory. A nested keyboard test created its empty folder in the
   previous location. New Folder and Up now remain disabled until a successful current
   listing is accepted. The test-created empty folder was removed; the successful
   rerun must verify creation under the requested isolated directory.
   `chooser-folder-before.txt` retains the failed control.

## Outcome suites

| Harness | What its assertions establish |
| --- | --- |
| KeyboardActionEffectsSmoke | Exact editing results and Undo/Redo, selections, settings, profiles, generated Java and semantic movement |
| KeyboardRefactoringEffectsSmoke | Real parameters/preview cancellation/application, expected source, javac and workspace Undo/Redo |
| KeyboardMultiFileEffectsSmoke | Pull Up, Push Down, Extract/Introduce Interface and Move across actual files, including history identity |
| KeyboardBranchEffectsSmoke | Workspace validation, nested file/overwrite dialogs, dirty Close/Quit branches, cloud-consent cancellation and real Maven processes |
| KeyboardExplorerEffectsSmoke | Tree navigation, file/folder/package creation, rename/delete, cancellation, undo and source opening |
| KeyboardPomEffectsSmoke | POM previews, XML changes and Undo/Redo, actual imported model views, profiles/settings and project search |
| KeyboardNavigationEffectsSmoke | Search/replace, method/call/type navigation, project searches, recent/edit locations and file cycling |
| KeyboardExecutionEffectsSmoke | Real Java workers, stop/EOF, shell processes, JShell, all formatter engines, settings/theme loading and fullscreen |
| KeyboardTestEffectsSmoke | Actual JUnit method/class/failed/all/context selection, failure navigation and cancellation after test readiness |
| KeyboardViewEffectsSmoke | Actual panel geometry/focus, visibility, diagnostics and explicit no-warning behavior |
| KeyboardApplicationEffectsSmoke | Project/file opening, Save All, multicaret effects, console clipboard and actual all-command reference export |
| KeyboardCherryEffectsSmoke | Real CLI execution, native result/line inspection and cancellation; external report browser remains separate |
| KeyboardIntelligenceEffectsSmoke | Actual JDT proposal selection/acceptance, documentation/signatures, menus, refactoring chooser cancellation and AI settings guards |

Maven lifecycle goals use a pinned, cached Maven distribution and an isolated POM
project. Its `test` lifecycle success does **not** establish JUnit execution; the
separate JUnit harness does. Maven cancellation uses an owned wrapper deliberately
waiting before invoking Maven, so no slow external service is needed. The initial
missing help-plugin cache control is retained in `maven-help-cold-cache.txt`; the
plugin was subsequently cached and the goal rechecked.

Cloud settings tests use an in-memory credential store and make no provider request.
AI-disabled guards and dialog cancellation do not certify cloud inference or model
download success. Live local inference belongs to the separate completion evidence.

## Reproduction

```sh
./mvnw install
bash scripts/verify-keyboard-effects.sh --regressions
```

The script records each native JVM's exit status and timestamps, individual logs,
and the exact application JAR hash under `app/target/keyboard-effects-suite`.
Per-command assertions are written by each new harness as `effects.tsv` under its
own `app/target/keyboard-*-effects` directory. Classpath entry points explicitly use
the same `jdk.compiler` exports provided by the production `-jar` manifest.

The native driver may reactivate its own window after macOS worker activation changes;
it does not substitute a target component or an expected application state during
an outcome assertion. Dialog focus and caret outcomes must still be produced by
the application. Ordinary input during a GUI run can interfere with its fixture.

## Scope

An assertion for one valid input or cancellation branch is not proof of all Java
programs, all provider configurations or all combinations of nested dialogs.
Routing coverage, dialog entry/cancellation and verified mutation/execution effects
are separate claims. Physical layouts, Fn/Globe, IME candidate UI and human VoiceOver
remain explicitly deferred under KEY-05.

## Initial outcome run and remaining scope (superseded by the boundary follow-up)

- **PASS:** all 22 entries in [the final native run](final-run/results.tsv), on one
  application artifact. [Artifact/environment](environment.json), [source hashes](effect-sources.sha256).
- **PASS:** 850 unit/integration tests across 121 affected-reactor suites, zero failures,
  errors or skips. [Per-suite totals](unit-integration-results.tsv). The dependency
  reactor completed its tests but stopped on a new harness import; the app was then
  successfully rebuilt/tested, followed by another successful CommandJ Swing + app
  install after the focus/chooser fixes. Logs retain both compile-only harness failures.
  The earlier full-root install passed 889 tests before these last changes; it is not
  presented as a final full-root rerun.
- **PASS:** [48 live local-AI assertions](reports/AILivePrecedenceSmoke.txt), using the
  externally owned installed model, without cloud requests or downloads.
- **PASS:** [actual Cherry workflow](cherry-effects.txt): two JUnit tests, native results,
  independent line-inspection sections and cancellation of a second real CLI run.
  The fixture's wrapper invokes cached Maven 3.9.11 offline using existing dependency
  and provenance-hook artifacts. Missing desktop `mvn` PATH and the initial combo-box
  test-driver error are retained as controls. A model/result failure is never called
  a successful test run.
- **PASS:** [registry generation](registry-audit.txt), 309 commands / 29 aliases,
  zero bindings without callbacks; [six reference export tests](reference-tests.txt).
  Bindings and the existing PDF layout did not change.

Before the follow-up, the generated [per-command ledger](command-outcomes.md) had **302 witnessed effects,
6 branch-only cases, and 1 unverified command**. Its descriptions deliberately bound
what each assertion proves. The six branch-only cases are cloud/local AI settings
cancellation, model-download cancellation, source/Javadoc attachment with no candidate,
and parent-POM navigation with no parent. They do not establish successful cloud
inference, model installation, attachment acquisition or external-parent navigation.
The annotation-processing command compiled a real Maven fixture without a custom
processor; custom processor execution has lower-level tests, not an equivalent native
wizard assertion here. Additional project/provider-specific nested branches remain.

`cherry.report` was **UNVERIFIED** in this initial run at the external-browser boundary: its optional
OS-keyboard probe saw a supported browser foreground, but could not establish the
report URL through the address bar. See [the retained probe](cherry-browser-unverified.txt).
This is not evidence of a broken report command or proof that Accessibility permission
was denied. The HTML artifact was generated and hashed; external rendering is not
certified. A report tab may remain open because the probe closes a tab only after
positively matching its URL. The clipboard was restored.

The [locked-desktop run](locked-desktop-run/README.md) is environment-blocked evidence,
not 22 product regressions. The user unlocked the Mac; the final 22-suite pass followed.
The runner now stops if macOS locks instead of cascading into focus failures.

At this stage KEY-04 remained partial for the listed successful external branches and
application-specific combinations; the follow-up above resolves the identified automated gaps. KEY-05's postponed human checks remain
separate. No commit, push, deployment or unrelated-example edits were performed here.


## Supplemental reproduction

After building, compile `AILivePrecedenceSmoke.java` and `KeyboardCherryEffectsSmoke.java`
against `app/target/test-classes:app/target/turboj-app.jar`. Run them sequentially with
`--enable-native-access=ALL-UNNAMED` and the compiler exports listed in
`scripts/verify-keyboard-effects.sh`:

```text
dev.turboj.app.AILivePrecedenceSmoke http://127.0.0.1:11434 qwen2.5-coder:1.5b-base
dev.turboj.app.KeyboardCherryEffectsSmoke /path/to/cherry-test-java.jar
```

The second harness requires the fixture's pinned Maven dependencies and Cherry
provenance hook already present in the user Maven cache. `--browser` adds the separate
optional external-browser probe; it did not pass here. The ledger can be regenerated
without executing the application:

```sh
python3 scripts/generate-keyboard-outcome-ledger.py
node --test website/tests/keyboard-reference.test.mjs
```
