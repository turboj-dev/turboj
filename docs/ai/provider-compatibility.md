# Provider compatibility

TurboAI speaks five protocols with the JDK HTTP client (no SDKs, no proxy, no redirects). Each adapter
is in `HttpAICompletionProvider`; `AIProviders.create` wraps them in a `FallbackChain`.

| Provider | Endpoint (base + path) | Auth | Request | Stream | Check / models | Verified |
|---|---|---|---|---|---|---|
| Ollama | `http://127.0.0.1:11434` + `/api/generate` | none (loopback) | `model`, `prompt` (prefix, after a context comment), `suffix`, `keep_alive`, `options{temperature 0.1, num_predict, num_ctx 8192}`; the model's template adds the FIM tokens | NDJSON `response`, `done` | `POST /api/show` (template must contain `.Suffix`, no remote model), `GET /api/tags` | **Live**, Ollama 0.40.10, Qwen2.5-Coder 0.5B/1.5B |
| llama.cpp server | `http://127.0.0.1:8080` + `/infill` | none | `input_prefix`, `input_suffix`, `input_extra`, `n_predict`, `cache_prompt` | SSE `content`, `stop` | `GET /health`, `GET /v1/models` | Protocol fixtures only |
| OpenAI | `https://api.openai.com/v1` + `/responses` | `Authorization: Bearer` | `model`, `instructions`, `input` (JSON with prefix, suffix, nearbyContext as untrusted data), `store: false`, `max_output_tokens`; `reasoning.effort: none` for `gpt-6-luna` | SSE `response.output_text.delta` | `GET /models/{id}`, `GET /models` | Protocol fixtures only |
| Anthropic | `https://api.anthropic.com/v1` + `/messages` | `x-api-key`, `anthropic-version: 2026-10-21` | `model`, `max_tokens`, `system`, one user message with the same JSON; no `temperature`/`top_p`/`top_k` (rejected on 2026-10-21); `output_config.effort: low` and the least thinking each family accepts | SSE `content_block_delta` / `text_delta`, `message_stop`, `error` | `GET /models/{id}`, `GET /models?limit=100` | Protocol fixtures only |
| xAI | `https://api.x.ai/v1` + `/chat/completions` | `Authorization: Bearer` | `model`, `max_tokens`, system + user messages | SSE `choices[0].delta.content`, `[DONE]` | `GET /models/{id}`, `GET /models` | Protocol fixtures only |

No live cloud request was made for this release: no key or authorization was available. A protocol fixture
proves the request and stream shapes TurboAI produces and parses, not that a provider accepts them today.

## Endpoints that are corrected

Ollama's native API lives at the server root. An endpoint typed as `http://localhost:11434/api` or as the
OpenAI-compatible `http://localhost:11434/v1` would produce `/api/api/generate` or `/v1/api/generate`
(HTTP 404); TurboAI removes a trailing `/api` or `/v1` for Ollama and `/v1` for llama.cpp. Cloud bases
keep their path: they must include `/v1`.

## Model choice

| Use | Local | Cloud |
|---|---|---|
| Inline completion while typing (latency first) | `qwen2.5-coder:1.5b-base` (default), `0.5b-base` on 8 GB Macs | Anthropic: `claude-haiku-5-5` (thinking disabled, effort low). OpenAI: a non-reasoning or `reasoning.effort: none` model. xAI: choose from `GET /v1/models` |
| Longer generation on explicit request (Alt+Shift+Space "longer") | `qwen2.5-coder:7b-base` where memory allows | The provider's default: `claude-opus-5-5` (Anthropic), `gpt-6-luna` (OpenAI) |

Defaults: OpenAI `gpt-6-luna`, Anthropic `claude-opus-5-5`; xAI has none, the user picks a model that their
key lists (TurboAI does not hard-code a Grok id it cannot verify). Conversational models produce
explanations, Markdown and repeated prefixes more often than FIM models; the insertion filter drops those,
which shows up as "no safe insertion". Cloud models are optional, cost money per request, and are never
used while AI is Off or Local unless the cloud fallback was chosen with consent.

## Errors

| `AIError` | Typical cause | Message |
|---|---|---|
| `MODEL_NOT_FOUND` | Ollama `404 {"error":"model '…' not found"}`, Anthropic `not_found_error` on the model | "Local AI: model 'qwen2.5-coder:1.5b-base' is not installed in Ollama. Download it or choose an installed model." |
| `UNSUPPORTED_ENDPOINT` | 404/405/501 without a model in the body, redirects | "…did not answer /api/show like an Ollama server (HTTP 404). Check the endpoint, or choose llama.cpp for llama-server." |
| `UNSUPPORTED_MODEL_CAPABILITY` | Template without `.Suffix`, "does not support insert" | "…does not support fill-in-the-middle. Choose a FIM base model…" |
| `RUNTIME_NOT_RUNNING` | Connection refused on a loopback endpoint | "Local AI: no Ollama server answers at http://127.0.0.1:11434." |
| `MODEL_LOAD_FAILED` | 5xx mentioning memory or loading | "…could not load '…' (HTTP 500). It may need more memory; try a smaller model." |
| `MODEL_NOT_LOADED` | llama.cpp 503 "Loading model" | "…is still loading the model." |
| `AUTHENTICATION_FAILED` | 401/403, missing or invalid key | "Anthropic rejected the API key (HTTP 401). Update it in AI settings." |
| `RATE_LIMITED` | 429, Anthropic 529 overloaded; `Retry-After` kept | "…is rate-limiting or overloaded (HTTP 429); TurboAI waits before retrying." |
| `INVALID_CONFIGURATION` | 400/413/422 | "…rejected the request (HTTP 400); check the model and settings." |
| `TIMEOUT`, `CONNECTION_FAILED`, `RESPONSE_PARSE_ERROR`, `INFERENCE_FAILED` | as named | — |

Messages are built from TurboAI's own configuration, never from the response body, which may echo source
or keys. The body's error message is kept, only when short and secret-free, in `AIDiagnostics`.
