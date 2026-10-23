# Acceptance evidence

The automated desktop smoke harness opens a real native Swing window and drives the implemented application commands. It is separate from headless/unit tests. This matrix distinguishes supported workflows from future milestones.

| Specification test | Implementation / verification |
|---|---|
| A Hello World | Core compiler, worker and real desktop new-buffer → run checks |
| B Compiler errors | javac unresolved-symbol diagnostics; bytecode withheld; desktop rejects Run |
| C Recompilation | Source/cache tests and desktop edit → new output; duration measured |
| D Live editing | Real visible component and full GUI synthetic key tests under compilation; physical keyboard-to-display latency remains unmeasured |
| E Infinite loop | Worker integration and GUI Stop; subsequent editing/execution verified |
| F Interactive input | Worker, CLI and GUI Scanner input/EOF checks |
| G JShell | Remote session tests for variables, expressions, multiline, reset, cancellation; GUI smoke separately checks explicit submission |
| H Themes | Three live theme switches preserve text/caret; JSON validation/fallback tests |
| I Rapid updates | Compiler supersession tests and GUI latest-version checks |
| J Project tests | Not implemented. Repository JUnit tests verify TurboJ, not user-project test discovery |
| K Project dependencies | Explicit CLI classpath tested end-to-end. Maven/Gradle project import not implemented |
| L Bundled macOS launch | Apple Silicon .app launched through LaunchServices; complete GUI smoke passed using bundled runtime/JAR; DMG checksum and ad hoc bundle signature verified. Intel and notarization remain unverified |

Run commands, raw benchmark boundaries and remaining performance measurements are documented in README.md and performance.md. No skipped milestone is represented by a fake menu action.
