# AI autocomplete: architecture audit (before AI Autocomplete 2.0)

Audit of the code as found on 26 October 2026 at commit `b8f5978`, before any change in this
assignment. Paths are the pre-change paths. "Implemented" means read in the code and, where
stated, exercised; "intended" means described in documents but not found in code.

## Diagram

```
 keystroke ─▶ EditorComponent ─▶ TurboFrame.changed()
                                     │ (Java buffer only)
                                     ▼
                       AICompletionController.changed()   EDT
                       ├─ dismiss ghost on every caret/version change
                       └─ Swing Timer (delayMillis, default 200 ms, restart per edit)
                                     │
                                     ▼
                       request(): skips if a JDT choice is shown, multi-caret,
                       selection, read-only, or inside the failure back-off
                                     │ Ticket(document, version, caret, config, generation)
                                     ▼  1-thread pool, queue 1, DiscardOldest   worker
                       CompletionContext.build()  (git check-ignore, exclusions,
                       secret scan, JDT signatures, prefix/suffix budget)
                                     ▼
                       HttpAICompletionProvider.stream()
                       ├─ Ollama  : POST /api/show (preflight) + POST /api/generate (NDJSON)
                       ├─ llama.cpp: POST /infill (SSE)
                       └─ Cloud   : POST {base}/responses (OpenAI Responses SSE)
                                     ▼ cumulative text
                       CompletionValidation.usable()  (suffix overlap, braces,
                       statement boundary, literals)
                                     ▼ coalesced invokeLater
                       CompletionCoordinator.ai(ticket) ─▶ EditorComponent ghost text
                                     ▼
                       Tab / Ctrl+Alt+Enter ─▶ acceptInlineCompletion / acceptNextAIWord
```

## Provider inventory

| Provider | Class / method | Endpoint | Protocol | State |
|---|---|---|---|---|
| Ollama | `core/.../ai/HttpAICompletionProvider.streamInternal` | `POST {endpoint}/api/generate` | `prompt` + `suffix`, NDJSON stream, `keep_alive: "2m"`, `num_ctx` 8192, `temperature` 0.1 | Implemented; verified 24 Oct with a dedicated server on port 11439 (`docs/ai-local-validation.md`) |
| llama.cpp | same | `POST {endpoint}/infill` | `input_prefix`/`input_suffix`/`input_extra`, SSE | Implemented; protocol fixtures only |
| OpenAI | same | `POST {endpoint}/responses` | Responses SSE `response.output_text.delta` | Implemented; protocol fixtures only, no live call |
| Anthropic | — | — | — | Absent |
| xAI | — | — | — | Absent |

There is one class for all protocols, created by `AIProviders.create(config, credential)`.

## The 20 questions about local AI

1. **Local providers:** Ollama and llama.cpp (`AIConfig.LocalBackend`).
2. **Both supported:** yes, chosen by a combo box in Options ▸ AI Completion.
3. **Launches a runtime:** no. There is no `ProcessBuilder` for any runtime; the dialog says
   "TurboJ neither downloads models nor starts an inference server".
4. **Connects to an existing server:** yes, only that.
5. **Default endpoint:** `http://127.0.0.1:11434` (`AIConfig` constructor).
6. **Default model:** `qwen2.5-coder:1.5b-base`.
7. **Model name:** free text field "FIM model", saved in `~/.config/turboj/config.json` (`ai.localModel`).
8. **Real FIM:** yes for Ollama (`suffix` field; the model template supplies
   `<|fim_prefix|>`/`<|fim_suffix|>`/`<|fim_middle|>`) and llama.cpp (`/infill`). The preflight refuses
   Ollama models whose template has no `.Suffix`.
9. **Completion endpoint:** `/api/generate` (Ollama), `/infill` (llama.cpp), `/responses` (cloud).
10. **Prefix/suffix:** `CompletionContext.build`: the context block (imports, type names, method
    signatures from a recovered JDT AST, ≤ `contextChars/4`, ≤ 4096 chars) is taken first; of the
    remaining budget (default 12,288 chars) 3/4 goes before the caret and the rest after, cut on
    UTF-16 surrogate boundaries. For Ollama the context block is prepended to the prompt as an escaped
    Java comment.
11. **Output parsing:** NDJSON `response`/`done` (Ollama), SSE `content`/`stop` (llama.cpp),
    typed Responses events (cloud). `CompletionValidation.usable` removes a suffix overlap and
    duplicated closing braces, rejects unbalanced or unterminated text, and cuts at the last `;` or `}`.
12. **Streaming:** yes; partial text is published at statement boundaries at most every 50 ms.
13. **Cancellation:** real. `Cancellation` cancels the `HttpClient` future and closes the response
    stream; Ollama stops generating when the connection closes. Results also carry a ticket checked on
    the EDT.
14. **Debounce:** fixed Swing timer, `delayMillis` (150–2000 ms, default 200), restarted on every edit.
15. **Caret move:** `changed()` dismisses the ghost and cancels the request. Only edits schedule a
    new request, so moving the caret never shows a suggestion.
