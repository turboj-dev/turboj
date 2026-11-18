# Product performance qualification — 18 November 2026

The paired editor, rendering, Git and semantic probes completed **18/18 JVM runs** with
exit status zero. The results below describe this host and workload. They do not establish
a causal speedup, a universal latency guarantee or physical input-to-display latency.

## Artifacts and method

| Role | Identity |
| --- | --- |
| Baseline | Commit `dd75866`, built in an isolated checkout; JAR SHA-256 `56165b7a1979281adc2d38b2d034c2020c2e919c03fe7d988b47a5b37eeb9dfb` |
| Candidate | Final copied macOS application JAR, SHA-256 `36ae34e651d3a60a47ba988a85ab91dae61d6b0445fd556137293f0504ea33a9` |
| Runtime | Candidate application's bundled Corretto `25.0.4.1+10-LTS`, for both artifacts |
| Host | macOS 26.5.1, arm64; 10 processors visible to the JVM |
| Execution | Three fresh JVMs per artifact per probe; serial runs, alternating artifact order by round; warm OS/dependency caches; `-Xmx768m` |

The baseline already contains Cherry integration. This is a **pre-batch comparison**, not
measurement of adding Cherry to an editor that had none. The same three harness sources
were compiled once against the candidate and used with each immutable JAR. Both artifacts
passed the harness's functional assertions. Source hashes, exact JVM commands, order and
artifact hashes are retained in [paired/manifest.json](paired/manifest.json).

[summary.json](summary.json) contains the aggregation. For sampled operations, **p50 and
p95 in the tables are the medians of the three per-JVM statistics**, not a pooled percentile
or a confidence interval. “Maximum” is the largest recorded sample across all three JVMs.
Single-shot operations use the median of their three fresh-JVM measurements. No sample was
discarded. The native and semantic JSON preserve individual samples; the existing offscreen
paint/Git harness retains per-JVM summaries, not its individual timings. Raw JSON and text
are under `paired/`; [sha256.json](sha256.json) covers those files and the generated summary.
Compiled harness classes are deliberately excluded.

## Native application editing and distant-line navigation

`ProductPerformanceProbe` opens the real `TurboFrame` with an isolated settings directory,
loads and compiles generated Java, then sends 120 synthetic typed events to the actual
editor, 15 ms apart. Every case recorded **120/120 painted input samples**. Measurement
starts in the editor's input handling and ends when Java2D painting completes. It excludes
physical keyboard transport, the wait before event handling and compositor/screen delivery.

The nominal 1,000/10,000-line workloads include a small class/comment wrapper. Each receives
100 alternating jumps between nearby and distant lines followed by synchronous painting.
This measures navigation plus paint, not a mouse-wheel or trackpad scroll gesture.

Dense snapshots contain synthetic Git/test/coverage records solely for rendering. They are
invalidated by the first source edit, as correctness requires, then reinstalled before the
dense navigation measurements. Therefore “dense setup” typing is **not** continuous typing
with supposedly current coverage retained after every edit. The fixed within-JVM order
(plain then dense; smaller then larger file) also includes JIT/cache warming effects.

All values below are milliseconds, formatted as **p50 / p95 / maximum**.

| Nominal lines and typing setup | Baseline handler → paint | Candidate handler → paint |
| --- | --- | --- |
| 1,000, plain | 1.312 / 3.751 / 6.206 | 0.695 / 1.216 / 5.191 |
| 1,000, dense setup then invalidation | 1.348 / 5.021 / 15.657 | 0.364 / 0.687 / 3.035 |
| 10,000, plain | 1.865 / 5.893 / 29.656 | 1.073 / 2.000 / 34.873 |
| 10,000, dense setup then invalidation | 0.853 / 4.800 / 37.448 | 1.010 / 1.778 / 11.945 |

| Nominal lines and navigation decorations | Baseline jump → paint | Candidate jump → paint |
| --- | --- | --- |
| 1,000, plain | 0.318 / 4.435 / 7.195 | 0.298 / 0.548 / 3.792 |
| 1,000, dense | 0.376 / 0.807 / 5.493 | 0.378 / 0.931 / 17.984 |
| 10,000, plain | 0.277 / 4.634 / 13.196 | 0.254 / 0.630 / 7.677 |
| 10,000, dense | 0.364 / 0.735 / 6.966 | 0.356 / 0.575 / 5.393 |

