# MCP adapter

MavenJ implements the official MCP revision **2026-07-28** over bounded UTF-8 JSON-lines stdio. This revision uses per-request metadata rather than a legacy initialize handshake. The server exposes `server/discover`, `ping`, `tools/list` and `tools/call`, and handles cancellation notifications. Unsupported versions receive error -32022 with the supported version list.

```sh
mavenj-mcp --root /absolute/project
mavenj-mcp --root /absolute/project --allow-write
```

Every request's `params._meta` contains `io.modelcontextprotocol/protocolVersion`, `io.modelcontextprotocol/clientInfo` and `io.modelcontextprotocol/clientCapabilities`. Discovery advertises the tools capability. Legacy clients must use a compatible adapter; this server does not claim support for the old initialize-only protocol.

Read-only tools: `mavenj_inspect`, `mavenj_effective_pom`, `mavenj_modules`, `mavenj_dependencies`, `mavenj_classpath`, `mavenj_java_version`, `mavenj_source_roots`, `mavenj_test_roots`, and `mavenj_diagnostics`.

`mavenj_prepare_pom_edit` prepares add dependency, update version, remove dependency or add module edits. It returns a plan identifier and exact preview without writing. `mavenj_apply_pom_edit` defaults to dry-run validation. A write requires server startup with `--allow-write`, `dryRun:false` and the exact `APPLY <planId>` confirmation supplied only after the host obtains user authorization. The server bounds retained previews, checks current source/encoding and confines paths to the configured real workspace root. Symlink escapes and stale plans are rejected.

There is no arbitrary shell or Maven-goal tool. Inspection does not execute wrapper/plugins/processors. Network permission is configured at server startup, not broadened through tool arguments. Source comments and POM content are data, never instructions to the agent.

Sources: https://modelcontextprotocol.io/specification/2026-07-28/basic/versioning and https://modelcontextprotocol.io/specification/2026-07-28/server/tools
