# TurboJ 1.0.9 — Maven first import and output copying

This maintenance build fixes the first-open workflow, rather than requiring a
pre-populated development dependency cache. It also fixes clipboard commands in
the output pane. The sample remains in `examples/maven` with two JUnit tests.

## Fixed behavior

- New desktop Maven imports resolve dependencies asynchronously from Maven
  Central and repositories/mirrors in user/global Maven settings. Saved offline
  mode and explicit repository restrictions remain respected. POM declarations
  cannot grant new repository permissions, and importing never trusts execution.
- Module dependency errors are printed in the output pane as well as project
  diagnostics, including the missing artifact and recovery guidance.
- With the POM still active, **Run → Run** compiles the project, finds its real
  bytecode entrypoint and executes it with its module's classpath and JDK.
  Multiple entrypoints get an in-frame chooser; cancellation runs nothing.
  Libraries with no main get an explanatory message. Folder import does not
  accidentally run the previous unrelated editor buffer.
- **Test → Run All Tests** works directly from the imported project. Run/Test
  are unavailable while import is pending or dependencies are incomplete.
- Copy, Cut, Paste and Select All follow the focused text surface, including
  output and REPL. Menu actions and customized shortcuts retain that target.
  Incoming output preserves a selected transcript, and Cut/Paste cannot alter
  printed text. Standard clipboard shortcuts also work in embedded dialogs.

## Verification

`./mvnw -q -l target/maven-open-copy-verify.log verify` passed **481 tests**,
with zero failures, errors or skips. This includes six main-discovery cases,
console selection/input protection, ASCII text bindings, default-import policy,
explicit offline preferences, authenticated mirrors and missing-dependency output.

The desktop harnesses passed again using the final **1.0.9 app's own runtime
and bundled JAR**:

- `MavenFirstOpenSmoke`: **27 checks**. A new temporary user home outside the
  repository starts without `.m2/repository`, downloads JUnit, opens the POM,
  runs the calculator and passes both tests. Folder import, multiple mains,
  chooser cancellation, no-main libraries, trust and one-frame behavior also pass.
  A sentinel wrapper is never invoked and native operations create no Maven
  `target` directory in the sample.
- `ClipboardSmoke`: **11 checks** for Cmd+C/Cmd+A, Edit → Copy, custom keybinding,
  source/output focus, streaming selection, stdin-only editing and embedded
  dialog copying. The harness restores the original system clipboard.
- `MavenSmoke`: **25 checks** for reactor compilation, cross-module refactoring,
  formatting, Auto Test, POM editing, three themes and exactly one explicit
  wrapper lifecycle invocation. This is a cached-artifact regression; its old
  report labels saying "offline" do not establish offline mode for this run.

Reports and the Maven screenshot are in `app/target/maven-first-open-smoke/`
and `app/target/clipboard-smoke/`. A separate first-import controller check
downloaded eight JUnit dependency JARs into a fresh default local repository:
`target/import-defaults-check/report.txt`.

The previous example verification used an explicitly configured populated cache;
it did not cover the desktop's first-import defaults. The new acceptance harness
does not preconfigure repository paths or project preferences.

These fixes do not change the documented Maven plugin-parity limitations.
The macOS package remains a local development build, unsigned and unnotarized.

## Local artifacts

- Application: `target/release-maven-copy-1.0.9/TurboJ.app`
- Disk image: `target/release-maven-copy-1.0.9/TurboJ-1.0.9.dmg`
- Bundled JAR SHA-256: `75537bbc858f045123f6a653ab51c132993817de93bce3ab7480906d3c4b45d7`
- DMG SHA-256: `b91c32f38414ad2b8182061c4ae70d5865cddabc7b415e2a688ae5248277fbd5`

Close the older application and launch this build to use the fixes. No user
Maven settings or existing example source files were changed by this work.
