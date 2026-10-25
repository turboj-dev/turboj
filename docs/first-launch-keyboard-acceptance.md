# First-launch keyboard acceptance

**PASS: the native Java and Maven sequences both reached a real passing JUnit result in one clean-profile session.** Run09 completed with exit 0, 241 keyboard events and 100 recorded PASS assertions on 2026-10-25. The app was visible throughout; no mouse events or direct command callbacks were used. This is automated Swing acceptance, not a human walkthrough or certification of OS keyboard layouts.

The [session report](evidence/first-launch/run09/report.txt), [key/focus trace](evidence/first-launch/run09/keyboard-events.tsv), [checkpoint data](evidence/first-launch/run09/checkpoints.json), [native Java result](evidence/first-launch/run09/java-green.png) and [native Maven result](evidence/first-launch/run09/maven-green.png) are committed for review. The screenshots are unmodified frame captures from the application, not mockups. [Evidence provenance](evidence/first-launch/README.md) describes path redaction and hashes.

## Environment and method

- macOS 26.5.1, arm64; Corretto 25.0.4.1+10-LTS; project Java 25.
- Application JAR SHA-256: `782d81bfc43b628bd143837f9d755be4b4ae8e4e148c77be83f12f2b2deeb651`.
- Eclipse macOS profile selected through CommandJ from a clean, isolated profile. Workspace/project creation was performed through real native dialogs and keyboard menus.
- Posted `KEY_PRESSED` / `KEY_RELEASED` events entered the actual AWT event queue and normal Swing focus/CommandJ dispatch path. Source text was entered through Cmd+V into the editor, with the system clipboard backed up and restored. No helper wrote project source files before the UI created them.
- Read-only inspection asserted current source, caret, diagnostics, reports and open buffers. It did not invoke application actions. Checkpoints preserve both dirty editor buffers and saved final projects.
- Maven ran offline with cached dependencies; its resolved generation, module, source roots and dependency coordinates are captured. Jupiter 5.13.4 / Platform 1.13.4 were actually resolved. This is not a cold-download or explicit Maven lifecycle test.
- The [build manifest](evidence/first-launch/build.json) records a repository source-fingerprint inventory, including tests and fixtures; it is not a compiler dependency graph. It retains the 466 passing app/core tests across 80 classes from before the final Eclipse-only keymap correction; that correction separately passed 24 keymap and nine registry tests. A separate [profile regression](evidence/first-launch/regressions/profile-report.txt) subsequently passed 32 assertions on the same artifact, covering all four profiles; it is not part of the run09 session. The final harness source/class matches package09; its exact hashes are in the [capture manifest](evidence/first-launch/manifest.json). The earlier run08 used a separately recompiled harness and palette Save All, and remains preserved as an earlier PASS rather than substituted for this final run.

The registry audit recorded **268 commands, 29 aliases and zero bindings without callbacks**. Six Node tests for the generated shortcut reference passed. These checks support registry/reference consistency; they do not substitute for native interaction evidence.

## Native workflow results

Each checkpoint below has `java-` and `maven-` variants in the checkpoint JSON unless noted. The same session performed the Java workflow first, then Maven.

| Requirement | Result | Native evidence |
| --- | --- | --- |
| Clean workspace choice, remember option, editor focus | PASS | `workspace`, key/focus trace |
| File → New → Java/Maven Project | PASS | F10, arrows, Return; `*-project-form` |
| Create packaged HelloWorld main source | PASS | Main form; real `src/main/java/tutorial/HelloWorld.java` |
| Compile and Run HelloWorld | PASS | `*-hello`: actual `Hello World!` and exit 0 |
| Cancel pending Cmd+1 inspection | PASS | Inspection was pending at Escape; no popup/source/selection change during subsequent 2 s observation |
| Popup owns keyboard input | PASS | Tab/Shift+Tab filter/list/explanation cycle; Cmd+D leaves hidden source unchanged |
| Global palette supersedes contextual popup | PASS | Cmd+3 then Escape, source focus restored without stale popup |
| Second Cmd+1 exposes Quick Assists | PASS | Fix-only context switches to assists; Escape preserves original selection |
| Create missing Calculator at the caret | PASS | Contextual inferred class form; `*-class-created` |
| Create JUnit test on existing class declaration | PASS | Inferred name/package/test root; no unrelated wizard detour |
| Configure missing JUnit explicitly | PASS | `*-junit-dependency`; plain bundled-library configuration / real Maven pom update |
| Execute generated `fail` stub | PASS | `*-assertion-failure`: one compiled, executed assertion failure |
| Distinguish unresolved method from failed assertion | PASS | `*-compile-failure`: current-version `add(int,int)` compiler diagnostic; prior JUnit report remains unchanged |
| Create inferred `add(int,int)` through Cmd+1 | PASS | `*-method-proposals`, `*-method-template`; generated target opens with body selected |
| Linked parameters and body references | PASS | Body entered first; Tab-driven i→left and j→right update real body references |
| Linked method-name rename | PASS | add→addition updates declaration and original test caller |
| Single Undo/Redo across both files | PASS | `*-linked-rename-undo`, source assertions and key trace |
| Save actual source changes | PASS | Actual Eclipse Cmd+Shift+S; no Save As dialog; both files saved in `*-method-implemented` |
| Preserve navigation history | PASS | One Back returns to original test; Forward returns to Calculator |
| Run previous JUnit from Calculator implementation | PASS | Shift+Cmd+F11; Calculator stays active; `*-green` |
| Repeat through Maven’s resolved project model | PASS | Maven generation 2, resolved=true, offline=true; `maven-green` |

