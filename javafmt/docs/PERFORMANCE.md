# Performance — measured development build

Measured 24 October 2026 on Apple M5, 10 logical processors, 16 GB RAM,
macOS 26.5.1, Corretto 25.0.4.1+10-LTS. This is a development workstation with
other applications present, not a controlled laboratory or a universal speed
claim. All samples, including slow observations, are retained.

## Warm in-process JMH

JMH 1.37, one fork per workload, one thread, two one-second warmup iterations,
three one-second measurement iterations, sample-time mode, GC profiler,
`-Xms256m -Xmx1g`. Run `sh scripts/benchmark.sh` to reproduce. Synthetic Java
classes contain repeated methods with declarations and control flow. Requested
sizes 1/10/100 KiB yield 1,070 / 10,293 / 102,402 UTF-16 ASCII units before
formatting. Already-formatted inputs are 1,885 / 18,212 / 180,547 units.

Times are milliseconds per complete API call, including safety checks. Allocation
is MiB per operation, not live heap or process RSS. Range calls format a statement
inside the corresponding complete input.

| Workload | Size family KiB | p50 ms | p95 ms | p99 ms | Maximum ms | Allocated MiB/op |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| Already formatted | 1 | 0.092 | 0.231 | 0.411 | 5.767 | 0.35 |
| Already formatted | 10 | 0.746 | 0.814 | 1.199 | 2.773 | 2.40 |
| Already formatted | 100 | 8.143 | 9.531 | 9.804 | 10.060 | 23.61 |
| Statement range | 1 | 0.142 | 0.159 | 0.344 | 1.118 | 0.55 |
| Statement range | 10 | 1.200 | 2.268 | 3.141 | 17.531 | 3.31 |
| Statement range | 100 | 12.108 | 14.334 | 14.808 | 15.466 | 30.85 |
| Whole file | 1 | 0.149 | 0.158 | 0.189 | 9.847 | 0.60 |
| Whole file | 10 | 1.229 | 1.313 | 1.715 | 1.870 | 3.80 |
| Whole file | 100 | 12.747 | 14.149 | 14.439 | 14.811 | 35.79 |

[Raw JMH results](measurements/2026-10-24/jmh.json) include sample histograms and
GC measurements. The whole-file p95 objectives are met on these inputs/hardware.
That does not establish performance on arbitrary files. Range formatting saves
edits, but its full parse/reparse is visible in both time and allocations. It
does not meet a sub-10ms p95 target for a statement inside the 100KiB workload.

## Comparative exploratory run

The separate `dev.javafmt.benchmarks.Comparison` harness reuses each formatter,
warms up 30 times, then retains 100 complete samples per workload. JDT 3.44.0 is
configured for four-space indentation and 100 columns; google-java-format 1.28.0
uses its own canonical style. Output rules and safety work differ, so these are
equivalent input workloads, not equivalent algorithms or proof of superiority.
Each no-op input is that formatter's own output.

| Unformatted input | javafmt p50 / p95 / p99 ms | JDT p50 / p95 / p99 ms | google-java-format p50 / p95 / p99 ms |
| --- | ---: | ---: | ---: |
| 1,070 units | 0.534 / 0.834 / 1.144 | 0.386 / 0.482 / 0.493 | 1.188 / 1.657 / 2.507 |
| 10,293 units | 1.427 / 1.968 / 3.694 | 1.332 / 1.541 / 3.164 | 2.681 / 3.739 / 4.136 |
| 102,402 units | 12.798 / 14.280 / 14.380 | 13.713 / 15.228 / 15.303 | 34.526 / 55.735 / 60.941 |

[Full comparative report](measurements/2026-10-24/comparison-run.txt) includes
no-op results, allocation bytes, maxima and sequential throughput. All raw CSV
samples are in the same directory. The sum of JVM memory-pool peaks was
671,486,672 bytes for the complete multi-backend process; this is neither peak
RSS nor javafmt's standalone memory requirement. JMH and this shorter harness
have different warmup/heap conditions; their results must not be combined.

Run the comparison class from the benchmark JAR. Optionally supply the path to
the **unmodified** google-java-format 1.28.0 all-deps JAR as its first argument.
That backend requires JVM `--add-exports` for `jdk.compiler/com.sun.tools.javac`
packages `api`, `file`, `parser`, `tree`, `util`, and `code`, each to `ALL-UNNAMED`.
Neither competing formatter is used by javafmt's production formatting engine.

## Fresh CLI processes and batch I/O

The packaged Corretto runtime formatted a 1,158-unit source through stdin in
12 fresh processes: first call 281.59ms, median 188.12ms, maximum 281.59ms.
No OS filesystem caches were flushed. A single process formatting/writing 100
files took 757.43ms; checking those 100 canonical files took 293.06ms.
These include process startup and actual pipe/file I/O. Twelve samples are too
few for reliable tail percentiles. [All CLI samples](measurements/2026-10-24/cli.json)
are retained. Reproduce with:

```sh
python3 scripts/measure-cli.py target/distribution/javafmt-0.1.0-Darwin-arm64/javafmt
```

This explains the resident JVM in external adapters and the direct in-process
TurboJ API. Neither starts a fresh CLI process for each formatting request.

## Native editor observations and remaining work

One real TurboJ GUI smoke session recorded 86.72ms on the first formatting call
and 1.62/1.46ms on subsequent small calls. These are individual observations,
not percentiles. Editor operations remained asynchronous and the test exercised
selection, undo/redo, save, all three principal themes and actual compile/run.

IntelliJ host integration is tested for behavior, but no comparative IntelliJ
formatter benchmark is claimed. GraalVM Native Image has not been built or
measured. Input-to-paint latency during formatting and a cached incremental
parser remain separate work. The main measured optimization opportunities are
parse/reparse allocation, full-file range analysis and command-line JVM startup.
