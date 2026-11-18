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

## CommandJ JMH

All **six artifact/round invocations completed with exit status zero**, each producing all
six benchmark/size results. JMH 1.37 runs dispatch, shortcut matching and palette search at
nominal registry sizes 100 and 1,000. Each artifact has three separate forks per case:
**36 benchmark JVM forks total**, one thread, sample-time mode, three one-second warmups
and five one-second measurements per fork, using the installed application's Corretto 25
runtime with `-Xmx768m`. Artifact order alternates by round. Raw result histograms, per-fork
percentiles/confidence output and logs are retained in [commandj/manifest.json](commandj/manifest.json)
and the adjacent twelve JSON/text files.

Both separately built benchmark JARs have the **same SHA-256**:
`188fed19bed428076f5daf40ee667f6d8c86c774e4e3a0a56b98debea2d823a4`.
The CommandJ source subtree is unchanged between `dd75866` and `c7d08485`. Therefore this
is a repeatability/load comparison of identical benchmark code, **not evidence of a
CommandJ optimization or speedup**. It also demonstrates why a lower median alone cannot
establish a product performance improvement.

The actual `CommandBenchmarks` setup registers `count` synthetic formatting commands plus
one Save command. Dispatch executes a no-op caller handler through `CommandRegistry` and
returns its completed stage. Shortcut timing resolves `META+S` using the bundled IntelliJ
map and the registered Save command; it does not execute Save. The 100/1,000 parameter
varies registry size, **not the number of bound shortcuts or competing contexts**. Palette
search uses the already-built `CommandSearch` index, repeated query `fmt doc` and a 50-result
limit. These are core API measurements, excluding Swing dispatch, dialogs, painting, actual
command work, physical input and registry/index rebuild cost.

All values below are **microseconds per operation**, formatted as
**p50 / p95 / p99 / maximum**. Percentiles are the median of the three fork percentiles;
maximum is the highest sampled latency across all forks, not the median maximum.

| Operation / nominal commands | Baseline | Candidate |
| --- | --- | --- |
| Dispatch / 100 | 0.083 / 0.084 / 0.125 / 440.832 | 0.083 / 0.084 / 0.125 / 536.576 |
| Dispatch / 1,000 | 0.083 / 0.084 / 0.125 / 8503.296 | 0.083 / 0.084 / 0.125 / 5726.208 |
| Shortcut / 100 | 0.083 / 0.084 / 0.125 / 707.584 | 0.083 / 0.084 / 0.125 / 347.136 |
| Shortcut / 1,000 | 0.083 / 0.084 / 0.125 / 442.880 | 0.083 / 0.084 / 0.125 / 811.008 |
| Palette / 100 | 9.072 / 10.288 / 23.936 / 11862.016 | 9.456 / 10.080 / 15.408 / 533.504 |
| Palette / 1,000 | 117.632 / 150.784 / 317.952 / 11501.568 | 116.608 / 125.952 / 152.320 / 4399.104 |

Dispatch and shortcut p50 values are around 83 ns, near the observed clock quantization;
the raw histograms even contain some zero-width samples. They must not be interpreted as
precise end-to-end keyboard latency. Long tails remain: candidate dispatch reached
**5.726 ms**, candidate palette search reached **4.399 ms**, and baseline palette search
reached **11.862 ms**. No GC/OS profiler was recorded, so their cause is not assigned.

The three 1,000-command palette p99 observations were **317.952, 496.497, 137.728 µs**
for baseline and **118.400, 155.351, 152.320 µs** for candidate. Candidate 1,000-command
dispatch sample means were approximately **0.070, 0.075, 0.160 µs** despite matching
p50/p95/p99, illustrating the retained extreme-tail cost. [summary.json](summary.json)
keeps every fork's mean, p50, p95, p99, p99.9 and maximum; the raw histograms preserve the
full measured distributions without dropping outliers.

Reproduce with `scripts/qualify-command-performance.py BASELINE_BENCHMARK_JAR
CANDIDATE_BENCHMARK_JAR --output /private/tmp/turboj-command-reproduction --java
/path/to/TurboJ.app/Contents/runtime/Contents/Home/bin/java`. This driver invokes
`commandj/commandj-benchmarks/.../CommandBenchmarks.java` from each shaded benchmark JAR;
it does not substitute the full application JAR for the benchmark artifact.

## Installed application startup, idle CPU, heap and RSS

The corrected startup driver completed all **six LaunchServices starts**, alternating
baseline/candidate order with three fresh JVMs and isolated profiles per artifact. Both
installed applications use the same `Startup.java` bytes, with SHA-256
`4ddb147b84025a171788855be29f1c5aa57c4fa38f39626a77a7bf9f9db04ad4`. The final driver requires
a fresh output directory and separates `workspace/` source from sibling `profiles/`.
The [startup manifest](startup/manifest.json) records artifact, probe, driver and fixture
hashes, PIDs, ordering and each original result.

The Swift probe uses `NSWorkspace.openApplication` with a new application instance and a
minimal `/usr/bin:/bin:/usr/sbin:/sbin` PATH. It measures LaunchServices dispatch until
`CGWindowList` observes the owned on-screen application window, polling every 20 ms.
This exercises the packaged launcher and bundled runtime through the macOS application
launch route. It is not a physical Finder-click measurement, cold-disk-cache start,
completed semantic initialization or proof of compositor presentation. Warm OS caches
are retained, and the decimal raw timestamps do not imply sub-millisecond observation
precision.

