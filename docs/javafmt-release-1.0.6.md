# TurboJ 1.0.6 development bundle: javafmt integration

The native Code menu now provides Format Document (`Cmd+Option+L`), Format
Selection (`Cmd+Option+Shift+L`) and optional Format on Save. JavaFMT Style 1
uses the independent `dev.javafmt:javafmt-core:0.1.0` library directly in memory.
Formatting is asynchronous; document/version guards discard obsolete results.
Whitespace edits are applied as one undo transaction with caret, selection and
viewport mapping. Invalid source is kept unchanged and remains saveable.

The independent [javafmt project](../javafmt/README.md) includes its own Maven
build, parser/layout API, CLI/runtime distribution, IntelliJ and VS Code adapters,
style specification, corpus and benchmark reports. It has no dependency on TurboJ.

Validation: 213 Java tests passed in the complete reactor, plus actual native
FormattingSmoke, 18 IntelliJ host assertions and 11 VS Code host checks. The
standalone project also built after extraction outside this checkout. See
[validation details](../javafmt/docs/VALIDATION.md).

JMH whole-file p95 on the available M5 was 0.158 / 1.313 / 14.149ms for the
1/10/100KiB workload families; raw samples, allocation figures and comparison
conditions are in [PERFORMANCE.md](../javafmt/docs/PERFORMANCE.md). Range formatting
still parses the full file. These are measured development workloads, not a
general speed guarantee or a fastest-formatter claim.

The macOS application version is 1.0.6 because jpackage requires a positive
major component. The Maven product remains a development preview. Local app/DMG
artifacts are unsigned and not notarized. Naming clearance and independent
public release of javafmt remain separate decisions.

Packaged-runtime validation also passed: the 1.0.6 app executed FormattingSmoke using its own included Java, formatted/undid/saved sources, then compiled and ran the result. The DMG was generated successfully.
