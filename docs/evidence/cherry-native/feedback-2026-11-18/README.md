# Cherry feedback integration — 18 November 2026

The consumer/producer process qualification below is complete for the stated capabilities.
It does not claim that an unsupported adapter supplies per-test starts or exact selectors. Native
UI acceptance against the final application artifact is recorded by the parent release run.

## Consumer implementation

- Every LCOV/precise provider collection remains an immutable independent observation,
  retaining phase, module, runtime, invocation and artifact identity. The gutter continues
  to use the provider-generated union, never sums of instruction counters.
- Line inspection and the native test-results dialog offer **Coverage collections…**.
  Arrow keys select an observation; Enter focuses readable counts, process/session,
  source/class and compilation identity. Unknown counters are written as `unknown`.
  Large detail views show at most 200 rows and explicitly identify the excerpt.
- The results dialog retains up to 12 independent executions, with a bounded test count,
  in its keyboard-accessible execution selector. Reruns never silently merge old passes
  into current results. Individual collection observations survive cancellation, history
  reload and save invalidation.
- `test.started` and `test.completed` consume actual stable producer identities. An
  unfinished pending test prevents a terminal success claim. Final phase results replace
  matching progressive identities. Existing phase-only producers remain compatible.
- Exact test selection is typed and carries manifest/phase/runtime/adapter identity.
  The selection is passed in a schema-v1 JSON file, not interpolated into a shell command.
  The producer must acknowledge the exact file SHA-256; an older CLI ignoring the selector
  cannot be reported as an exact rerun. Unsupported selections explain why and offer the
  existing, explicitly broader **Rerun configured suites** action.
- Source roots outside the workspace are accepted only from a launched CLI or a historical
  stream matching its locally retained completion receipt. Ordinary imported streams stay
  root-confined. Source/class real paths are checked against those roots; report artifacts
  remain workspace-confined. A receipt is an integrity check, not a cryptographic signature.
- Test declaration resolution scopes duplicate qualified names by the reported module root.
  Git remains a direct comparison to each source file's actual worktree HEAD.

## Wire extension

Schema version remains 1. New optional fields preserve historical producers/readers;
new event kinds require the opt-in capability environment variable:

```
CHERRY_TEST_IDE_CAPABILITIES=test-events-v1,selectors-v1,repositories-v1
CHERRY_TEST_IDE_SELECTION=/owned/cache/run.jsonl.selection.json
```

`run.started` may contain `repositories: [{id, root, worktree}]`, a `capabilities` map
and `selectionSHA256`. Phase/test events may include `moduleRoot` and `repositoryId`.
`test.started`/`test.completed` carry `test`, with the same stable local ID used by
the final phase. An emitted selector contains `kind`, `value`, `phaseKey`, `runtimeId`,
`adapterId`, `manifest`, `exact` and an optional unsupported `reason`.

## Automated checks

- `./mvnw -q -pl core -Dtest=CherryEventsTest,CherryRunHistoryTest,TestSourceResolverTest install`:
  Initial checkpoint: 40 tests passed; final clean-reactor result: 44 passed (Events32, History2, Resolver10), recorded in `reactor-checks.json`.
- `./mvnw -q -pl app -Djava.awt.headless=true -Dtest=CoverageCollectionDetailsTest,CherryExecutionReceiptTest,CherryTestProcessLifecycleTest,CherrySaveWorkflowTest,LineIntelligenceControllerTest,LiveTemplateFinishTest test`:
  34 Cherry tests and 3 template tests passed at that checkpoint.
- The first sandboxed app test JVM exited 134 during macOS AWT initialization;
  its log is retained. The authorized headless retry passed.

The later focused suite passed 38 Cherry app tests (collection details 2, receipts 5, process lifecycle 7, save workflow 8, controller 12, exact selection 4); the complete reactor independently repeated these with zero failures. Recovery/files 15 passed alongside them. Parent release evidence identifies the final application artifact; the raw app/core focused logs and final reactor count extract are retained here.

## Real process qualification

The fixtures are copied from the existing `integration-tests/fixtures/cherry-native` source,
with Java release 21, JUnit 6.1.3, JaCoCo 0.8.15 and Cherry provenance 0.3.16 unchanged.
`fixtures/repo-one` and `fixtures/repo-two` are independent worktrees with the same initial
HEAD and the same `demo.PriceTest` qualified name. The workspace config explicitly declares
both module roots. Maven manifest paths in Cherry are workspace-relative, including external
paths; they are not relative to each project's `path`.

`CherryProcessAcceptance` launches the real CLI through `CherryTestProcess`, receives actual
EDT callbacks, validates the process-completion receipt, then resolves test declarations and
checks that unsaved source changes withhold current coverage. No production tests are mocked.
The observed successful results are:

| Control | Actual result |
|---|---|
| `workspace/full-3.jsonl` | Four passing JUnit tests across two repositories; two independent precise collections, each 1 covered / 1 partial / 4 uncovered executable lines; all current with compiler/class/process proof. |
| `workspace/exact-1.jsonl` | Exactly one selected method in repo-one; no execution or coverage collection from repo-two. Selection SHA acknowledged. |
| `workspace/failure-1.jsonl` | Real source denominator changed from 100 to 50: three pass, one fails. Both repositories remain distinct. |
| `workspace/failed-exact-1.jsonl` | Exactly the single failed method reruns and fails; unrelated tests do not run. |
| `workspace/restored-1.jsonl` | Original source restored: all four tests pass and coverage is current again. |

The archive preserves initial controls rather than replacing their outcomes:

- `full.log`: our initial fixture used the wrong manifest path. It failed before execution;
  `invalid-relative-manifest.yaml` preserves that configuration error.