16. **File change:** the ticket checks `editor.document()`; non-Java buffers call `dismiss()`.
17. **Model kept warm:** only through `keep_alive: "2m"` on each request. After two idle minutes
    Ollama unloads the model and the next suggestion pays a cold load (≈1.1 s measured on M5).
18. **Exit:** `close()` shuts the HTTP client down. The model is not unloaded; no process is owned.
19. **Packaged app and runtimes:** nothing is located. The app speaks HTTP to the configured URL.
20. **Absent model:** `/api/show` returns 404; `execute()` throws
    `"AI provider returned HTTP 404."` without reading the body. Automatic requests only change the
    status to "AI · Local unavailable" and back off 2–30 s; an explicit request shows
    "AI completion unavailable: check provider configuration or connection".

## Cloud (OpenAI)

- Endpoint `POST https://api.openai.com/v1/responses`, `GET /models/{id}` for the test button.
- Body: `model`, `store: false`, `max_output_tokens`, `instructions` (insert only the missing code,
  untrusted data), `input` = JSON with `prefix`, `suffix`, `nearbyContext`; `reasoning.effort: none`
  only for `gpt-6-luna`. Default model `gpt-6-luna`.
- Auth: `Authorization: Bearer`, key read from macOS Keychain bound to the normalized endpoint
  (`MacKeychainCredentialStore`), never logged or stored in settings.
- Context: the same bounded current-buffer context as local.
- Streaming SSE; timeout = `timeoutSeconds` (default 30 s) for connect, headers and stream.
- Errors: any non-2xx → "AI provider returned HTTP n."; refusal/failed/incomplete events → generic.
- Privacy: cloud requires `cloudConsent`; changing endpoint or model clears consent; automatic cloud
  requests are a separate opt-in. Proxies are disabled and redirects are not followed.
- Live inference: not verified (no credentials used).

## Coordination with Java IntelliSense

- JDT is independent: semantic completion, navigation and refactoring never wait for AI.
- `CompletionCoordinator` owns presentation. A JDT inline proposal or an open alternatives popup
  (`hasSemanticChoice`) blocks AI requests and `coordinator.ai()` refuses an AI proposal; every
  `clear()` bumps a generation that invalidates in-flight AI tickets. They never show together.
- Tab is a CommandJ command (`completion.accept`) available only with a ghost and no template; AI
  `handleKey` runs before the JDT one. Inline templates win: the `completion` context is not set while
  a template is active.
- Stale results are rejected by ticket: generation, config, document, version, caret, focus.
- AI cannot overwrite a JDT proposal; generated text is validated only syntactically (braces,
  literals, suffix overlap), not semantically.
- Imports and current-file signatures are in the context. Other project classes, Maven classpaths and
  dependency types are not.

## Editor rendering and keys

Ghost text uses the theme token `intelligence.aiGhost`; multi-line suggestions insert temporary visual
rows. Keys: Alt+Space request, Alt+Shift+Space request again, Tab accept, Ctrl+Alt+Enter accept next
word, Escape dismiss. Acceptance is one undo step (`applyCompletion`). There is no accept-next-line.

## Security and privacy

Local endpoints must be loopback; cloud endpoints HTTPS. Files matching exclusion globs, Git-ignored
files (`git check-ignore`, run on the worker for every request), secret-looking names and detected
secrets are refused before any request. Diagnostics (timings, counts) are opt-in and stay in memory.

## Tests found

`core/src/test/.../AIProviderTest` (15, loopback fixtures for all three protocols, timeouts, limits),
`CompletionValidationTest` (6), `CompletionContextTest` (8), `app/src/test/.../AICompletionControllerTest`
(12), `AISettingsPolicyTest` (2), `CredentialStoreTest` (3), real-window `AISettingsSmoke`,
`AICompletionSmoke`, `AILocalSmoke`, and the probes `LocalAIProbe` / `AILocalLatencyProbe`.

## Known problems

1. **The reported 404 (reproduced).** The saved preferences point at `http://localhost:11434` with
   `qwen2.5-coder:1.5b-base`. That server is the Homebrew Ollama service, whose only model was
   `hf.co/mradermacher/Qwen2.5-1.5B-GGUF:Q4_K_M`; the October validation had installed the Coder model in
   a dedicated server (`.cache/ai-models`, port 11439). Ollama answers
   `404 {"error":"model 'qwen2.5-coder:1.5b-base' not found"}` to `/api/show` and `/api/generate`;
   TurboJ discards that body and reports a bare "HTTP 404".
2. Every error collapses into one string; nothing tells model-missing from wrong-endpoint.
3. No runtime is started, located, monitored or stopped; no model is downloaded or warmed.
4. `keep_alive` of two minutes makes the first suggestion after a pause slow.
5. `git check-ignore` is spawned on every request.
6. Failure back-off is ad hoc (`retryAfter`), per controller, not per provider; no half-open probe.
7. No accept-next-line, no type-through (typing the ghost's next characters discards it), no
   adaptive debounce.
8. AI may be requested while an inline template is active (Tab still goes to the template).
9. No Anthropic or xAI adapter; one class mixes three protocols.
10. The AI code lives inside `turboj-core` and the app, so it cannot be used outside TurboJ.
