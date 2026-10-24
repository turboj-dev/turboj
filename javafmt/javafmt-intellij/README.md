# javafmt for IntelliJ IDEA

A native IntelliJ formatting service using JavaFMT Style 1. The plugin is independent of TurboJ. It formats the current unsaved buffer and applies the engine's whitespace edits in one undoable IDE command.

## Build and install

Requirements: Python 3, a JDK 25 compiler, and an installed IntelliJ IDEA **2026.1** SDK. The supported build range is deliberately pinned to `261.*`; other IDEA versions have not been tested.

From the independent `javafmt` project:

```sh
./mvnw -pl javafmt-cli -am package
python3 javafmt-intellij/scripts/build.py
```

On macOS the SDK defaults to `/Applications/IntelliJ IDEA.app/Contents`. Override `IDEA_HOME` to use another installation root containing `lib/app.jar`. The build compiles original plugin code against those real SDK JARs with `--release 21`; it neither redistributes IDEA platform classes nor substitutes SDK stubs. It requires no Gradle service or downloaded IDE distribution.

Install `javafmt-intellij/target/javafmt-intellij-0.1.0.zip` using **Settings → Plugins → gear menu → Install Plugin from Disk**. The ZIP bundles the independent CLI engine under `engine/javafmt.jar`, outside the plugin class path. A **Java 25 runtime** must be available to run that engine. No TurboJ installation is required.

## Use

1. Open **Settings → Tools → javafmt**.
2. Enable JavaFMT for the current project. The default is disabled, so installing the plugin does not replace existing formatting behavior in other projects.
3. Set the Java 25 executable if `JAVA_HOME/bin/java` or `java` on `PATH` does not select it. Leave the engine-JAR field empty to use the bundled engine.
4. Use **Code → Reformat Code** for a document or one contiguous selection. The ordinary IDE shortcut and undo/redo work unchanged.
5. For optional format on save, enable IntelliJ's **Tools → Actions on Save → Reformat code** and select **Whole file**. Disjoint changed-line ranges are explicitly rejected instead of formatting intervening unselected code.

The formatter may expand a selection to a safe enclosing syntax region. Comments, literals and program tokens remain protected by the core's validation. No import optimization is performed. Files containing unsupported syntax or parse errors are left intact with a diagnostic.

## Process and document safety

- One Java 25 engine is started lazily per project and reused over a versioned JSON-lines protocol on standard input/output. There is no network endpoint and no process launch per reformat.
- Executable paths and opt-in are kept in the user's per-project workspace settings, rather than a shared formatter configuration intended for version control.
- Source comes from the in-memory IDE document. Formatting does not require saving and never starts the Java program.
- Request IDs, source versions, modification stamps and source text are checked before applying edits. A newer edit invalidates the result.
- Cancellation invalidates delivery immediately; the engine suppresses cancelled results. JDT parsing itself is not interrupted mid-parse.
- Returned edits are validated as ordered, nonoverlapping, whitespace-only replacements and checked against the returned formatted text.
- The IDE applies only those edits, preserving its document markers and producing one write command. A malformed response never replaces the document wholesale.
- Project disposal or configuration changes stop the owned backend; a subsequent request can restart a crashed backend. Each request has a 25-second deadline.
- The adapter targets Java 21 bytecode so it can coexist with supported IDEA runtimes; the engine remains a separate Java 25 process.

## Actual host tests

```sh
python3 javafmt-intellij/scripts/test-host.py
```

This builds a **test-only** plugin copy, starts the actual installed IDEA in headless mode with temporary config, system, plugins and log directories, and opens a real project. It uses genuine Java PSI, editor documents, the registered `AsyncDocumentFormattingService`, the resident engine, IDE write commands and undo manager. The test starter is never included in the release ZIP. The user's IDE profile is not modified.

Validated on **2026-10-24**, IntelliJ IDEA **2026.1.3 / IU-261.25134.95**, macOS arm64, bundled JetBrains Runtime **25.0.3+9-b329.124**, engine Java **25.0.4.1**:

- **18 passing assertions**: disabled-by-default settings, extension registration, current unsaved Java buffer, canonical indentation, one-step undo and redo, selection isolation, same-process reuse, stale-result rejection, cancellation before execution, syntax-error preservation, the standard asynchronous formatting entry point, and recovery after forcibly terminating the owned engine.
- Build and ZIP creation succeeded against the installed SDK. Final result is written to `target/host-test-result.txt`; detailed isolated-host logs remain under `target/idea-host-*/`.
- The isolated installation emits warnings about unavailable unrelated Ultimate plugins. Java support and this plugin load and execute successfully; the test does not depend on those plugins.

Not yet validated: interactive Settings UI navigation, Actions on Save through the graphical settings flow, remote development/split mode, Windows/Linux hosts, other IDEA release lines, and Marketplace publication/signing. Range support currently accepts one contiguous selection, not multiple disjoint selections.

## API references

- [IntelliJ formatter architecture](https://plugins.jetbrains.com/docs/intellij/code-formatting.html)
- [AsyncDocumentFormattingService](https://github.com/JetBrains/intellij-community/blob/master/platform/code-style-api/src/com/intellij/formatting/service/AsyncDocumentFormattingService.java)
- [AsyncFormattingRequest](https://github.com/JetBrains/intellij-community/blob/master/platform/code-style-api/src/com/intellij/formatting/service/AsyncFormattingRequest.java)
- [Plugin compatibility and dependencies](https://plugins.jetbrains.com/docs/intellij/plugin-compatibility.html)

The installed 261 SDK is the compilation authority; newer upstream signatures are not assumed to exist in it.
