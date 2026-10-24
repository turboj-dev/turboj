# TurboJ 1.0.10 — Save actions and a consistent interface

This build adds independent save actions and makes the existing ASCII interface
scale as one application. It also fixes semantic project boundaries and improves
test results without changing user example source files.

## Behavior

- **Options → Auto Run on Save / Auto Test on Save** are independent persisted
  switches, both initially off. A successful Java-source save schedules one
  workspace compilation. Only successful, current results can start the enabled
  actions. Rapid saves coalesce; failed saves, compilation errors and obsolete
  generations cannot launch their automatic actions. Maven projects require
  resolved dependencies and execution trust. Tests and a program waiting for
  input can proceed independently. **Auto Test on Changes** retains the existing
  separate source-change/refactoring behavior.
- **Options → Show Pressed Shortcuts** optionally displays the actual executed
  binding in the editor's bottom-right corner for 1.5 seconds. It supports
  remapping and chords, follows the theme accent and excludes normal typing,
  embedded-dialog input and passwords. It defaults off.
- **Smaller Font / Larger Font** apply to the editor, menu labels and shortcut
  text, output/REPL, project explorer, status, semantic popups, theme previews and
  embedded dialogs. The source size remains 10–48 points; surrounding text keeps
  its existing 14:18 proportion. Preferences persist independently of theme.
- The extra Maven strip below the menu is removed. One bottom line puts the
  current outcome first, followed by compact Maven state and file/caret
  information. Full Maven details, timing, ordinary AI mode and common shortcut
  hints are available in its tooltip. The output border has a simple title.
- Test output has themed headings, per-test outcomes and durations, complete
  totals, failure details and readable colors. Existing output recolors on theme
  changes. Duplicated informational compiler notes move to Compilation Details;
  errors and warnings remain visible. A failed suite cannot retain a successful
  project-build status. Plain-text copying retains explicit outcome labels.
- Loose Java source discovery respects nested independent projects. Opening the
  loose examples no longer merges the Maven, plain-Java and intelligence example
  source identities. Explicit reactor source roots remain supported. Underlying
  semantic initialization errors are now preserved in diagnostics.
- Save As for an unnamed source suggests the inferred Java source filename.
  Existing filenames are unchanged: a public `HelloWorld` class still requires
  `HelloWorld.java` under ordinary Java compilation rules.

## Verification

`./mvnw -q -l target/interaction-final-verify.log verify` passed 419 Surefire
tests and 77 Failsafe integration tests (496 total), with zero failures, errors
or skips. The final status, dialog-sizing and cancellation changes were then
rebuilt with 46 targeted tests, also all passing. The combined reports contain
421 Surefire and 77 Failsafe cases (498 total), including two additional regressions.
The earlier 419/421 figures counted only Surefire and omitted the Failsafe reports.
The targeted command was:

```sh
./mvnw -q -l target/interaction-targeted-final.log -pl app -am \
  -Dtest=AsciiSurfaceTest,AsciiChromeTest,MenuStylerTest,WorkspaceStatusTest,ConsolePaneTest,PressedShortcutDisplayTest,CommandSettingsTest,KeymapTest,TestOutputTest,TurboTestControllerTest,MavenAutoTestControllerTest \
  -Dsurefire.failIfNoSpecifiedTests=false package
```

Coverage includes all 103 theme palettes at multiple font sizes, semantic result
colors, keymap remapping/chords, independent project inventories, and prevention
of stale plain-Java/Maven test execution. A five-check actual-file semantic probe
also verifies loose examples, Maven switching and JDK completion; its report is
`target/semantic-workspace-check/report.txt`.

Desktop harnesses use private fixtures and preferences. The final 1.0.10 app's
own runtime and bundled JAR passed these completed checks:

- `InterfaceZoomSmoke`: 33 checks for one footer, compact state, all three main
  themes, actual font commands, open dialogs, persistence and long filenames.
- `PressedShortcutsSmoke`: 16 checks for Options, persistence, actual remapped
  shortcuts, chords, theme accent, timeout and excluded typing/password input.
  Its first final-runtime attempt failed the completed-chord assertion; an
  unchanged rerun passed. The cause of that non-reproduced failure is unconfirmed.
- `PendingAutomationStopSmoke`: 17 checks using a blocked preparation lane and
  real execution markers. Stop Tests preserves the independently requested Run;
  Stop Program preserves Tests; a later save executes both again. An initial
  fixture overlapped startup POM synchronization, which correctly invalidated
  its source generation. The corrected fixture waits for that synchronization;
  the production bundle did not change.
- `TestOutputSmoke`: 11 checks with actual passing, failing, skipped and aborted
  JUnit outcomes; selected failure text, copied summaries, failed status and
  existing output recoloring in Classic, Dark and Light.
- `SaveAutomationSmoke`: 62 checks on plain-Java and Maven projects for run-only,
  test-only and combined saves, disk-before-execution, rapid saves, compilation
  failure, failed writes, persisted preferences and project trust. Native actions
  never invoke a Maven lifecycle or wrapper.

Reports and inspected screenshots are in `app/target/interface-zoom-smoke/`
and `app/target/pressed-shortcuts-smoke/`. Cancellation and result reports are
in `app/target/pending-stop-smoke/` and `app/target/test-output-smoke/`;
save automation is recorded in `app/target/save-automation-smoke/`.

The pending-cancellation harness was executed from its corrected source using
the bundle's Java source-file launcher, then recompiled into the test classes.
The other final harnesses use `app/target/test-classes` plus the bundle JAR.
These are actual Swing windows with synthetic command/key events, not an OS
keyboard-latency benchmark.

## Local distribution

The macOS application and disk image are prepared in
`target/release-interaction-1.0.10/`. This is a local development build without
Developer ID signing or notarization. Close an older running TurboJ instance
before launching the new application.

- Bundled JAR SHA-256: `87997d6395e5dc5d06f2258ea2ad9572d0f64cf00bf116237ec07dde2feaf893`
- DMG SHA-256: `d2ca38d617222be9b2d9f0a72042c29b9bef4522c90a4f35ee80c191e8563000`
