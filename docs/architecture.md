# TurboJ architecture

TurboJ targets Java 25 without preview features. The desktop is Swing/Java2D with a single custom editor painting surface. The GUI never executes user bytecode.

- **core**: piece table, immutable UTF-16 snapshots, compiler abstraction, in-memory javac, JDT recovery parser and coalesced versioned analysis. One compiler thread owns compiler state. A shared JDT binding service supplies completions, symbol identity, declaration/source lookup, Javadoc and argument signatures.
- **app**: command system, keyboard menus, editor, native JSON theme catalog exported into the website build, asynchronous file operations, bounded output and measured UI paint timings. Theme browsing separates a searchable metadata index from six lazily loaded previews and a bounded palette cache. The semantic controller has a serial, bounded mailbox with at most one pending request per operation. Buffer identity, version and cursor/hover tokens reject stale results. Source snapshots become strings only on that worker. Navigation retains piece tables and undo independently of backward/forward history. The REPL controller has a separate bounded executor; JShell user snippets execute in its remote JVM and Stop/reset kill that process.
- **worker**: independent JVM, versioned inherited private pipes, per-run class loaders, streamed output/input. Stop kills the process; no unsafe thread termination. Isolated runs get a separate process. Class loaders are not a security sandbox.
- **cli**: standalone commands sharing the compiler/worker implementation. Resident GUI IPC is a later milestone.
- **benchmarks**: repeatable latency samples; timings distinguish compile, worker dispatch/execution and editor mutation/paint. Targets are not claims.

## Embedded ecosystem

The application and its original modules use the `dev.turboj` Java namespace.
Independent `commandj`, `refactorj`, `javafmt` and `turbotest` projects are ordinary
versioned Java dependencies. CLI, LSP, MCP and external IDE adapters are not part
of TurboJ's internal invocation path. FormatJ retains its `dev.javafmt` API.

RefactorJ's resident Eclipse runtime owns one semantic workspace shared through
the immutable `JavaIntelligence` API. Refactoring preparation uses the same bounded
semantic lane as editor intelligence. Plans carry input versions; candidate
validation, disk journaling and generated-region formatting run off the EDT.
The native host publishes verified edits to retained buffers as a logical workspace
transaction. Undo refuses to overwrite later edits or external file changes.
TurboTest receives a committed source snapshot only after successful application;
its code executes in the existing worker, not the editor JVM.

CommandJ provides one registry for menus, keymaps and the palette. Its optional
Swing overlay host places internal dialogs in the main frame's layered pane.
Completion/documentation and menu/combo popups are also contained by that frame.
The theme supplies menu/control colors independently from the keymap. Modal
internal interactions use Swing's secondary event loop rather than blocking
painting or background-result delivery.

An edit publishes a document version, repaints affected text, then queues analysis. Snapshots capture pieces without copying the full source on each key. String materialization and all compiler work occur on background threads. Results are checked again against the current version on the event dispatch thread. Run compiles the submitted buffer if needed; saving is unnecessary. A later edit invalidates the pending run request.

The first implementation deliberately uses a linear piece list and incrementally maintained line offsets. Project dependency/ABI tracking is not yet claimed. Fast Run uses trusted local code; JVM global state is not fully reset. No automatic code execution, telemetry, account, or network activity occurs at application startup.

## Optional generative completion

`CompletionCoordinator` owns the one inline presentation. Explicit semantic
choices take priority over automatic Java suggestions, which take priority over
AI. The native `AICompletionController` has a separate bounded inference lane;
no parsing, HTTP or credential access occurs on the EDT. It consumes immutable
piece-table snapshots only after scheduling, while the disabled per-edit path
returns immediately. Cumulative streaming output is validated and coalesced
before one EDT delivery, guarded by document identity/version, caret and request
generation. The renderer projects multiline ghost rows without mutating source.

`core/ai` contains endpoint-validated, cancellation-aware HTTP adapters and
current-buffer context/validation. `app/ai` contains orchestration, settings and
lazy macOS Keychain access through Java25 FFM. Providers have no editor or worker
execution API. Accepted suggestions follow the ordinary edit/compile path.
See [AI completion](ai-completion.md) for privacy and live-validation limits.
