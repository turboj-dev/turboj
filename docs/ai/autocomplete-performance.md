# AI autocomplete: performance

Measured on 26 October 2026: Apple M5, 16 GB unified memory, macOS 26.5.1, Java 25.0.4.1, Ollama 0.40.10
(Metal), models in Q4_K_M. Full report with every suggestion: [benchmarks/results/ai-completion-2026-10-26.md](../../benchmarks/results/ai-completion-2026-10-26.md).
Reproduce with:

```sh
./mvnw -o package -pl benchmarks -am -DskipTests
java -cp benchmarks/target/turboj-benchmarks.jar dev.turboj.benchmarks.AICompletionBenchmark \
  --models qwen2.5-coder:0.5b-base,qwen2.5-coder:1.5b-base --runs 3 --out benchmarks/results/ai-completion.md
```

## Model comparison (28-fixture corpus, 3 runs each, warm unless noted)

| Model | Cold load | Memory | First text p50 / p95 | Full suggestion p50 / p95 | Shown | Matches an expected completion | Compiles (shown, checked) |
|---|---|---|---|---|---|---|---|
| `qwen2.5-coder:0.5b-base` | 809 ms | 670 MB | 9 / 38 ms | 77 / 1,510 ms | 80/84 | 46/84 (55 %) | 19/25 |
| **`qwen2.5-coder:1.5b-base`** (default) | 1,034 ms | 1,257 MB | 15 / 117 ms | 134 / 446 ms | 81/84 | **63/84 (75 %)** | **26/26** |
| `Qwen2.5-1.5B` general base (control) | 773 ms | 1,257 MB | 21 / 133 ms | 2,903 / 4,034 ms | 0/84 | 0/84 | – |

- **Default: Qwen2.5-Coder 1.5B Base.** It is 20 points more accurate than 0.5B, every shown suggestion
  that could be compiled compiled, and its p95 (446 ms) is below 0.5B's, which sometimes runs to the
  token limit. 0.5B stays the choice for 8 GB Macs.
- **A general model is not a completion model.** The same architecture without code FIM training wrote
  Markdown and Chinese prose in every case and took ~3 s; every answer was dropped by the filters.
- "Cold load" is the wall time of a load request with the request context size, after unloading.
  TurboJ's managed start (server start + load + warm-up) measured 1,143 ms from Enable to Ready, and
  747 ms when reopening TurboJ (`AIManagedLocalSmoke`).
- Memory is Ollama's `/api/ps` size, all of it on the GPU side of unified memory; the JVM is separate.
- Not measured: Qwen2.5-Coder 7B (4.7 GB download refused with 11 GB free on this disk; `--models
  qwen2.5-coder:7b-base` after `ollama pull qwen2.5-coder:7b-base` adds it), CPU/GPU utilization, cloud
  providers (no authorized key).

## JDT baseline

JDT completes names: with the caret right after `tasks.` its first proposal is `clear()`, and it cannot
propose a statement. Its first proposal matched 0/28 fixtures; an expected completion was somewhere in its
list for 3/28 (the member-name fixtures). Latency p50 78 ms, p95 182 ms. The two engines are
complementary, which is why JDT keeps priority whenever it offers a choice.

## Request path costs

| Step | Cost | Where |
|---|---|---|
| Debounce | 200 ms configured; +120 ms while typing fast, +150 ms if the provider is slow; max 1 s | EDT timer |
| Context build | Git ignore check cached 5 s (a `git check-ignore` process per request before); recovered JDT AST for signatures | worker |
| Ollama preflight `/api/show` | Once per minute per provider instead of every request | worker |
| Model keep-alive | 30 min (was 2 min, which made the first suggestion after a pause a ~1 s cold load) | server |
| Java filter | Two JDT parses without bindings, skipped above 120,000 chars | worker, final result only |
| Delivery | Coalesced `invokeLater`, at most one per 50 ms while streaming | EDT |

Generation never runs on the EDT; the editor paints the ghost from text already validated. One inference
lane with a one-slot queue: a newer request cancels the HTTP exchange of the older one, so obsolete
requests never pile up.

## Targets

| Target | Status |
|---|---|
| Typing never waits for AI | By construction: no AI work on the EDT except installing a validated ghost |
| JDT completion responsive when AI is slow | JDT runs on its own worker; AI requests are skipped while a JDT choice shows |
| First useful suggestion under 200 ms warm (p50) | 1.5B: 134 ms full suggestion p50 |
| p95 full suggestion under 500 ms warm | 1.5B: 446 ms |
| Model stays loaded while working | 30 min keep-alive; IDLE state after it, reload on demand |
| Idle memory reclaimable | Ollama unloads after the keep-alive; Disable or Quit stops a TurboJ-owned server |
