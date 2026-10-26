# AI autocomplete: troubleshooting

The AI segment of the status line (right side, "AI · Local · …") says what the local runtime is doing;
its tooltip has the runtime, version, model, ownership and the last detail; a click opens the settings.
Code ▸ AI ▸ Test Completion sends one synthetic snippet (never your source) and reports the suggestion and
its latency. Code ▸ AI ▸ Copy AI Diagnostics copies the last provider exchanges, sanitized: methods,
paths, status codes, the server's short error message, field sizes instead of source, no keys.

## "HTTP 404" (before this release) / "model … is not installed"

Cause found on 26 October 2026: the preferences named `qwen2.5-coder:1.5b-base` on the Homebrew Ollama
at `localhost:11434`, which did not have it (the October validation had installed it into a separate
server on port 11439). Ollama answers `404 {"error":"model '…' not found"}`. Now the status reads
**Model not installed** and Code ▸ AI ▸ Download Local Model… (or Download in settings) installs it after
showing size, license and source. From a terminal: `ollama pull qwen2.5-coder:1.5b-base`, or
`turbo-ai pull --model qwen2.5-coder:1.5b-base`.

## Other states

| Status | What to do |
|---|---|
| Runtime not installed | Install Ollama (`brew install ollama` or ollama.com), then Retry (Code ▸ AI ▸ Retry Local AI) |
| Unavailable — "No Ollama server answers" with Runtime = External | Start Ollama, or set Runtime to Automatic so TurboJ starts one |
| Unavailable — "Another Ollama server already uses port …" | Runtime = Managed needs a free port: use External for that server, or another port (for example `http://127.0.0.1:11439`) |
| Model has no FIM | The model's template has no suffix support: use a `*-base` coder model such as `qwen2.5-coder:1.5b-base` |
| Check settings — "did not answer /api/show like an Ollama server" | The endpoint is not Ollama: for llama-server choose Backend llama.cpp |
| Error — "could not load … may need more memory" | Choose `qwen2.5-coder:0.5b-base`, or close memory-heavy apps; the 1.5B model needs about 1.3 GB |
| Error — "stopped 3 times in 10 minutes" | See `~/.config/turboj/ai/logs/ollama-managed.log`; Retry starts it again |
| Loading model… for a long time | The first load after boot reads the model from disk; a 7B model can take tens of seconds |
| Ready, but no ghost text | Suggestions appear after a typing pause in a Java buffer, not inside an inline template, not with several carets or a selection, not while a Java proposal is shown; "no safe insertion" means the filters dropped what the model returned |
| Key rejected / Rate-limited (cloud) | Update the key in settings (stored in macOS Keychain); a rate limit pauses that provider for its Retry-After |

## Command line

```sh
java -jar turbo-ai/turbo-ai-cli/target/turbo-ai-cli-0.1.0-all.jar status              # detect, check, load
java -jar turbo-ai/turbo-ai-cli/target/turbo-ai-cli-0.1.0-all.jar models              # installed and known models
java -jar turbo-ai/turbo-ai-cli/target/turbo-ai-cli-0.1.0-all.jar test                # synthetic completion and latency
curl -s http://127.0.0.1:11434/api/tags                                                # what Ollama has
curl -s http://127.0.0.1:11434/api/ps                                                  # what is loaded, and its memory
```

## Privacy checks

- Local mode never sends source to a cloud provider; the cloud fallback is a separate option that needs
  the cloud consent.
- Excluded globs, Git-ignored files (checked with `git check-ignore`, cached 5 s), file names that look
  secret, and detected secrets block a request before it is built.
- Diagnostics are in memory only and copied only by the explicit command.
