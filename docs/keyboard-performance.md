# Keyboard implementation — measured costs and limits

Measured **24–25 October 2026** (Europe/Zurich), during the keyboard implementation.
Raw reports retain UTC timestamps. These are local
engineering measurements, not guaranteed latency, a comparison with another IDE,
or proof that every physical keyboard/layout has been validated.

## Environment and identities

- Apple M5, **16 GiB RAM**, 10 logical processors; hardware values read using
  `/usr/sbin/sysctl -n machdep.cpu.brand_string hw.memsize hw.logicalcpu`.
- macOS 26.5.1, build 25F80, aarch64; JDK **25.0.4.1+10-LTS**.
- Editor baseline: the frozen TurboJ 1.0.10 application associated with native
  commit `5d801f84a183cc6d6415d6e605d442818d2e3310`. Its JAR SHA-256 is
  `87997d6395e5dc5d06f2258ea2ad9572d0f64cf00bf116237ec07dde2feaf893`.
- Current implementation was an uncommitted working tree. The measured class
  hashes below identify the loaded code instead of inventing a release commit.
- Background machine load, frequency and thermal state were not controlled.
  Repeated runs expose variation; these short probes are not a statistically
  controlled release benchmark.

| Loaded class | SHA-256 |
| --- | --- |
| Current `EditorComponent.class` | `1eca82de3c6ae872ab1e45269ee9421a1c3a2292fa2c175be8bf1b5f41b2e7da` |
| Frozen 1.0.10 `EditorComponent.class` | `01f14322870ae631ccd9cf091bf27de7057c4dc8d9195031aa4c6115c9732492` |
| Measured `JdtKeyboardEditing.class` | `ef46a824414cb4ca188f4a02d4724098568f58decfefeb59ff0b536d269dd506` |

The editor probes print the loaded class hash, UTC timestamp, runtime and heap
settings in every retained report. The semantic report records the loaded JDT
operation class hash and measurement conditions. These hashes identify the measured
components, not an entire subsequently packaged application.

## Editor operations on the EDT

Harness: [`KeyboardEditingBench.java`](../app/src/test/java/dev/turboj/ui/KeyboardEditingBench.java).
The document contains 1,000 repetitions of `    int sample = 42; // plain text`
plus LF, totalling 35,000 UTF-16 code units and a trailing empty logical line.
Single-caret operations start eight code units into line index 500; multicaret
operations use eight successive lines. The component is 1,000 × 700 pixels.

Each operation has 2,000 warmup iterations and 5,000 measured samples. Measurements
run on Swing's EDT with `java.awt.headless=true`, `-Xms256m -Xmx256m`. The clock is
`System.nanoTime()`, immediately before the editor operation through its return.
Allocation is the same thread's `ThreadMXBean` allocated-byte delta. Undo and
fixture-caret restoration run **outside** each measured interval. Ordinary host
callbacks remain their default no-op implementations.

The first complete current run and first complete baseline run are reported below.
No row is taken from a different run to select a faster result. All three complete
current and baseline runs are retained in [keyboard-evidence](keyboard-evidence/).

| Operation | Current p50 µs | Current p95 µs | Current p99 µs | Baseline p50 µs | Baseline p95 µs | Baseline p99 µs | Current median B/op | Baseline median B/op |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| Duplicate current line | 3.416 | 5.625 | 16.417 | 1.000 | 2.667 | 3.958 | 9,208 | 5,656 |
| Delete current line | 1.541 | 5.917 | 15.083 | 1.416 | 3.417 | 6.208 | 7,440 | 5,032 |
| Type one character | 1.166 | 2.416 | 4.084 | 1.416 | 4.042 | 7.625 | 6,832 | 5,192 |
| Type at eight carets | 7.667 | 17.000 | 30.167 | — | — | — | 13,616 | — |
| Backspace at eight carets | 17.334 | 18.208 | 21.083 | — | — | — | 14,816 | — |
| Move eight selected lines | 3.708 | 4.125 | 4.625 | — | — | — | 16,840 | — |

