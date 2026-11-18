# Cherry Test × TurboJ native integration

Status: **implemented and tested vertical slice; Task 2 is not complete**. Baselines inspected: TurboJ `bb59258`, Cherry Test `0dc649cb`, JUG `b1f1ad8`. Existing example edits and JUG working-tree changes were preserved.

Published source pair (16 November): TurboJ `1414347` and Cherry Test
`302325e8`. Cherry's tracked remote is `https://github.com/lmrrcc/cherry-test`,
not the legacy `test-it` remote. See [CH-01 delivery and clean-checkout instructions](../evidence/cherry-native/delivery-2026-11-16/README.md).
The CLI and provenance artifact still identify themselves as `0.3.16`; pin the
commit when reproducing this integration. No binary release or Maven Central
publication is implied.

## Real-project delivery (16 November)

The [completed CH-02/08/09 delivery](../evidence/cherry-native/completion-2026-11-16/README.md)
supersedes the implementation blockers in the earlier qualification. Cherry `39af874d`
combines native events and the sidecar/container scheduler, pins mini `9811e78` and
shop-java `b3429b6`, and preserves standalone behavior. TurboJ now offers the
CommandJ action **Cherry Test: Project Execution Mode…** with persisted per-project
configured/local/container policy; Save uses the same policy. Configured omits an
override. Cancellation allows Cherry's TERM cleanup before forced termination.

External JVM adapters identify actual source roots, fresh class directories and
`.exec` data in a versioned `coverage-evidence.json`. The host regenerates precise
counters and validates compiler inputs, class hashes, process IDs, session IDs and
completion receipts. Completed phases with compatible identities can supply a
provider-generated terminal `coverageUnion` artifact: actual probes are unioned,
never counters added. Contributing collections, compilations, processes and class
files remain identifiable. Missing/ambiguous/mismatched data stays unknown.

A source collection whose compiler/process inputs are verified but whose class
was not loaded is not current on its own. Only a union that actually contains
all required matching class execution records can certify that source. Kotlin,
Rust/LLVM, ECJ and arbitrary reused/incremental compiler outputs still have no
supported proof producer and cannot be promoted to current by timestamps.

Actual native Save runs all four mini Java categories (36 passes) and displays
verified coverage: five source files, 81 executable lines. Shop Java's 12 passing
integration tests produce three proven JVM sessions and 425 current lines; its
preexisting unit assertion failure remains visible. Kotlin Spring (25) and Rust
shop (12) integration tests pass with real counters but unknown provenance.

Native JUG runs its complete configured suite: 33 phase results, 1701 passes and
one skip, with Spring/Quarkus and browser packages distinct. Coverage-disabled
and explicitly enabled configuration were tested independently. Enabled test-JVM
counters do not certify an uninstrumented backend. JUG's report groups HTTP
observations and collapses some names; the evidence report reconciles its totals
without manufacturing native tests or source attribution. Real Docker cancel
removes owned containers and cannot produce complete success.

See the delivery report for exact commands, artifact hashes, source restoration,
reactor-only test accounting, and known capability boundaries. QFX-04 is
still the separate keyboard-only workflow, not implied by programmatic native
acceptance.

## Ownership and execution

Cherry Test remains the orchestrator. Its Java CLI optionally emits schema-v1 JSONL when `CHERRY_TEST_IDE_EVENTS` is set. Ordinary CLI and HTML reporting remain available. TurboJ does not parse ANSI output or HTML colors as results and adds no test-framework dependency to the editor.

```mermaid
flowchart LR
  CLI[Isolated Cherry Java CLI] --> Events[Versioned events + hashed artifacts]
  Events --> Reader[CherryEvents + TestSourceResolver]
  Reader --> Run[Immutable Run]
  Git[Git baseline → editor buffer] --> Snapshot[Immutable line snapshot]
  Run --> Snapshot
  Snapshot --> Editor[Java2D gutter + keyboard inspection]
  Run --> Panel[Native results view]
  CLI --> HTML[Existing complete HTML report]
```

`TurboTestController` owns `CherryTestProcess`. The child CLI owns scheduling, adapters and aggregation. `LineIntelligenceController` handles project discovery, history loading, source identity and asynchronous Git/source projection. Plain projects keep the existing TurboTest execution route.

Normal **Run Java / JUnit** starts the configured Cherry workflow alongside a main-program execution. A test target in a Cherry project takes the Cherry route, avoiding duplicate execution through native JUnit. Explicit native Run Current Test/Class commands retain their original meaning. Fast/Isolated execution internals are unchanged. Cherry runs use saved files; declining Save before Run does not make its disk-based tests validate unsaved buffers.

**Run All Tests** retains native TurboTest execution and also queues the complete Cherry workflow when configuration is present. No package, test or kind selector is passed: Cherry owns the configured unit, integration, functional, UI and default visual phases, their dependencies and adapter support. This does not invent suites missing from configuration or enable Cherry's separately opt-in mutation phase. Native and Cherry results retain their own identities and views; their counts are not added together as if duplicate unit executions were unique tests.

