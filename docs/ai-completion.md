# Optional native AI completion

AI is disabled by default. Java intelligence, compilation and execution remain
independent of the AI worker. The AI runtime is the [TurboAI library](../turbo-ai/README.md).
No model is bundled; in Local mode TurboJ reuses a running Ollama or starts its
own, and downloads a model only after you confirm (see
[local model manager](ai/local-model-manager.md)). There is no chat panel,
telemetry, source upload to turboj.dev, or execution of a suggestion before
explicit acceptance and an ordinary Run command.

## Setup and controls

Open **Options → AI Completion → Configure AI completion…**. Select Disabled,
Local or Cloud. Local supports an Ollama FIM model (reused, or started and
stopped by TurboJ) or a llama.cpp infill server. Cloud supports OpenAI
(Responses), Anthropic (Messages) and xAI (Chat Completions) with a
configurable HTTPS endpoint and model ([provider compatibility](ai/provider-compatibility.md)). The Cloud tab explains source sharing and
API charges and requires explicit consent. Automatic cloud requests have a
separate opt-in; editing never enables them implicitly.

| Input | Action |
| --- | --- |
| Alt+Space | Request an AI insertion |
| Alt+Shift+Space | Request again, bypassing the cache |
| Tab | Accept the entire displayed suggestion as one undo operation |
| ⌥→ or Ctrl+Alt+Enter | Accept the next word of an AI suggestion |
| ⌥⇧→ | Accept the next line of an AI suggestion |
| Typing the suggestion's next characters | Keeps the rest of it, without a new request |
| Escape | Dismiss and cancel pending suggestions |
| Options → AI Completion → Disabled | Immediately stop AI requests and the server TurboJ started |
| Code → AI → Test Completion | One synthetic request with its latency; no project source |
| Code → AI → Download Local Model… | Install the configured model after confirmation |

`keymap.json` overrides `ai.request`, `ai.another`, `ai.larger`,
`aiAcceptWord`, `acceptCompletion` and `dismissCompletion`. These bindings apply
inside TurboJ, not globally; remap an OS/input-method conflict in that file.
The menu also offers a longer completion, capped at twice the configured token
limit and at 2,048 tokens. A new request can produce the same answer; model
sampling is not a guarantee of different suggestions.

The AI status segment ("AI · Local · Ready", "Loading model…", "Model not
installed") identifies Local or Cloud and the runtime state; click it for settings. AI ghost text uses each
native theme's `intelligence.aiGhost` token, including custom/imported themes.
Multiline display creates temporary visual rows and shifts the remaining source
visually without changing document offsets, line indexing or undo history.
Tab inserts only at the original caret. Undo once removes a whole acceptance.
Partial word acceptance is a separate explicit edit.

An active semantic suggestion or explicit alternative has priority. For example,
`System.out.pr` continues to use the Java engine. AI cannot overwrite a selected
semantic completion. Changing text, caret, buffer, settings or focus invalidates
the pending AI request. A failed provider leaves ordinary Java intelligence usable.

## Context and privacy

Only the current Java buffer contributes context. Prefix and suffix surround the
caret; bounded current-file imports and method/type signatures provide additional
context. No neighboring files, binary files, repository instructions or external
sources are automatically read for AI. Larger source files omit the extra AST
summary; the prefix/suffix still use bounded UTF-16 ranges. The configured context
budget includes all three parts. Context conversion and parsing run off the EDT.

Non-Java files, credential/secret filenames, configured exclusion globs and
Git-ignored files are rejected. Git's own ignore checker runs with a two-second
limit outside the EDT; an unsuccessful ignore check fails closed. The application
also checks assembled context for several credential/private-key patterns. These
heuristics cannot identify every secret. They are an extra safeguard, not a
promise that code is safe to share. Source comments and declarations are untrusted
context data, never system instructions.

Credentials are endpoint-bound items in macOS Keychain. Neither configuration
JSON, command arguments, screenshots nor diagnostics include the key. Changing
cloud endpoint/model clears the dialog's sharing consent. The editor never reads
or sends a cloud credential in Local mode. Local endpoints must be loopback;
Ollama cloud model metadata is rejected. A user-controlled server can itself relay
traffic, so TurboJ cannot provide an operating-system network sandbox for it.
See [provider protocols](ai-providers.md) and [Keychain implementation](ai-credentials.md).

## Scheduling, validation and resources

The default inactivity delay is 200 ms. Explicit requests bypass the delay.
One inference worker and one pending slot bound work; cancellation closes the
stream and supersedes queued work. Stream deliveries to Swing are coalesced and
never send individual tokens into the document. Only coherent statement/block
boundaries are eligible for partial display. Completion state includes document
identity/version, caret, coordinator generation and immutable provider settings.

The insertion validator handles Markdown fences, duplicate suffix text, obvious
extra closing braces, malformed controls and unfinished literals. It is deliberately
conservative and may hide otherwise useful output. It does not prove semantic
correctness, dependency availability or security. After acceptance, the existing
Java compiler reports actual diagnostics. No provider may rewrite preceding or
following source or apply imports automatically.

A 16-entry in-memory completion cache is scoped to provider/model/configuration
and exact bounded source context; it is cleared on reconfiguration and shutdown.
It is never persisted. HTTP connections are reused while configured. Turning AI
off cancels the client and releases its resources. Ollama models use a two-minute
keep-alive, and **Unload local model** explicitly releases one when appropriate.
TurboJ does not terminate user-managed inference servers that other apps may use.

Optional diagnostics are local and off by default. When enabled, Performance
reports scheduling delay, connection time, first validated candidate, total
generation, request/cache/cancellation/acceptance counts and editor JVM heap use.
Candidate timing excludes compositor paint and does not measure model quality;
external model memory is not included in JVM heap. Disabling diagnostics clears
its samples. No network metrics collector or external telemetry exists.

## Validation boundary

Automated protocol fixtures exercise real HTTP/SSE streaming, failures, timeouts,
cancellation and payloads without live credentials. Native tests cover semantic
priority, stale responses, multiline acceptance/undo, theme colors and continued
editing while a provider stalls. A separate native smoke test compiles and runs
accepted fixture code, explicitly labeled as a fixture.

The authorized local validation passed 12/12 generated-code behavior checks with
Qwen2.5-Coder 1.5B Base on Ollama, followed by a real-window test using the packaged
app runtime. See [the model report](ai-local-validation.md) and
[release verification](ai-release-1.0.5.md). This does not establish the quality or
latency of other models. Live paid-cloud validation requires the user's configured endpoint and
Keychain credential. Passing a mock protocol test does not establish either live
inference quality or account access. No cloud key or model is silently obtained
from unrelated applications. Public website capability claims must reflect this
validation boundary.