The final test is `assertEquals(42, new Calculator().addition(20, 22))`; the implementation returns `left + right`. Both runs discovered and executed `testCalculatorAddition`, with one passed test and no engine failures. The supplied document’s ambiguous zero operands and incorrect prose were not copied into the acceptance fixture.

The compilation-failure checkpoints establish an uncompilable current revision and unchanged historical test report. They do **not** claim that a JUnit test process was launched for that invalid revision. Background compiler updates can replace console text; the assertion deliberately uses current compiler diagnostics and source version instead of treating a transient message as the result.

## Failed controls retained

[controls.json](evidence/first-launch/controls.json) retains the earlier reports, key traces and failure states. Earlier failed runs are not relabeled as the final success.

| Run | Outcome | Classification and resolution |
| --- | --- | --- |
| 01 | FAIL | Harness compared `/var` with canonical `/private/var`; temporary root canonicalized. |
| 02 | FAIL | Harness synchronously waited for modal Find key dispatch to return; real AWT events are now posted separately so secondary modal loops can receive subsequent keys. |
| 03 | FAIL | Product popup Tab escaped to the hidden editor. Local popup focus cycle fixed; confirmed in later runs. |
| 04 | FAIL | Product generic refactoring availability gate blocked second Cmd+1 while popup owned focus. Contextual availability fixed. |
| 05 | FAIL | Harness expected Save/OK while the real Save before Run dialog had Yes/No/Cancel. Harness now selects actual Yes. |
| 06 | FAIL | Linked semantic edits polluted navigation history with same-file jumps. Resume now preserves the original caller history; verified in 07/08. |
| 07 | PARTIAL | Java workflow fully passed. Maven reached real initial assertion failure and current missing-method diagnostics; harness incorrectly required a transient console sentence. State assertion corrected. No complete Maven success is claimed for 07. |
| 08 | PASS — prior flow | One coherent native session completed both workflows using palette Save All. Its original report, bundle, PNGs and manifest remain preserved. |
| 09 | PASS — final | Both workflows repeated coherently with the corrected actual Eclipse Cmd+Shift+S Save All gesture. |

## Limits and reproduction

The [20-page parity map](eclipse-workflow-parity.md) records partial rows: sponsor/welcome pages, optional Eclipse wizard features/framework versions, the per-file Save and Launch checklist, and content assist not exercised here. These limits are not hidden by the successful core project/test flow. Non-UTF-8 Maven POM files are explicitly unsupported for automatic dependency editing; they are refused rather than rewritten using a guessed encoding. The extensible Quick Fix/Quick Assist provider covers the implemented actions and does not claim the entire Eclipse processor/refactoring catalogue. Physical US/Italian/Swiss layouts, OS-level shortcut delivery, IME composition, assistive technology and a human keyboard-only walkthrough remain **UNVERIFIED** for this change. Source/popup rendering in other themes was not certified by this session.

Use a logged-in macOS desktop and JDK 25. First build with the repository Maven wrapper, including test compilation. If using a fresh Maven cache, populate the dependencies before the offline harness. Do not run another Swing application or change foreground focus during acceptance.

```sh
./mvnw -pl app -am test package
java -Dfirstlaunch.output=app/target/first-launch-keyboard-smoke-review \
  -cp app/target/test-classes:app/target/turboj-app.jar \
  dev.turboj.app.FirstLaunchKeyboardSmoke
```

The harness creates only its own temporary home/preferences/workspace and copies source/checkpoints into its chosen output directory. It calls the actual TurboJ bootstrap. Optional `--robot` uses OS AWT Robot events and requires host accessibility permission; that mode was **not** used for the committed run09 evidence. Final generated projects are under [fixtures](evidence/first-launch/fixtures), but replaying the acceptance harness starts from an empty workspace and does not load those files as shortcuts around the UI.

Only the generated keyboard-reference files in the website public directory were refreshed; the website demo/design and production deployment were unchanged. Recorded timings are observations from this session, including automation waits, not a performance comparison with Eclipse.