**Changed editor saves trigger Cherry automatically**, independently of the native Auto Test on Save and Auto Run on Save preferences. Scheduling happens after the atomic write succeeds and only when the written fingerprint differs from the previous disk fingerprint. Source, test, build and configuration files use the same hook. A 350 ms quiet period coalesces saves; saving during a run queues one full follow-up run, without interrupting the active suites or starting overlapping Cherry processes. Native save compilation/tests finish before the queued Cherry invocation. The editor stays usable while Cherry runs.

Saving marks the previous in-memory execution evidence stale across the active project, even when another file changed. Results from a run started before that save remain stale; source hashes and compiler/process proofs still govern current coverage. Unsaved edits do not launch Cherry. An unchanged save or a failed write does not queue a run. Project switches and closing discard pending launches; **Stop Tests** and **Cherry Test: Cancel Execution** clear the queue and cancel the owned process. A later changed save can schedule a new run. Unresolved or untrusted Maven projects retain the existing execution gate; automation reports that it is paused rather than opening an unexpected trust dialog. Plain projects retain existing behavior.

Feedback is progressive **per adapter phase**, with genuine individual starts/completions when the opt-in Cherry JUnit lifecycle extension supplies them. Other adapters remain phase-only; capabilities and complete-inventory limitations are explicit. The existing Cherry scheduler remains authoritative; this change does not add speculative duration-based prioritization. Cancellation terminates the CLI and observed descendants, retains partial evidence and invalidates old callbacks. A terminal success event remains provisional until the CLI exits successfully: a later nonzero exit becomes incomplete, retaining actual individual results. A bounded 64 KiB output pump avoids an unbounded EDT queue; CLI output is also retained beside the event file as `.jsonl.log`.

## Configuration and use

1. Open a source within an existing `cherry-test.yaml`, `.yml` or `.toml` project. The nearest configuration is selected. Detection does not create or rewrite configuration.
2. In the Command Palette, use **Cherry Test: Configure CLI…** and supply the modified Java CLI JAR or a compatible executable/wrapper. A blank preference uses `CHERRY_TEST_JAR`, then `cherry-test` on PATH. The executable must support the new protocol; a legacy/Rust CLI without events cannot provide native results and is reported incomplete.
3. Use **Run All Tests**, **Run Java / JUnit**, or **Cherry Test: Run Configured Suites** for an initial run. Subsequent changed saves automatically queue the full Cherry workflow. Configure Maven/tool dependencies in the launch environment as the standalone project requires.
4. Use **Inspect Line Intelligence…**, or click any of the three gutter lanes. Arrow keys choose Git, Tests or Coverage; Enter focuses details; Escape restores editing. Quick Fix explicitly navigates to the inspected source line.
5. **Cherry Test: Native Results…**, **Cancel Execution**, and **Open Full Report** remain available through CommandJ. No default keymap or custom shortcut is changed.

The CLI preference is `cherryTestExecutable` in TurboJ settings. JSONL history is under the TurboJ configuration directory, `cherry-runs/<workspace-path-hash>/`. Completed and incomplete prior schema-v1 sessions can be reloaded. A controller-owned `.jsonl.completion.json` receipt binds the observed process outcome to the event SHA-256 and run identity. Reloading cannot turn a rejected success or cancelled execution into success. Missing, malformed or mismatched receipts leave the historical execution incomplete and retain its actual reported evidence. Arbitrary historical HTML/legacy adapter directories are not automatically imported.

Configuration presence is detected passively; full validity is checked by the CLI on execution. Preflight failures that occur before the first event are explicitly incomplete, with captured output. Projects with a mandatory launcher must configure/use that launcher. In particular, JUG validation used its network-isolating `scripts/local-run.sh`, not an unrestricted direct invocation of its full configuration.

### Finder launch and external tool prerequisites

The installed app does not import an interactive shell's PATH. When the configured Cherry
executable is an absolute `.jar` path, TurboJ starts it with its own `java.home/bin/java`,
so locating the CLI itself does not require `java` or `cherry-test` on PATH. Its adapters
still need their actual external toolchains. TurboJ does not add MavenJ's Maven installation
to the child environment or choose a project JDK for Cherry automatically.

