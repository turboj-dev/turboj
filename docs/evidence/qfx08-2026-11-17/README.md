# QFX-08 — semantic inline editing

Verified on 17 November 2026 using real JDT/RefactorJ, the native Swing application, isolated project fixtures and
keyboard events. Baseline commit, environment, production source hashes and application artifact hash are recorded
in [environment.json](environment.json). This evidence includes local changes after that baseline commit.

## Delivered behavior

- Generated method fields now include parameter types. Leaving a changed parameter type runs Change Signature,
  starting at the unique source hierarchy root when necessary. Related declarations are updated together. Invalid
  arguments/types, binary or ambiguous roots and silent changes of call/method-reference target are refused.
  No invented casts or replacement arguments make an unsafe change appear successful.
- Enter validates current unsaved source on the semantic worker against the post-generation workspace. JDT
  checks assignment compatibility, bodies and callers. Errors directly intersecting template fields are checked
  independently of historical error counts and are displayed beside those fields. Java constant narrowing rules
  apply; for example, a byte return accepts `127`, rejects `128`, and accepts an explicit `(byte)128` cast.
- Save, Save All and Format await successful confirmation. Changed captures, cancellation and ended sessions
  cannot publish an obsolete validation response. Validation restores temporary semantic snapshots in `finally`.
- Extract Variable, Extract Method and Create Field retain their real forms/previews. Applying a preview starts
  editable declaration fields. Semantic renames update references; Escape and transaction Undo/Redo remain intact.
  Extracted return expressions are not replaced by the generated-method default-value mechanism.

Implementation is in `JavaQuickActions`, `JdtQuickActions`, `InvocationBindings`, `RefactoringController` and
`TurboFrame`. No new dispatcher, framework, command or shortcut was added; the command cheat sheets are unchanged.
The editor's existing linked-field renderer is reused. Usage is documented in [inline templates](../../inline-templates.md).

## Executed verification

| Check | Result | Evidence |
| --- | --- | --- |
| Real semantic integration | PASS — 41 tests, zero failures/errors/skips | [semantic-tests.txt](semantic-tests.txt) |
| App build and tests | PASS — 330 tests, zero failures/errors/skips; packaged application built | [app-build-tests.txt](app-build-tests.txt) |
| New native semantic workflow | PASS — parameter widening, invalid caller/return rejection, corrected Save, Escape, three preview-to-inline actions and transaction Undo/Redo; resulting sources compiled with javac | [InlineSemanticTemplateSmoke](native/InlineSemanticTemplateSmoke.txt), [effect ledger](semantic-effects.tsv) |
| Existing cross-file generated template | PASS — method/caller rename, fields, cancellation, local rename and Undo/Redo | [InlineTemplateSmoke](native/InlineTemplateSmoke.txt) |
| Live templates and generated lifecycle | PASS — four profiles, custom snippets, linked fields, Save/Format, completion/imports, file switching, persistence and print documentation | [LiveTemplateWorkflowSmoke](native/LiveTemplateWorkflowSmoke.txt), [effects](live-template-effects.tsv) |
| Template / AI precedence | PASS — four profiles, proposal filtering and keyboard acceptance; explicit ghost fixtures, not paid cloud inference | [LiveTemplatePrecedenceSmoke](native/LiveTemplatePrecedenceSmoke.txt) |
| Refactoring effects | PASS — existing forms/previews, actual source transformations, javac and workspace Undo/Redo | [KeyboardRefactoringEffectsSmoke](native/KeyboardRefactoringEffectsSmoke.txt) |
| Full first launch | PASS — plain Java and Maven creation, Cmd+1 generation, linked parameter/method edits, real JUnit pass, global shortcuts from bottom panels and modal isolation | [FirstLaunchKeyboardSmoke](native/FirstLaunchKeyboardSmoke.txt) |

Native exit statuses and timestamps: [results.tsv](native/results.tsv). Exact artifact and harness hashes:
[artifact.sha256](native/artifact.sha256), [test-sources.sha256](native/test-sources.sha256).
Test fixtures, source creation and preferences are isolated; key events exercise the actual command/UI paths.
The driver restores only its owned window if a worker JVM takes macOS foreground focus.

Commands, from the repository root:

```sh
./mvnw -pl refactorj/refactorj-runtime -am install \
  -Dtest=NoSuchTest -Dsurefire.failIfNoSpecifiedTests=false -Dit.test=QuickActionsIT
./mvnw -pl app install
KEYBOARD_EFFECTS_OUTPUT=app/target/qfx08-final-native \
  bash scripts/verify-keyboard-effects.sh --only \
  InlineSemanticTemplateSmoke InlineTemplateSmoke LiveTemplateWorkflowSmoke \
  LiveTemplatePrecedenceSmoke KeyboardRefactoringEffectsSmoke FirstLaunchKeyboardSmoke
```

The first command deliberately selects the real runtime integration suite and skips unrelated Surefire tests in
its reactor. The second runs the app's full test suite. Native checks need an unlocked macOS desktop and permission
to launch Swing outside the sandbox. Quit an older development instance and run `./turboj` to try the built app.

## Findings and limits

- The original overload control failed: changing `value(int)` to `value(Number)` silently selected a sibling
  `value(Integer)` for `value(2)`. The [pre-fix failure](overload-before-fix.txt) is retained. The final suite verifies
  rejection through binding comparisons, not merely successful compilation.
- A sandboxed Swing JVM aborted with exit 134 before tests ran. [Original log](sandbox-abort.txt). Running outside
  that sandbox produced the successful app test/build result above; the aborted invocation is not counted as a pass.
- Semantic checks require the actual JDT project/classpath. Existing unrelated errors can remain; this does not
  certify a whole project as error-free. External binaries, reflection and generated consumers outside the source
  workspace cannot be updated or proven by this workflow.
- Multiple or binary signature roots are refused. Unsupported/ambiguous multiple generated declarations retain
  preview-only behavior. Parameter arity/order editing remains in the full Change Signature dialog.
- File switching or editing outside fields still ends the session and retains the text; it is not confirmation of
  valid Java. Ordinary compilation handles unfinished code afterward.
- Physical keyboard layouts, IME candidate windows and spoken VoiceOver remain KEY-05. Paid-cloud inference remains
  AI-05. No performance benchmark, production deployment or complete IntelliJ template parity is claimed here.
- QFX-10's template dialog/search/editor-preview work and AI-13 remain separate tasks.
