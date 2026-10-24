# Dependencies and naming

Original CommandJ implementation: MIT (LICENSE). No Eclipse, IntelliJ or VS Code implementation
source is copied. Shortcut facts are documented with primary sources in docs/KEYMAPS.md.

Runtime: Gson 2.13.2 (Apache-2.0), used by keymap JSON only. Core/palette have no runtime
third-party dependencies. Optional Swing uses the JDK desktop module.
Tests: JUnit 5.13.4 (EPL-2.0). Benchmarks: OpenJDK JMH 1.37 (GPL-2.0 with Classpath Exception),
its Apache-2.0 jopt-simple dependency and Apache-2.0 Commons Math dependency. Maven wrapper
and plugins have their upstream licenses; redistributed runtime dependencies retain JAR notices.

CommandJ is a working project name, not a representation of legal exclusivity. A public web
search for the exact name and Java command library use was performed on 2026-10-24, but this
is not trademark clearance. Check Maven coordinates, GitHub repository availability and
trademark obligations before public release. No Maven Central publication is performed here.
