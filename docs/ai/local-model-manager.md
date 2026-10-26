# Local model manager

`dev.turboj.ai.local.LocalModelManager` (TurboAI, `turbo-ai-core`) owns the local inference lifecycle.
TurboJ creates one at startup with state in `~/.config/turboj/ai/` and applies the saved AI preferences
to it; every change in Options ▸ AI Completion is applied again. All work runs on the manager's own
thread (`turbo-ai-local`) or a virtual thread for downloads; the editor never waits for it.

## Lifecycle

```
OFF ─▶ DETECTING_RUNTIME ─┬─ server answers ───────────────────────────────┐
                          └─ none ─▶ STARTING_RUNTIME (Automatic, Managed)  │
                                     └─ none installed ─▶ UNAVAILABLE       │
   ┌────────────────────────────────────────────────────────────────────────┘
   ▼
 model installed? ── no ─▶ UNAVAILABLE (MODEL_DOWNLOAD_REQUIRED) ─ Download ─▶ DOWNLOADING_MODEL ─┐
   │ yes                                                                                          │
   ▼                                                                                              │
 FIM template? ── no ─▶ UNAVAILABLE (UNSUPPORTED_MODEL_CAPABILITY)                                │
   │ yes                                                                       ◀──────────────────┘
   ▼
 LOADING_MODEL (load with num_ctx 8192, then a one-token FIM warm-up) ─▶ READY ⇄ IDLE (unloaded after keep-alive)
                                                                          │
              health check every 10 s ── owned server died ─▶ restart (at most 3 in 10 min) ─▶ ERROR
```

| State | Status line | Meaning |
|---|---|---|
| `DETECTING_RUNTIME` | AI · Local · Detecting runtime… | Probing `GET /api/version` (Ollama) or `/health` (llama.cpp), 0.8 s timeout |
| `STARTING_RUNTIME` | Starting runtime… | TurboJ launched its own server and waits up to 30 s |
| `DOWNLOADING_MODEL` | Downloading 42% | `POST /api/pull`, progress summed over the layers |
| `LOADING_MODEL` | Loading model… | Model load and warm-up |
| `READY` | Ready | Model loaded; suggestions go out |
| `IDLE` | Ready (idle) | The server unloaded it after the keep-alive; the next suggestion reloads it |
| `UNAVAILABLE` | Model not installed, Runtime not installed, Unavailable | Needs a user action; the detail says which |
| `ERROR` | Error | The runtime failed to start, load, or kept crashing |

While the state is not `READY` or `IDLE`, the completion controller prepares no automatic request (no
context, no Git check, no breaker failures) and an explicit request explains why.

## Runtime ownership

| Preference | Server already answers | Nothing answers |
|---|---|---|
| Automatic (default) | Reused as **external** | Started and **owned** by TurboJ |
| Managed | Refused if it is not TurboJ's ("another server uses port …") | Started and owned |
| External | Reused | `RUNTIME_NOT_RUNNING`; nothing is started |

- An external server is never stopped, restarted, reconfigured or unloaded. The explicit "Unload local
  model" action in settings is the only exception, and it asks first.
- An owned Ollama runs as `ollama serve` with `OLLAMA_HOST=<loopback>:<port>`, `OLLAMA_KEEP_ALIVE`,
  `OLLAMA_NUM_PARALLEL=1`, `OLLAMA_MAX_LOADED_MODELS=1`, `OLLAMA_CONTEXT_LENGTH=8192` and
  `OLLAMA_NO_CLOUD=1`. It uses the user's normal model store (`~/.ollama/models` or `OLLAMA_MODELS`), so a
  model is downloaded once for every Ollama on the Mac. Output goes to `~/.config/turboj/ai/logs/ollama-managed.log`.
- The server runs under a `/bin/sh` watchdog (`ProcessLauncher.watchdog()`) that polls TurboJ's pid every
  second: if TurboJ exits in any way, including `kill -9`, the server gets SIGTERM. Quit, Disable and a
  port change stop it explicitly (SIGTERM, then SIGKILL after 3 s).
