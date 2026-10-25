# Native console finale validation — 2026-10-25

The final hero chapter now has a new native recording. Chapters 1–4 retain the
original `hero-session-2026-10-25` recording. These are **two separately captured
sessions**, with independent builds, clocks, source revisions and cut maps. No
operation timings were mixed into a claim of one continuous native session.

## Native verification

| Requirement | Result | Evidence |
| --- | --- | --- |
| Import the isolated Spring project | PASS | One Maven module, 39 dependencies, offline warm cache; observed model `cacheHit: true` |
| Start Spring through native Maven goal | PASS | Real `maven.build.custom`, `spring-boot:run`, trust and confirmation dialogs |
| Verify HTTP readiness | PASS | Background HTTP GET returned status 200 and the expected two-member JSON object |
| Keep editor and Maven workspace visible | PASS | Actual frame captures show the main/controller source and project explorer |
| Escape → colon opens command row | PASS | Actual AWT key events, unchanged editor source, native field gains focus |
| Type `! curl …` and press Return | PASS | Actual AWT character and Return events, native process runner, preserved prior Spring output |
| Show HTTP 200 and JSON inside Output | PASS | Exact stdout includes HTTP/1.1 200, application/json, `{"status":200,"message":"OK"}` |
| Curl completes successfully | PASS | Native `[command exited 0]`; command job duration 15.871916 ms |
| Curl leaves Maven running | PASS | Owned Maven process and native lifecycle controller remained active |
| Stop owned server after capture | PASS | Native Stop Maven, process tree exited, endpoint port rebound; actual Maven exit 137 retained |
| Preserve source/output/screenshot provenance | PASS | Export validation plus SHA-256 artifact tests |
| No browser request to localhost | PASS in replay source/evidence checks | HTTP requests occurred only during the native recording |

Maven setup uses the real command registry and real dialog buttons. The new
Escape/colon/command/Return interaction is actual posted Swing/AWT keyboard input.
This does not claim that the entire Maven setup was performed through physical
keyboard hardware, nor that a video was recorded. Screenshots contain only the
native frame. No other windows were captured.

The real Output splitter was moved to 300 px in the 1060×740 capture; its recorded
fraction is 0.5406626506024096. The real editor caret moved to the end of the file,
showing the main method and controller. This presentation adjustment is explicitly
recorded, not a fabricated terminal layout. The native frame simultaneously shows
the command, HTTP headers, JSON and exit status.

The visible chapter ends at `curl-visible-result`. The later `spring-stopped`
checkpoint remains in the original trace and screenshot archive as cleanup
evidence. The browser's final reading hold and restart are editorial playback
behavior, excluded from native operation intervals.

## Recorded session and timings

Representative recording: `native-console-session-2026-10-25`, nominated before
execution at `2026-10-25T14:32:54.761391+00:00`; recording began at
`2026-10-25T14:33:17.170043Z`. Two prior functional controls are archived separately:
`native-console-control-01` and `native-console-control-02`. Both passed. The first
used the default Output height; the second verified the larger recorded layout.
A subsequent native mouse-focus regression fix was packaged and tested before
nomination; the representative recording uses that final candidate build.

| Operation | Dispatch → visible checkpoint |
| --- | ---: |
| Maven import | 582.220667 ms |
| Confirm Maven goal → first observed HTTP readiness → paint | 1870.600000 ms |
| Native command-row Return → completed curl → paint | 31.533083 ms |

These are times from this recording, not performance guarantees. `System.nanoTime`
measures dispatch through an EDT paint checkpoint; completion polling is nominally
10 ms, subject to scheduling. HTTP readiness probes wait 25 ms between completed
requests. Decimal precision preserves the recorded values and does not imply
physical display timing at that precision. All operation intervals retain 1×
playback. The separately reported 15.871916 ms command-job duration begins at native
runner job construction, before worker scheduling, and ends after process exit,
stream draining and owned-process cleanup; it excludes final Swing rendering.

The emitted chapter is 11,991.393666 ms after shortening only explicitly recorded
reading intervals. Unknown gaps remain. Each source, console, dialog, command-row
hint and cursor checkpoint comes from the native collector.

