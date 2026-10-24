# Native Maven compilation and EDT measurements

Measured 2026-10-24 on macOS 26.5.1, Apple M5, 16 GiB RAM, Corretto 25.0.4.1.
The generated public-domain fixture contains 12 reactor modules, 240 Java files,
Java release 17 and no external dependencies. The last module is changed in memory.

Command (run after `./mvnw -pl benchmarks -am package -DskipTests`):

```
java -cp benchmarks/target/turboj-benchmarks.jar org.openjdk.jmh.Main \
  'Maven(Native|Edt)Benchmarks' -wi 2 -i 3 -w 1s -r 1s -f 1 -prof gc \
  -rf json -rff benchmarks/results/maven-native-jmh.json
```

| Operation | p50 | p95 | p99 |
| --- | ---: | ---: | ---: |
| Unchanged reactor compile request | 4.071 ms | 4.647 ms | 7.382 ms |
| Changed leaf module compile | 14.287 ms | 16.335 ms | 28.428 ms |
| EDT queue + 10,000-line document edit/snapshot, idle compiler | 13.456 µs | 15.904 µs | 20.736 µs |
| EDT queue + same edit, continuously compiling reactor | 12.656 µs | 23.904 µs | 33.894 µs |

The unchanged compile request includes source inventory/read/fingerprint checks. It is
not the cached Maven model getter, which is measured separately. Changed leaf compilation
allocated about 11.57 MB/op; unchanged reactor checks allocated about 1.05 MB/op.
The raw JSON includes GC rates, counts, timing, percentiles and JVM flags.

EDT samples include `invokeAndWait` plus PieceTable insert/snapshot/delete, not rendering,
display presentation, physical keyboard input, a complete TurboJ window or semantic indexing.
The background benchmark continuously compiles, with no typing debounce. GC allocation
figures for that benchmark include both foreground and background work. These short runs
are engineering observations, not a guarantee of latency on arbitrary projects or machines.

The separate `MavenNativeAcceptanceTest` imports a real Java 21 POM with JUnit 5.13.4,
compiles main/test sources without touching `target`, executes two tests in the selected
Java 21 resident worker, and then explicitly invokes offline Maven `verify`. Both engines
report two passing tests. This fixture does not establish parity for custom build plugins,
resource filtering, alternative Surefire providers, forks or Maven extensions.
