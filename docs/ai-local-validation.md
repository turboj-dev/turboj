# Local FIM validation — 24 October 2026

This report covers a user-authorized local model download and explicit native
Java provider validation. It is separate from protocol fixtures and from editor
typing/paint latency. No cloud account or credential is used.

## Reproducible environment

- Apple M5, 16 GB unified memory, macOS 26.5.1 (25F80); Corretto Java 25.0.4.1.
- [Official Ollama v0.40.10 release](https://github.com/ollama/ollama/releases/tag/v0.40.10),
  published 23 October 2026. The universal macOS `ollama-darwin.tgz` artifact is
  181,123,017 bytes. Its SHA-256 matches the GitHub release asset digest:
  `ac4f4d012a6d156718eef9aa8f2c7dd7e1f5c47362faf84d3eed412d805f2e2b`.
- Runtime extracted into `.cache/ai-runtime/ollama-v0.40.10/`, with its bundled
  license notices preserved. No global executable or application was installed.
- Model: [Qwen2.5-Coder 1.5B Base, Q4_K_M](https://ollama.com/library/qwen2.5-coder:1.5b-base),
  approximately 986 MB, Apache 2.0. The real suffix-aware template uses
  `<|fim_prefix|>`, `<|fim_suffix|>`, and `<|fim_middle|>`.
  Installed manifest digest:
  `02e0f2817a890a6de385d534465c04c5d0980abddc83615c09e79cee2c094446`;
  downloaded weights blob digest:
  `6a77366395772462c84f0c4d226ac404674327cbe78c01e4391cc7e0c698851e`.
  Ollama verified the downloaded SHA-256 before installing the manifest.
  `/api/tags` reports 986,060,385 total model bytes and the `completion` and
  `insert` capabilities.
- Dedicated loopback endpoint `http://127.0.0.1:11439`, Metal on Apple M5,
  one inference request at a time, 8,192-token context, cloud disabled.
- Weights live in `.cache/ai-models/`. Ollama created its standard local identity
  key under `~/.ollama/` because one did not exist; no existing server or model
  configuration was changed. Runtime, weights and generated probe artifacts are
  excluded from Git.

The dedicated server is started explicitly, rather than by the editor:

```sh
OLLAMA_HOST=127.0.0.1:11439 \
OLLAMA_MODELS="$PWD/.cache/ai-models" \
OLLAMA_NO_CLOUD=1 OLLAMA_NUM_PARALLEL=1 OLLAMA_CONTEXT_LENGTH=8192 \
  .cache/ai-runtime/ollama-v0.40.10/ollama serve
```

The authorized model download is a separate operation:

```sh
OLLAMA_HOST=127.0.0.1:11439 \
  .cache/ai-runtime/ollama-v0.40.10/ollama pull qwen2.5-coder:1.5b-base
```

## Probe procedure

`LocalAIProbe` is an explicit executable test utility, not a JUnit test. It
does not download models, change preferences or access cloud credentials.
It calls the same `AICompletionProvider` and insertion validator as TurboJ.
Four synthetic source fixtures cover a square method, array sum, prime test,
and insertion between existing statements. Each is requested three times,
using 192 maximum output tokens, temperature 0.1 and a 45-second deadline.

For this explicit acceptance test only, accepted suggestions are compiled with
the actual Java compiler and run in separate JVMs with a five-second timeout.
Their output is compared with expected results. The editor itself never
executes an AI suggestion automatically. All generated candidates, rejected
results, compilation failures and timings are retained in
`core/target/ai-local-validation/results.json`.

```sh
./mvnw -q -pl app -am -DskipTests package
javac -cp core/target/classes:app/target/turboj-app.jar \
  -d core/target/test-classes \
  core/src/test/java/dev/turboj/ai/LocalAIProbe.java
java -cp core/target/test-classes:core/target/classes:app/target/turboj-app.jar \
  dev.turboj.ai.LocalAIProbe \
  http://127.0.0.1:11439 qwen2.5-coder:1.5b-base
```

First-usable latency includes the source-free model metadata preflight and
waiting for a coherent insertion boundary. Total generation latency stops
before compilation/execution. Model-cold and subsequent warm samples are
reported separately. A small synthetic probe is not a general code-quality or
latency benchmark.

## Results

All 12 requests produced accepted insertions, compiled successfully, and matched
their fixture's expected output. No failing attempt was removed or retried.
The source-free availability check also passed against the installed template.
The model was unloaded before the first request; all subsequent requests reused
the loaded model. Repeated prompts can benefit from Ollama's prompt cache.

| Fixture | Round | First usable (ms) | Generation total (ms) | Compiled | Expected output |
| --- | ---: | ---: | ---: | --- | --- |
| Square (model cold) | 1 | 1041.03 | 1050.46 | Pass | `49` |
| Sum | 1 | 157.32 | 316.79 | Pass | `10` |
| Prime | 1 | 126.46 | 470.38 | Pass | `true false false` |
| Middle insertion | 1 | 110.56 | 120.77 | Pass | `11` |
| Square | 2 | 52.14 | 62.54 | Pass | `49` |
| Sum | 2 | 79.48 | 238.71 | Pass | `10` |
| Prime | 2 | 98.62 | 438.14 | Pass | `true false false` |
| Middle insertion | 2 | 79.53 | 90.21 | Pass | `11` |
| Square | 3 | 52.81 | 62.52 | Pass | `49` |
| Sum | 3 | 80.97 | 241.29 | Pass | `10` |
| Prime | 3 | 116.22 | 510.51 | Pass | `true false false` |
| Middle insertion | 3 | 78.36 | 88.41 | Pass | `11` |

For the 11 model-warm samples, nearest-rank p50 / p95 / p99 first-usable latency
was **80.97 / 157.32 / 157.32 ms**. Total generation latency was
**238.71 / 510.51 / 510.51 ms**. This sample is too small for a robust tail-latency
claim; p95 and p99 both select the slowest observation. These are provider
measurements, not keypress-to-paint, Java compilation, or run-command timings.

The actual square insertion was `return value * value;`. The middle insertion
was `int result = doubled + 3;`, preserving both the existing preceding assignment
and following return statement. Sum produced a loop over the existing array;
prime produced a valid but naive linear divisor loop. Passing these examples
does not prove general Java correctness, algorithm quality or safety.

Immediately after the probe, `ps` reported **24,080 KiB server RSS** and
**1,249,968 KiB runner RSS**. Ollama `/api/ps` reported **1,367,049,502 bytes**
for the loaded model allocation, all assigned to Metal, with context length
8,192. These are point-in-time readings, not peak memory. RSS and Metal allocation
overlap on Apple unified memory and must not be added together. The runtime
keeps the model for two minutes after the last request unless explicitly
unloaded; stopping this dedicated server also releases it.

The HTTP fixture suite additionally covers cancellation, stalled-stream
timeouts, malformed streams, remote-model rejection and redacted failures.
Those fixture results remain distinct from this live model run. llama.cpp's
direct `/infill` adapter and OpenAI Responses adapter were validated with local
HTTP fixtures only; no live cloud inference or direct llama.cpp model server
is claimed here. This probe does not measure editor responsiveness while
inference is running; native UI smoke/latency results are documented separately.

The [native release report](ai-release-1.0.5.md) records the successful packaged
GUI run, and [editing under local inference](ai-local-editing-latency.md) records
resource-contention measurements. The dedicated validation server was stopped
after those checks to release memory. Runtime and model files remain in the
ignored caches; the explicit start command above makes them available again.
