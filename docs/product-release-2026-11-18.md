# TurboJ product delivery — 18 November 2026

The agreed nine-group implementation is delivered in TurboJ source commit
[`c7d08485a30a02ddc321c360ff02a3ab38872ccb`](https://github.com/turboj-dev/turboj/commit/c7d08485a30a02ddc321c360ff02a3ab38872ccb),
pushed to `main`, with the final browser visibility correction in
[`ee98ac8`](https://github.com/turboj-dev/turboj/commit/ee98ac8381f52d466dd917bb8028e1fac94734ae).
The local macOS application was built, copied from the verified DMG and
exercised with its bundled runtime. The website was built and checked locally; production
was not deployed. This record identifies what was actually qualified and the remaining
external or unsupported cases; it does not declare every broader project TODO complete.
Of the 28 registered items in the requested sequence, **27 are verified within their
declared scope**. REF-01 remains partial for network filesystems and cross-user ownership;
destructive metadata recovery beyond the current journal is safely refused.

After that production-source commit, `ede0821ed2613147250aa0d3d8b91784af36172b`
isolated CI Maven publishing settings and a MavenJ CLI test fixture from host settings.
`98002efc291df11ec1a2ccfe2eb843bd7976536c` bounded CI report collection to reactor
directories, avoiding traversal through the packaged DMG staging `Applications` symlink.
These are workflow/test-fixture repairs; neither changed production Java or the qualified
application package. The final hosted run of the repaired workflow passed on Linux,
Intel macOS and the website job.
The Intel runner then exposed a platform-specific test expectation: an existing runtime
qualification warning was missing from the expected text. Commit
`48815c83e6f5b5c81c7e7aa99e8c413b3cfbe3cc` corrects only that assertion, preserving the
warning and application behavior. All ten focused LocalModelManager tests pass locally
and in the final hosted Intel reactor; a separate simulated architecture-label control
is retained without being presented as Intel execution.

The machine-readable [source/artifact matrix](evidence/product-sequence-2026-11-18/source-artifact-matrix.json)
connects the commit, captured inputs, JAR, installed application, companion patch and
generated references. [Detailed execution evidence](evidence/product-sequence-2026-11-18/README.md)
retains the earlier unsuccessful controls alongside the passing final runs.

## Source and artifact identity

| Deliverable | Identity / verification |
| --- | --- |
| Native production source | `c7d08485a30a02ddc321c360ff02a3ab38872ccb`; all 743 inputs in the final build receipt match committed Git blob bytes |
| Qualified application JAR | SHA-256 `36ae34e651d3a60a47ba988a85ab91dae61d6b0445fd556137293f0504ea33a9` |
| Local arm64 DMG | `target/product-sequence-final-2026-11-18/TurboJ-1.0.10.dmg`; SHA-256 `00e8b0843e8373c0b59da61a1de952ada002023952fb23eacfd46c22d371c561` |
| Installed package | 123 regular files, 99 symlinks and regular-file modes match the schema-2 external receipt; strict ad-hoc signature verification passes |
| Bundled runtime | Corretto `25.0.4.1+10-LTS`, macOS arm64 |
| Cherry companion | Local commit `9cbd9e1fe808c0a5b56050c42b823355d036cdf5` over `39af874d3d7c3d2589866aed9ee5d7664d47d0df`; qualified CLI SHA-256 `01004385b5163b3ef9aad1b55f01b482956640aeee79e8fe7f29e6c72a937f25` |
| Published companion source patch | [15-file patch](evidence/cherry-producer-feedback-2026-11-18/companion.patch), SHA-256 `0484aafc97876881ba725ff05342172ad152d616f8e6da653bdcbf358708ecda`; applied to isolated base snapshots and all resulting hashes matched |

The build receipt honestly records the then-current `dd75866` commit plus a dirty working
tree: it was captured before the source commit. Its 743 recorded input hashes were subsequently
checked against `c7d08485` with `git cat-file --batch`; there were **zero mismatches**. The
receipt was not rewritten to pretend a different build history. The final clean reactor and
final package receipts differ only in two packaging/receipt scripts corrected afterward;
the Java reassembly retained identical JAR bytes. Those scripts have separate regression
evidence. This is source-to-artifact correspondence, not a claim that unsigned receipts are
a trusted third-party signature or that all future rebuilds are byte-identical.

The `1.0.10` bundle label is the existing development label; use the commit and hashes to
identify this delivery. This package is locally **ad-hoc signed**, without Developer ID
signing or notarization. No user-installed application was replaced.

## Delivered behavior

| Group | What is working | Verification and practical boundary |
| --- | --- | --- |
| AI-13: less intrusive suggestions | Relevant insertion triggers, cadence limits, persistent dismissal and validation of automatic streams; templates/JDT retain precedence | [Controller/native typing evidence](evidence/product-sequence-2026-11-18/ai13/README.md). Native deterministic provider control passes; live paid-cloud inference remains separate. |
| QFX-02/06: predictable Cmd+1 and semantic fixes | Diagnostic/caret priority, repeated invocation while inspection is pending, checked-exception fixes, constructors and applicable Java transformations | [81 focused semantic/protocol tests](evidence/quick-fix-precision-2026-11-18/README.md), then full reactor and final native popup checks. Unsafe caller/override propagation is refused. Catch wrapping is an explicit user-selected semantic change. |
| QFX-10: advanced templates | Bound variable/type inference, generic collection/array contexts, project API and preview checks, nested macros, safe imports/name shortening and formatting | [Template evidence](evidence/template-semantics-2026-11-18/README.md). Save/Format wait for finishing edits; stale work is discarded; expansion and finishing retain one Undo. Ambiguous/unsupported macros remain editable or unavailable; arbitrary scripts are not executed. |
| UI-07/08 and REF-01: unsaved work | Owned all-buffer recovery checkpoints, bounded atomic manifests, continuous-typing checkpoint scheduling, concurrent source/metadata conflict checks and native ACL/xattr preservation | [Recovery](evidence/recovery-2026-11-18/README.md), [metadata](evidence/product-sequence-2026-11-18/native-metadata/README.md), final RecoverySmoke. Destructive refactoring is refused when the text journal cannot restore ACLs/user attributes. |
| CH-03/04/05/07: richer Cherry feedback | Independent coverage collections, typed exact selectors with producer acknowledgement, genuine Cherry JUnit pending events, execution history and repository/runtime identities | [Real two-repository execution](evidence/cherry-native/feedback-2026-11-18/README.md), final native collection inspection and exact reruns. Other adapters remain phase-only unless they provide real callbacks. Unsupported selectors expose an explicit broader rerun. |
| REL/GOV: delivery traceability | Source/JAR receipt, sealed application inventory, DMG copy verification and pinned CI workflow | [Package installation](evidence/product-sequence-2026-11-18/package/installation.json), [receipt correction](evidence/product-sequence-2026-11-18/package-receipt-correction/README.md), [.github/workflows/verify.yml](../.github/workflows/verify.yml). Hosted result is tracked separately below. |
| MVN/FMT: real project compatibility | Actual Java 17 execution/test toolchain with editor JDK 25, parent/three-module dependency behavior, unsaved dependent compilation, explicit lifecycle/code generation and packaged formatter | [Toolchain qualification](evidence/maven-toolchain-2026-11-18/README.md), final FormatterSmoke. Import/native compile does not silently execute Maven plugins; plugin-generated sources/resources require the explicit lifecycle where applicable. |
| PERF/CH-16: measured responsiveness | Paired native input/paint, distant navigation, dense rendering, Git, semantic completion and separate large-report import data | [Measurements](evidence/product-sequence-2026-11-18/performance/README.md). All 18 paired JVMs pass; outliers and report-import overhead are retained, without a causal speedup claim. |
| WEB: native/site alignment | Single pane outlines and font scale, recorded-pane resizing with keyboard access, preserved minimal controls, corrected theme-dialog race, fresh native references and four-page PDFs | [Final website validation](evidence/website-visibility-2026-11-18/README.md). Original source/output/timings remain unchanged; this is an HTML reconstruction, not a new native recording. No deployment. |

Cherry remains an external, standalone orchestrator. TurboJ receives structured schema-v1
results with negotiated optional extensions and owns native rendering. Coverage collection
identity and source/class/process proof remain independent of test status. Missing data or
instrumentation never becomes “uncovered”; aggregate coverage does not invent a passing or
failing test-to-production-line association. A source edit invalidates current evidence.

The companion repository had **16 already-unpublished ancestor commits** at task entry.
They were preserved, and no Cherry history was pushed. The owned companion commit is local;
its patch is available in the pushed TurboJ repository. That patch requires the identified
base, including earlier native/provenance infrastructure; applicability to the older public
Cherry upstream is not asserted. A future companion publication must handle that dependency
history explicitly.

## Verification results

The final Java command was:

```sh
./mvnw --batch-mode --no-transfer-progress \
  -Dturboj.test.java17=/private/tmp/turboj-jdk17/amazon-corretto-17.jdk/Contents/Home \
  clean install
```

Result: **976 test entries across 141 suites; zero failures, errors or skips**.
[Raw output](evidence/product-sequence-2026-11-18/final-reactor/clean-install.txt) and
[reactor-only counts/hashes](evidence/product-sequence-2026-11-18/final-reactor/reactor-results.json)
are retained. The original broad XML inventory included an unrelated two-test example
suite under `examples/maven/target`, outside the root reactor. Its archived 978/142
count is preserved for audit, but the current qualified count excludes that preexisting
report: **976/141**. Clean hosted CI independently confirms the tracked-suite inventory.
The modified Cherry reactor reported **425 entries: 423 executed passes,
two opt-in Docker skips, zero failures/errors**. Docker controls were not silently counted
as verified executions.

Thirteen distinct real-window checks have passing final results against the DMG-installed
JAR and bundled Java: AIInteractionSmoke, QuickFixPopupSmoke, LiveTemplateWorkflowSmoke,
LiveTemplatePrecedenceSmoke, LiveTemplateDialogSmoke, InlineSemanticTemplateSmoke,
RecoverySmoke, FormatterSmoke, CherryNativeSmoke, KeyboardProfileSmoke,
SaveAutomationSmoke, RunTargetIsolationSmoke and TestOutputSmoke. See the
[initial final batch](evidence/product-sequence-2026-11-18/native-final/results.tsv),
[bounded follow-up](evidence/product-sequence-2026-11-18/native-final-followup/results.tsv)
and [actual test-output control](evidence/product-sequence-2026-11-18/native-final-followup/TestOutputSmoke.txt).

The first final Cherry driver lost host foreground; its follow-up waits for genuine owned
window focus. The initial SaveAutomation driver omitted its frozen resource directory;
the corrected invocation includes and hashes it. Earlier cancellation and harness failures
remain in the evidence. These follow-ups did not replace the application JAR or hide a
production behavior change.

Website verification includes **53 unit/content/evidence checks**, **375 browser checks**
including six visibility-batch regressions, plus the earlier deterministic theme-dialog lifecycle control, all 103 themes, and layouts at
320/390/768/1440/1920 px. Three local mobile Lighthouse runs scored 100 in all four categories.
Final guarded build/reference checks pass without bypassing the source-manifest guard. The final
[visibility follow-up](evidence/website-visibility-2026-11-18/README.md) preserves the hosted
320 px failure and its natural reproduction: the browser delivered two visibility entries,
and the renderer had used the oldest. All six new controls failed before the fix and pass
after selecting the latest observation. Fresh build hashes, browser results and three
Lighthouse runs supersede the earlier renderer measurements; recordings and native references
remain byte-identical.

The final [hosted workflow](https://github.com/turboj-dev/turboj/actions/runs/44378326946)
is **SUCCESS** at `48815c83e6f5b5c81c7e7aa99e8c413b3cfbe3cc`:

| Job | Reactor results | Additional verified checks |
| --- | --- | --- |
| Ubuntu 24.04 | 976 entries / 141 suites: 966 passes, 10 skips, zero failures/errors | Actual Corretto 17 toolchain, standalone module builds, real Swing Quick Fix and JUnit output under Xvfb, receipts and upload |
| macOS 15 Intel | 976 entries / 141 suites: 975 passes, one skip, zero failures/errors | Actual Corretto 17 toolchain, native metadata controls, standalone module builds, ad-hoc application/DMG, sealed receipt and bounded upload |
| Website | 53 unit checks and 375 browser checks pass | Guarded production build, keyboard/accessibility checks, 6,713-byte hero and 14,872-byte total JavaScript gzip budgets |

Both Java jobs skip the older test requiring a particular locally installed Corretto 21;
Linux additionally skips nine macOS metadata controls. The two new actual Java 17
qualifications execute on both platforms. CI's independently built JARs and runtime
versions are recorded in [the final summary](evidence/product-sequence-2026-11-18/ci/final-summary.json).
All 743 production input hashes match the locally qualified artifact, but the hosted
JAR bytes and platform package identities remain distinct. The local website budget
measurements below also remain separate from Node 22 hosted measurements. Historical
failures, runner-capacity cancellation and corrections are retained in the
[CI record](evidence/product-sequence-2026-11-18/ci/README.md).

The exported CommandJ registry has **312 commands and 29 aliases**. All four PDF profiles
contain all 312 commands exactly once in the established 16-column layout, four landscape
A4 pages each, with no clipping. PDF and generated Markdown hashes match both the final
validation record and committed `c7d08485` files. PDF download links are in the
[cheat-sheet index](cheatsheets/README.md); exact outputs are recorded in
[final-validation.json](evidence/website-alignment-2026-11-18/final-validation.json).

## Performance evidence

The paired baseline is `dd75866`, which already contains Cherry. It is a pre-batch baseline,
not a pre-integration editor. Native measurements exclude physical input and compositor
delivery; offscreen painting, Git and JDT have their own boundaries. Candidate dense
100,000-line paint median was 467.958 µs versus 466.042 µs baseline; Git median was
40.990 versus 40.075 ms. Candidate outliers include 34.873 ms input-to-paint and 44.995 ms
changed-source semantic completion. They are retained, not averaged away into guarantees.

Separately, 100,000 test results plus 100,000 coverage rows took a median 435.694 ms versus
411.214 ms baseline and approximately 66.77 versus 55.25 MiB retained heap. Independent
collection/context preservation has an observed cost; this delivery does not claim a
blanket performance improvement. Website emitted/inline JavaScript is 14,874 gzip bytes,
with a 6,727-byte hero controller/model and 21,080 bytes of lazy chapter payloads, within
the stated budgets.

CommandJ JMH also completed: six artifact/round invocations, with three forks per operation
and registry size, **36 benchmark JVM forks total**. Dispatch/shortcut p50 was 0.083 µs;
1,000-command palette p50 was 117.632 µs baseline and 116.608 µs candidate. Both benchmark
JARs are byte-identical and CommandJ production code is unchanged, so those differences
are variation, not an optimization claim. All p99 values, histograms and outliers are
preserved, including candidate dispatch at 5.726 ms and palette search at 4.399 ms.
See [the CommandJ measurement boundaries](evidence/product-sequence-2026-11-18/performance/README.md#commandj-jmh).

Six corrected isolated LaunchServices starts also completed: baseline median **771.118 ms**,
candidate **846.696 ms**, measured to window-server observation with 20 ms polling and warm
OS caches. Three samples do not establish a causal regression or improvement. Separate
visible-idle controls each recorded twenty periods: **600.989 s baseline / 600.841 s candidate**,
with weighted CPU **0.178040% / 0.121496% of one core**, not zero. Summed parent/worker RSS
medians were **46.75 / 47.66 MiB**; these are not private-memory measurements. Independent
main-JVM heap and GC captures do not share those ten-minute boundaries: the candidate's
first captures were late. All three G1 counters remained unchanged only between their
observed capture endpoints; this does not establish zero GC for the full CPU/RSS interval
or matched-window heap growth. Original file-write UTC metadata records capture order,
not monotonic timing. The initial six starts and a prior baseline control
with missing end metadata are preserved separately and excluded from final aggregation.
See [installed startup and idle boundaries](evidence/product-sequence-2026-11-18/performance/README.md#installed-application-startup-idle-cpu-heap-and-rss).

## Run the qualified local application

From the repository root:

```sh
open target/product-sequence-final-2026-11-18/TurboJ.app
```

The adjacent `TurboJ-1.0.10.dmg` contains the same sealed application. Keep
`TurboJ.package-receipt.json` beside the app when copying it if you want to rerun
`python3 scripts/build-receipt.py verify-package --package target/product-sequence-final-2026-11-18/TurboJ.app`.
The isolated DMG installation used for native tests is
`/private/tmp/turboj-product-final-installed/TurboJ.app`; temporary paths may be
removed by the operating system. The durable local build remains under `target/`.

## Outstanding gates and unsupported cases

| Status | Exact boundary |
| --- | --- |
| PARTIAL — REF-01 | Network-filesystem and cross-user ownership qualification require a suitable environment. Local crash/restart and metadata controls pass; destructive operations unsupported by the metadata journal are refused before mutation. |
| POSTPONED | User physical keyboard layouts, Fn/Globe, IME and VoiceOver walkthrough. Injected Swing events do not certify these. |
| UNVERIFIED | Live paid-cloud inference; deterministic providers and protocol fixtures were used. Other hardware, network filesystem behavior and cross-user ownership remain outside the local qualification. |
| UNSUPPORTED / safely refused | Destructive rename/delete with ACL/user xattrs the journal cannot restore; case-only disk rename on an aliasing filesystem; unsafe exception propagation; arbitrary template scripting; exact reruns unsupported by the underlying adapter. |
| NOT CLAIMED | Per-test dynamic production coverage from aggregate probes; portable atomic compare-and-swap against noncooperating external writers; arbitrary Maven plugin emulation; Developer ID/notarized distribution; production website publication. |

The installed app still requires a compatible configured Cherry CLI and appropriate project
build tools. Finder's minimal PATH does not acquire Maven automatically. The supported
wrapper/toolchain and explicit environment routes, including their limitations, are in
[the integration architecture](architecture/cherry-test-turboj-integration.md).

All 13 preexisting user files were preserved byte-for-byte and excluded from the owned
changeset. The [delivery audit](evidence/product-sequence-2026-11-18/delivery-audit.json)
records the scope. The [independent evidence audit](evidence/product-sequence-2026-11-18/final-independent-audit.json)
checks the qualified suite inventory, source/artifact hashes and recorded performance boundaries.
The [prepublication recheck](evidence/product-sequence-2026-11-18/prepublication-integrity.json)
also verifies all 98 performance files, 53 website payload hashes, startup records and local links.
Source publication is separate from website deployment and from publishing the companion
repository's older history.