The built-in Maven adapter uses an executable `./mvnw` in the request's working directory
(normally the declared manifest's parent); otherwise it runs `mvn` from the inherited job
PATH. It does not search parent directories for a wrapper. A wrapper removes the need for
Maven on PATH, but still requires compatible Java and access to its pinned distribution and
project dependencies, either cached or downloadable. A missing wrapper/toolchain is a real
runner failure, not a successful empty test run.

For a trusted project opened from Finder, an existing supported setup is a config-root
`.env.test` with explicit local tool paths, for example:

```text
JAVA_HOME=/absolute/path/to/compatible-jdk/Contents/Home
PATH=/absolute/path/to/apache-maven-3.9.11/bin:/usr/bin:/bin:/usr/sbin:/sbin
```

These are literal examples to replace with actual installed paths. Cherry's `.env.test`
parser does not expand `$PATH`, `~`, shell quotes or `export`. Its per-job environment
overlays the parent environment with these values. Alternatively, configure an absolute
executable CLI launcher that sets the required tool environment and forwards all arguments
to the qualified Cherry JAR. A phase `command:` under the built-in `maven` adapter is not a
Maven-executable override; an explicitly registered custom adapter must implement Cherry's
adapter protocol rather than simply invoke an arbitrary Maven binary.

The native Cherry smoke fixture intentionally contains no Maven wrapper; its qualification
launcher supplies Maven on PATH. That evidence proves operation with declared external
prerequisites, not a self-contained Finder installation of every test toolchain. These
boundaries were checked against `CherryTestProcess.command`, Cherry's `Plan.job`,
`Builtins.jvm`, `ExecutableLookup.resolve`, `Scheduler.EnvLayers` and `EnvFile.read`;
no new Finder/minimal-PATH runtime check is claimed by this source review.

## Contract and semantic rules

Cherry's authoritative contract is [`docs/native-ide-events.md`](../../../cherry-test/docs/native-ide-events.md) in the sibling repository. Base event kinds are `run.started`, `phase.started`, `phase.completed`, `run.completed`; negotiated lifecycle support also emits `test.started` and `test.completed`. Each event carries schema version, run ID, sequence and monotonic elapsed time. Phase identity uses the emitted full-manifest key plus adapter/runtime, preserving separate manifests even when display labels match; repeated test names retain distinct occurrence IDs. Legacy controls lacking that key use an unambiguous tuple of their supplied identifiers. Artifact hashes are checked before ingestion.

The immutable Java model separates `TestStatus`, `Freshness`, `CoverageState`, `Availability`, `Precision`, `Attribution`, `SourceIdentity`, `Deletion`, `Snapshot` and `Run`. Unknown counters are `-1`; a measured zero is zero. Disabled, absent, incomplete and excluded data remain distinguishable.

* A failed package or infrastructure failure never creates a failed production-line test. The legacy Java **and Rust** report interpretations were corrected too; aggregate failure remains visible at its real scope.
* Adapter-reported locations remain `REPORTED_TEST_LOCATION` unless their role is explicit. They are not assumed to be production execution edges.
* `TestSourceResolver` resolves exact JUnit class/method identities to direct declarations through JDT, using matching source hashes. Duplicate types, overloads, incomplete/changed inventories, inherited methods, display-name suffixes and unresolved annotations are conservatively left unattributed.
* JaCoCo sidecars preserve `ci`, `mi`, `cb`, `mb`; a positive instruction count alone does not erase missed instructions or branches. Binary LCOV remains binary. Multiple binary collections use union; separate numeric counter sets are not added or treated as identical probes. Ambiguous counter unions become unknown. Every source collection is also retained as an immutable observation and available in **Coverage collections…**, independently from the provider-generated aggregate used by the gutter.
* An unavailable source hash, a changed buffer, or a different document prevents old line associations from being painted at new positions. Undo does not itself certify compiled identity.
* Since 15 November, an opt-in javac/JaCoCo path transports compiler generations, class identities and instrumented process/session evidence (see CH-02 below). Coverage is current only after verified completion and matching source/class identities. Older reports and other providers remain `Freshness.UNKNOWN`. Test declaration results still do not establish per-test production coverage.

This conservative policy follows the need for matching runtime and analyzed class identities described in [JaCoCo class IDs](https://www.jacoco.org/jacoco/trunk/doc/classids.html). Instruction and branch counters are distinct measurements; see [JaCoCo counters](https://www.jacoco.org/jacoco/trunk/doc/counters.html).


## CH-02 — compiler and execution provenance (15 November 2026)

Cherry's new `java/cherry-test-provenance` module contains a Java 21-compatible javac plugin and a small premain agent, with no third-party runtime dependencies. Cherry itself still runs on JDK 25. A project explicitly enables these hooks; TurboJ does not rewrite its build configuration. The native fixture POM demonstrates the opt-in profile, activated by the adapter's per-invocation environment.

1. Cherry's JVM adapter allocates a fresh UUID (`CHERRY_TEST_PROVENANCE_TOKEN`) and capture directory (`CHERRY_TEST_PROVENANCE_DIR`). Existing Maven/Gradle commands and test runners remain authoritative.
2. `CherryProvenance` records UTF-8 compiler-file-object content around parse events and checks it again after generation. It records generated binary names, output SHA-256s, a compilation UUID and the actual javac vendor/version. It refuses pre-existing class output: incremental/reused output is not proof of this compilation. The normal native fixture uses Cherry's isolated `testit.buildDirectory`.
3. After the JaCoCo agent, `ProcessCapture` assigns a unique JaCoCo session and records the real PID, process start instant, runtime and agent version, then writes a shutdown receipt. Verification also requires that the captured process has exited; an interim dump from a still-running server is not a completed collection. Without the Cherry environment the hooks are inert, preserving ordinary project use.
4. Cherry reads the actual `.exec` data with JaCoCo 0.8.15, matches every observed session to a captured process, rejects missing process collections and mixed bytecode generations, verifies class SHA-256s, and checks JaCoCo class IDs. It recomputes instructions/branches from those bytes and probes and compares every executable line with the XML. It does not sum independent numeric reports.
5. Each `jacoco-lines-v1` source gains optional `provenance`: schema version 1, `verified`/`mismatch`/`unknown`, source hash, compiler and compilation IDs, class identity digest and class-file hashes, process/session/collection IDs, execution-file hashes and an explanation. The existing hashed artifact envelope binds the sidecar to its phase. Older consumers may ignore the new object; top-level legacy `compiledIdentity: unknown` is not a certificate for all files.
6. TurboJ parses this into immutable `CoverageProof`. It requires matching source-before/after hashes and completed execution. The controller-owned completion receipt remains necessary for historical acceptance. Pending, cancelled and incomplete runs cannot retain `CURRENT` coverage. A completed failing test run may still have current coverage; it never implies a red production-line test marker.
7. Snapshot preparation rechecks captured class-file hashes off the EDT, once per distinct proof. Edited buffers withhold old positions. Undo restores current coverage only if the original source and retained compiled bytes still match. Missing/replaced class files produce `BYTECODE_MISMATCH`. The existing Coverage inspector exposes identities and counters through keyboard navigation.

This is evidence from cooperating local build tools, not a signed attestation against malicious project code. It certifies the captured Cherry compilation, not a separate Fast Run compilation. The output directory supplied to the plugin must be the build's class output; reused output is deliberately rejected. Snapshot refresh revalidates retained class artifacts; there is no new general-purpose filesystem watcher for arbitrary external artifact edits.

Limitations remain explicit: no ECJ/Kotlin/Rust provenance producer, no per-test dynamic attribution, no automatic insertion of hooks into arbitrary projects, and no certification for classes lacking an execution record. A wholly unloaded class stays unknown because absence from `.exec` does not prove it was within instrumentation scope. An unexecuted method in a matched instrumented class can correctly be uncovered. Different module definitions of the same binary name in one capture are rejected as ambiguous. Generalized incremental compilation and arbitrary compiler output paths need a stronger capture mechanism before being accepted.

Reproduce the supported path:

```sh
# From TurboJ: install the sibling CLI/capture artifact into the repository
# used by the fixture's Maven adapter (normally ~/.m2/repository).
(cd ../cherry-test/java && ./mvnw -pl cherry-test-cli -am install)
./mvnw install
# Maven must be on PATH for the adapter; the native smoke copies its own fixture.
java -cp app/target/test-classes:app/target/turboj-app.jar \
  dev.turboj.app.CherryNativeSmoke \
  ../cherry-test/java/cherry-test-cli/target/cherry-test-java.jar
```

For an isolated cache, pass `-Dmaven.repo.local` to both builds and propagate
the same absolute path through `MAVEN_OPTS` when launching the smoke. TurboJ's
root wrapper configuration otherwise uses `.cache/m2`, whereas the fixture's
independent Maven invocation normally uses `~/.m2/repository`. Installing the
agent into one and launching against the other is not a valid reproduction.

`CoverageEvidenceTest` launches real javac and instrumented JVM processes, checks partial/uncovered counters and nested classes, and exercises changed source/bytecode, old/mixed execution generations, mismatched counters, missing compiler/process/exec evidence and foreign sessions. TurboJ regression tests cover ingestion, source staleness, lifecycle/receipt downgrades, undo and class-file replacement. Validation outcomes for this change are recorded in the master `TODO.md` and [15 November evidence](../evidence/cherry-native/provenance-2026-11-15/validation.json): final TurboJ install 841 tests, selected Cherry reactor 308 tests, including eight real compiler/agent controls, and native pass → fail → undo → pass all passed. The earlier evidence below remains historical.

## Git and revision mapping

`GitBufferDiff` compares the selected baseline blob directly with the captured current buffer. Thus staged, unstaged and unsaved changes are not concatenated or selected in preference order. HEAD is the current UI baseline; the provider API accepts another explicit baseline. Staged renames, new/untracked files, unborn repositories, worktrees and nested repositories are tested. An unstaged move is conservatively a new file unless Git supplies a rename identity.

Deleted text is stored at a **gap**: 0 before the first line, n after line n, including EOF and an empty document. It never receives current test or coverage facts. Ignored, binary, inaccessible and oversized files return a diagnostic, not fabricated additions. CRLF/CR is normalized for physical-line comparison; source fingerprints remain exact, so line-ending normalization cannot certify coverage freshness.

Git runs off the EDT with bounded input/output, interruption and timeout. It does not write the index or working file, and disables repository fsmonitor helpers. Publication checks both document identity and revision; an old project cannot remain launch-eligible during asynchronous detection.

## Rendering and themes

The existing `EditorComponent` has three independent cells split across both edges: Git and Tests on the left before line numbers; Coverage on the right edge of the editor viewport. The coverage cell stays fixed during horizontal scrolling and follows resizing. Source text, selection, coverage tints and AI text are clipped before it; caret visibility reserves its width. All existing glyphs and evidence semantics remain unchanged. Test/coverage space is allocated together for the session. Evidence invalidation and buffer restoration keep geometry stable; explicit context removal collapses it. Only visible real source rows are painted; AI ghost rows do not inherit source facts. Mouse hits, deletion notches and keyboard details are independent of caret placement.

Coverage with state `NOT_APPLICABLE` no longer draws a placeholder dot on the right, including excluded test sources. The cell remains inspectable and retains its raw explanation. Unknown (`?`) coverage is also hidden at an exact verified test declaration when no measurement exists; unknown production coverage and stale (`~`) measurements retain their distinct indicators. This uses resolved declaration associations, not test filename guesses, and does not suppress genuinely measured test-source coverage. Test outcomes on the left are unaffected. Test sources with genuine measured coverage are not blanket-excluded by filename.

When a line has coverage but no associated test results, the Tests cell stays blank rather than printing a placeholder dot. Its width and inspection target remain unchanged, so code does not shift and the inspector can still explain that attribution is unavailable. Actual associated test states, including unknown, pending, skipped, aborted and stale, retain their indicators.

Optional theme roles are `test.success`, `test.failure`, `test.pending`, `test.unknown`, `coverage.covered`, `coverage.uncovered`, `coverage.partial`, `git.added`, `git.deleted`, `status.stale`, `status.unknown` (and compatibility `status.pending`). Legacy themes derive these roles once at load, retaining semantic hue families and at least 4.5:1 contrast against the actual gutter background. Selection/emphasis painting is clipped to the text area and does not replace that background. Shapes convey the meaning independently of color. No 103-theme JSON migration is required.

Since 16 November, current available coverage also shades the source area: a subtle blue/cyan tint for covered lines, yellow for uncovered lines, and a blue tint with the distinct split gutter symbol `◧` for partial coverage. The default editing display has no coverage underline: the earlier dashed accent was removed after visual review to avoid resembling a diagnostic. Coverage alone determines this background; Git additions and associated test failures keep their separate gutter indicators. Unknown, stale and non-applicable coverage leaves the source background neutral. Selection and emphasis paint above the coverage layer. AI preview rows, including rows mixing proposed and original text, remain unshaded; unaffected real source rows retain their coverage at the projected coordinates.

Brace-only Java source lines (ignoring whitespace and comments) suppress coverage glyphs and backgrounds, including covered, uncovered and stale decorations. The compiler may map implicit-return or other bytecode instructions to those delimiters; this is a presentation policy, not a rewrite of instrumentation results or report totals. Raw counters remain in the snapshot and inspection explicitly explains the hidden decoration. Git and verified test indicators are unaffected. Lines with statements or other tokens retain normal coverage, and braces inside string/text-block tokens are not treated as source delimiters. The policy reuses the editor lexer and a bounded visible-line cache, cleared when evidence/source changes.

**View → Show Coverage Backgrounds** is a checked CommandJ action (`view.coverageBackgrounds`), also available in the palette. It hides all coverage row backgrounds while retaining gutter symbols, geometry and inspection. `showCoverageBackgrounds` persists in settings, defaults to true, and survives theme changes. It does not stop tests or invalidate evidence.

`CoverageRowPalette` resolves and caches surfaces and syntax inks on theme changes. Background hue follows the semantic covered/uncovered roles; saturation adapts to the theme, and relative luminance stays close to the editor background. All 103 bundled themes now have distinct cyan/blue covered and yellow/amber uncovered surfaces, including the formerly contrast-constrained light themes. Theme authors can explicitly override `coverage.coveredBackground` and `coverage.uncoveredBackground` with optional hex colours. No bundled JSON rewrite is required.

On shaded rows only, cached syntax inks adjust minimally toward white or black if needed: text already meeting 4.5:1 retains that threshold, while lower-contrast legacy text retains at least its original contrast. Selection foreground/background and gutter roles remain unchanged. Turning highlighting off restores the original syntax inks. Proposed AI text is never treated as covered code. No parsing, report scan or contrast computation occurs during painting. The background colours are no longer omitted as a contrast fallback.

Gutter symbols use the editor font at its existing zoom: `+` for additions, `✓`/`×` for current associated test outcomes, and `■`/`□`/`◧` for covered/uncovered/partial coverage. Partial coverage retains two colours within its glyph; the split denotes partial coverage, not a measured 50%. If a Unicode glyph is unavailable or wider than its cell, the renderer uses ASCII `v`/`x` and `C`/`U`/`P`. Glyphs are clipped to their own cells. Deleted hunks retain a boundary notch because a deletion belongs between source lines. `?` means unknown coverage and `~` stale evidence; stale test outcomes also become neutral while the inspector retains their actual historical status. The Coverage inspector includes a textual legend.

Rendering regressions cover independent failure/coverage colours, selection precedence, neutral unknown/stale rows, absence of coverage underlines, AI row mapping, 1×/2× raster scaling, and all 103 bundled themes at font sizes 10/24/48. `CoverageVisualPreview` generates explicitly synthetic Monokai/light/ASCII review images using the actual editor renderer:

```sh
java -Djava.awt.headless=true -cp app/target/test-classes:app/target/turboj-app.jar \
  dev.turboj.ui.CoverageVisualPreview
```

Images are written to `app/target/coverage-visual-preview/`, including five `themes-N.png` palette-gallery pages and `theme-palettes.json` with exact resolved backgrounds and syntax inks for all themes. Their test associations illustrate rendering and are not execution evidence. This change does not alter Cherry report CSS or infer a passing test from coverage.

## Implementation files

| Path | Responsibility |
|---|---|
| `core/src/main/java/dev/turboj/testing/intelligence/LineIntelligence.java` | Immutable domain model |
| `core/src/main/java/dev/turboj/testing/intelligence/CherryEvents.java` | Versioned events, hash checking, precision-preserving normalization |
| `core/src/main/java/dev/turboj/testing/intelligence/TestSourceResolver.java` | Conservative JDT test declaration mapping |
| `core/src/main/java/dev/turboj/testing/intelligence/GitBufferDiff.java` | Baseline-to-buffer comparison |
| `app/src/main/java/dev/turboj/testing/CherryTestProcess.java` | Isolated CLI, cancellation, bounded output |
| `app/src/main/java/dev/turboj/testing/CherryExecutionReceipt.java` | Atomic process-outcome proof for safe history reload |
| `app/src/main/java/dev/turboj/testing/LineIntelligenceController.java` | Asynchronous discovery, revisions and publication |
| `app/src/main/java/dev/turboj/testing/LineIntelligenceDialogs.java` | Native inspection/results surfaces |
| `app/src/main/java/dev/turboj/testing/TurboTestController.java` | Existing controller owns Cherry process lifecycle |
| `app/src/main/java/dev/turboj/app/TurboFrame.java` | Run, commands, focus and project lifecycle |
| `app/src/main/java/dev/turboj/config/Settings.java` | CLI path and per-project execution preferences |
| `app/src/main/java/dev/turboj/ui/EditorComponent.java` | Renderer extension and interaction |
| `app/src/main/java/dev/turboj/ui/intelligence/LineGutter.java` | Paint/hit/description model |
| `app/src/main/java/dev/turboj/ui/themes/Theme.java` | Semantic colors and compatibility |

Cherry modifications are in `java/cherry-test-cli` (`NativeEvents`, `CherryTest`, `Runner`, `Builtins`, `Formats`, `DiffView`, `AdapterView`), their regression tests and fixture; the equivalent false-failure interpretation is fixed in Rust `test/cherry-test/src/report/{diff_view,adapter_view}.rs`.

Whole-reactor validation also exposed a pre-existing inconsistency: native contextual `CREATE_CLASS` had entered `Operation`, while external typed CLI/MCP catalogs assumed every enum member had a `Requests` record. `Json.typedOperations()` now supplies the shared supported list, and contextual creation is explicitly rejected there. CLI, MCP and LSP regression coverage verifies this boundary; no new fake Create Class protocol handler was added.

## Reproduction and measured validation

The pinned fixture is [`integration-tests/fixtures/cherry-native`](../../integration-tests/fixtures/cherry-native): Java release 21, JUnit 6.1.3, JaCoCo 0.8.15, compiler plugin 3.16.0 and Surefire 3.6.0. `CherryNativeSmoke` copies it into an isolated temporary project, creates its own Git baseline and invokes real commands through Swing key dispatch. Test setup and caret placement are programmatic; this is not a human/physical-keyboard audit.

```sh
# TurboJ and all native-application dependencies
./mvnw -q -pl app -am verify

# In the Cherry Test repository (JDK 25): install the capture hook for the fixture
cd ../cherry-test/java
./mvnw -q -pl cherry-test-cli -am install

# Back in TurboJ; Maven must be on PATH for the fixture adapter
java -cp app/target/test-classes:app/target/turboj-app.jar \
  dev.turboj.app.CherryNativeSmoke \
  ../cherry-test/java/cherry-test-cli/target/cherry-test-java.jar
```

The GUI command requires an available macOS desktop. Outputs are in `app/target/cherry-native-smoke`, including report, screenshots, saved source revisions and the native session cache. The driver reactivates its window if another JVM takes host focus; that action is recorded.

**Save workflow acceptance (15 November 2026) PASS:** Run All Tests starts Cherry (two real JUnit passes); editing and saving alone yields one failed assertion; Undo and saving alone restores two passes and current proven coverage. Saving unchanged content does not launch a fourth run. See [native report](../evidence/cherry-native/save-workflow-2026-11-15/native-report.txt) and [run/XML hashes](../evidence/cherry-native/save-workflow-2026-11-15/validation.json). Seven new `CherrySaveWorkflowTest` controls exercise scheduling, cancellation, lifecycle and unfiltered four-phase protocol transport. Those transport runners are synthetic; the native acceptance fixture executes JUnit, not real browser or service suites.

**Original native walkthrough (historical) PASS:** normal Run → actual main output and two passing JUnit tests → independent coverage inspection → unsaved change withholds old line measurements → Save/Run produces one real failure → native failure details → keyboard Undo/Save → complete-suite rerun returns both tests to passing. All three mouse hit regions preserve caret position; keyboard inspection and Escape restore focus. The completed walkthrough and screenshot hashes are in [`native-walkthrough.json`](../evidence/cherry-native/native-walkthrough.json). Earlier controls exposed a run-level-versus-line-level availability bug, which was fixed with a regression test. Driver controls also needed macOS foreground restoration and exact palette selection/awaiting of deferred command execution; those failures are retained locally, not counted as successful runs.

The derived real-execution summary, individual run IDs, source hashes and original artifact hashes are in [`real-executions.json`](../evidence/cherry-native/real-executions.json). It deliberately records separate executions rather than combining their timings into one session.

Historical standalone observations (before CH-02 capture; not rerun on 15 November). Original temporary artifact directories may no longer exist:

| Execution | Observed result | Coverage interpretation |
|---|---|---|
| Two-test Java fixture | 2 pass; deliberate failure control 1 fail/1 pass | Real JaCoCo partial branches and uncovered code; compiled identity unknown |
| Isolated copy of mini Java unit module | 3/3 pass | Real precise JaCoCo sidecar |
| Isolated copy of shop cart | 12/12 pass | Real LCOV; binary precision |
| Isolated shop Java unit subset | 32/33 pass, existing `reports_a_price_mismatch` failure retained | Missing report in first failing control; JaCoCo available when explicitly allowing reporting after test failure |
| JUG shop-domain | 9/9 pass | Configuration disabled coverage |
| JUG shop-spring | 5/5 pass | Coverage disabled; inherited declaration mapping withheld |
| JUG shop-quarkus | 5/5 pass | Separate runtime/package identity, coverage disabled |

JUG used its mandatory local network boundary, synthetic/local fixtures and offline Maven. No production suite or production service was contacted. That historical session did not execute the mini/shop service/browser/Docker matrix; the current supported matrix and its limitations are recorded in the CH-02/08/09 delivery above.

All 103 themes passed automated role/contrast checks; raster/hit checks cover sizes 10, 24 and 48, HiDPI, CRLF/Unicode, AI projection and deletion boundaries. The targeted tests also exercise obsolete events, document/project switches, missing protocol, cancellation, child cleanup, partial collections, hash mismatches and overlapping coverage artifacts.

Final validation commands and machine-readable report hashes are in [`automated-validation.json`](../evidence/cherry-native/automated-validation.json):

| Command | Result |
|---|---|
| TurboJ `./mvnw -q verify` | PASS, 721 tests across the full reactor before the final receipt/manifest-identity fixes |
| TurboJ `./mvnw -q -pl app -am verify` | PASS on final code, 690 tests, including 71 new integration-specific regressions; no failures/errors/skips |
| Actual Cherry repository, `java/`: `JAVA_HOME=<JDK25> ./mvnw -q verify` | PASS on final code, 341 tests; no failures/errors/skips |
| Cherry isolated clone: `cargo test -p cherry-test -p cherry-test-core` | PASS, 241 tests; exported source files verified byte-identical to actual repository |
| Cherry isolated clone: `cargo build -p cherry-test` and `cargo fmt --all --check` | PASS |
| `CherryNativeSmoke` using the built application and actual Cherry CLI | PASS after the final fixes, including real passing → failing → passing executions and refreshed final gutter snapshot |
| `git diff --check` in both repositories | PASS |

These runs overlap; their totals must not be added as unique tests. Old XML reports and nested demonstration-project reports are excluded. An earlier sandboxed macOS Swing JVM aborted with exit 134; the full reactor and final application verification passed with desktop access. This was an environment failure, not counted as a successful test run.

Persistent native captures: [editor and neutral provenance indicators](../evidence/cherry-native/native-editor.png), [actual failed-test details](../evidence/cherry-native/native-test-failure.png), and [real partial JaCoCo counters](../evidence/cherry-native/native-coverage-inspection.png). The final screenshot waits for the restored source hash and matching run counters; it does not mistake an empty intermediate snapshot for completed rendering.

Measured offscreen painting without and with dense decorations in the **same build** is in [`paint-and-git-benchmark.json`](../evidence/cherry-native/paint-and-git-benchmark.json). It is not a separate checkout/build of the pre-integration revision. For 100,000 lines the median visible-region paint changed from 290.375 µs to 322.541 µs. Dense snapshot preparation was 17.497 ms and EDT installation 0.029 ms. A 10,000-line Git comparison measured 56.985 ms cold, 42.374 ms warm median and 46.072 ms p95. Negative differences in the smaller paint fixture are warmup/noise, not a claimed speedup. These are **not native input-to-paint or scrolling latency measurements**.

## Native feedback extension — 18 November 2026

Schema v1 now has optional repository manifests, typed exact selectors and negotiated test
lifecycle events. TurboJ opts into `test-events-v1,selectors-v1,repositories-v1`; older producers
remain phase-only. Cherry owns the adapter execution, selector validation and actual callbacks.
The Swing model never infers pending tests from a completed report or an estimated inventory.

Each execution retains its independent phase ledger, runtime/module/repository identities and
coverage observations. The native result view shows up to 12 independent runs (a 50,000-test
history budget, retaining the newest run even when it is larger); prior passes are never merged
into a partial rerun. Coverage collections have their own provider/precision, process/session,
compilation, source/class and artifact identities. Enter opens selected collection details;
large detail views explicitly excerpt the first 200 rows.

**Rerun selected** and **Rerun failed** use typed selector records carrying adapter, manifest,
phase and runtime identity. The process receives an owned schema-v1 JSON selection file, and
must acknowledge its exact SHA-256 before results can count as a selective execution. Unknown,
unsupported or ambiguous selectors explain their limitation and offer **Rerun configured
suites**. No affected-test relationship is invented from aggregate coverage. The native transport
bounds selections to 1,000 tests and 1 MiB. Test identities remain distinct across repositories
with identical qualified class names.

A launched trusted CLI may declare explicitly configured repository/module roots. Source and
compiled-class real paths must remain inside those roots; result artifacts remain inside the
workspace. Historical external roots require a matching local process-completion receipt;
unowned imported streams remain confined to their workspace. Receipts detect local mismatches,
not adversarial forgery. Git still compares each source against its own actual worktree HEAD.

Two real external repositories containing the same `demo.PriceTest` qualified name were run
through the production process controller: four passing tests, separate current JaCoCo
collections, an exact one-test rerun, an intentional one-test failure, exact failed-test rerun,
and restored four-test success. Every fixture test resolves to its own hashed declaration;
unsaved edits invalidate current coverage. The controlled large-report benchmark and genuine
Cherry JUnit progress results, including retained failed controls, are recorded in
[feedback qualification](../evidence/cherry-native/feedback-2026-11-18/README.md).

## Remaining acceptance work

| Milestone | Delivery status | Acceptance boundary |
|---|---|---|
| 1 — Normalized data | VERIFIED for the implemented contract | Real events become typed test outcomes and counters; unknown/disabled/missing are distinct. Optional javac provenance is verified per source; unsupported providers remain unknown. |
| 2 — Native lanes | VERIFIED for rendering and inspection | Real data is accepted; synthetic verified-state raster tests exercise all colors. Real unproven freshness stays neutral; proven aggregate coverage can use current colors. |
| 3 — Git to buffer | VERIFIED for listed cases | HEAD-to-buffer, staged/unstaged/unsaved, new files, staged renames, gaps and stale-response guards. Other baselines are provider API only. |
| 4 — Native execution | PARTIAL | Full configured run, cancellation and report work. Typed individual/failed-test rerun is verified for the ordinary Maven JUnit fixture; unsupported adapter selectors retain an explicit broader fallback. Genuine per-test progress is limited to Cherry JUnit lifecycle callbacks; other adapters remain phase-only. |
| 5 — High-fidelity coverage | PARTIAL | Actual instruction/branch counters survive ingestion. Opt-in javac/JaCoCo provenance is implemented. Server provenance and provider probe union are verified on the real matrix; independent collection presentation is implemented. Other compiler producers remain unsupported. |
| 6 — Full developer workflow | INCOMPLETE | Inspection, known declaration navigation and existing Quick Fix entry work; create-test-to-current-coverage acceptance is not complete. |
| 7 — Scale and compatibility | PARTIAL | Themes, dense offscreen painting, Git timing and representative subsets verified; the agreed mini/shop/JUG matrix is now qualified; native latency, memory and wider large-report validation remain. |

1. Extend the opt-in javac/JaCoCo capture contract to other compiler/provider paths beyond the qualified Java server matrix. Missing capture, reused class directories and unobserved instrumentation remain unknown; they are not inferred from timestamps.
2. Broaden exact selection only where an adapter can prove the requested identity. Parameterized/dynamic/custom/ordered execution may require the explicit complete-suite fallback. No affected-test selection is inferred from aggregate coverage.
3. Extend genuine per-test starts beyond the Cherry JUnit extension and supply a verified complete test inventory where adapters can expose it. Execution history now keeps runs separate. Safe inherited-contract navigation still requires classpath/module resolution.
4. Independent collection inspection is delivered; extend provider compatibility without summing counters. Provider-level probe merging handles overlapping compatible JaCoCo processes/phases.
5. Explicit configured external repositories are now verified with duplicate qualified names; broader inherited-source navigation and additional provider matrices remain. The agreed mini Java/Kotlin, shop Rust/Java and JUG qualification is recorded in the completed delivery.
6. A full create-test → run → refreshed-current-coverage workflow and a human keyboard walkthrough, plus native input-to-paint/scroll latency and large-report/memory measurements.

The implemented slice is useful for real execution, accurate historical counters, Git and native inspection. It must not be described as the completed seven-milestone testing-intelligence product.
