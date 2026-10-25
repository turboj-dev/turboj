# Five-chapter native hero capture

This external harness compiles against the existing application JAR. It does not
modify TurboJ, CommandJ, RefactorJ, FormatJ, TurboTest, MavenJ, or their runners.
The fixtures live in `website/demo/projects/hero-story/`. Historical captures and
fixtures remain unchanged.

## Reproduce

Use a macOS graphical session with a built `app/target/turboj-app.jar`, JDK 25 for
the application/driver, the installed Java 21 toolchain at
`/Library/Java/JavaVirtualMachines/amazon-corretto-21.jdk/Contents/Home`, and the
fixture's already downloaded Maven 3.9.11 distribution and Spring Boot 4.1.1
dependencies. The wrapper's SHA-256 is pinned in the fixture. A later environment
must prepare its cache explicitly before claiming warm-cache reproduction.
The harness checks the actual Maven and project-JDK version output.

```sh
javac -cp app/target/turboj-app.jar -d target/hero-story/classes \
  website/demo/capture/HeroStoryCapture.java \
  website/demo/capture/HeroStorySpringCapture.java

java -cp target/hero-story/classes:app/target/turboj-app.jar \
  dev.turboj.app.HeroStoryCapture target/hero-story/hero-control-unique \
  --controls --spring

java -cp target/hero-story/classes:app/target/turboj-app.jar \
  dev.turboj.app.HeroStoryCapture target/hero-story/hero-session-unique \
  --presentation --spring
```

Use the intended runtime executable explicitly when multiple JDKs are installed.
The 2026-10-25 capture used
`target/release-interaction-1.0.10/TurboJ.app/Contents/runtime/Contents/Home/bin/java`
as its runtime, with **the current `app/target/turboj-app.jar`** on the classpath.
It did not load the older release directory's application JAR.

Each invocation refuses an existing trace or reused workspace. Its workspace,
private preferences, and controlled home are under
`/private/tmp/turboj-demo/<output-directory-name>/`. The controlled Maven home has
public-path aliases to already cached repository/distribution files and a newly
created offline settings file; personal Maven settings/credentials are not loaded.
No developer project or personal preferences are edited. The fixture uses loopback
port 18765 and refuses to proceed if that port is occupied.

## Real actions and controls

The driver explicitly selects **TurboJ Classic**. It records registered command
IDs, actual active bindings, and dialog button dispatches. F2/F9/Ctrl+F9 are not
presented as universal macOS defaults. Source is inserted line by line into the
real editor; corrective edits replace the actual editor buffer. This is automated
native interaction, not human typing or a desktop video.

The story performs:

1. CommandJ New, editor source entry, Save's actual file chooser, explicit Compile
   and Run, and observed `Hello TurboJ!` output.
2. Two integer-cent JUnit tests through TurboTest.
3. Actual RefactorJ Rename and Extract Method inspection, preview, and Apply
   actions, then the same two passing tests. Captured post-refactoring sources are
   actual results, never fixture substitutions.
4. Auto Test on Save enabled with Auto Run and Auto Test on Changes disabled;
   unsaved observations, Save → one failing test, correction → two passing tests.
   Then Auto Test on Save disabled and Auto Run on Save enabled; an unsaved
   observation followed by Save → `Updated price: 800 cents`. Options are restored.
5. Maven import/effective model, a real explicit `spring-boot:run` goal, HTTP 200
   readiness with an exact body check, an external curl process, and Stop Maven.

`--controls` additionally captures real stdout, stderr, and invalid-Java compiler
diagnostics. Non-presentation runs also execute the native **Maven validate
lifecycle goal**, separately from the Spring plugin goal. These controls are
retained separately; their durations do not populate the representative story.

Server ownership is verified against the native Maven execution's PID and child
processes. The listener must belong to this tree. Stop uses TurboJ's own
`maven.stop`, waits for owned processes to exit, and proves the port can be rebound.
The observed cancellation exit code is preserved. Successful cleanup does **not**
claim graceful Spring/JVM shutdown.

## Evidence and measurement

`trace.json` contains session metadata, full UI-state events, separate measured
operations, explicitly classified editorial intervals, actual command dispatches,
and SHA-256-addressed source revisions. Full source revisions, project files,
console output, JUnit reports, imported Maven configuration, curl output, and
frame-only screenshots are retained beside it. `artifact-hashes.json` covers the
artifacts; the trace is excluded from that file to avoid a recursive hash.

`System.nanoTime()` brackets operation dispatch and an EDT state/paint checkpoint
after the current result predicate. The nominal completion polling interval is
10 ms, subject to scheduling. The endpoint probe waits 25 ms between completed
requests and uses bounded connect/request timeouts. This is first **observed**
HTTP readiness, not exact socket-binding time. curl has bounded connect and total
timeouts, exact response verification, and a successful process exit check.

Compiler-result, JVM-runner, JUnit-runner, MavenJ-model, and external-curl process
intervals are recorded separately where exposed. They are not interchangeable
with command-to-visible-result measurements. `compilerResult` specifically means
`TurboFrame.currentResult`: the latest **single-editor live compiler** result. It
is not the workspace compiler result for JUnit, Maven, or on-save automation; its
source version and success can legitimately differ from a passing TurboTest run.
For example, the live editor can report missing JUnit imports while the test
workspace compiles and both tests pass. The capture preserves both facts.
Refactoring inspection, preview
preparation, and applying changes are separate measurements; entering parameters
and reading previews are outside them. The two unsaved observations are timed
controls, not compiler/runner speed measurements.

Frame paint capture precedes PNG encoding. Checkpoint/image-writing overhead
between operations stays in the recorded timeline. Only intervals explicitly
marked `typing`, `reading`, or `navigation` are eligible for editorial shortening;
unknown gaps and every measured operation remain intact. The presentation driver
records reading holds after state/result checkpoints and line-entry holds outside
operation intervals. A website exporter must not infer that arbitrary unmeasured
time is idle.

Screenshots contain the application frame only. No other application windows,
desktop, microphone, or screen-recording tool is used. State capture rejects
personal `/Users/` paths; a full artifact/privacy audit is also required before
export. The source and menu/project-tree states are native component data, not
invented replay UI.

## Nominated run

The current baseline is commit `802f49609bfe1da1be63d3afce868110db22d4c7`, with
application JAR SHA-256
`50b2c49c3b8329b4812a5dcef7d327ddfaf0845a4b7bf76e5da24ab319302f0c`.
`target/hero-story/hero-control-01` and `hero-control-02` are retained functional
controls. Both completed successfully; control 02 includes Maven validate.

`target/hero-story/hero-session-2026-10-25` was nominated before execution in
`target/hero-story/nomination-2026-10-25.json`. It is the single representative
session. There is no fastest-run selection or mixing of operation durations from
different runs. Published results and outstanding limitations belong in the
website validation report, after checking the completed trace and production build.

The checked-in public dataset was generated from those exact captures with:

```sh
node website/scripts/prepare-hero-story.mjs \
  target/hero-story/hero-session-2026-10-25 \
  target/hero-story/hero-control-01 target/hero-story/hero-control-02 \
  --nomination target/hero-story/nomination-2026-10-25.json
```

For a new capture, first write a nomination containing the intended session ID,
application commit/JAR hash and a timestamp before launching the representative
run. Use fresh directory names and pass the new nomination to the exporter.
The exporter validates the native effects, hashes, privacy and measurement
boundaries before generating `website/public/demo/hero-story/`. It refuses to
replace previously archived evidence with different bytes. The historical
`prepare-session.mjs` and `/session/` assets are separate and remain unchanged.

This workflow does not deploy or publish the site.
