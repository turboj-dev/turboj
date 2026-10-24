# RefactorJ performance measurements

Measured on 2026-10-24: Apple M5, 10 logical CPUs, 16 GiB RAM, macOS 26.5.1
(25F80), Amazon Corretto OpenJDK 25.0.4.1+10-LTS, JMH 1.37. These are local
engineering measurements, not a comparison against Eclipse or IntelliJ.
Hardware, JVM flags and the measured artifact hash are in
[benchmarks/environment.json](benchmarks/environment.json).

The measured snapshot precedes the later public-symbol-ID hardening (versioned,
workspace-scoped IDs that survive process restarts) and scratch-buffer unit-name
handling. These subsequent correctness changes are covered separately by protocol
and semantic regression tests; these timings have not been rerun against them.
Filenames containing `final` mean the final measurement
of this profiling session, **not a benchmark of the final release artifact**.

## Workload and limits

`SemanticBenchmark` uses one or twenty independent Java files, each containing
one short class, one method and a local variable (about 90 characters per file).
Every trial initializes a real embedded Equinox/JDT workspace with unsaved source
snapshots. A local or method rename runs the real LTK conditions, constructs text
edits and validates the candidate using compiler bindings. The twenty-file case
checks candidate diagnostics for all twenty files; it is not a twenty-file call
graph or a realistic Maven application.

Warm runs use one JVM fork, one benchmark thread, two 500-ms warm-up iterations,
three 500-ms measurement iterations, SampleTime mode and the GC allocation
profiler. This short run is useful for identifying costs and regressions; more
forks and larger real projects are needed for release performance claims. The
checked-in benchmark defaults are longer than these explicitly overridden runs.

`cachedSymbol`, `references` and `validatePreview` deliberately repeat the same
immutable query or plan at the same source version. Final measurements exercise
the bounded caches. They do not measure initial resolution, source changes, or
uncached semantic validation. Values below 0.001 ms are reported as such because
individual samples approach timer resolution. Zero-valued raw samples are not a
claim of zero execution time. `validateUncached` is a separate operation with a
fresh plan identity on every invocation; it retains the warm project model and
unchanged-baseline diagnostic cache but must check the actual candidate again.

`applyAndUndo` uses the real transaction engine with a fresh **in-memory** workspace.
It excludes disk journaling, original-file writes, Swing publication, compilation
after application and Auto Test. The reported latency is not a GUI shortcut-to-result
measurement. No typing-latency or large-project target is inferred from these tests.

## Warm results

Time is milliseconds. Allocation is decimal MB per operation, measured by JMH's
GC profiler, not retained heap or process RSS. The baseline column is an earlier
development run with the same six benchmark methods and run settings, before
batched verification and immutable-result caches. Its raw measurements are retained,
but the earlier uncommitted engine state was not archived as an independent release;
the comparison must not be presented as a reproducible published-version contest.

| Operation | Files | p50 ms | p95 ms | p99 ms | Allocated MB/op | Baseline p95 ms |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| `applyAndUndo` | 1 | <0.001 | 0.001 | 0.001 | 0.006 | 0.001 |
| `applyAndUndo` | 20 | 0.006 | 0.007 | 0.018 | 0.013 | 0.007 |
| `cachedSymbol` | 1 | <0.001 | <0.001 | <0.001 | <0.001 | 0.190 |
| `cachedSymbol` | 20 | <0.001 | <0.001 | <0.001 | <0.001 | 0.188 |
| `localRename` | 1 | 4.219 | 6.024 | 8.205 | 7.515 | 4.801 |
| `localRename` | 20 | 20.677 | 28.423 | 30.867 | 67.529 | 46.413 |
| `methodRename` | 1 | 24.281 | 30.179 | 33.161 | 39.854 | 31.215 |
| `methodRename` | 20 | 41.943 | 54.113 | 55.706 | 100.604 | 76.094 |
| `references` | 1 | <0.001 | <0.001 | <0.001 | <0.001 | 0.707 |
| `references` | 20 | <0.001 | <0.001 | <0.001 | <0.001 | 0.709 |
| `validatePreview` (cache hit) | 1 | <0.001 | <0.001 | <0.001 | <0.001 | 2.589 |
| `validatePreview` (cache hit) | 20 | <0.001 | <0.001 | <0.001 | <0.001 | 40.534 |
| `validateUncached` | 1 | 1.460 | 3.146 | 5.073 | 2.893 | — |
| `validateUncached` | 20 | 17.236 | 19.882 | 53.608 | 62.905 | — |

Uncached validation was measured in a separate run with the same warm-up, iteration
and profiler settings. It includes candidate staging, fresh compiler checks and
restoration; the source baseline is unchanged and warm. Its twenty-file p99 reaches
53.6 ms even though p95 is 19.9 ms. This cost must not be replaced by the exact-plan
cache-hit measurement when describing first-time validation.