The largest candidate input sample was **34.873 ms** in round 2's plain 10,000-line case;
the largest dense 1,000-line jump was **17.984 ms** in round 3. Baseline maxima reached
37.448 ms for input and 13.196 ms for navigation. Between-fork variation is substantial:
candidate plain 10,000-line p50 values were 0.340, 1.851 and 1.073 ms. These data do not
justify attributing the lower aggregate medians to a particular code change.

The in-process frame-construction-to-first-paint median was **802.129 → 741.401 ms**;
candidate samples were 739.732, 741.401 and 975.693 ms. This excludes JVM process launch
and is not Finder application startup. End-of-workload JVM used heap had medians
**116.25 → 94.67 MiB**. That sample has no forced GC and is neither retained-object size
nor resident memory; it cannot establish a memory reduction.

## Offscreen rendering, snapshot installation and Git

`LineIntelligenceBenchmark` paints `EditorComponent` into a 1200×800 `BufferedImage` on the
EDT, with 20 warmups and 100 measured paints per case. Only a visible viewport is painted;
the synthetic 10,000/100,000-line documents carry dense immutable decoration maps. This is
an offscreen renderer measurement, separate from the native application measurements above.

Paint values are **microseconds**, formatted as **p50 / p95 / maximum**.

| Source size / decoration | Baseline offscreen paint | Candidate offscreen paint |
| --- | --- | --- |
| 10,000, plain | 361.458 / 401.500 / 1517.209 | 364.417 / 394.500 / 460.625 |
| 10,000, three lanes | 446.458 / 505.542 / 933.667 | 449.708 / 495.709 / 961.458 |
| 100,000, plain | 288.333 / 304.667 / 437.458 | 288.750 / 306.458 / 504.709 |
| 100,000, three lanes | 466.042 / 495.500 / 744.667 | 467.958 / 524.042 / 4149.416 |

Dense paint medians are close in these measurements. Candidate round 3 at 100,000 lines
had a 524.833 µs p50, 850.542 µs p95 and **4.149 ms maximum**, all retained. The smaller
plain-paint median at 100,000 lines is not evidence that a larger file is intrinsically
faster: viewport and fixed harness order/JIT state matter.

| Snapshot operation | Baseline median | Candidate median |
| --- | ---: | ---: |
| Construct 10,000 dense records | 5.429 ms | 5.748 ms |
| Install 10,000-record snapshot | 0.059 ms | 0.045 ms |
| Construct 100,000 dense records | 15.861 ms | 15.351 ms |
| Install 100,000-record snapshot | 0.033 ms | 0.033 ms |

These timings isolate synthetic snapshot construction and the editor setter. They exclude
source hashing, report parsing, asynchronous publication delay and semantic attribution.
Map lookup measurements are retained, but their tens-of-nanoseconds values are too close
to timer/JIT effects to support a product-level latency claim.

Git uses an owned local repository and the real `GitBufferDiff.compute` path to compare a
10,000-line buffer with HEAD, asserting one addition and one deletion. Each JVM records a
first call, three warmups and 15 measured calls. Process launch, baseline lookup, temporary
snapshot files, patch parsing and cleanup are included; no remote Git operation occurs.

| Git operation | Baseline | Candidate |
| --- | ---: | ---: |
| First call median | 51.089 ms | 49.538 ms |
| Warm p50 / p95 / maximum | 40.075 / 44.582 / 58.076 ms | 40.990 / 47.978 / 75.735 ms |

With 15 samples, each fork's nearest-rank p95 is its maximum. The **75.735 ms** candidate
outlier remains visible; asynchronous Git refresh must not be described as zero-cost.

## JDT semantic service

`SemanticBenchmark` calls the real core `JdtJavaIntelligenceService`, which bridges to
RefactorJ's Java intelligence implementation. It checks actual `println()` and
`java.util.ArrayList` proposals. Cold completion includes JDT initialization. Changed-source
completion changes the buffer/version to force a fresh binding parse; there are 10 warmups
and 50 measured calls. Cached cases reuse the same context. No EDT dispatch, popup rendering,
hover delay, AI request or network inference is included.

All values below are milliseconds; sampled rows show **p50 / p95 / maximum**.

