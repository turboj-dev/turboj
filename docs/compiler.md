# Compiler and live analysis

TurboJ compiles editor snapshots with the running JDK's `JavaCompiler` API,
`--release 25`, UTF-8, debug information, and `-proc:none`. It does not run an
external `javac` for each edit. Annotation processors are deliberately disabled
because compiling text must not silently execute project code. The standard
file manager stays resident; each compiler task receives a fresh task context.
All generated class files remain in memory.

`SourceSnapshot` carries file identity, text and version. Compiler diagnostics
use one-based line/column positions and UTF-16 character offsets (end exclusive).
Errors never return old bytecode. Success and identical-source cache hits have
distinct flags and measured monotonic durations. The bounded cache includes
file identity and exact source text. This is single-file caching, **not yet a
dependency-aware incremental project build**. Classpath/language level belong
to the compiler instance; construct a new backend when they change.

Entry points are inspected with Java's Class-File API without loading or
initializing user classes inside the editor JVM. Ordinary, compact-source and
instance main declarations are recognized. Execution is delegated to a worker.
Multiple entry points currently select the one matching the source name, then
the first generated candidate; explicit project entry-point selection is future
work. Inherited-only main methods are not yet discovered.

Unsaved buffers use `SourceSnapshot.forBuffer`: a JDT parse selects the public
top-level type name (or the first named top-level type). Compact sources receive
`Untitled.java`. This runs off the EDT and does not confuse comments or quoted
`class` text with declarations.

The serial `ContinuousCompiler` executor keeps one running operation and one
replaceable queued operation. A 35 ms initial coalescing window is a starting
configuration, not a guaranteed analysis latency. It parses with JDT and runs
semantic compilation immediately for a syntactically valid file or a newly
completed AST unit; otherwise it coalesces another 35 ms. Explicit Make/Run
bypasses this scheduling delay. Obsolete tasks are not forcibly interrupted
inside javac; generation checks discard their results. UI consumers also check
document identity/version when applying callbacks on the event thread.

Eclipse JDT Core **3.44.0** provides a Java 25 AST with statement recovery for
incomplete source. Statement ranges come from AST nodes, never semicolon regexes.
This is a full asynchronous parse with recovery, not a claim of incremental AST
maintenance. Semantic authority remains javac. JDT parsing does not resolve
bindings; parser failure leaves javac available.

Version choice is based on Eclipse's [AST API documentation](https://help.eclipse.org/latest/topic/org.eclipse.jdt.doc.isv/reference/api/org/eclipse/jdt/core/dom/AST.html),
which introduces `JLS25` in 3.44, and the [ASTParser contract](https://help.eclipse.org/latest/ntopic/org.eclipse.jdt.doc.isv/reference/api/org/eclipse/jdt/core/dom/ASTParser.html).
JDT is distributed under EPL-2.0. Its required Eclipse platform dependencies
retain their licenses in the packaged dependency jars. No ECJ backend or speed
comparison is claimed until measured separately.
