# Editor painting during real local inference — 24 October 2026

This explicit contention probe measures actual `EditorComponent` edits and
Java2D painting while TurboJ's provider streams real output from the installed
local FIM model. It is distinct from the blocked-provider unit-test fixture,
model response time, and native operating-system input-to-screen latency.

The environment was Apple M5, 16 GB unified memory, macOS 26.5.1, Amazon
Corretto 25.0.4.1+10-LTS, and the dedicated Ollama v0.40.10 server on
`127.0.0.1:11439`. Model: `qwen2.5-coder:1.5b-base`, Q4_K_M. See the
[local validation report](ai-local-validation.md) for artifact digests and
server setup. No cloud service, credential, model download, or source from a
user project is involved in this probe.

## Procedure

`AILocalLatencyProbe` is an optional executable under `app/src/test`, not a
JUnit test. Each session creates a real editor with exactly 1,000 or 10,000
lines: repeated Java comments followed by a small method. On the Swing EDT,
it inserts one space at the method's cursor and paints the 900 × 500 component
to a `BufferedImage`. It performs 20 explicit warmup edit/paint operations,
then retains all 200 measured samples, at a target cadence of 8 ms.

An independent background thread sends sequential FIM requests through the
production `AICompletionProvider` to the already installed local model. The
measurement starts only after the first nonempty streamed model output.
Requests continue throughout the sample window and the last request is allowed
to finish afterward. This deliberately keeps inference busy: it does **not**
use the editor controller's normal cancel-on-edit scheduling or display the
resulting suggestions. It measures resource contention, rather than claiming
the normal editor makes hundreds of completion requests while typing.

The measured intervals are:

- **Edit + paint:** time inside the EDT to update the real document and finish
  offscreen painting.
- **Dispatch to paint:** time from submitting that EDT action until its
  completion returns, including EDT dispatch overhead.

There is no Robot keyboard injection, operating-system window compositor,
physical display refresh, compilation, or concurrent JDT indexing in this
probe. The surrounding code is synthetic and edits are whitespace, so these
short sessions do not establish performance for every editing workload.
No outliers were discarded. Percentiles use nearest rank across all 200
samples in each session.

## Recorded results

The main run began at `2026-10-24T07:33:30.257379Z`. It used a fixed square-method
FIM prompt with a 64-output-token cap. Repeated identical prompts may benefit
from Ollama's prompt cache. Each session completed five sequential requests;
four completed during its approximately 1.59-second measurement interval and
the final request finished afterward. The provider request was active at all
200 sample starts in each session; this indicator includes transport and
metadata work and is not a continuous GPU utilization measurement.

All times below are milliseconds.

| Workload | Metric | Samples | p50 | p95 | p99 | Maximum |
| --- | --- | ---: | ---: | ---: | ---: | ---: |
| Square, 1,000 lines | Edit + paint | 200 | 0.178 | 0.407 | 0.539 | 0.716 |
| Square, 1,000 lines | Dispatch to paint | 200 | 0.242 | 0.471 | 0.595 | 0.907 |
| Square, 10,000 lines | Edit + paint | 200 | 0.146 | 0.366 | 0.460 | 2.658 |
| Square, 10,000 lines | Dispatch to paint | 200 | 0.193 | 0.443 | 0.546 | 2.720 |
| Initial prime pilot, 1,000 lines | Edit + paint | 200 | 0.180 | 0.478 | 0.639 | 0.921 |
| Initial prime pilot, 1,000 lines | Dispatch to paint | 200 | 0.234 | 0.545 | 0.732 | 1.314 |

The initial pilot is included rather than discarded. Its longer prime-method
prompt used a 192-token cap and kept one generation active for all 200 edits.
The server recorded 36.39 ms of prompt evaluation and 1,796.76 ms of generation,
1,833.15 ms total; its HTTP request took approximately 2.36 seconds. No request
completed within the sample window, which triggered an overly strict initial
probe assertion requiring completion during measurement. Actual inference
overlap was present throughout. The final utility correctly accepts that case.
The subsequent shorter square workload demonstrates multiple sequential
requests; it was not used to remove slower editor samples.

The 10,000-line square session's largest edit/paint observation, **2.658 ms**,
is retained in the table and raw data. The smaller median for that session is
not evidence that larger files are faster: it ran later in the same JVM, after
additional JIT warmup, with a short synthetic workload. These observations
support responsiveness during this particular local-inference load and are
not a general latency guarantee or a compiler benchmark.

The five provider request durations were `347.17, 303.68, 302.38, 472.32,
303.50 ms` in the 1,000-line session and `304.90, 478.57, 299.49, 392.88,
310.90 ms` in the 10,000-line session. The probe only drains these generations;
it does not accept or execute them. Generated-code correctness is tested
separately by the local validation and native application smoke tests.

## Reproduction and raw artifacts

Start the explicitly configured local server and install its model as described
in [local validation](ai-local-validation.md). The probe does neither itself.
From the repository root:

```sh
./mvnw -q -pl app -am -DskipTests package
javac -cp app/target/classes:core/target/classes:app/target/turboj-app.jar \
  -d app/target/test-classes \
  app/src/test/java/dev/turboj/ai/AILocalLatencyProbe.java
java -Djava.awt.headless=true \
  -cp app/target/test-classes:app/target/classes:core/target/classes:app/target/turboj-app.jar \
  dev.turboj.ai.AILocalLatencyProbe \
  http://127.0.0.1:11439 qwen2.5-coder:1.5b-base
```

The completed run's artifacts are under
`app/target/ai-local-editing-latency/`: `report.txt`, `1000-lines.csv`,
`10000-lines.csv`, and one `*-inferences.csv` file per session. Every measured
sample is preserved with request-active state and completed-request count.
The earlier pilot's raw samples, initial report, and server timing notes are
retained in that directory's `pilot-prime-192tokens/` subdirectory. Target
artifacts are local build outputs and are not committed. A future run rewrites
the main report and CSV files; preserve them first when comparing runs.