| Operation | Baseline | Candidate |
| --- | ---: | ---: |
| Cold completion median | 1087.910 | 1055.446 |
| Warm changed-source completion | 4.947 / 8.518 / 16.399 | 4.491 / 8.394 / 44.995 |
| Cached completion | 0.316 / 0.562 / 1.586 | 0.310 / 0.483 / 1.218 |
| First type-index/completion median | 71.916 | 63.851 |
| First documentation lookup median | 1.089 | 0.555 |
| Cached documentation lookup | 0.067 / 0.121 / 0.460 | 0.033 / 0.044 / 0.071 |

**JDK source was not attached in either artifact's three runs** under the packaged runtime
(`jdkSourceAttached: false`). Documentation timings therefore describe the available
metadata/fallback path, not parsing or displaying full attached JDK documentation. Candidate
changed-source completion reached **44.995 ms** in round 2. Baseline initial type indexing
reached 146.352 ms in round 3; neither observation was removed from the aggregate.

## CH-16: separate large-report qualification

The earlier [Cherry report benchmark](../../cherry-native/feedback-2026-11-18/README.md#large-report-measurements)
uses three sequential fresh JVMs per version, `-Xms512m -Xmx2g`, and controlled synthetic
schema-v1 input with equal counts of test results and coverage rows. It measures event
import plus immutable snapshot publication; fixtures are generated before timing. It does
not run tests or certify coverage. This is a separate experiment, not another sample of
the native app benchmark.

The baseline JAR is the same `dd75866` artifact above, already containing Cherry. The report
experiment qualified the changed core implementation by source hash; both recorded
`CherryEvents.java` and `LineIntelligence.java` hashes still match the final source. It
should not be relabeled as execution of the final installed application JAR. Full raw logs,
all three forks, source identities and earlier pilots are retained in
[report-benchmark/](../../cherry-native/feedback-2026-11-18/report-benchmark/).

| Test results + coverage rows | Baseline median import/publication | Current median | Baseline retained heap | Current retained heap |
| --- | ---: | ---: | ---: | ---: |
| 10,000 + 10,000 | 37.275 ms | 59.827 ms | 5.33 MiB | 6.49 MiB |
| 100,000 + 100,000 | 411.214 ms | 435.694 ms | 55.25 MiB | 66.77 MiB |

The 100,000-result workload costs approximately **6% more elapsed time and 21% more retained
heap**, while preserving independent collection/context data. The 10,000-result current
samples were 55.107, **117.853** and 59.827 ms. Retained heap is sampled after requested GC
and remains approximate, not an allocation profile. Three forks do not support a useful
p95 estimate for one import per fork, so none is invented. Earlier concurrently loaded
pilot measurements remain available and are excluded from the final comparison.

## Reproduction and remaining measurement boundaries

The root driver is `scripts/qualify-product-performance.py`. It compiles only these test
entry points against the candidate JAR and invokes each in isolated JVMs:

- `app/src/test/java/dev/turboj/app/ProductPerformanceProbe.java`: native `TurboFrame`,
  actual `EditorComponent`, real compile, synthetic keyboard events and paint boundaries.
- `app/src/test/java/dev/turboj/testing/intelligence/LineIntelligenceBenchmark.java`:
  app renderer plus core `LineIntelligence`/`GitBufferDiff`.
- `core/src/test/java/dev/turboj/intelligence/SemanticBenchmark.java`: core JDT bridge and
  RefactorJ semantic implementation from the selected artifact.
- Separate CH-16 driver: `core/src/test/java/dev/turboj/testing/intelligence/CherryReportBenchmark.java`.

Example (use an unlocked desktop, stable artifact paths and no competing build/browser load):

```sh
python3 scripts/qualify-product-performance.py \
  /path/to/baseline/turboj-app.jar \
  /path/to/TurboJ.app/Contents/app/turboj-app.jar \
  --output /private/tmp/turboj-performance-reproduction --rounds 3 \
  --java /path/to/TurboJ.app/Contents/runtime/Contents/Home/bin/java
```

CommandJ JMH, Finder-launched startup, idle CPU and resident-memory controls are separate
release measurements and were still pending when this paired summary was written. The
release owner will add their exact artifacts and boundaries separately. These measurements
also do not qualify physical keyboard/IME, compositor presentation, paid-cloud AI latency,
network filesystems, metadata-heavy saves or performance on other hardware.
