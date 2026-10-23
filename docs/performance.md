# Measured performance

These are development measurements from the actual implementation, not product
guarantees. The raw reports retain slow samples and identify the operation being
timed. No measurements from other editors are claimed.

## Environment

- Apple M5, 10 logical processors, 16 GiB physical RAM, ARM64.
- macOS 26.5.1, Amazon Corretto 25.0.4.1+10-LTS.
- Measurements recorded 2026-10-23. Local source/build artifacts and default
  Java2D rendering; no native rendering optimization was introduced.
- The end-to-end harness used 5 warmup iterations and normally 15 measured
  iterations. With only 15 samples the nearest-rank p95 and p99 equal the maximum;
  they are coarse estimates. Follow-up release runs should use the default
  40-sample harness and repeated independent JVM runs.
- Benchmark sessions ran sequentially. These are measurements on a development
  laptop, not a thermally/power-controlled laboratory comparison.

## Compiler and execution

All values below are milliseconds. Compilation workloads contain executable
Java statements and change source between iterations to prevent cache hits.
The 10,000-line workload divides statements among methods to avoid the JVM's
per-method bytecode size limit. Cache hits are a separately named workload.

| Operation | Samples | p50 | p95 | p99 |
|---|---:|---:|---:|---:|
| First in-process Hello compilation | 1 | 155.423 | — | — |
| In-process compile, empty source | 15 | 8.019 | 9.856 | 9.856 |
| In-process compile, Hello (5 lines) | 15 | 8.850 | 11.470 | 11.470 |
| In-process compile, 100 lines | 15 | 7.065 | 11.063 | 11.063 |
| In-process compile, 1,000 lines | 15 | 9.560 | 33.771 | 33.771 |
| In-process compile, 10,000 lines | 15 | 23.013 | 30.494 | 30.494 |
| Identical-source cache hit | 15 | 0.002 | 0.007 | 0.007 |
| JDT syntax parse, Hello | 15 | 0.377 | 1.012 | 1.012 |
| JDT syntax parse, 1,000 lines | 15 | 1.808 | 2.506 | 2.506 |
| JDT syntax parse, 10,000 lines | 15 | 4.942 | 6.734 | 6.734 |
| Worker process creation to READY | 1 | 37.915 | — | — |
| Warm worker dispatch to first output | 15 | 0.392 | 0.514 | 0.514 |
| Warm worker dispatch to completion | 15 | 0.644 | 1.029 | 1.029 |
| Warm worker program invocation only | 15 | 0.388 | 0.822 | 0.822 |
| Isolated worker dispatch to first output | 15 | 32.831 | 37.144 | 37.144 |
| Isolated worker dispatch to completion | 15 | 37.295 | 41.895 | 41.895 |
| External javac process, Hello | 15 | 203.728 | 228.129 | 228.129 |
| JShell consecutive declarations | 100 | 4.618 | 9.475 | 19.710 |
| 2 MiB output capped at 1 MiB, dispatch to completion | 10 | 2.291 | 3.960 | 3.960 |

[Raw compiler/worker report](../benchmarks/results/latency-macos-arm64.json).
Worker timing starts after bytecode is compiled and ends when the protocol
callback/future reaches the benchmark. It does **not** include pressing Run,
unsaved-source-name inference, UI updates, or a changed-source compilation.
Compiler duration includes the compiler task and entry-point inspection but
excludes source construction and UI scheduling. The external javac comparison
includes process creation and writing class files, whereas the in-memory
compiler avoids those operations by design.

The initial sandboxed run exposed a real failure: macOS denied the worker's
process-descendant inspection, and an exception outside invocation handling
prevented a completion message. The worker now reports completion and retires
conservatively when inspection is unavailable. Worker tests were rerun after
the fix. Measurements above use normal local process privileges; the unsuccessful
sandbox attempt is not counted as a latency sample or silently reported as a
successful warm run.

## Editing while compilation is busy

The model harness performed **1,000 consecutive insertions**, each followed by
an immutable snapshot, while another thread repeatedly compiled a 10,000-line
file. This grows the piece sequence during typing. It measures document work,
not input-to-display latency.

| Source size | p50 ms | p95 ms | p99 ms |
|---|---:|---:|---:|
| 100 lines | 0.019 | 0.036 | 0.043 |
| 1,000 lines | 0.004 | 0.021 | 0.025 |
| 10,000 lines | 0.007 | 0.017 | 0.022 |

A separate visible Swing benchmark dispatched 250 synthetic key events at a
12 ms cadence for each size, while a background compiler repeatedly generated
bytecode. Each workload produced 250 measured paints; no events coalesced in
these runs. Window/renderer initialization preceded the sample interval.