**A regression is visible:** Duplicate Line p95 rises from 2.667 to 5.625 µs,
about 2.11× (+2.958 µs), and median allocation rises by 3,552 bytes. Delete Line
also costs more in this pair. The new editing path validates normalized edits and
records caret/selection state for coherent undo; it allocates more than the old
single-caret path. This probe does not attribute each allocation or timing delta
to an individual change. The lower typing result in one pair is not evidence of a
general speedup.

Across the three complete current runs, Duplicate Line p95 spans 5.583–11.500 µs;
Type Character p95 spans 2.375–5.125 µs; eight-caret Backspace p95 spans
18.208–42.917 µs. The baseline Duplicate Line p95 spans 2.250–2.667 µs. Retaining
these controls avoids presenting the smallest observation as representative.

These are operation costs. They exclude CommandJ dispatch, the operating-system
input queue, painting, compositor/display scanout, live compiler/indexing work,
actual clipboard services, file I/O and application callbacks. A `repaint()` request
inside an operation is timed, but its later paint is not. They therefore **do not
establish unchanged input-to-visible-paint latency** or responsiveness under indexing.

### Reproduction

Use JDK 25 and a private temporary directory. The comparison compiles only the new
editor component against the frozen application's dependencies, then runs the same
harness with either that component or the frozen baseline class first on classpath.
The `baseline` argument excludes multicaret methods that the old class does not have.
No source checkout or user preferences are reset.

```sh
frozen_jar="$PWD/target/release-interaction-1.0.10/TurboJ.app/Contents/app/turboj-app.jar"
bench_root=$(mktemp -d /private/tmp/turboj-keyboard-bench.XXXXXX)
mkdir "$bench_root/editor" "$bench_root/harness"
javac -cp "$frozen_jar" -d "$bench_root/editor" \
  app/src/main/java/dev/turboj/ui/EditorComponent.java
javac -cp "$bench_root/editor:$frozen_jar" -d "$bench_root/harness" \
  app/src/test/java/dev/turboj/ui/KeyboardEditingBench.java
java -Djava.awt.headless=true -Xms256m -Xmx256m \
  -cp "$bench_root/harness:$bench_root/editor:$frozen_jar" \
  dev.turboj.ui.KeyboardEditingBench
java -Djava.awt.headless=true -Xms256m -Xmx256m \
  -cp "$bench_root/harness:$frozen_jar" \
  dev.turboj.ui.KeyboardEditingBench baseline
```

Run three complete current and three complete baseline processes for comparable
controls. Later source or JDK changes require fresh hashes and measurements.

## Structural Java preparation

Harness: [`KeyboardJavaIT`](../refactorj/refactorj-runtime/src/test/java/dev/turboj/refactorj/runtime/KeyboardJavaIT.java),
`recordsSharedModelKeyboardPreparationMeasurements`.
Raw selected run: [semantic-preparation.json](keyboard-evidence/semantic-preparation.json),
UTC **2026-10-24T21:47:58.807539Z**. The fixture is one 71-character Java source
with two methods. Eclipse is already warm; the source AST is cached in the existing
shared workspace. There are 20 warmup iterations and 60 samples per operation.

| Operation | p50 µs | p95 µs | p99 µs | Median allocated B/op |
| --- | ---: | ---: | ---: | ---: |
| Expand AST selection | 9.042 | 13.750 | 75.292 | 1,376 |
| Prepare method movement | 717.959 | 1,221.459 | 1,632.458 | 1,082,152 |

The move measurement includes candidate parsing, binding resolution and compiler
error comparison. It does not apply an edit or paint the editor. It also includes
the test's successful applicability assertion. The first selection in a newly
created workspace took **14.329 ms** once; this includes workspace creation with
an already-running Eclipse runtime. It is not a process cold-start distribution.
The roughly 1.08 MB allocated per move preparation remains an optimization target.

