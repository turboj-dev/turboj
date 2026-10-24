# TurboTest

Independent Java 25 JUnit Platform integration, `dev.turboj.turbotest:turbotest-core:0.1.0`. The library has no TurboJ, Swing, RefactorJ or protocol dependency. It discovers and executes real Jupiter tests, emits versioned events, records failures/stack traces and supports class, method and failed unique-ID selection.

```
./mvnw -f turbotest/pom.xml test
```

A standalone checkout can use `mvn test`. JUnit Platform 1.13.4 and Jupiter 5.13.4 are pinned. `TestEngine` executes tests in its calling JVM, so embedders must supply an appropriate isolation and cancellation boundary. It must never run on a UI thread.

```java
var request = new TestEngine.Request(version, List.of("example.CalculatorTest"), List.of());
var report = new TestEngine().execute(request, event -> updateProgress(event));
```

TurboJ compiles immutable unsaved source snapshots in-process, then invokes `WorkerEntry` in the existing resident test worker. No new JVM is launched for every passing run. Stop terminates a running worker; later runs recreate it. JUnit dependencies are extracted locally from intact bundled JARs and provided to compilation/runtime classpaths. Test reports are private local files and removed after reading. No network or telemetry is involved.

The native commands run the current method, current class, all tests, failed tests, stop tests, and show results. Automatic testing is disabled by default. **Auto Test on Changes** schedules tests after source changes or complete applied refactorings, never during refactoring preparation/preview. The current conservative affected-test selection is the whole discovered workspace suite; this can run extra tests rather than omit indirect dependencies. Older source-version results cannot replace current diagnostics. Failed results are displayed first; the library does not reorder a user's framework-defined test execution semantics.

Options → **Auto Run on Save** and **Auto Test on Save** are separate persisted
switches. They start only after a successful Java-source save and share a current
workspace compilation. Compilation failure prevents both execution and tests;
stale generations are discarded. Enabling these switches alone does not execute
on typing. Maven projects require resolved dependencies and explicit execution
trust. Program execution and tests may proceed independently after compilation,
so a program waiting for stdin does not block the test suite. The normal Test
Results command retains automatic results without opening a dialog after each save.

The core tests cover discovery, outcomes, skipped/aborted results, failure events and selectors. Native controller tests exercise compilation from unsaved files, the actual resident worker, compile failures, reruns, opt-in automatic execution and obsolete results. Desktop acceptance checks live in TurboJ's `TurboTestSmoke` and exercise the actual application integration.

TurboJ separates each run with an ASCII-style `TURBOTEST` heading. The output shows each test's status and duration, followed by passed, failed, skipped and aborted totals and the elapsed run time. Colors come from the active theme, retain readable contrast, and update existing output when the theme changes. Explicit status labels remain available when copying plain text. Failure messages and stack traces stay selectable in the output and in Test Results; the results view also contains deduplicated compiler notes under **Compilation details**. Compiler errors and warnings remain visible in the output. `TestOutputSmoke` exercises these presentations with actual passing, failing, disabled and aborted JUnit tests in Classic, Dark and Light.

TurboTest source is MIT. JUnit Platform/Jupiter and OpenTest4J retain EPL-2.0 obligations; API Guardian and Gson retain Apache-2.0 notices in their distributed JARs. Distribution auditing is shared with TurboJ's dependency inventory. This local artifact is not yet advertised as published on Maven Central.
