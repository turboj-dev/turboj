# CLI and optional protocol adapters

TurboJ uses the Java API in-process. The CLI, LSP, MCP and external editor packages are optional clients of the same `RefactorJRuntime`; they do not contain a second semantic implementation. Java 25 is required for the engine. IntelliJ embeds the engine directly when its host runtime supports Java 25; a resident process is available for older compatible runtimes. VS Code uses one resident engine per project.

Build with `./mvnw -f refactorj/pom.xml verify`, then `python3 refactorj/scripts/package.py`. The independently runnable distribution is `refactorj/target/refactorj-0.1.0.zip`; unpack and add its `bin` directory to PATH. In a standalone checkout use Maven and pass your Maven repository with `--repository` to the packaging script. The distribution preserves Eclipse bundle JARs and their embedded notices inside the runtime; no Eclipse UI or SWT bundle is loaded by the engine.

## Command line

```
refactorj list --root /project
refactorj inspect src/A.java:4:15 --root /project
refactorj rename --symbol src/A.java:4:15 --to total --root /project > plan.json
refactorj extract-method --file src/A.java --range 4:5-7:6 --name calculate --root /project
refactorj change-signature --request request.json --root /project
refactorj validate plan.json --root /project
refactorj apply plan.json --dry-run --root /project
refactorj apply plan.json --root /project
refactorj history --root /project
refactorj undo TRANSACTION_ID --root /project
refactorj redo TRANSACTION_ID --root /project
refactorj recover --root /project
```

All transformations produce JSON previews. Only explicit apply, undo, redo and recover commands modify source. Exit codes: 0 success, 1 conflict/stale preview, 2 invalid input or operational failure. `--source-root` and `--classpath` accept lists separated by the platform path separator. Coordinates on the CLI are one-based lines and UTF-16 columns. JSON locations use absolute or root-relative files and zero-based half-open UTF-16 offsets. The typed request properties match `Requests.java`; enum values use uppercase Java names. `--json` is accepted for automation; structured output is already JSON by default.

Serialized plans include source snapshots and must be treated as source code for access control. Apply regenerates the original typed request and compares the computed edits/file operations before writing. Modified plans cannot inject arbitrary replacements. Within a resident session, source revisions must match; after a CLI restart, every source's full content and workspace configuration must match before rebasing the new session's revisions. This permits reviewed plans to be applied across processes without pretending process-local version counters are durable identifiers.

Public symbol IDs are opaque, versioned and scoped to the normalized workspace root. They remain stable when the same source workspace is reopened, so member selections from `inspect` can be used in later CLI processes. A rename, move or declaration change can change an identity; snapshot validation still applies. IDs from another root, raw Eclipse handles and unsupported ID versions are rejected. `ImplementMethods.methodIds` accepts the same public method IDs as other member-selecting requests. Consumers must not parse or construct these IDs.

## LSP

Launch `refactorj-lsp --root /allowed/project`. One explicitly allowed local root is required. The server implements LSP 3.17 Content-Length framing with UTF-8 transport, UTF-16 positions, initialize/shutdown, incremental didOpen/didChange/didClose, prepareRename/rename, definition, references, workspace/document symbols, codeAction/resolve and executeCommand. Open buffers override disk content. Closed source changes are refreshed before semantic requests. Pending requests can be cancelled with `$/cancelRequest`; semantic work runs on a bounded serial worker, with a separate input reader.

Commands: `refactorj.catalog` returns the public typed request field catalog; `refactorj.prepare` takes one typed request; `refactorj.validate` takes one prepared envelope; `refactorj.workspaceEdit` validates and converts it to a versioned WorkspaceEdit. The client reviews/applies the edit and owns its undo transaction. The LSP server never saves or executes source. File creation, deletion and noncyclic moves are supported; cyclic/overwriting moves are explicitly rejected by the LSP adapter and remain available through the native transaction layer. Operations needing names or signature parameters require client input; the adapter does not invent those values.

## MCP

Launch `refactorj-mcp --root /allowed/project` for read-only preparation. Mutation is enabled only by an explicit host launch with `--allow-write`. Use newline-delimited stdio JSON-RPC. This implementation targets the official **2026-07-28** specification: there is no initialize handshake. Every request supplies `params._meta` keys `io.modelcontextprotocol/protocolVersion`, `io.modelcontextprotocol/clientInfo`, and `io.modelcontextprotocol/clientCapabilities`. `server/discover` returns supported versions and tools capability; responses include `resultType: "complete"`. Legacy handshake clients are rejected with a version-specific diagnostic.

Tools: symbols, references, resolve, available, prepare, validate, preview, apply, undo and redo. `refactorj_prepare` exposes all typed operations through a discriminated `oneOf` input schema derived from the public request records. Apply/undo/redo default to `dryRun: true`. A mutation also requires `dryRun: false` and exact confirmation `APPLY <plan.id>`, `UNDO <transactionId>` or `REDO <transactionId>`. The integrating host must obtain explicit user approval before supplying confirmation; tool arguments alone cannot establish that a human has approved an action. Starting the server read-only provides the enforceable default boundary. There is no shell tool, network listener, telemetry or arbitrary root switching.

Protocol messages are bounded to 16 MiB; work queues hold at most 64 requests. Oversized/malformed frames are rejected. Protocol state is not inferred from previous MCP requests; warmed semantic caches are implementation state only. Preview/validation never run automatic journal recovery. See TRANSACTIONS.md for explicit recovery and durable disk undo.

References: [official MCP discovery](https://github.com/modelcontextprotocol/modelcontextprotocol/blob/main/docs/specification/2026-07-28/server/discover.mdx), [MCP versioning](https://github.com/modelcontextprotocol/modelcontextprotocol/blob/main/docs/specification/2026-07-28/basic/versioning.mdx), [MCP tools](https://github.com/modelcontextprotocol/modelcontextprotocol/blob/main/docs/specification/2026-07-28/server/tools.mdx), [LSP 3.17](https://microsoft.github.io/language-server-protocol/specifications/lsp/3.17/specification/).

## Verification

Surefire exercises transaction invariants and actual stdio JSON-RPC framing, invalid inputs, UTF-8 byte lengths, UTF-16 positions, cancellation, current MCP metadata and operation schemas. Failsafe runs real Eclipse-backed CLI prepare/apply/undo, tampered/stale plans, LSP unsaved-buffer rename and MCP authorization/apply/undo. Tests never use live credentials. External editor host tests are separate; their result files record the installed host version. IntelliJ requires active Local History for resource undo. VS Code moves/deletes require explicitly saved affected files because that host persists dirty working copies during resource operations; text-only operations retain unsaved-buffer support. VS Code resource undo restores the path and editor text but leaves that text dirty; an explicit Save persists the undo to disk. Do not infer host compatibility from protocol tests alone.
