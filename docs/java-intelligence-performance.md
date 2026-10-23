# Java intelligence: measured backend latency

Measured on 2026-10-23 using the implemented JDT semantic service, not a simulated UI. Raw samples are retained in [`benchmarks/results/java-intelligence-2026-10-23.json`](../benchmarks/results/java-intelligence-2026-10-23.json).

## Environment and method

- Apple M5, 10 logical processors, 16 GiB RAM.
- macOS 26.5.1, build 25F80, ARM64.
- Amazon Corretto Java `25.0.4.1+10-LTS`; Eclipse JDT Core 3.44.0.
- One serial semantic-service instance, `System.nanoTime()`, 10 warmup iterations followed by 50 measured calls per repeated workload.
- Percentiles use nearest rank; all 50 samples, including the slowest, are recorded. This is one local run, without CPU affinity or forced garbage collection.

The changed-source workload completes `System.out.pr` inside a small Java `main` method. Each iteration changes the source and document version, forcing a new JDT binding parse. Measurements include the private source-overlay update, parsing, member lookup, sorting and result construction. Cached completion repeats the identical context. Documentation samples resolve `String.substring(int, int)` from the locally attached JDK sources; cached documentation measurements begin with an already resolved symbol and source attachment.

These durations exclude the UI request coalescing interval, the approximately 350 ms hover delay, EDT dispatch, painting, application launch and JVM startup. They are **not** input-to-visible-suggestion or input-to-documentation-window measurements. Large project indexing, simultaneous compilation and many open files are separate workloads.

## Results

| Operation | p50 | p95 | p99 |
| --- | ---: | ---: | ---: |
| Warm completion, changed source | 3.859 ms | 9.246 ms | 22.155 ms |
| Completion, identical cached context | 0.289 ms | 0.582 ms | 1.034 ms |
| Documentation, cached JDK source and symbol | 0.232 ms | 0.711 ms | 1.181 ms |

Single observations, not distributions:

| Operation | Duration |
| --- | ---: |
| First completion, including JDT initialization | 245.440 ms |
| First JDK documentation/source lookup, symbol already resolved | 75.107 ms |
| First exported-platform type index and `ArrayL` completion | 78.519 ms |

The warm small-file completion samples meet the initial 100 ms backend target in this run. That does not establish the same result for arbitrary projects or cold startup. Cached documentation meets the 50 ms backend target here; the deliberate hover delay remains additional.

## Reproduce

From the repository root, with Java 25:

```sh
./mvnw package
java -cp core/target/test-classes:core/target/classes:app/target/turboj-app.jar \
  com.turboj.intelligence.SemanticBenchmark
```

The harness writes `core/target/java-intelligence-performance.json`. Keep both the source tree and the built class files aligned before comparing measurements. The harness checks the actual `println()` proposal, the resolved JDK symbol and the imported `java.util.ArrayList` proposal; it does not measure a successful empty operation.

## What is being measured

All four editing capabilities share `JavaIntelligenceService` and one `JdtJavaIntelligenceService`. Standalone JDT AST bindings supply receiver types, members, overload identities and declaration relationships. The editor's bounded asynchronous coordinator constructs the service lazily on its worker thread. Parsing, local class indexing, attached-source lookup and private temporary source staging stay off the EDT. Referenced source-file timestamps invalidate semantic caches when files change externally. Current in-memory buffers are staged under a private temporary source root; existing user files are never overwritten by analysis. Temporary source and attachment files are removed when the service closes.

This integration deliberately uses JDT's public [standalone AST binding environment](https://help.eclipse.org/latest/ntopic/org.eclipse.jdt.doc.isv/reference/api/org/eclipse/jdt/core/dom/ASTParser.html). The [ICompilationUnit code-assist and working-copy APIs](https://help.eclipse.org/latest/topic/org.eclipse.jdt.doc.isv/reference/api/org/eclipse/jdt/core/ICompilationUnit.html) require an established Java model; the application does not create a hidden Eclipse workspace or falsely claim to call `codeComplete()` against arbitrary strings.

Completion derives proposals from resolved Java types and lexical scopes. New top-level type proposals come from actual local sources, dependency class files and exported JDK packages, and are verified by JDT bindings. The editor receives replacement ranges, caret placement and any import edit together. A conflicting import produces an explicitly qualified type name. Method proposals insert no guessed argument values.

## Scope and remaining limits

- This is a conservative standalone semantic completion implementation, not full feature parity with Eclipse, IntelliJ or a JDT language server.
- Top-level type discovery starts after an uppercase prefix of at least two characters. Package-name completion and a persistent whole-workspace symbol index are not implemented. Type indexing is local and bounded.
- Member completion, exact overload navigation, generics, inherited members, local-variable scopes and simple positive `instanceof` branches are covered by tests. More complex pattern-flow scopes are withheld rather than producing an invalid local-variable proposal.
- Javadoc comes from project sources, available local source JARs and JDK source archives. Binary attached-Javadoc HTML is not fetched through an Eclipse Java-model attachment API. Missing source gives the resolved signature and an explicit missing-source state.
- Documentation is sanitized plain text with readable paragraphs, lists and code text. No scripts or remote images execute or load. Rich HTML links/tables and complete `{@inheritDoc}` merging are not implemented; empty overriding documentation can inherit an available parent description.
- No source archive is downloaded on hover. Dependency configuration and source attachments must already exist locally. Go to Implementation and remote source retrieval are not exposed as working actions.
- These backend measurements do not establish UI latency under concurrent compiler/indexing load. Use the native GUI acceptance run and its recorded input-to-paint samples for that separate claim.