| Visible editor workload | Handler→paint p50 ms | p95 ms | p99 ms | Event-queue p95 ms |
|---|---:|---:|---:|---:|
| 1,000 lines | 0.214 | 0.399 | 0.495 | 0.088 |
| 10,000 lines | 0.136 | 4.152 | 4.432 | 0.050 |

[Raw GUI report](../benchmarks/results/gui-latency-macos-arm64.json).
These values start inside the editor's key handler and stop when Java2D painting
returns. They exclude hardware keyboard delivery and the macOS compositor's
presentation to the display. The benchmark displays the editor component alone;
it does not include application chrome or live diagnostic UI updates. Therefore
it is evidence about editor responsiveness, not proof that the full application's
physical keypress-to-visible-character target has been met.

The complete application smoke test also measured 120 synthetic key events
against a 1,000-line buffer during compilation: p50 0.752 ms, p95 1.650 ms,
p99 1.972 ms for its edit-handler-to-paint interval. Its full report is produced
as `app/target/gui-smoke.txt` when the smoke command is run. Different harnesses
exercise different UI work; their timings should not be conflated.

## JMH

JMH 1.37 annotation processing and forked execution were verified on JDK 25.
The brief verification used one 300 ms warmup and two 300 ms measurement
iterations, one fork, `-Xms256m -Xmx1g`, sampling mode. For an insert/snapshot/delete
cycle in a 10,000-line piece table, 13,630 samples produced:

- p50: 5.000 µs; p95: 6.682 µs; p99: 12.784 µs.
- Maximum: 748.544 µs, retained in the result.
- Mean: 6.311 µs/op; JMH 99.9% error estimate: ±0.705 µs/op.

[Raw JMH report](../benchmarks/results/jmh-model-10000.json).
This constant-size cycle is different from the growing-piece consecutive typing
workload. The short JMH run verifies the harness and gives an initial baseline;
it is not a release-quality study. The source defaults to longer warmup and
measurement intervals.

## Reproduction

```sh
./mvnw -pl benchmarks -am -DskipTests package
java -jar benchmarks/target/turboj-benchmarks.jar --output benchmarks/results/local.json
java -jar benchmarks/target/turboj-benchmarks.jar jmh CoreBenchmarks -rf json -rff benchmarks/results/jmh.json
java -cp benchmarks/target/turboj-benchmarks.jar com.turboj.benchmarks.GuiLatencyBench
```

`--quick` selects the shorter 5-warmup/15-sample run recorded above. GUI
measurements require a graphical desktop. On macOS a restricted sandbox that
denies process inspection is a different worker lifecycle configuration and
should be identified separately. For profiling, add JDK Flight Recorder options,
for example `-XX:StartFlightRecording=filename=turboj.jfr,settings=profile`, before
the `-jar` argument. JMH code is a benchmark-only dependency under its
[upstream GPLv2 + Classpath Exception license](https://github.com/openjdk/jmh/blob/1.37/LICENSE).

## Remaining measurements

No claim is made yet for: physical keypress-to-presentation latency, complete
input-to-diagnostics latency, cold packaged-app launch distributions, long-duration idle CPU,
memory/GC across long sessions, the 100-file project, dependency-aware project
recompilation, an external-library project, or ECJ backend comparisons. The
single-file javac and worker feedback paths meet their individual initial
latency targets on this machine; whole-product release targets still need the
missing end-to-end measurements and broader hardware validation.

## Packaged-runtime verification

The full GUI smoke also passed using `dist/TurboJ.app/Contents/runtime/Contents/Home/bin/java` and the bundled application JAR, with no external JDK on that execution path. Its harness start-to-visible-window sample was 496.45 ms. Its 120 synthetic keys in a 1,000-line file produced p50 0.435 ms, p95 1.376 ms and p99 2.620 ms for handler/edit-to-paint. This remains a harness timing, not native LaunchServices cold-launch latency. [Full report](../benchmarks/results/gui-smoke-bundled-runtime-macos-arm64.txt). Earlier runs and their slower samples remain in `benchmarks/results/gui-smoke*`.

After launching the actual native application on Hello.java and allowing initial analysis to finish, three consecutive approximately 5-second idle observations found no detectable CPU-time increase in the app plus its immediate worker. `ps` reports CPU counters rounded to hundredths of a second; this does not prove exactly zero consumption. Combined resident-set readings ranged from about 143 to 171 MiB and include shared pages, so they are not private-memory estimates. [Raw idle samples](../benchmarks/results/idle-macos-arm64.json). This short observation does not replace long-session power/memory testing. Reproduce with `python3 scripts/measure-idle.py NATIVE_APP_PID`.