Environment: macOS 26.5.1 arm64, Apple M5, 16 GiB RAM; Amazon Corretto
25.0.4.1+10-LTS application runtime; project Java 21.0.11; Apache Maven 3.9.11;
Spring Boot 4.1.1. Dependencies and wrapper distribution were already cached.
The build was made from working-tree native changes based on commit
`32ee9643411d8a89cbc6212a76cd52d480b04ac8`; that base commit alone is not claimed
to contain the new feature. The archive includes all app source hashes.

- Application JAR SHA-256: `c79d587a14c0c2af960271926268e54d42a6b7116ed1354e422f00969ee1363c`
- Trace SHA-256: `e630ffc16656d9835f4a98e8d8754ad1b56dbd72aa1203a1f01ce554aa4d6467`
- HelloService source SHA-256: `750e5ee0c35d685c0956cfa3e3c8ccbbca619240ec62ead2b1266951d7eca04f`
- Maven POM SHA-256: `d0fd71673c2987c642757690271773106d0ca4ccf7f78d4ddc4c86c75cbde807`

## Reproduction and checks

See `website/demo/capture/CONSOLE_FINALE.md`. The isolated source fixture lives in
`website/demo/projects/console-finale/spring-hello/`. The public raw evidence is
`website/public/demo/hero-story/evidence/native-console-session-2026-10-25/`;
controls are alongside it. The standalone exporter refuses incomplete effects,
changed hashes, invalid keyboard sequence, missing cleanup and late nomination.
It preserves all historical evidence bytes.

Executed verification:

```sh
node --test website/tests/hero-evidence.test.mjs
```

Result: **8 tests passed**, including regeneration of every active chapter from
its own raw trace, exact source/output matches, all exported artifact hashes,
privacy/path checks, real keyboard evidence, cleanup, and rejection of corrupted
or fabricated success data.

The full app dependency reactor passed `./mvnw -q -pl app -am test`. After the
final focus correction, a targeted package run passed **31 tests**: ten process
runner tests, six controller tests, six editor-gesture tests and nine existing
ConsolePane tests. The final packaged JAR above passed **24 actual Swing keyboard
assertions** across IntelliJ, Eclipse, VS Code and Classic, plus a separate mouse
routing regression probe. See
[`keyboard-smoke.txt`](evidence/output-command-line/keyboard-smoke.txt) and
[`validation.json`](evidence/output-command-line/validation.json). Registry export
found 270 registered commands, 29 aliases and zero bindings without callbacks.
Physical keyboard layouts/OS input-method behavior remain unverified.

Native usage and scope are described in [Output command line](output-command-line.md).

## Final website verification

The Astro production build and all **53 unit/content/evidence/payload tests passed**.
The production preview at `http://127.0.0.1:4322` passed **200 browser checks**:

| Suite | Passed |
| --- | ---: |
| Replay clock, controls, seeking, reduced motion and loop boundaries | 36 |
| Native scenes, readable arrival state, keyboard scrolling and axe | 30 |
| Evidence/details page | 14 |
| Themes | 74 |
| Homepage, responsive layout, navigation and axe | 46 |

Checks cover 320, 390, 768, 1440 and 1920 px, keyboard-only playback, hidden/offscreen
suspension, exact-boundary resume, repeated seeking, theme preservation and no
browser requests to the recorded service. Pristine final-scene screenshots confirm
the complete curl command, HTTP 200, JSON and exit status are simultaneously visible
on desktop; the response and exit status remain readable on mobile, with full logs
available by scrolling. The final 3-second hold uses the same active playback
clock, suspends with playback, and is disabled for automatic repetition under
reduced motion. Play/Pause and the single narrative row remain the visible controls.

Measured production gzip sizes: hero controller/model plus bootstrap **6,175 bytes**
(budget 8 KiB); all emitted and inline JavaScript **14,318 bytes** (budget 18 KiB).
The five lazily loaded chapter payloads total 21,080 bytes gzip. Screenshots remain
on demand. No fresh Lighthouse score is claimed. Report hashes, individual browser
check names and production bundle hashes are retained in
[`website-validation.json`](evidence/output-command-line/website-validation.json).
Local browser artifacts are in `website/artifacts/native-console-qa/`; pristine
arrival images are `demo/scenes/1440-curl-result-arrival.png` and
`demo/scenes/390-curl-result-arrival.png` within that directory.

To preview the built site: `cd website` then `npm run preview -- --port 4322`
using the supported Node version in `website/package.json`. To try the native
feature, run `./turboj`, then Escape, colon, `! command`, Return.

Production was not deployed or modified during this change.
