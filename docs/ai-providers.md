# AI provider protocols

TurboJ's optional AI transport uses the JDK HTTP client and the existing Gson
library. Disabled mode constructs no provider, starts no inference process and
makes no network request. These adapters do not load or bundle model weights.
All methods are worker-thread APIs; callbacks carry cumulative source text to the
completion coordinator, which owns validation and UI publication.

## Verified protocol references

References were checked on 24 October 2026:

- [Ollama Generate](https://docs.ollama.com/api/generate): `POST /api/generate`,
  `prompt` and `suffix`, newline-delimited JSON streaming, `options`, `keep_alive`.
  TurboJ preserves the model template, requests temperature 0.1 and bounds output
  tokens. Additional context is prepended as an escaped Java comment. A source-free
  metadata preflight before generation rejects remote-model metadata and templates
  without suffix support. It uses a two-minute keep-alive. An explicit release action sends the
  model name with `keep_alive: 0` and no source; disabling the editor feature does
  not forcibly unload a server model that other applications may be using.
- [llama.cpp server](https://github.com/ggml-org/llama.cpp/blob/master/tools/server/README.md#post-infill-for-code-infilling):
  `POST /infill`, `input_prefix`, `input_suffix`, optional `input_extra`, and the
  completion stream's `content` / `stop` events. The server, rather than TurboJ,
  applies the loaded model's actual FIM tokens. `/health` tests connectivity but
  does not establish model quality or FIM compatibility.
- [OpenAI Responses streaming](https://developers.openai.com/api/docs/guides/streaming-responses):
  `POST /responses`, typed SSE `response.output_text.delta`, successful completion
  and error/refusal handling. Requests set `store: false`, use no tools, and put
  bounded prefix/suffix/context into a JSON input distinct from trusted
  completion instructions. Model metadata is checked via `GET /models/{id}`
  without project text. API calls can incur charges.
- [GPT-6 Luna](https://developers.openai.com/api/docs/models/gpt-6-luna) is the
  configurable initial cloud model. Its documented `none` reasoning setting is
  used for short inline continuations. Other model identifiers are accepted and
  use provider defaults; unsupported temperature or reasoning settings are not
  guessed. Availability still depends on the user's account and endpoint.

## Optional local setup

A verified FIM candidate is
[Ollama qwen2.5-coder:1.5b-base](https://ollama.com/library/qwen2.5-coder:1.5b-base).
The published Q4_K_M weights are approximately 986 MB, under Apache 2.0. Its
actual template branches on `.Suffix` and uses `fim_prefix`, `fim_suffix` and
`fim_middle` tokens. The [original Qwen model card](https://huggingface.co/Qwen/Qwen2.5-Coder-1.5B)
identifies the 1.54B base model and its FIM use. This is not the similarly named
instruction/chat model. Weights size is not total RAM use: the inference server,
context cache and Java compiler require additional memory.

If the user chooses to install Ollama and download these weights, start a local
server in one terminal:

```sh
OLLAMA_NO_CLOUD=1 ollama serve
```

Then explicitly download the model from another terminal:

```sh
ollama pull qwen2.5-coder:1.5b-base
```

These commands are instructions, not an automatic installer. `ollama serve` is
unnecessary when the Ollama application already runs its local server. Set Local
AI to Ollama, endpoint `http://127.0.0.1:11434`, and the model above. Test connection
queries `/api/show` and rejects a template without suffix support. It does not
send source or download a missing model. Disabling Ollama's own cloud support
adds server-level protection; a user-managed local proxy is trusted infrastructure,
not a security sandbox. For llama.cpp, start a user-managed
server with a compatible FIM GGUF on loopback and enter its base URL (normally
`http://127.0.0.1:8080`). TurboJ does not terminate user-managed inference servers.

After explicit user authorization, an isolated Ollama runtime and Qwen base
model were downloaded and tested on Apple M5. The
[local FIM validation report](ai-local-validation.md) records pinned artifact
digests, all 12 generated-code outcomes, cold/warm timings and memory readings.
Protocol fixtures remain distinct from actual model inference. The direct
llama.cpp and Responses adapters have fixture coverage only; mock Responses
tests do not establish successful paid cloud inference.

## Transport limits and credential boundaries

Local endpoints must be literal `127.0.0.1`, `::1`, or `localhost`; no LAN/cloud
fallback, redirects or system proxy forwarding is allowed. Cloud endpoints
require HTTPS, explicit consent and a key supplier backed by secure storage.
No URL may contain user info, query parameters or a fragment. A test-only
package-private transport override accepts only loopback fixtures; production
factories cannot bypass cloud HTTPS validation.

Requests use immutable context snapshots, a configured context-character limit,
16–2,048 normal output tokens, and a 1–120-second total deadline. Cancellation
closes pending futures and active response streams without blocking its caller.
The deadline covers stalled response bodies as well as connection setup. Event
lines are bounded to 64 KiB, complete responses to 1 MiB and generated source to
64 KiB. Invalid UTF-8, malformed fields, premature EOF and provider failures
produce concise messages without echoing source, credentials or response bodies.
No retries are made automatically. The provider never executes generated code.

Connection, first-delta and total duration use monotonic time. These transport
samples are not compiler benchmarks and are separate from a coordinator's
first-usable-completion measurement.

## Reproduction

```sh
./mvnw -q -pl core -am -Dtest=AIProviderTest -Dsurefire.failIfNoSpecifiedTests=false test
```

The suite uses ephemeral loopback HTTP fixtures and non-secret fixture tokens.
It covers FIM payloads, cumulative Unicode streaming, SSE, cloud opt-in,
endpoint restrictions, source-free availability checks, explicit unloading,
redirect refusal, error redaction, malformed/oversized/truncated streams,
cancellation and deadlines. The sandbox must permit local listening sockets;
no request reaches a live cloud account.
