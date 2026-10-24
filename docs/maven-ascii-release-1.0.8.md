# TurboJ 1.0.8 — Maven projects and ASCII interface

Local development build, verified on macOS 26.5.1, Apple M5, 16 GiB RAM,
Corretto 25.0.4.1. Library artifacts remain version 0.1.0; no Maven Central
or marketplace publication is implied.

## Native Maven integration

MavenJ imports effective models through Apache Maven 3.10.0 and resolves
dependencies through Maven Resolver 2.0.24. TurboJ embeds its Java APIs directly.
Parents, BOMs, profiles, settings, toolchains, separate dependency scopes and
reactor modules feed the existing compiler, shared semantic workspace and
TurboTest. Ordinary source edits do not invoke Maven or refresh dependencies.

Fast Compile and explicit Maven lifecycle commands are separate. The application
shows module count, Java version, dependency state and synchronization status.
POM edits have previews and preserve comments, properties, encoding and BOMs.
Project-controlled wrappers and annotation processors require project trust;
processing is an explicit isolated operation, not an import or keystroke side effect.

The complete Maven acceptance record, including actual native/Maven parity,
Auto Test generations and its limitations, is in
[MavenJ validation](../mavenj/docs/VALIDATION.md). That work passed 462 reactor
tests, an additional targeted Auto Test regression and 25 actual GUI checks.
The standalone CLI and MCP distribution was also started as separate processes:
CLI inspection and MCP discovery, 11 tools and four-POM inspection passed.

The independently built archive is `mavenj/target/mavenj-0.1.0.zip`, SHA-256
`b4117037037acd637caaeb0a347839091e0b0e507e8f32a9ac4d81f54b322f12`.
It contains original runtime JARs and verified legal provenance for 40 third-party
dependencies. Source and API documentation JARs are attached by its release profile.

## One ASCII interface

Editor, output, explorer, menus, completion and dialog surfaces share monospaced
text and terminal box-drawing characters. Titles occupy the top border row.
Controls use brackets, textual selection markers and block scrollbars. The
Classic, Dark, Light and catalog themes retain their color definitions.

Dialogs are ordinary panels with a root pane inside the application frame.
They have no native/internal desktop title bar, dragging frame, shadow or
background dimmer. Nested modal focus, Escape, close/cancel and theme-preview
restoration remain functional. Close controls retain keyboard access and the
surfaces expose the accessible dialog role.

Menus use the same text geometry for labels and shortcuts, with separate readable
disabled states. Narrow frames put trailing menus in More and restore their
original objects and order when widened. Build contains the existing native
compile commands alongside explicitly named Maven actions.

## Executed verification

`./mvnw -q -l target/ascii-final-verify.log verify` passed **469 tests** with
zero failures, errors or skips, including the final Auto Test pruning correction
and the ASCII UI changes. Surefire and Failsafe reports contain:

| Area | Tests |
| --- | ---: |
| TurboJ app | 119 |
| Editor/compiler core | 91 |
| Worker | 17 |
| CommandJ | 30 |
| RefactorJ | 114 |
| FormatJ / javafmt | 32 |
| MavenJ | 58 |
| TurboTest | 3 |
| TurboJ CLI | 5 |

The UI tests include 103 catalog palettes, text/shortcut/disabled contrast,
compact menu access, lifecycle/focus, output input handling and layout/caret
preservation during restyling. The earlier 22-test UI subset overlaps this total;
it is not an additional unique test count.

`AsciiChromeSmoke` passed 99 real-window checks with 12 screenshots under
`app/target/ascii-chrome-smoke/`. It compiles and runs an interactive Java program,
submits stdin and checks stdout/stderr in all three primary themes. It checks
the actual 640-pixel menu overflow, keyboard submenu opening, accessible dialogs,
preserved source/caret and one visible native frame. Screenshots were inspected.

`IntelligenceSmoke` also passed against the final application JAR. Its observed
p50/p95/p99 were 15.756/24.919/27.034 ms for warm completion,
2.741/4.682/5.257 ms for cached JDK documentation, and 0.312/0.589/0.859 ms
for edit-to-paint on a 1,000-line source with compiler/semantic work active.
All 120 key cycles and 120 paints completed. These observations exclude the OS
input queue, compositor and display scanout; they are not physical input latency.

Final real-window reruns also passed:

- Overlay: 18 checks, using the **1.0.8 app's own runtime and bundled JAR**.
- Maven: 25 checks on that same bundle, including current-generation Auto Test
  and no Maven launch during editing, formatting or refactoring.
- AI Settings: lifecycle, consent, Cancel and three-theme contrast. Its local
  HTTP fixture needs a full JDK; this does not verify paid-cloud inference.
- Theme Browser: seven scenarios covering asynchronous previews, search/paging,
  cancellation, persistence, random theme selection and reduced motion.
- Refactoring: 18 checks including the ASCII split preview, apply, undo/redo
  and stale-plan rejection.
- Menu: three themes, nested menus, constrained scrolling, combo choices and
  a single native frame, including the Aqua host look and feel.

These GUI checks are separate from the 469 JUnit/Failsafe cases. Their reports
and screenshots are in the corresponding `app/target/*-smoke` directories.

## Measurements and limits

[MavenJ performance](../mavenj/docs/PERFORMANCE.md) records cold/warm imports,
allocations, network resolution and reproducible JMH data. The synthetic
24-module/240-source warm import observed p95 0.583 ms; the fresh-engine import
inside a warm JVM observed p95 189.543 ms. Neither number describes cold downloads.
[Native compiler measurements](../benchmarks/results/MAVEN_NATIVE.md) separate
compilation and EDT queue/edit work from complete UI or physical-keyboard latency.

The development Mac has Java 21 and 25. Actual Java 17 runtime execution and a
clean macOS installation are not verified. Java 17 source/API compatibility is
tested separately. Maven 4.1 model semantics, arbitrary build plugins, custom
Surefire/Failsafe behavior, older-JDK preview compilation and named-module JUnit
patching require the explicitly identified Maven path where native parity is
unavailable. Source/Javadoc downloads are supported; HTML Javadoc-JAR rendering
is not claimed. See [current limitations](limitations.md).

The macOS app is unsigned and unnotarized. Existing user source buffers and
example files are not part of the release changes.

## Local macOS artifacts

- Application: `target/release-maven-ascii-1.0.8/TurboJ.app`
- Disk image: `target/release-maven-ascii-1.0.8/TurboJ-1.0.8.dmg`
- Bundled JAR SHA-256: `98ad4dd7d4caaa17a17f7dad90caf420e3621d1c599ceb58654c75b48f6ef2de`
- DMG SHA-256: `73004451b130dbff1cd4c377578acc0af334f714f54986797df1662d749b2fb5`

The app has its own Java runtime. Launch the new build to see the updated UI;
an already running JVM keeps its currently loaded application classes.
