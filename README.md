# TurboJ

A small, keyboard-first Java 25 desktop editor for macOS. DOS blue, a source buffer, real compiler diagnostics and one command to run. Swing/Java2D, no browser shell or telemetry.

**Official website: [turboj.dev](https://turboj.dev)**

![TurboJ running Hello World in its native Swing interface](docs/images/turboj-classic.png)

## Run

```sh
./mvnw package
./turboj
./turboj examples/Hello.java
```

Development requires **JDK 25**; Maven Wrapper downloads pinned Maven/dependencies on the first build into `.cache/`. The packaged desktop includes its runtime and compiler.

The initial buffer is a working Hello World. New installations use the IntelliJ macOS keymap: **Ctrl+R** runs, **Cmd+S** saves, **Cmd+O** opens, and **Cmd+Shift+A** finds an action. Existing installations retain TurboJ Classic, including **Cmd+R / Ctrl+F9** for Run and **F2 / F3** for Save/Open. Options → Keymap selects or customizes IntelliJ, Eclipse, VS Code or Classic bindings independently from the visual theme. Menus show the active shortcuts.

Run works from unsaved buffers. Open Run → Java REPL for expressions and declarations without a main method. For `Scanner(System.in)`, type directly after the program prompt in the output pane and press Enter. The execution timeout pauses while the program waits for stdin. Use Run → Stop to terminate a program.

Select text in the output pane and use **Cmd+C** or **Edit → Copy** to copy it.
Clipboard commands follow the focused text area; arriving output preserves the selection.

[Example projects](examples/README.md) include the same small calculator with and
without Maven, each with two runnable JUnit tests.

Open a Maven folder or `pom.xml`, wait for **Dependencies Resolved**, then use
**Run → Run** and **Test → Run All Tests** directly. New imports download from
Maven Central or user-configured mirrors; explicit offline preferences remain
respected. Run discovers the project's main class and offers a choice if needed.

Options → **Auto Run on Save** and **Auto Test on Save** are independent, off by
default, and remembered. Saving Java source first writes the file, then compiles
the workspace. Only a successful, still-current compilation can launch the
enabled actions. Maven projects must have resolved dependencies and execution
trust. **Auto Test on Changes** remains a separate Test-menu option.

Options → **Show Pressed Shortcuts** briefly displays the executed shortcut in
the editor's bottom-right corner using the current theme's accent. It is off by
default. **Smaller Font / Larger Font** scale the whole interface, including
menus, output and embedded dialogs. Workspace and activity information share one
compact status line at the bottom; details remain available in its tooltip.

## Implemented

- A custom Java2D editor backed by a piece table, incremental line index and immutable snapshots; selection, Unicode/UTF-8, CRLF, clipboard, undo/redo, indentation, search, comments, bracket matching and input-method composition.
- Keyboard menus and command palette, a compact searchable catalog with 100 curated editor themes plus Turbo Classic, Modern Dark and Modern Light, six live previews and an ASCII random-theme drum. Shared native/site JSON palettes, font sizes, save protection for external file changes, atomic writes and recoverable buffers.
- Independent [CommandJ](commandj/README.md) registry, four macOS keymaps, chord shortcuts, conflict-aware customization and a local command palette. Dialogs, previews and auxiliary editor surfaces stay inside the main frame.
- Native [RefactorJ](refactorj/README.md): semantic previews, source-version checks, cross-file edits and workspace undo/redo. The editor and refactoring engine share the same semantic workspace.
- [TurboTest](turbotest/README.md): current-method/class/project JUnit execution, failed-test reruns, theme-colored results and optional automatic testing on changes or saves, using the existing resident worker.
- Native in-process [javafmt](javafmt/README.md): Code → Format Document / Selection, optional Format on Save, versioned minimal edits and one-step undo.
- Optional native AI completion: multiline ghost text, semantic-completion priority, cancellation and insertion-only acceptance. Local Ollama/llama.cpp FIM and opt-in OpenAI Responses adapters; endpoint-bound macOS Keychain credentials. Disabled by default. See [setup and validation boundaries](docs/ai-completion.md).
- Offline JDT semantic assistance: inline ghost text, completion alternatives, declaration navigation, real Javadoc, argument hints and source history. Menus show the selected keymap's bindings. Navigation retains unsaved buffers and undo. Attached JDK/library sources open read-only; see [Java intelligence](docs/java-intelligence.md) for local project classpaths and limits.
- Background JDT Java 25 recovery analysis and in-memory javac compilation, version checks, coalescing and bounded successful-source cache. Diagnostics navigate through **Compile → Go to first error**.
- Persistent independent JVM for Fast Run, fresh class loader per run, Isolated Run, streaming stdout/stderr and stdin, timeout, Stop, crash recovery and bounded output. Java 25 compact sources and instance main methods are supported.
- An integrated remote JShell console with multiline submission, history, completion, selection/statement evaluation, Stop/reset, plus standalone `tj` commands; see [CLI documentation](docs/cli.md).
- Unit/integration tests, desktop smoke harness, JMH and end-to-end benchmarks, and macOS packaging scripts.

Maven projects use the independent [MavenJ library](mavenj/README.md) for effective POMs, dependency resolution, profiles, toolchains and reactor graphs. Fast Compile uses current native module outputs; explicit Maven Build runs the real lifecycle. Gradle import and the debugger remain open. Refactoring eligibility, plugin parity and external-host limitations are documented per operation. Read [limitations](docs/limitations.md) before using it on a larger project. Fast Run executes trusted local code and is **not a sandbox**.

## Verify

Tests launch child JVMs and local JShell/JDI sockets. Restricted execution sandboxes must permit those operations; normal local macOS execution was verified. See the [implementation session report](docs/session-2026-10-23.md).

The [1.0.3 Java intelligence verification report](docs/java-intelligence-release-1.0.3.md) records the 98 automated tests, real-window acceptance checks, packaged-runtime validation and measured semantic latency.

The [1.0.4 theme catalog report](docs/themes-release-1.0.4.md) records the shared 103-theme catalog, vertical browsing, fast random-theme drum, 116 automated Java tests and packaged-runtime validation.

The [1.0.5 AI completion report](docs/ai-release-1.0.5.md) separates automated
protocol tests, native editor checks, real local inference and unverified live
cloud access. AI remains disabled until configured in Options.

The [1.0.9 Maven first-open and clipboard report](docs/maven-open-copy-release-1.0.9.md)
records fresh dependency downloads, project Run/Test and output clipboard checks.

The [1.0.10 interaction report](docs/interaction-release-1.0.10.md) records save
automation, themed test output, shortcut feedback and consistent interface sizing.

The [1.0.8 Maven and ASCII interface report](docs/maven-ascii-release-1.0.8.md)
records native Maven project integration, independent MavenJ artifacts and the
unified terminal-style interface.

The [1.0.7 ecosystem report](docs/ecosystem-release-1.0.7.md) records the native
RefactorJ/CommandJ/TurboTest integration, unified window, theme contrast and
`dev.turboj` namespace migration, with executed tests and measured limits.

```sh
./scripts/test.sh
./scripts/benchmark.sh
./tj doctor
./tj run examples/Hello.java
./tj eval '1 + 2'
```

The desktop smoke harness exercises new buffer → compile → run → edit → run, actual diagnostics, themes, Stop, stdin and typing during compilation. After building:

```sh
java --enable-native-access=ALL-UNNAMED -cp 'app/target/test-classes:app/target/turboj-app.jar' dev.turboj.app.GuiSmoke
java --enable-native-access=ALL-UNNAMED -cp 'app/target/test-classes:app/target/turboj-app.jar' dev.turboj.app.IntelligenceSmoke
```

It opens a real window, emits `app/target/gui-smoke.txt` and paints the frame to `app/target/turboj-smoke.png`. This uses synthetic key events; it does not measure the operating system input queue or display scanout.

## Package

```sh
./scripts/package-macos.sh       # dist/TurboJ.app
./scripts/package-macos.sh dmg path/to/new-output-directory
```

Run packaging on the target architecture. The macOS bundle version is `1.0.10` because jpackage requires a positive first component; this remains the `0.1.0-SNAPSHOT` development preview. `TURBOJ_SKIP_BUILD=1` reuses an already verified application JAR. Builds are for local development, without Developer ID signing or notarization. A bundled `jlink` runtime includes compiler, JShell, desktop and debugger API modules; the worker launches its included `bin/java`.

[Architecture](docs/architecture.md) · [Compiler](docs/compiler.md) · [Performance](docs/performance.md) · [Shortcuts](docs/shortcuts.md) · [Themes](docs/themes.md) · [Worker protocol](worker/docs/protocol.md) · [Licenses](docs/licenses.md)

Planned work: [TODO](TODO.md).

## Website

The official static website lives in [`website/`](website/README.md), separately from the desktop build. It uses Astro, TypeScript and local assets. Build and browser/accessibility verification instructions are included there. The original screenshot is preserved and the displayed timings describe that single captured run.
