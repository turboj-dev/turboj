# Separately recorded native console finale

`HeroConsoleSpringCapture` replaces only chapter E of the hero. Chapters A–D
retain their original capture. Each recording keeps its own monotonic clock,
application build hash, environment and evidence archive. The combined replay
must never be described as one continuous measured native session.

The driver uses the existing `HeroStoryCapture` evidence collector with
`--console-finale`. Import, Maven goal selection, project trust and confirmation
use the actual existing native callbacks/dialog buttons, as explicitly recorded.
For the new interaction, it posts actual Swing/AWT key events to the focused
native component: Escape, typed colon, the `! curl …` characters, then Return.
The command row's callback is never called directly by the driver. Assertions
require the editor source to remain unchanged, the command row to own focus,
curl to complete successfully with real HTTP 200 JSON, and the Maven service to
remain alive after curl.

The service is a new copy in `website/demo/projects/console-finale/`; historical
fixtures and evidence are immutable. The process runner executes a real shell
command in the current project directory. This feature is a one-command output
console, not a PTY or a full-screen terminal emulator.

## Reproduce

Wait until the native feature has been packaged in `app/target/turboj-app.jar`.
Use the existing Java 25 runtime for the application and the already installed
Java 21 toolchain for the Maven project. The fixture uses the same pinned Maven
and Spring versions as the historical capture and an explicitly offline warm
cache. The capture creates private preferences/home/workspace under
`/private/tmp/turboj-demo/<unique-session-id>/`; it never loads personal Maven
settings or credentials.

```sh
javac -cp app/target/turboj-app.jar -d target/hero-console/classes \
  website/demo/capture/HeroStoryCapture.java \
  website/demo/capture/HeroConsoleSpringCapture.java

java -cp target/hero-console/classes:app/target/turboj-app.jar \
  dev.turboj.app.HeroStoryCapture target/hero-console/<unique-session-id> \
  --console-finale --presentation

node website/scripts/prepare-console-finale.mjs \
  target/hero-console/<unique-session-id> target/hero-console/nomination.json
```

Before execution, write a nomination with `session`, `nominatedAt` and
`sha256: {"app/target/turboj-app.jar": "<actual hash>"}`. The exporter rejects a
nomination written after recording or for a different JAR. Failed controls remain
in their fresh output directories and must be retained, never repurposed as a
successful session. Native GUI capture requires a foreground macOS desktop.

## Boundaries and privacy

The collector measures dispatch to the first successful completion predicate and
an EDT paint checkpoint using `System.nanoTime`; nominal completion polling is
10 ms. The command runner's own process duration is recorded separately. HTTP
readiness is first observed success from a bounded background GET probe, not the
startup log timestamp. Screenshot encoding is outside the measured end boundary;
bookkeeping gaps remain unless explicitly classified as reading/typing/navigation.
The command/result output is copied exactly; headers and JSON field order are not
rewritten. The command line and its hint are real captured Swing component state.

A separately captured cleanup checkpoint proves Stop Maven and listener removal.
It is retained in raw evidence but excluded from the visible finale so the hero
holds the actual successful JSON output. The browser's 3-second loop hold is an
editorial playback hold, never an operation measurement.

`native-build-source-hashes.json` and the JAR SHA identify working-tree build inputs
when the native feature has not yet been committed. `turbojCommit` then identifies
the base checkout, not a claim that the feature existed in that commit. Raw traces
and all artifacts pass private-home/credential checks before public export.