All offsets and results belong to immutable source snapshots. The runtime suite
also exercises actual compilation, CRLF/comments, Unicode, overload/lifetime
rejections, parameter/call-site refactoring and undo, generated code, and observable
method-move/equality behavior. Correctness coverage is separate from timing.

The exact selected verification command was:

```sh
./mvnw -q -pl refactorj/refactorj-runtime -am \
  -Dtest=KeyboardJavaIT -Dsurefire.failIfNoSpecifiedTests=false \
  -Dit.test=KeyboardJavaIT -Dfailsafe.failIfNoSpecifiedTests=false verify
```

Because of the explicit selectors this runs the same suite in Surefire and Failsafe.
It verified **32 distinct cases**, not 64 distinct tests. The report file is written
by each execution; the retained JSON is the final Failsafe run. A future ordinary
full-reactor verification can overwrite the build-directory report; the retained
copy above identifies this measured run.

## CommandJ dispatch and palette

The checked-in JMH report replaces the local user-home prefix with
`<user-home>` in its JVM executable path. Metrics, sample distributions, runtime
versions and benchmark settings are unchanged; the unredacted run is retained in
`target/keyboard-commandj-raw-unredacted.json` locally.

Harness: [`CommandBenchmarks.java`](../commandj/commandj-benchmarks/src/main/java/dev/turboj/commandj/benchmarks/CommandBenchmarks.java).
Raw data: [keyboard-measurements-2026-10-24.json](../commandj/docs/keyboard-measurements-2026-10-24.json).
The measured benchmark JAR has SHA-256
`988dd95c43f2ca07a5561933bd7de34507132e5a08e53671b916c374f7d27a6e`.

JMH 1.37 used sample-time mode, one fork/thread, two 500 ms warmup iterations,
three 500 ms measurement iterations and the GC profiler. No explicit heap-size
JVM arguments were supplied in this run. The fixture registers 1,000 generated
commands plus Save; it is not the live TurboJ command catalog. Palette search is
`fmt doc` with a 50-result limit after warming its index. Dispatch invokes a
synchronous empty callback; shortcut matching resolves Cmd+S against the bundled
IntelliJ profile with editor/editable context. Registering 1,000 commands does not
create 1,000 shortcut bindings.

| Operation | Recorded samples | p50 µs | p95 µs | p99 µs | Allocated B/op |
| --- | ---: | ---: | ---: | ---: | ---: |
| Registry lookup | 46,980 | 0.000 | 0.042 | 0.042 | 16.0 |
| Empty command dispatch | 59,511 | 0.042 | 0.084 | 0.084 | 392.0 |
| Shortcut resolution | 30,673 | 0.042 | 0.084 | 0.084 | 344.0 |
| Palette filtering | 15,567 | 95.744 | 103.808 | 114.985 | 128,753.7 |

Zero and very small lookup samples reflect timer quantization; lookup is not free.
Allocation here is JMH GC-profiler normalized allocation, not the per-thread median
used in the other tables. The shortcut result measures library resolution, not
AWT dispatch, focus transfer, an OS key event or command rendering. Palette filtering
does not include rebuilding its index, text-field events or result-list painting.

The benchmark preceded the final fresh-default Go to Type mapping correction and
palette UI scheduling correction. Those changes are outside the measured lookup,
empty-dispatch and filtering implementations; the saved JAR hash keeps the scope
explicit. This is not a claim of final full-application latency. Historical
CommandJ results in its older performance document are retained as separate runs,
not combined with these percentiles.

Executed command, from the repository root:

```sh
java -jar commandj/commandj-benchmarks/target/commandj-benchmarks.jar \
  'CommandBenchmarks.(lookup|dispatch|shortcut|palette)' -p count=1000 \
  -wi 2 -i 3 -w 500ms -r 500ms -f 1 -prof gc \
  -rf json -rff commandj/docs/keyboard-measurements-2026-10-24.json
```

