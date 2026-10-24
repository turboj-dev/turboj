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

The native commands run the current method, current class, all tests, failed tests, stop tests, and show results. Auto Test is disabled by default. Enabling it schedules tests only after a complete applied refactoring, never during preparation/preview. The current conservative affected-test selection is the whole discovered workspace suite; this can run extra tests rather than omit indirect dependencies. Older source-version results cannot replace current diagnostics. Failed results are displayed first; the library does not reorder a user's framework-defined test execution semantics.

The core tests cover discovery, outcomes, skipped/aborted results, failure events and selectors. Native controller tests exercise compilation from unsaved files, the actual resident worker, compile failures, reruns, opt-in automatic execution and obsolete results. Desktop acceptance checks live in TurboJ's `TurboTestSmoke` and exercise the actual application integration.

TurboTest source is MIT. JUnit Platform/Jupiter and OpenTest4J retain EPL-2.0 obligations; API Guardian and Gson retain Apache-2.0 notices in their distributed JARs. Distribution auditing is shared with TurboJ's dependency inventory. This local artifact is not yet advertised as published on Maven Central.