The twenty-file local-rename p95 is below the initial 30-ms engineering target
for this tiny workload. Its allocations remain high: 67.5 MB per request, down
from 108.5 MB in the baseline. Twenty-file method rename allocates 100.6 MB per
request (baseline 142.0 MB) and its 54.1-ms p95 exceeds the 50-ms small-preview
target. One-file local rename is **slower** than the baseline (6.0 versus 4.8 ms
p95). These regressions and remaining costs matter; cache-hit timings cannot hide
them. The complete operation catalog has not been performance-characterized.

Batching compiler AST verification and caching unchanged baseline diagnostics
reduce repeated work across files. Further work should profile JDT/LTK processor
allocation, candidate working-copy creation and index invalidation, then repeat
measurements on actual projects with dependencies and cross-file references.

## Cold results

Each result consists of five fresh forked JVMs, no warm-up and one SingleShotTime
measurement per fork. The timed operation includes runtime startup **and clean
shutdown**. JVM launch before JMH invokes the method is excluded. Operating-system
filesystem caches are not flushed; this is cold Java infrastructure, not a cold
disk/boot benchmark. With only five observations, p95 and p99 equal the largest
observation and are not reliable estimates of a production tail distribution.

| Cold operation | p50 ms | p95 / p99 ms | Mean allocated MB/op |
| --- | ---: | ---: | ---: |
| Embedded runtime, start and close | 527.542 | 856.540 | 75.232 |
| Runtime + twenty-source workspace + first symbol resolution + close | 1079.097 | 1097.401 | 318.123 |

TurboJ starts semantic infrastructure lazily on its background semantic worker.
Keeping the same runtime and project model warm avoids paying these startup costs
for each command. These measurements do not justify initializing another project
index for completion, navigation, documentation or refactoring.

## Raw data and reproduction

The complete JMH data, including sample histograms, forks and secondary GC metrics,
is retained without selecting the fastest runs:

- [baseline-warm.json](benchmarks/baseline-warm.json)
- [final-warm.json](benchmarks/final-warm.json)
- [final-cold.json](benchmarks/final-cold.json)
- [final-uncached.json](benchmarks/final-uncached.json)

From the TurboJ checkout (substitute `mvn` when building the independent repository):

```sh
./mvnw -f refactorj/pom.xml -pl refactorj-benchmarks -am package -DskipTests
java --enable-native-access=ALL-UNNAMED \
  -jar refactorj/refactorj-benchmarks/target/refactorj-benchmarks.jar \
  'SemanticBenchmark.(applyAndUndo|cachedSymbol|localRename|methodRename|references|validatePreview)$' \
  -wi 2 -i 3 -w 500ms -r 500ms -f 1 -p files=1,20 -prof gc \
  -rf json -rff warm-results.json
java --enable-native-access=ALL-UNNAMED \
  -jar refactorj/refactorj-benchmarks/target/refactorj-benchmarks.jar \
  SemanticBenchmark.validateUncached -wi 2 -i 3 -w 500ms -r 500ms -f 1 \
  -p files=1,20 -prof gc -rf json -rff uncached-results.json
java --enable-native-access=ALL-UNNAMED \
  -jar refactorj/refactorj-benchmarks/target/refactorj-benchmarks.jar \
  ColdRuntimeBenchmark -prof gc -rf json -rff cold-results.json
/usr/bin/time -l java --enable-native-access=ALL-UNNAMED \
  -cp refactorj/refactorj-benchmarks/target/refactorj-benchmarks.jar \
  dev.turboj.refactorj.benchmarks.MemoryProbe
```

JMH uses a loopback socket to communicate with its forked JVMs. A sandbox must
permit that local connection. No benchmark sends source to an external service.

The memory probe executes twenty-source setup and twenty-five complete local-rename
preparations in one process, prints heap/nonheap usage without requesting GC and
closes the runtime. On macOS, `/usr/bin/time -l` adds the actual process peak RSS.
Heap-at-end, allocation totals and peak RSS are different quantities and must not
be substituted for one another.

## Process memory observation

The documented memory probe was executed once on the same machine/JDK, with no
explicit GC and no heap-size override. It completed successfully, including runtime
shutdown. The [raw operating-system report](benchmarks/memory-probe.txt) records:

| Quantity | Bytes |
| --- | ---: |
| Maximum process resident set size | 442,564,608 |
| macOS peak memory footprint | 424,166,624 |
| Heap used at the end of the measured work | 59,215,088 |
| Heap committed at that point | 262,144,000 |
| Nonheap used at that point | 62,598,536 |

These are substantial costs for only twenty tiny files. The process includes the
JVM, JDT/Equinox, compiler metadata, JIT compilation and formatting/refactoring
dependencies. It excludes the TurboJ GUI, program/test workers and AI inference.
One observation is not a memory percentile or a retained-size analysis; GC timing
changes end-of-work heap usage. Larger projects, long editing sessions, cache
eviction, and combined editor/compilation workloads still need dedicated memory
profiling before claiming a small memory footprint.
