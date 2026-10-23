# Implementation status and limits

This release implements the usable single-file desktop milestone, continuous compilation, the persistent worker and an integrated remote JShell console. It contains initial CLI and benchmark work; it does not claim completion of the entire product specification.

## Scope

- Multiple source buffers retained during navigation, with one visible editor and one compilation unit per Make/Run. The semantic service reads local source roots and explicit resolved classpaths; this is not a Maven/Gradle project build/import, module-path UI, external dependency resolver or ABI-aware dependent recompilation. The compiler source cache is exact content/name reuse, not a dependency graph. GUI always uses Java 25.
- Inline completion, declaration navigation, Quick Documentation and parameter hints share JDT bindings. They are conservative on malformed or unresolved source; full IDE completion parity, Go to Implementation and automatic source downloads are not claimed. Javadoc is safe plain text, with paragraphs and tag content, rather than a complete HTML renderer. See [Java intelligence](java-intelligence.md) for source attachment and classpath setup.
- JDT does a recovery-enabled full AST parse off the EDT; it is not a truly incremental parser. javac tasks use fresh compilation contexts with a resident file manager. Annotation processors are disabled.
- JShell runs in the GUI bottom pane and CLI. Selection evaluation and AST-based statement evaluation are explicit actions; local variables inside an edited class are not automatically imported into JShell. JLine terminal editing, resident CLI IPC, project JUnit execution and JDI debugger are later work. Tests in this repository test TurboJ itself; SampleTest.java is a future project-support example.
- Source names for unsaved public types are inferred from JDT. Multiple entry points have no selection dialog. Some inherited-main cases and unusual launch forms may need broader testing.
- Menus are lightweight, keyboard-navigable Swing menus styled to the DOS palette. The source surface is custom Java2D. DOS font fidelity, exhaustive accessibility, bidirectional scripts and complex grapheme clusters need further validation. Offsets are UTF-16; caret deletion preserves surrogate pairs, but the component is not a complete Unicode grapheme editor.
- The piece list is linear; highly fragmented documents and extremely long lines can cost more. The additions buffer is not compacted; undo retains at most 2,000 edits. Input is capped at 32 MiB per source file. Large pastes may still consume significant EDT time.
- Plain console pipes support Scanner/input; there is no PTY or ANSI terminal emulator. The GUI display is bounded to approximately 200k characters; each worker run defaults to 1 MiB output, 256 MiB heap and a 30-second execution budget, excluding blocked stdin waits. Worker API supports other limits; the GUI preferences do not expose them yet.
- Fast Run uses fresh class loaders in a reused JVM. It detects several global-state changes and lingering threads, but cannot reset every JVM-global state or native library. When process inspection is restricted it conservatively retires the worker. System.exit/Stop/timeout kill the process. Isolated Run is a process boundary, not a security sandbox. Programs retain the user's normal filesystem/network permissions.
- Atomic save checks external content before replacing; a narrow external-write race remains between check and replacement. Navigation retains unsaved buffers and undo, and Quit asks about each changed buffer. Crash recovery remains best-effort for the last active buffer; it does not restore all retained buffers or cursor/source-path metadata. Do not use concurrent TurboJ instances on the same recovery directory.
- Settings persist only a subset of the planned preference schema. No signing, notarization, updater or telemetry. Intel packaging is scripted but has not been validated on Intel hardware.

## Next vertical slice

Add a real multi-file project model and JUnit Platform execution, then connect CLI commands to the resident backend. Dependency invalidation must be proven by tests before calling project builds incremental. The debugger follows those foundations.

The performance report distinguishes measured paths from unmeasured targets. No comparison with other IDEs is claimed.
