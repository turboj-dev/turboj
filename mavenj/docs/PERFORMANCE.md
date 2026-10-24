# Performance methodology and measured import results

Measured 2026-10-24 on Apple M5, 16 GB, macOS 26.5.1, Amazon Corretto 25.0.4.1, JMH 1.37. These are exploratory local results, not product latency guarantees or comparisons with other IDEs.

Command from the TurboJ checkout:

```sh
java -jar mavenj/mavenj-benchmarks/target/mavenj-benchmarks-0.1.0-all.jar   '.*ImportBenchmarks.*' -f 1 -wi 1 -i 2 -w 1s -r 1s   -prof gc -rf json -rff mavenj/target/import-jmh.json
```

The fixtures contain 1 or 24 dependent Java modules plus one aggregator, with 10 Java files per module (10/240 files). Imports are offline, with no remote artifacts. JMH measures a warmed JVM: `coldImport` creates/closes a fresh MavenJ engine per invocation and rebuilds its effective models, but does **not** represent cold JVM/class loading or cold disk/network. `warmImport` checks metadata fingerprints and reuses the engine's cached workspace. Cached model/classpath/graph lookups read immutable published data.

| Operation | Java modules | p50 µs | p95 µs | p99 µs | Allocated bytes/op |
|---|---:|---:|---:|---:|---:|
| cachedClasspathLookup | 1 | 0.000 | 0.042 | 0.042 | 0 |
| cachedClasspathLookup | 24 | 0.000 | 0.042 | 0.042 | 0 |
| cachedModelLookup | 1 | 0.042 | 0.042 | 0.042 | 224 |
| cachedModelLookup | 24 | 0.125 | 0.167 | 0.333 | 224 |
| cachedModuleGraph | 1 | 0.000 | 0.042 | 0.042 | 0 |
| cachedModuleGraph | 24 | 0.000 | 0.042 | 0.042 | 0 |
| coldImport | 1 | 2109.440 | 5405.082 | 10059.776 | 1520533 |
| coldImport | 24 | 102301.696 | 189543.219 | 190054.400 | 132895045 |
| warmImport | 1 | 42.688 | 48.064 | 54.272 | 18591 |
| warmImport | 24 | 544.768 | 582.656 | 638.597 | 204809 |

Sub-microsecond accessor measurements approach clock resolution; zero samples do not mean zero execution time. Fresh-engine 24-module imports have fewer samples than cached lookups; inspect the raw confidence intervals and sample counts in [JMH JSON](performance/import-jmh.json). A single fork with short iterations is a smoke performance study; use the class defaults (two forks, two warmup and three measurement iterations) and larger real projects before making a release performance commitment.

The main TurboJ suite separately measures a 12-module/240-source native compilation workload, actual edits and EDT queue responsiveness: [native integration measurements](../../benchmarks/results/MAVEN_NATIVE.md). Those results distinguish queue/edit latency from painting and end-to-end physical input latency. JVM allocation metrics include workload scope stated in each report.

Dependency download timings depend on repository/network state and are recorded separately from these offline numbers. They must never be used to claim millisecond-scale cold downloads. Production cache invalidation follows POM/parent/BOM/settings/profile/toolchain/artifact metadata and snapshot update policy; `.java` content edits do not resolve dependencies.

## Fresh JVM and repository/network observations

Five independent packaged CLI processes imported the public three-module reactor with warm filesystem caches. Total process times were 224.8–257.7 ms (median 241.5 ms); model-building times were 109.7–121.3 ms. These are five individual observations, not a statistically meaningful p95/p99 study. Raw data: [fresh JVM samples](performance/fresh-jvm.json).

A separate public JUnit fixture resolved eight artifacts from the explicitly permitted Maven Central URL into an initially empty isolated repository. Import/model resolution took **2,451 ms**; a second fresh JVM using the populated repository took **150 ms** for the model. Both imports resolved successfully. Network behavior is environment-dependent; these two observations are not a percentile benchmark. The first sandbox attempt failed DNS resolution and is excluded from successful timing results. Raw successful observations: [repository resolution](performance/network-resolution.json).