- `managed-runtime.json` records the server pid, executable and port. After a crash of the whole session,
  the next start adopts that server only if the pid is alive, runs the same executable and serves the same
  port; otherwise the record is deleted. A process is never killed because it occupies a port.
- A managed llama.cpp server is `llama-server -m <file.gguf> --host 127.0.0.1 --port <port> -c 8192`;
  set Model to the `.gguf` path.

## Runtime discovery

A Finder-launched app has `PATH=/usr/bin:/bin:/usr/sbin:/sbin`, so `RuntimeLocator.system()` checks, in
order: `/Applications/Ollama.app/Contents/Resources/ollama`, `~/Applications/Ollama.app/…`,
`/opt/homebrew/bin`, `/usr/local/bin`, `~/.local/bin`, `~/bin`, then `PATH`. `llama-server` is searched in
the same directories.

## Models

`ModelCatalog` lists the models TurboAI can describe before a download (sizes from the Ollama registry
manifests, 26 Oct 2026):

| Model | Size | License | Role |
|---|---|---|---|
| `qwen2.5-coder:0.5b-base` | 531 MB | Apache-2.0 | Small memory |
| `qwen2.5-coder:1.5b-base` | 986 MB | Apache-2.0 | Balanced, default |
| `qwen2.5-coder:7b-base` | 4.7 GB | Apache-2.0 | Higher quality |

Qwen2.5-Coder 3B is left out: its Qwen Research license does not allow commercial use. Any other Ollama
model works if its template has a `.Suffix` section (real fill-in-the-middle); the manager refuses one
without it and remote (`:cloud`) models.

A missing model shows **Local completion model not installed: qwen2.5-coder:1.5b-base (986 MB, Apache-2.0,
from registry.ollama.ai/library/qwen2.5-coder/1.5b-base)**. Code ▸ AI ▸ Download Local Model… and the
Download button in settings ask first, then download through the server (`POST /api/pull`). Before that
the manager checks the free space of the model store (size + 10 % + 512 MB). Ollama verifies every layer's
SHA-256 before installing it. A cancelled download keeps the layers Ollama already stored and resumes from
them. Alternatives offered by the settings: choose an installed model (listed in the status tooltip),
type a `.gguf` path for llama.cpp, or choose Disabled.

## Keep-alive

Every request and the warm-up send `keep_alive` = the "Keep loaded" preference (default 30 minutes; the
previous release sent 2 minutes, which made the first suggestion after a short pause a cold load). The
warm-up uses the same `num_ctx` as requests: a different value makes Ollama reload the model.

## Bundling a runtime: evaluation

| Option | License | Apple Silicon | Signing / notarization | Size | Updates | Decision |
|---|---|---|---|---|---|---|
| Installed Ollama (`brew`, Ollama.app) | MIT | Native, Metal | Signed by its publisher; TurboJ only launches it | 0 in TurboJ; Ollama 0.40.10 universal archive 181 MB | The user's package manager | **Implemented and verified** |
| Managed installed `llama-server` | MIT | Native, Metal | Same | 0 in TurboJ | The user's package manager | Implemented, **not verified** (llama.cpp not installed here) |
| Bundled `llama-server` in TurboJ.app | MIT (keep notices) | Native build needed | Every embedded Mach-O must be signed with hardened runtime and a secure timestamp for notarization; TurboJ has no signing pipeline yet | Tens of MB with Metal (not measured) | TurboJ releases | Best self-contained next step, not done |
| Native library in the JVM (JNI/FFM to llama.cpp) | MIT | Possible | Same as above, plus a crash takes the editor down | Similar | TurboJ releases | Rejected: a model crash must not kill the IDE |
| Bundled model weights | Apache-2.0 for Qwen2.5-Coder 0.5/1.5/7B | — | — | 0.5–4.7 GB | — | Rejected: download on consent instead |

## Known limits

- Managed llama.cpp, Intel Macs (flagged as "not verified" in the runtime name) and sleep/wake were not
  verified.
- The health check notices a dead server within 10 s; a request in between fails with
  `RUNTIME_NOT_RUNNING` and opens the route's breaker until the restart.
- Ollama cannot report download size before the pull for models outside the catalog.
