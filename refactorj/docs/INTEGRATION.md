# Native integration

Coordinates are `dev.turboj.refactorj:refactorj-api`, `refactorj-runtime`, `refactorj-transactions` and optional `refactorj-formatj`, version `0.1.0`. Java 25 is required. Build/install the artifacts locally before using them from another checkout; they are not advertised as published to Maven Central.

A host supplies a `WorkspaceSnapshot`: root, revision, immutable source snapshots including unsaved buffers, source roots and classpath. Offsets are half-open UTF-16 indices. Run potentially blocking semantic work on a bounded serial executor. Cancellation is cooperative. Reuse a warm runtime; do not create one per command. Synchronize new immutable snapshots before analysis. Cache results only while source and project configuration remain unchanged.

The result of preparation is a preview, not permission to overwrite buffers. Recheck versions, identities and content at application. Show conflicts and warnings. Use `WorkspaceTransactions` with an appropriate store. Open editors must be handled as buffers, not saved through `DiskWorkspace` behind the user's back.

TurboJ submits refactoring and IntelliSense tasks through the same `IntelligenceController` semantic lane. `NativeWorkspaceCommitter` checks all captured editor versions on the EDT, commits closed files on its background worker, then publishes open-buffer edits in one logical undo transaction. Open unsaved files remain unsaved, including renamed files. A stale buffer during a disk commit triggers rollback rather than publication. A private journal records interrupted disk/UI transitions and requires explicit recovery. Filesystem races with noncooperating writers remain a documented boundary.

A formatting/refactoring operation is one editor undo step. Later ordinary typing must be undone before that workspace transaction. Caret, selection and breakpoint offsets are mapped through edits. Whole-workspace previews do not run tests or compile user bytecode. After successful publication, TurboJ invalidates diagnostics and, only with Auto Test enabled, compiles the new snapshot and runs JUnit in its existing isolated worker. Version checks prevent old results from replacing current diagnostics.

CommandJ dispatches menus, keymaps and the palette to the same command handlers. TurboJ interactions are hosted inside its main frame; themes change presentation, not keymap or semantic behavior. Library consumers can supply a different UI without bringing Swing into the semantic API.

See [ADAPTERS.md](ADAPTERS.md) for external editor host behavior. IntelliJ resource undo relies on Local History. VS Code requires affected files to be explicitly saved before move/delete operations; resource undo restores editor content and path, and a subsequent Save persists that content. Text-only operations support unsaved buffers in both hosts.
