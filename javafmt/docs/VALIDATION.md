# Executed validation — 24 October 2026

JDK 25 / macOS arm64. Commands and results below were actually executed.

| Scope | Result |
| --- | --- |
| Independent parser tests | 9 passed |
| Independent core tests | 13 passed |
| Independent CLI tests | 9 passed |
| Entire containing TurboJ reactor, `./mvnw -q verify` | 213 passed, 0 failures/errors/skips |
| Extracted standalone folder in a temporary directory, own `./mvnw verify` | Passed without any TurboJ sources or parent POM |
| Own parser/core/benchmark source formatted with `javafmt -w`, then `--check` | Passed; second run made no changes |
| TurboJ actual GUI FormattingSmoke, including 1.0.6 bundled runtime | Passed document/selection, all themes, undo/redo, save/failure, compile/run |
| IntelliJ IDEA 2026.1.3 actual isolated host | 18 assertions passed |
| VS Code 1.138.0 actual isolated extension host | 11 checks passed |
| Shaded CLI notice audit | 54 resource files verified byte-for-byte |
| HTML core API documentation via JDK `javadoc` | Generated successfully |
| JMH warm time/GC and independent comparative harness | Completed; raw results retained |
| Packaged CLI with bundled runtime | Version/stdin/check/diff/write/protocol/safety checks passed |

The 13 core test methods include 240 seeded generated variations, original Java
25 syntax fixtures, two attributed OpenJDK sources, idempotence/token/comment
checks, actual compile-before/after and literal runtime-value comparisons for
LF/CRLF. Counts refer to JUnit test methods, not the number of generated cases.
TurboJ batch-edit tests include 300 generated sparse-edit cases.

The initial containing-reactor attempt inside the sandbox failed because JShell
and local AI protocol fixtures could not bind localhost sockets. The complete
rerun with local-process permission passed; no live cloud credentials were used.

The standalone copy reused a Maven dependency/download cache only. It had its
own sources, wrapper and parent POM, no TurboJ module dependencies. This verifies
project independence; it is not a claim that a new Git repository was published.

See [CLI-VALIDATION.md](CLI-VALIDATION.md), the
[IntelliJ host report and instructions](../javafmt-intellij/README.md),
[VS Code host instructions](../javafmt-vscode/README.md), and
[PERFORMANCE.md](PERFORMANCE.md). The graphical IntelliJ Settings/Actions on Save
workflow is not manually audited; the standard formatting service, selected
range, cancellation, undo and crash recovery were tested in the actual host.

No marketplace publication, Intel build, GraalVM binary, formal semantic proof
or true incremental parser is claimed.
