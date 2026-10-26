# TurboAI

Local-first code completion runtime for Java tools, extracted from TurboJ so that it can be used inside
and outside the IDE, like CommandJ, RefactorJ and MavenJ.

| Module | Contents | Dependencies |
|---|---|---|
| `turbo-ai-core` | Provider adapters (Ollama, llama.cpp, OpenAI Responses, Anthropic Messages, xAI Chat Completions), typed errors, sanitized diagnostics, circuit breakers, fallback chains, `LocalModelManager`, model catalog, completion probe | JDK, Gson |
| `turbo-ai-java` | Bounded Java context (`CompletionContext`) and the JDT-based suggestion filter (`JavaSuggestionFilter`) | `turbo-ai-core`, Eclipse JDT core |
| `turbo-ai-cli` | `turbo-ai status · models · pull · test · complete · serve` | `turbo-ai-java` |

```sh
./mvnw -o verify                      # from the repository root: ./mvnw -o verify -pl turbo-ai/turbo-ai-cli -am
java -jar turbo-ai-cli/target/turbo-ai-cli-0.1.0-all.jar status
java -jar turbo-ai-cli/target/turbo-ai-cli-0.1.0-all.jar complete --file Calculator.java --offset 120
java -jar turbo-ai-cli/target/turbo-ai-cli-0.1.0-all.jar serve --endpoint http://127.0.0.1:11439 --runtime managed
```

## Embedding

```java
var manager = new LocalModelManager(stateDirectory, status -> System.out.println(status.summary()));
AIConfig config = AIConfig.defaults().withMode(AIConfig.Mode.LOCAL)
        .withLocal(AIConfig.LocalBackend.OLLAMA, "http://127.0.0.1:11434", "qwen2.5-coder:1.5b-base",
                AIConfig.LocalRuntime.AUTOMATIC);
manager.configure(config);                       // detect, reuse or start, check, load, warm — off the caller's thread

try (AICompletionProvider provider = AIProviders.create(config, null, manager::ready)) {
    var request = CompletionContext.build(file, source, version, caret, config, false, new Cancellation());
    provider.stream(request, text -> show(CompletionValidation.usable(request, text, false)), cancellation);
}
manager.close();                                  // stops only what it started
```

- `AIProviders.create` returns a `FallbackChain`: one route per provider, each behind a `CircuitBreaker`.
  Local mode: the model, the fallback model, and the cloud only when `cloudFallback` was chosen with
  consent. Cloud mode: the cloud model, then the local model when it is ready.
- Every failure is an `AIException` with an `AIError` (`MODEL_NOT_FOUND`, `RUNTIME_NOT_RUNNING`,
  `UNSUPPORTED_ENDPOINT`, …) and a message for the user that never repeats a provider's response body.
- `AIDiagnostics.global()` keeps the last 32 exchanges in memory, sanitized: request fields that carry
  source become their length, keys never enter.

## Guarantees and limits

- Local endpoints must be loopback; cloud endpoints HTTPS. No proxy, no redirects.
- No cloud request in local mode unless `cloudFallback` and `cloudConsent` are both set.
- A server TurboAI did not start is never stopped, restarted or reconfigured. A started one runs under a
  watchdog that stops it when the owning JVM exits, even after `kill -9`.
- Models are downloaded only through `LocalModelManager.download()`, after a disk-space check.
- The suggestion filters are heuristics on syntax and names; they do not prove a suggestion correct.

Documentation: [TurboJ AI docs](../docs/ai/). License: MIT.