- `full-2.jsonl`: four JUnit tests actually passed, but the legacy coverage importer rejected
  explicitly configured external source roots. The overall run correctly failed. The producer
  was corrected to allow only workspace plus declared manifest roots, including realpath checks.
- `progress.jsonl`: real Cherry JUnit callbacks exposed missing configured package environment;
  both tests actually failed. The producer now injects the configured package into each job.
- `progress-2.jsonl`: two real tests pass and two genuine pending callbacks arrive. An additional
  declaration-navigation assertion exposed the final Surefire `(TestEnv)` display name, which
  cannot be treated as a canonical method identity by guessing. The producer was corrected
  using only reflection-proven lifecycle aliases. The final controls below passed; original XML
  display names remain preserved separately.

Raw event paths retain their original `/private/tmp` locations. `artifacts/<project>/<run>/`
archives the corresponding LCOV, precise sidecars, JaCoCo probes, process/compiler receipts and
class bytes. These are archival evidence, not an in-place replay after moving directories.
Reproduce a new execution to obtain new path/process identities. `fixtures/` holds source,
configuration and the deliberate-failure revision; no personal project is modified.

## Reproduction

Build TurboJ's test classes with `./mvnw install`, and build/install the companion Cherry
CLI, JUnit extension and provenance modules. Copy `fixtures/workspace`, `fixtures/repo-one`,
`fixtures/repo-two` and `fixtures/progress-project` to a fresh temporary parent directory.
Initialize repo-one and repo-two as independent Git repositories and commit their baseline
sources. Put a working Maven installation on PATH. The production process harness requires:

```
java -Djava.awt.headless=true \
  -cp app/target/test-classes:app/target/turboj-app.jar \
  dev.turboj.testing.CherryProcessAcceptance \
  /absolute/path/cherry-test-java.jar \
  /absolute/temp/workspace /absolute/temp/workspace/full.jsonl \
  4 COMPLETED false
```

A successful run writes `full.jsonl.selected.json`. Pass that filename as the optional final
argument with expected count `1` to verify exact selection. Change repo-one's denominator to
50 and run the whole workspace with `4 FAILED false`; its `.failed.json` selects the one
actual failed method. Restore the source before leaving the fixture. For the progress project,
use expected `2 COMPLETED true`. The fixture intentionally sleeps 250 ms in each test solely
to observe pending lifecycle callbacks; these are not benchmark timings.

## Large-report measurements

`CherryReportBenchmark` generates a controlled synthetic schema-v1 protocol workload before
timing. Each size includes the same number of test results and LCOV source rows, with immutable
snapshot publication after every phase. It checks actual result count/state, bounded artifact
hash validation and line count. It does not execute tests, prove coverage, parse source, paint
Swing or measure input-to-display latency.

Three fresh JVMs per build ran sequentially in alternating baseline/current order, using
`-Xms512m -Xmx2g`, Java 25 on this macOS arm64 host. Baseline `dd75866` already contains Cherry;
it is the pre-batch baseline, not a pre-integration release. Retained heap is measured after
requested GC and is approximate, not an allocation profile.

| Workload | Baseline median import + snapshots | Current median | Baseline retained heap | Current retained heap |
|---|---:|---:|---:|---:|
| 10,000 tests + 10,000 rows | 37.275 ms | 59.827 ms | 5.33 MiB | 6.49 MiB |
| 100,000 tests + 100,000 rows | 411.214 ms | 435.694 ms | 55.25 MiB | 66.77 MiB |

The larger workload is approximately 6% slower and retains approximately 21% more heap while
preserving independent observations and context. This is an observed cost, not a speedup claim.
The current 10,000-case samples were 55.11, 117.85 and 59.83 ms; the outlier is retained.
The six raw final JSON/text records, metadata and aggregate are under `report-benchmark/`.
Earlier pilot measurements under concurrent builds are also retained and excluded from the
final comparison. A no-op freshness update now reuses immutable values instead of copying
unchanged records, reducing measured pilot retained heap without changing freshness semantics.


## Final producer controls

Final CLI SHA-256: `01004385b5163b3ef9aad1b55f01b482956640aeee79e8fe7f29e6c72a937f25`.
The companion modified reactor passed 425 tests, with zero failures/errors and two intentionally
opt-in Docker controls skipped. Producer details are in
[companion qualification](../../cherry-producer-feedback-2026-11-18/README.md).

- `workspace/final-full.jsonl`: final companion revalidation passes four tests across two external
  repositories, including the provider's external-source probe union. Independent declarations,
  exact selector capabilities, source hashes and two precise collections remain valid.
- `progress-project/progress-3.jsonl`: two real Cherry JUnit tests pass, two genuine pending
  callbacks reach TurboJ, all tests resolve to their exact declaration, and no duplicate final
  identities remain. The final names are normalized only from reflection-proven lifecycle
  aliases; original Surefire display names remain available in the wire evidence.
- `progress-project/progress-exact.jsonl`: typed exact selection runs one Cherry JUnit method,
  emits one genuine pending callback and one successful final result, resolves its declaration,
  and validates the exact selection SHA and completed process receipt.

Progress is supported by the Cherry JUnit lifecycle extension. Plain JUnit and other adapters
continue to report real phase progress unless they expose their own callbacks. No known total
or per-test start is invented. Custom, parameterized, dynamic or ordered cases that cannot be
selected exactly retain their explicit unsupported reason and broader complete-suite route.
Coverage remains aggregate instrumentation, not per-test production-line attribution.

The native harness `CherryNativeSmoke` now exercises collection inspection and the actual
**Rerun selected** / **Rerun failed** buttons through Tab/Space, checking one-test effects and
then Save-triggered restoration of both original tests. Its execution belongs to the final
artifact's GUI evidence; headless process success above does not substitute for that check.