JMH uses a coordinator/fork socket; this environment required permission for that
local socket. No network service is part of CommandJ matching or palette search.

## Swing typing through paint with compilation and semantic work

Harness: [`IntelligenceSmoke.java`](../app/src/test/java/dev/turboj/app/IntelligenceSmoke.java),
`--editing-only`. This reuses its existing editing measurement rather than changing
application instrumentation. The same compiled harness runs against both complete
application JARs. Its class SHA-256 is
`a24cf4eac6ac3ea24e846877797c01707897501408a26b9096396cb005776c5e`.
The measured current candidate application JAR SHA-256 is
`60f6715dfe55300e4be88ba7f1fcd1595fb7ecf3fb6e9f64d02df05487b92ed0`.
The baseline JAR is the frozen 1.0.10 artifact identified above. The measured current
JAR was copied to a temporary location and its hash verified before measurement;
no application classes were replaced during these runs.

The subsequent package from the first interaction audit
`b3441f5efac0aafef40d45357baf5e2bcd60b1efa29ad89099d75551b9148790`
adds CommandJ modal-focus restoration and diagnostic-navigation focus corrections.
It was not remeasured in this typing probe; those corrections are outside the character-edit/paint path. That scope
assessment does not establish unchanged performance for the later artifact: all
paint figures below identify the measured `60f6715d…` candidate only.

The later profile-switching/shared-control artifact
`50b2c49c3b8329b4812a5dcef7d327ddfaf0845a4b7bf76e5da24ab319302f0c`
adds the profile menu/palette actions and corrects local combo, tree, list and
text-control key maps. It passed the functional checks in the accessibility audit,
but it has not been remeasured by this performance probe. These historical timing
figures must not be presented as measurements of that later artifact.

All six complete sessions ran on **24 October 2026, 22:09:41–22:10:28 UTC**
(25 October, 00:09–00:10 in Zurich), alternating baseline/current three times.
Each uses a fresh JVM, isolated settings, TurboJ Classic keymap, Metal Swing look
and feel, and `-Xms256m -Xmx256m`. Dependencies were already available locally.
Repository builds, browser QA and other agent GUI harnesses were paused during
this interval. OS background activity, thermal state and display scheduling were
not controlled.

The source is one valid `Main` class containing a typing comment and **1,000 integer
fields**. The harness explicitly starts compilation and semantic completion, then
dispatches 120 synthetic `x` key sequences using a 12 ms Swing timer. This timer
sets the intended input cadence; EDT scheduling can delay its events. The native
compiler and semantic service remain enabled throughout. The harness verifies
that all 120 characters were inserted and that the final compiler diagnostics
belong to the current source version. Every run recorded 120 paint samples and one
semantic completion; this is concurrent startup/compilation work on one file,
not a sustained large-reactor indexing workload.

The monotonic interval begins inside `EditorComponent` handling of `KEY_TYPED`,
before its character edit, and ends at completion of the corresponding Swing
editor paint. It includes the edit, application callbacks and deferred Swing
painting, but excludes the OS input queue, compositor and display scanout. It
does not measure physical key-to-photon latency. No screenshot/video frame timing
was used. Raw capture-order nanoseconds are retained in every report.

The first complete pair is the primary observation; the two later pairs are
controls. No percentile was selected from another run to improve the comparison.

