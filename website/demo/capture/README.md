# Capturing a real TurboJ session

These are external verification drivers, not IDE features. They compile against
the frozen TurboJ application JAR and operate its actual Swing frame, editor,
registered commands, refactoring dialogs, test runner and Maven controller.
No application source changes are required.

Run from the repository root with a Java 25 JDK:

```sh
TURBOJ_CAPTURE_JDK=/path/to/jdk-25
"$TURBOJ_CAPTURE_JDK/bin/javac" \
  -cp target/release-interaction-1.0.10/TurboJ.app/Contents/app/turboj-app.jar \
  -d target/hero-session/capture-classes \
  website/demo/capture/SessionCapture.java website/demo/capture/SpringCapture.java

target/release-interaction-1.0.10/TurboJ.app/Contents/runtime/Contents/Home/bin/java \
  -cp target/hero-session/capture-classes:target/release-interaction-1.0.10/TurboJ.app/Contents/app/turboj-app.jar \
  dev.turboj.app.SessionCapture target/hero-session/your-unique-session \
  --presentation --spring
```

Replace the compiler path with your installed Java 25 JDK. A macOS GUI session
is required. The output name must be new: existing captures and workspaces are
never overwritten. Run one GUI capture at a time. The isolated projects live at
`/private/tmp/turboj-demo/<session-name>/workspace`. Preferences and evidence live
in the selected output directory. Existing examples and user settings are untouched.

The driver deliberately authorizes **only the freshly copied demo Spring
project** through TurboJ's existing trust and Maven confirmation dialogs. Chapter E
uses configured Maven repository settings and the existing artifact cache. Initial
downloads need network access. It starts a local service on `127.0.0.1:18765`, runs
a real external `curl`, then stops Maven. Port 18765 must be unused. No shell or
terminal emulator is represented inside TurboJ.

`--spring` includes chapter E; omitting it captures A–D only. `--presentation`
adds actual reading pauses outside all operation measurements: 1400 ms after
state snapshots, 1600 ms after results, and 600 ms after the initial empty file.
Without it, the driver is a fast functional control. Source entry is automated
in complete edits; it is not a recording of human typing.

## Evidence and clocks

Each run writes `trace.json`, frame-only PNG screenshots, the post-refactoring
Java sources, and (when E runs) `curl.txt`.

- Relative times use `System.nanoTime`; correlated wall times use `Instant.now`.
- An operation starts immediately before its registered command or button is
  dispatched, after the before-state image has been encoded.
- It ends at an EDT paint checkpoint after the actual completion predicate,
  before result PNG encoding. This is not physical display scanout timing.
- Completion is checked with a nominal 10 ms polling interval, subject to OS
  scheduling. These are instrumented session observations, not latency guarantees.
- Test-runner and program-worker internal times remain verbatim in the console;
  they are distinct from command-to-paint measurements.
- Maven import includes model/dependency work and pending configuration sync.
- Spring startup includes the explicit Maven launch through the first successful
  HTTP response and final paint. HTTP readiness probes wait 25 ms between
  completed requests, with a 500 ms request timeout.
- `metadata.curl` separately records process creation through stdout EOF and exit,
  including curl startup. The displayed curl operation includes the UI checkpoint.
  The endpoint has already answered the readiness probe, so curl is a subsequent
  request to the running service, not its first cold request.

Screenshots are rendered from the real frame on its EDT, not invented or redrawn
captures. Intermediate evidence checkpoints add instrument overhead; they have
no intentional reading pause. The final replay must preserve all operation
intervals and clearly disclose any changes to outside-operation pauses.

## Verified workflow

Hello World uses the native compiler and Fast Run. With presentation pauses the
live compiler can already have finished; a subsequent explicit Compile correctly
reports a cache hit. The plain Java price project has two bundled JUnit tests.
Its live single-file compiler reports missing JUnit/Price symbols while editing
the test source; the subsequent Run All Tests workspace compilation succeeds
with the test classpath and both tests pass. The captured console retains the
earlier diagnostics. This limitation is not repaired or hidden by the replay.
Rename updates declaration and test references; Extract Method previews and applies
the selected calculation statements. The same tests run afterward.

Auto Test and Auto Run are toggled separately through their registered commands.
The driver observes unsaved edits without execution, then saves to trigger the
real pipeline. Live compilation and diagnostics can still occur before Save.

Spring Boot uses **Build → Run Maven Goal → `spring-boot:run`**. Control runs
demonstrated that the current native Fast Run and Isolated Run workers retire
after Spring's `main` returns, terminating its server threads. The demo therefore
does not present native Fast Run as a working persistent Spring server launcher.

The existing Maven output pane joins some lifecycle log lines without separators.
Capture and replay preserve this observed output; they do not repair the IDE or
insert fabricated console line breaks.

Run controls first, then nominate the complete presentation session before seeing
its timings. Retain failed controls with their explanation; never combine the
fastest measurements from different runs. Review screenshots and JSON for private
paths before publishing evidence. No production deployment is part of capture.
