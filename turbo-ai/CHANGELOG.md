# Changelog

## 0.1.0 — 2026-10-26

- Extracted from TurboJ 1.0.10: Ollama, llama.cpp and OpenAI Responses adapters, bounded Java context, insertion validation.
- Typed errors (`AIError`) and sanitized in-memory diagnostics; a missing Ollama model is no longer a bare HTTP 404.
- Anthropic Messages (`anthropic-version: 2026-10-21`) and xAI Chat Completions adapters.
- Circuit breakers per route and cheapest-first fallback chains; no automatic local-to-cloud fallback.
- `LocalModelManager`: runtime discovery, reuse of external servers, owned loopback servers under a watchdog, consented downloads with a disk check, load and warm-up with the request context size, health checks and bounded restarts.
- JDT suggestion filter for syntax errors and invented variable names.
- `turbo-ai` command line.

Not published to Maven Central.
