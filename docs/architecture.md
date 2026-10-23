# TurboJ architecture

TurboJ targets Java 25 without preview features. The desktop is Swing/Java2D with a single custom editor painting surface. The GUI never executes user bytecode.

- **core**: piece table, immutable UTF-16 snapshots, compiler abstraction, in-memory javac, JDT recovery parser and coalesced versioned analysis. One compiler thread owns compiler state. A shared JDT binding service supplies completions, symbol identity, declaration/source lookup, Javadoc and argument signatures.
- **app**: command system, keyboard menus, editor, four JSON themes, asynchronous file operations, bounded output and measured UI paint timings. The semantic controller has a serial, bounded mailbox with at most one pending request per operation. Buffer identity, version and cursor/hover tokens reject stale results. Source snapshots become strings only on that worker. Navigation retains piece tables and undo independently of backward/forward history. The REPL controller has a separate bounded executor; JShell user snippets execute in its remote JVM and Stop/reset kill that process.
- **worker**: independent JVM, versioned inherited private pipes, per-run class loaders, streamed output/input. Stop kills the process; no unsafe thread termination. Isolated runs get a separate process. Class loaders are not a security sandbox.
- **cli**: standalone commands sharing the compiler/worker implementation. Resident GUI IPC is a later milestone.
- **benchmarks**: repeatable latency samples; timings distinguish compile, worker dispatch/execution and editor mutation/paint. Targets are not claims.

An edit publishes a document version, repaints affected text, then queues analysis. Snapshots capture pieces without copying the full source on each key. String materialization and all compiler work occur on background threads. Results are checked again against the current version on the event dispatch thread. Run compiles the submitted buffer if needed; saving is unnecessary. A later edit invalidates the pending run request.

The first implementation deliberately uses a linear piece list and incrementally maintained line offsets. Project dependency/ABI tracking is not yet claimed. Fast Run uses trusted local code; JVM global state is not fully reset. No automatic code execution, telemetry, account, or network activity occurs at application startup.
