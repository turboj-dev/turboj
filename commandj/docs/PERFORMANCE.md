# Performance — measured 2026-10-24

Apple M5, macOS 26.5.1, aarch64, Corretto 25.0.4.1. JMH 1.37, one fork, one thread, sample-time mode, 2 × 500 ms warmup, 3 × 500 ms measurement, GC profiler. These are short local microbenchmarks, not end-to-end editor latency or guarantees.

| Operation | Registered commands | p50 µs | p95 µs | p99 µs | allocated B/op |
| --- | ---: | ---: | ---: | ---: | ---: |
| conflicts | 100 | 29.056 | 33.536 | 39.186 | 102344.2 |
| conflicts | 1000 | 28.448 | 34.368 | 41.856 | 102321.2 |
| dispatch | 100 | 0.042 | 0.084 | 0.084 | 392.0 |
| dispatch | 1000 | 0.042 | 0.084 | 0.084 | 392.0 |
| lookup | 100 | 0.000 | 0.042 | 0.042 | 16.0 |
| lookup | 1000 | 0.000 | 0.042 | 0.042 | 16.0 |
| palette | 100 | 8.864 | 9.904 | 16.561 | 13443.3 |
| palette | 1000 | 90.624 | 99.840 | 108.032 | 128750.6 |
| registration | 100 | 9.952 | 11.360 | 16.768 | 147154.6 |
| registration | 1000 | 10.208 | 19.904 | 29.856 | 147156.1 |
| shortcut | 100 | 0.208 | 0.250 | 0.292 | 376.1 |
| shortcut | 1000 | 0.250 | 0.292 | 0.375 | 352.1 |
| switchProfile | 100 | 1.250 | 1.416 | 2.248 | 2520.5 |
| switchProfile | 1000 | 1.000 | 1.250 | 1.832 | 2520.6 |

The palette indexes 100/1000 generated command names and searches `fmt doc`. Shortcut resolution/conflict checks use the bundled IntelliJ profile; increasing the command registry size does not increase that profile’s binding count. Registration always registers 100 commands into a fresh registry, irrespective of the count parameter. Dispatch uses a synchronous empty callback. Lookup samples near timer resolution must not be interpreted as zero-cost operations.

Profiling exposed temporary sublist allocations in chord-prefix comparisons. Replacing those with direct immutable-list comparison reduced unnecessary work. No background indexing/compiler activity was included in these library measurements. Cold GUI startup, peak RSS and alternate physical keyboard layouts remain unmeasured.

Reproduce after `./mvnw package`:

```sh
java -jar commandj-benchmarks/target/commandj-benchmarks.jar \
  -wi 2 -i 3 -w 500ms -r 500ms -f 1 -p count=100,1000 \
  -prof gc -rf json -rff target/jmh-results.json
```

Use the annotations’ longer defaults or multiple forks for release comparisons. JMH uses a local socket between its coordinator and fork; restricted sandboxes may need permission. Raw local data are saved under `target/`; a compact recorded dataset is included alongside this report.