| LaunchServices → observed window | Baseline | Candidate |
| --- | --- | --- |
| Three observed intervals | 1170.363 / 771.118 / 765.139 ms | 940.516 / 785.851 / 846.696 ms |
| Median | 771.118 ms | 846.696 ms |
| Range | 765.139–1170.363 ms | 785.851–940.516 ms |

The candidate median is approximately 75.6 ms higher in this small sample. There is no
causal regression/speedup conclusion or useful p95 estimate from three launches. The
original six starts remain in [startup-control/manifest.json](startup-control/manifest.json)
and are excluded from this final aggregate: their driver placed preferences below the
source/output root. A later bounded [workspace check](startup-control/workspace-control.json)
found only the expected Java fixture; it does not retrospectively prove every transient
state. The corrected separation removes that potential fixture-input ambiguity.

Idle qualification is **one approximately ten-minute interval per artifact**, measured
sequentially, with 20 contiguous 30-second sample periods. The native window remained
visible after initial compilation. Both used the same Java source hash above and profiles
outside the source tree. `scripts/measure-idle.py` records monotonic elapsed time, rounded
`ps` process CPU counters and RSS for the owned application plus its immediate worker
children; both sets contained two processes throughout their measured periods. It is not
an all-descendant process audit or a repeated-fork idle experiment.

| Ten-minute CPU/RSS measurement | Baseline | Candidate |
| --- | ---: | ---: |
| Actual summed sample duration | 600.989184 s | 600.841158 s |
| CPU seconds from rounded counters | 1.07 s | 0.73 s |
| Time-weighted CPU, percent of one core | 0.178040% | 0.121496% |
| Largest individual interval CPU | 0.399207% | 0.365973% |
| Summed RSS median | 46.75 MiB | 47.66 MiB |
| Summed RSS range | 38.45–56.13 MiB | 38.64–79.53 MiB |

CPU is **not zero**. The weighted value is total measured CPU divided by total measured
wall time, expressed relative to one core; it is not normalized by all ten processors.
The counter precision is limited by `ps`. Summed RSS can double-count shared pages and
reflect macOS paging/compression/reclamation; it is not private retained memory and its
decline does not prove a TurboJ memory optimization. A single quiet interval does not
certify absence of long-term leaks, all idle states, sleep/wake behavior or a CPU improvement.

Heap and GC were captured independently of those CPU/RSS samples. **The candidate's first
heap and GC captures were late and neither pair brackets its complete ten-minute CPU/RSS
interval.** These observations therefore do not establish matched-window heap growth or
absence of GC throughout either complete CPU/RSS interval.

| Independent main-JVM captures | Baseline | Candidate |
| --- | ---: | ---: |
| Used heap, first → last reading | 61.24 → 61.67 MiB | 58.40 → 58.66 MiB |
| Committed heap, first/last reading | 102 / 102 MiB | 109 / 109 MiB |
| Collector invocation/time deltas between counter captures only | All three G1 collector counters unchanged | All three G1 collector counters unchanged |

Used-heap readings differed by 433 KiB baseline and 266 KiB candidate, without forcing GC;
these are separate endpoint differences, not comparable ten-minute growth measurements.
No young/full/concurrent G1 invocation or time increment was observed **between each pair
of captured counters only**. Worker heap and worker GC were not inspected.

The original files' last-write times below document the capture order on 2026-11-18. They
are retrospective **wall-clock filesystem metadata, not monotonic measurement boundaries**,
counter sample timestamps or precise command-dispatch times. The CPU/RSS durations above
remain the original monotonic measurements; no UTC start is inferred by subtracting them
from a file-write time. Exact metadata and the retained failed control are recorded in
[idle/capture-metadata.json](idle/capture-metadata.json).

| Original file written (UTC) | Baseline | Candidate |
| --- | --- | --- |
| Launch result | 18:11:32.559466 | 17:47:58.373345 |
| First heap capture | 18:12:14.912513 | 17:50:14.498623 |
| First GC-counter capture | 18:12:15.750223 | 17:55:51.562229 |
| Completed CPU/RSS result | 18:22:30.690663 | 17:58:25.551389 |
| Last heap capture | 18:22:36.298176 | 17:59:37.947810 |
| Last GC-counter capture | 18:22:45.115491 | 17:59:39.917108 |

Raw CPU/RSS samples, launch metadata, runtime and heap endpoints are under
`idle/baseline/` and `idle/candidate/`; the aggregate is [installed-summary.json](installed-summary.json).
Only allowlisted collector names/counts/times and the counter frequency from `jcmd
PerfCounter.print` are archived as `gc-start.txt`/`gc-end.txt`. Full JVM property dumps
are deliberately excluded. The prior baseline control completed its CPU/RSS samples, but
its end `jcmd` calls failed under the sandbox before the owned process was stopped. Its
[control record](idle-control/baseline-matched/control-limitation.json) and original
CPU/RSS/heap-output files remain available. The PID-header-only failed endpoint is **not**
treated as a zero-GC/heap measurement. The final baseline was repeated with successfully
captured endpoints and is the only baseline used in the table.

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

For installed startup, compile `scripts/launch-package-probe.swift`, then use
`scripts/qualify-package-startup.py` with both app bundles, `--probe`, a fresh `--output`
directory and `--rounds 3`. Idle collection uses `scripts/measure-idle.py OWNED_PID --seconds
30 --samples 20`, with explicit approved `jcmd` endpoint captures before stopping the owned
application. Never infer missing endpoint data from a terminated process.

These measurements do not qualify physical keyboard/IME, compositor presentation,
paid-cloud AI latency, network filesystems, metadata-heavy saves or performance on other
hardware. The measured gates above are complete for their stated local scope; hosted CI
and other release decisions are tracked in the consolidated release record.
