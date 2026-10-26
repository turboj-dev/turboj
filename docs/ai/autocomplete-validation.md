# AI autocomplete: validation

Status of AI Autocomplete 2.0 on 26 October 2026 (Apple M5, 16 GB, macOS 26.5.1, Java 25.0.4.1,
Ollama 0.40.10 from Homebrew). VERIFIED means exercised against the real thing; mock protocol tests never
count as live verification.

## The reported HTTP 404

| Step | Result |
|---|---|
| Saved preferences | `LOCAL`, Ollama, `http://localhost:11434`, `qwen2.5-coder:1.5b-base` |
| Server at that endpoint | Homebrew `ollama serve` (pid 91108), Ollama 0.40.10; installed models: `hf.co/mradermacher/Qwen2.5-1.5B-GGUF:Q4_K_M` only |
| `POST /api/show {"model":"qwen2.5-coder:1.5b-base"}` | `404 {"error":"model 'qwen2.5-coder:1.5b-base' not found"}` |
| `POST /api/generate` (same model) | same 404 |
| TurboJ before | Discarded the body: "AI provider returned HTTP 404." / "AI · Local unavailable" |
| Root cause | The Coder model had been installed into a separate validation server (`.cache/ai-models`, port 11439) on 24 Oct; the user's own server never had it |
| Fix | Typed `MODEL_NOT_FOUND` with an actionable message; the manager reports **Model not installed** with size, license and source and offers Download; endpoint normalization for `/api` and `/v1` |
| Regression test | `AIProtocolTest.theReported404IsAMissingModelWithAnActionableMessage` replays Ollama's exact body |
| After installing the model | The same FIM request returned `a * b;` (cold load 1,076 ms, total 1,551 ms) |

## Feature status

| Feature | Status | Evidence |
|---|---|---|
| TurboAI library (core, java, cli) used by TurboJ | VERIFIED | Reactor build; TurboJ runs on it |
| Typed errors, sanitized diagnostics | VERIFIED (fixtures) | `AIProtocolTest` (12) |
| Circuit breakers, fallback chains, no local→cloud without consent | VERIFIED (unit) | `FallbackChainTest` (8) |
| Managed local runtime: detect, start, own, load, warm, stop | **VERIFIED live** | `AIManagedLocalSmoke`: Enable → Ready 1,143 ms with TurboJ's own `ollama serve` on 127.0.0.1:11439; stopped on Disable and on Quit; no orphan process |
| Reuse of an external server, never stopped | VERIFIED (unit + live) | `LocalModelManagerTest`; the user's Homebrew Ollama on 11434 untouched through every run |
| Saved configuration starts local AI at launch | **VERIFIED live** | New TurboJ from saved preferences → Ready in 747 ms with no action |
| Watchdog: no orphan after the owner dies or `kill -9` | VERIFIED (real processes) | `theWatchdogStopsTheServerWhenItsOwnerDiesAndForwardsTerm` |
| Crash restart (≤ 3 in 10 min), idle detection | VERIFIED (fake server) | `LocalModelManagerTest` |
| Consented download with disk check, progress, cancel | VERIFIED (fake server); real pulls done with Ollama itself | `LocalModelManagerTest`; 0.5B and 1.5B pulled on 26 Oct |
| Real suggestion → Tab → source → one Undo | **VERIFIED live** | `a * b;` accepted in `Calculator.multiply`, undone |
| Multi-line suggestion | **VERIFIED live** | `for (int i = 0; i < values.length; i++) { total += values[i] * values[i]; }` |
| JDT completion independent with AI on | **VERIFIED live** | `Math.ab` → `abs()` with the managed runtime running |
| Accept next word / next line, type-through, template suppression, readiness gate | VERIFIED (unit) | `AICompletionControllerTest` (16) |
| Java suggestion filter (syntax, invented names) | VERIFIED (unit + benchmark) | `JavaSuggestionFilterTest` (5); 0 correct suggestions rejected over 252 real ones after the fix |
| Status segment and settings (runtime, model, keep-alive, fallback, providers, Download, Retry, Test completion) | IMPLEMENTED, segment VERIFIED live | Smoke read "AI · Local · Ready"; dialog controls compiled and covered by the existing settings smoke names |
| Test Completion (synthetic snippet) | IMPLEMENTED BUT UNVERIFIED in the GUI | `CompletionProbe` used by the benchmark warm-up |
| Anthropic and xAI adapters | IMPLEMENTED BUT UNVERIFIED live | Protocol fixtures only; no authorized key |
| OpenAI adapter | IMPLEMENTED BUT UNVERIFIED live | Protocol fixtures only |
| Managed llama.cpp | IMPLEMENTED BUT UNVERIFIED | llama-server not installed |
| Packaged `.app` | PARTIAL | See below |
| Intel Macs, sleep/wake | NOT VERIFIED | — |
| Bundled runtime | NOT IMPLEMENTED (evaluated) | [local model manager](local-model-manager.md#bundling-a-runtime-evaluation) |

## Benchmark

[autocomplete-performance.md](autocomplete-performance.md): Qwen2.5-Coder 1.5B matched an expected
completion in 63 of 84 runs (75 %), every compiled suggestion compiled, warm p50 134 ms, p95 446 ms.
The first run of the benchmark found two real defects that are fixed and have regression tests:
`CompletionValidation` threw `StringIndexOutOfBoundsException` on a fenced answer starting with a blank
line, and the Java filter rejected correct members completed right after a dot (`tasks.isEmpty`), before
a parameter list (`removeEldestEntry`) and after `::` (`Integer::sum`).

## Packaged application

Pending in this revision: the `.app` must be rebuilt (`scripts/package-macos.sh`) and run with the
Finder environment (`PATH=/usr/bin:/bin:/usr/sbin:/sbin`) to verify runtime discovery outside a shell,
the managed start, and that terminating the app leaves no Ollama process.

## Automated tests

| Suite | Tests |
|---|---|
| `turbo-ai-core` | `AIProviderTest` 15, `AIProtocolTest` 12, `FallbackChainTest` 8, `CompletionValidationTest` 9, `LocalModelManagerTest` 10 |
| `turbo-ai-java` | `CompletionContextTest` 8, `JavaSuggestionFilterTest` 5 |
| `app` | `AICompletionControllerTest` 16, `AISettingsPolicyTest` 2, keymap, menu and cheat-sheet tests |
| Real window | `AIManagedLocalSmoke` (managed runtime end to end), `AILocalSmoke` (external server) |
