# Java intelligence

TurboJ shares one offline Java semantic service across completion, declaration navigation, documentation and argument hints. It uses Eclipse JDT 3.44 bindings against the active buffer, other open buffers, local source roots and a resolved local classpath. Java offsets are UTF-16 offsets, matching the editor document model.

## Why the standalone binding API

JDT's `ICompilationUnit.codeComplete()` and `codeSelect()` belong to the Eclipse Java model; they are not standalone operations on arbitrary source strings. Establishing that model would add a managed Eclipse resource workspace. TurboJ instead uses JDT's public `ASTParser` with `setEnvironment`, binding resolution and recovery. The same resolved types and method bindings provide receiver members, overload identities, declarations and signatures. Completion is intentionally conservative when an incomplete expression has no trustworthy receiver type.

This is a full recovery parse with cached results, not a claim of incremental Java parsing or full IntelliJ completion parity. The parser and source loading stay on a serial semantic worker. The editor validates request identity, source version and caret/hover position before applying a result. Local project configuration contributes to semantic cache keys and is inspected on subsequent requests.

API references: [JDT ASTParser](https://help.eclipse.org/latest/ntopic/org.eclipse.jdt.doc.isv/reference/api/org/eclipse/jdt/core/dom/ASTParser.html) and [ICompilationUnit](https://help.eclipse.org/latest/topic/org.eclipse.jdt.doc.isv/reference/api/org/eclipse/jdt/core/ICompilationUnit.html).

## Local project context

Opening a Java file discovers its package root and conventional Maven/Gradle source and compiled-class directories. The nearest `.turboj`, `pom.xml`, Gradle build file or `.git` marks the local project root. This is source/classpath discovery; it does not import an effective Maven or Gradle model or infer transitive dependencies from dependency names.

Source discovery stops at nested project markers so independent examples or
checkouts do not become duplicate compilation units in the current workspace.
Explicit source roots, including roots supplied by a Maven reactor model, remain
included. Retained open buffers follow the same boundaries.

Optional UTF-8 files relative to that root configure semantic assistance:

| File | Contents |
|---|---|
| `.turboj/source-roots.txt` | Source directories, one per line |
| `.turboj/classpath.txt` | Already resolved JARs or compiled-class directories |
| `.turboj/source-archives.txt` | Locally available source archives |
| `target/turboj-classpath.txt` | Cached Maven dependency classpath |
| `build/turboj-classpath.txt` | Cached Gradle dependency classpath |

Paths may be absolute or relative to the project root. Blank lines and lines beginning with `#` are ignored. Classpath files also accept the operating system's path separator, allowing Maven's existing `dependency:build-classpath` output. Files are limited to 64 KiB and 4,096 entries. Missing paths generate warnings; malformed files do not prevent basic source assistance. Wildcards and remote URLs are not accepted. TurboJ does not invoke Maven/Gradle, install libraries, or download source in response to hovering or typing.

For a Maven project, generate an explicit classpath using the project's existing Maven setup when desired:

```sh
./mvnw dependency:build-classpath -Dmdep.outputFile=target/turboj-classpath.txt
```

That explicit command may resolve dependencies through Maven. TurboJ only reads its output. These paths configure semantic assistance; the current single-file compilation workflow is separate and does not constitute a multi-file build system.

## Source and documentation

Project source and open buffers supply declarations and Javadoc. Existing sibling `artifact-sources.jar` files and explicitly configured source archives supply library source. For the JDK, TurboJ looks for `lib/src.zip` in its runtime; packaged runtimes may omit it. It also checks a matching installed JDK in the standard macOS locations, `JAVA_HOME`, or the explicitly supplied `-Dturboj.javaHome=/path/to/jdk` property. No subprocess is launched to locate source.

Source from archives is read-only. If no attached source exists, the UI shows resolved signature metadata and an explicit missing-source message. No declaration body or documentation is invented. The documentation preview renders safe plain text; it cannot execute markup or load remote resources. Rich Javadoc HTML layout and automatically retrieving source attachments are not implemented.

## Manual example

Open [`examples/intelligence/Main.java`](../examples/intelligence/Main.java). Its two calls to `Calculator.add` exercise integer and String overloads. The integer declaration calls `MathUtils.sum`, giving a three-file navigation chain. `String.substring` exercises JDK source and documentation lookup. Project Javadoc includes genuine parameter names and return descriptions.

The example's `.turboj/source-roots.txt` marks it as an independent project,
separate from the loose example files and the Maven/plain-Java samples.

The native acceptance harness, `dev.turboj.app.IntelligenceSmoke`, opens a real desktop window and exercises ghost text, Tab acceptance and undo, Escape, completion alternatives, both method overloads, three-file navigation, retained unsaved buffers, JDK source and documentation, delayed project-documentation hover, parameter hints, incomplete-source recovery and typing with analysis active. It also switches among Classic, Amber, Dark and Light while a suggestion is visible. This harness is separate from headless JUnit tests; it must be run on a desktop.

## Current boundaries

- Completion uses resolved Java types and scope. A missing or recovered receiver binding suppresses proposals instead of inventing a target. Type-name indexing covers top-level source/JAR/JDK classes for uppercase prefixes of at least two characters; it is not full JDT code-assist parity.
- Local source/JAR/JDK attachments are supported. Downloading source, importing generated binary Javadoc HTML and Go to Implementation are not implemented.
- Documentation is safe plain text with signature metadata. Rich Javadoc formatting, clickable inline links and a full documentation browser remain later work.
- Conventional source-root discovery and explicit dependency classpaths do not replace effective Maven/Gradle model import or a multi-file incremental build graph.
- The native measurements distinguish cold initialization, warm changed-source completion, cached lookup and UI delivery. Hover delay and hardware input-to-photon latency are separate from the reported timings.

## Desktop validation on 23 October 2026

The native harness passed on macOS 26.5.1, Apple M5, Java 25.0.4.1. Its keyboard checks exposed a real macOS popup-binding issue: Escape needed a binding on the focused documentation component, alongside explicit return of focus to the editor. The final run verified Escape closes the documentation window, restores editor focus and permits F12 navigation to read-only JDK source.

| Measured path | Samples | p50 | p95 | p99 |
|---|---:|---:|---:|---:|
| Warm completion, distinct source versions | 30 after 10 warmups | 6.372 ms | 7.926 ms | 9.523 ms |
| Cached JDK documentation | 30 after 10 warmups | 1.929 ms | 4.011 ms | 4.029 ms |
| Edit handler to paint, 1,000-line source | 120 paints | 0.228 ms | 1.281 ms | 1.648 ms |

Semantic UI timings include local context discovery and EDT delivery, observed by a 1 ms Swing timer. They exclude the configured 350 ms hover delay. The typing check used 120 synthetic key events while compilation and semantic assistance were enabled; it excludes the operating system input queue, compositor and screen scanout. These are measurements of this development environment, not cross-machine guarantees.

The same run retained mixed cold/warm controller samples: completion p99 was 131.68 ms across 47 requests, and documentation p99 was 74.87 ms across 42 requests. Warm values above must not be presented as cold-start values. The complete desktop report is in [the recorded GUI results](../benchmarks/results/java-intelligence-gui-2026-10-23.txt). Backend-only measurements have separate conditions and should not be confused with the UI path.