| Complete pair | Baseline paints | Baseline p50 / p95 / p99 ms | Current paints | Current p50 / p95 / p99 ms | Raw reports |
| --- | ---: | --- | ---: | --- | --- |
| 1 — primary | 120 | 0.839 / 3.629 / 5.231 | 120 | 0.563 / 3.466 / 4.937 | [Baseline](keyboard-evidence/keyboard-paint-baseline-run1.txt), [current](keyboard-evidence/keyboard-paint-current-run1.txt) |
| 2 — control | 120 | 0.553 / 3.546 / 4.753 | 120 | 1.244 / 4.366 / 8.642 | [Baseline](keyboard-evidence/keyboard-paint-baseline-run2.txt), [current](keyboard-evidence/keyboard-paint-current-run2.txt) |
| 3 — control | 120 | 0.716 / 3.415 / 3.987 | 120 | 1.509 / 4.535 / 5.928 | [Baseline](keyboard-evidence/keyboard-paint-baseline-run3.txt), [current](keyboard-evidence/keyboard-paint-current-run3.txt) |

The first pair has similar p95 observations, but **both controls are slower for
the current application**: p95 increases by 0.820 ms and 1.120 ms respectively;
pair 2's p99 increases by 3.889 ms. Across these short runs,
current p95 spans 3.466–4.535 ms and baseline p95 spans 3.415–3.629 ms. This
variation does not establish unchanged latency or a general improvement. It also
does not isolate a cause for the observed differences. All input and current-result
checks passed; broader workloads and more independent sessions are still needed
before a release-level claim that responsiveness has not meaningfully regressed.

### Reproduce the visible-window measurement

Use a supported desktop macOS session and keep other GUI automation and builds
idle. The test opens only its own temporary fixture and settings; it does not read
or edit a user's project. Use the same JDK for both complete JARs:

```sh
frozen_jar="$PWD/target/release-interaction-1.0.10/TurboJ.app/Contents/app/turboj-app.jar"
paint_root=$(mktemp -d /private/tmp/turboj-keyboard-paint.XXXXXX)
cp app/target/turboj-app.jar "$paint_root/current.jar"
javac -cp "$frozen_jar" -d "$paint_root/classes" \
  app/src/test/java/dev/turboj/app/IntelligenceSmoke.java
dist/TurboJ.app/Contents/runtime/Contents/Home/bin/java -Xms256m -Xmx256m \
  -cp "$paint_root/classes:$frozen_jar" dev.turboj.app.IntelligenceSmoke \
  --editing-only target/keyboard-paint-baseline-run1.txt
dist/TurboJ.app/Contents/runtime/Contents/Home/bin/java -Xms256m -Xmx256m \
  -cp "$paint_root/classes:$paint_root/current.jar" dev.turboj.app.IntelligenceSmoke \
  --editing-only target/keyboard-paint-current-run1.txt
```

Repeat the last two commands for `run2` and `run3`, retaining all reports. The
executed sessions used `/private/tmp/turboj-keyboard-paint-classes` for the harness
and `/private/tmp/turboj-keyboard-final-app.jar` for the copied current JAR.
Published raw reports normalize only the absolute checkout path to `<repository>`;
original reports remain in `target/`. Timestamps, samples and artifact hashes are
unchanged. The separate [keyboard editing walkthrough on the measured candidate](keyboard-evidence/keyboard-editing-before-modal-handoff.txt)
records 94 passing assertions across all four profiles on the `60f6715d…` JAR;
the [subsequent editing walkthrough](keyboard-evidence/keyboard-editing-final.txt)
identifies its own tested artifact. Those functional assertions are not latency samples.

## Coverage limits

No whole-application cold-start, peak RSS, sustained large-reactor typing latency,
physical input-to-paint latency or cross-layout latency distribution was measured
by these probes. Per-operation allocations are not retained memory or peak RSS.
Physical keyboard/Fn/dead-key behavior and assistive technology require the host
checks listed in [the accessibility audit](keyboard-accessibility-audit.md).

Normal line editing does not parse Java; semantic editing uses the existing serial
background worker and shared workspace. This architectural separation and passing
functional tests do not substitute for an end-to-end performance measurement.
Before a release-level “no meaningful regression” claim, extend the single-file
Swing measurements to active completion/indexing on representative Maven projects, repeat independent
forks, and profile the duplicate-line and semantic-move allocation increases.
