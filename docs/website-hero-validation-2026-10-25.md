# Native-verified website hero — local validation

This change replaces the illustrative HelloWorld-only hero with five independently
seekable chapters: Hello World, Tests, Refactoring, AutoRun / AutoTest, and Spring
Boot + curl. The native application and libraries are unchanged. No production
deployment or Git push was performed for this assignment.

## Native session: PASS, with the limits below

One representative run was nominated before execution, after two functional
controls. All hero results come from that run, never the fastest control timings.
The capture drives the real Swing editor, CommandJ actions and actual dialogs;
source entry is automated, not a recording of human typing.

| Operation | Observed result |
| --- | --- |
| New, source entry, Save, Compile, Run | Actual save dialog, saved HelloWorld source, `Hello TurboJ!`, exit 0 |
| TurboTest | Exactly two integer-cent tests: 20% off 1,000 cents = 800; no discount = 1,000 |
| RefactorJ Rename | `discount` → `discountedPrice`, including the references in both original tests |
| RefactorJ Extract Method | Actual preview and Apply produce private `applyDiscount`; original tests pass again |
| Auto Test on Save | Auto Run and Auto Test on Changes off; unsaved observation does not run tests; Save causes exactly one failure (900 instead of 800); correction Save restores two passes |
| Auto Run on Save | Auto Test off; unsaved observation does not run; Save compiles and prints `Updated price: 800 cents`, exit 0 |
| Maven import | Resolved effective project, one module, 39 dependencies; offline/warm dependency cache |
| Explicit Maven lifecycle | Separate control 02 runs `validate` through TurboJ and observes BUILD SUCCESS |
| Spring Boot | Actual native Maven `spring-boot:run`; HTTP readiness requires status 200 and exact response body |
| External curl | `curl --silent --show-error --fail --connect-timeout 2 --max-time 10 http://127.0.0.1:18765/hello` returns `Hello from TurboJ!` followed by a newline, exit 0 |
| Stop and isolation | Listener belongs to the owned Maven process tree; native Stop closes the owned processes and the port can be rebound; automation options restored in isolated preferences |
| Additional controls | Real stdout, stderr and invalid-source diagnostics captured separately |

Native evidence is in `website/public/demo/hero-story/evidence/`, including both
controls and `hero-session-2026-10-25`. Reproducible fixtures are in
`website/demo/projects/hero-story/`; driver instructions are in
[`HERO_STORY.md`](../website/demo/capture/HERO_STORY.md). The old `/session/`,
historical captures and unrelated `examples/` edits remain untouched.

## Environment and provenance

- TurboJ source baseline: `802f49609bfe1da1be63d3afce868110db22d4c7`.
- Executed JAR SHA-256: `50b2c49c3b8329b4812a5dcef7d327ddfaf0845a4b7bf76e5da24ab319302f0c`.
- Selected trace SHA-256: `909cb7499ab78cc5b4cab6ee96d352867d08f5ffe721f04c2005ce0713d48dfb`.
- macOS 26.5.1, Apple M5, arm64, 16 GiB RAM; application Corretto 25.0.4.1.
- Maven project: Java release 21, observed Corretto 21.0.11, Maven 3.9.11,
  Spring Boot 4.1.1. Plain Java tests use bundled JUnit Jupiter 5.13.4.
- Explicit **TurboJ Classic** keymap; displayed bindings are session-specific.
- Fresh application JVM/workspace/preferences; workers warm progressively within
  the session. Maven dependencies and wrapper were already cached.
- 89 native checkpoints, 35 immutable source revisions, 37 deduplicated output
  snapshots and 18 measured operations. All exported artifacts have SHA-256
  provenance. Text privacy checks reject personal home paths and credential patterns.

The raw trace clock reaches 73.440 s at its final checkpoint. The story's first
checkpoint is at 0.999888375 s, so the captured story interval is **72.440177583 s**.
The exported replay is **38.031867586 s** after 67 explicitly classified cuts.
All 18 measured intervals retain their complete duration; unknown gaps are retained.
No video was recorded; images are actual frame-only Swing paints on the EDT.

## Measured operation boundaries

Elapsed intervals use `System.nanoTime` from command/button dispatch to an EDT
paint checkpoint after the actual completion predicate. Nominal polling is 10 ms,
subject to OS scheduling. HTTP readiness is observed by real GET requests with
25 ms between completed probes. These are observed intervals, not physical
display scanout or exact server socket-binding measurements. Decimal values
preserve the data; they do not imply millisecond rendering accuracy.

| Operation | Dispatch → captured result (ms) |
| --- | ---: |
| Compile HelloWorld (cache hit) | 15.52 |
| Run HelloWorld | 41.06 |
| Two tests before refactoring | 293.97 |
| Rename: inspect / prepare preview / apply | 28.00 / 1,577.77 / 16.36 |
| Extract Method: inspect / prepare preview / apply | 23.75 / 105.89 / 17.36 |
| Same two tests after refactoring | 113.77 |
| Unsaved Auto Test observation | 607.09 |
| Save → failing automatic test | 267.98 |
| Save correction → passing automatic tests | 243.39 |
| Unsaved Auto Run observation | 607.55 |
| Save → updated automatic run output | 200.80 |
| Maven import, warm cache | 572.92 |
| Confirmed Maven goal → observed HTTP readiness | 2,048.13 |
| External curl including capture checkpoint | 14.94 |

The two unsaved observations are trigger controls, not speed measurements.
Preview review/parameter entry is outside the transformation intervals. Internal
compiler, JVM, JUnit, MavenJ and curl-process durations remain separately available
in the evidence and captured output. `compilerResult` is the last live single-file
editor result; it must not be mislabeled as the workspace JUnit compiler duration.

## Browser implementation

The existing Astro component, TypeScript model/controller and demo-specific CSS
remain the architecture. A single active-time clock reads immutable source/output
checkpoints. It suspends on Pause, document hiding and offscreen visibility; an
epoch guard rejects stale chapter loads. Only Hello World autoplays, once; full
story playback is explicit. No Java, AI or localhost requests execute in the browser.

The static `/demo-session/` page exposes complete sources/logs, native images,
dialog state, environment, boundaries, controls and editorial cuts. Images load
only on explicit request. Source and captured logs use text rendering. There is
no new frontend framework, tracking, native editor feature or invented shell.

## Verification: PASS

The static production build succeeded. Astro checking has no errors or warnings
(one existing nonblocking performance-helper hint). All **51 unit/content/evidence
checks** passed, including actual result/hash validation, native shortcut-reference
integrity and production payload budgets.

| Browser suite | Passed | Failed |
| --- | ---: | ---: |
| Full homepage, anchors, CTA, keyboard navigation | 40 | 0 |
| Hero playback, seeking, clocks, focus, lazy loads, reduced motion | 30 | 0 |
| Evidence page, hashes, downloads, no-JS and accessibility | 14 | 0 |
| Captured preview/test/menu/Maven/curl scenes | 26 | 0 |
| Existing theme behavior and state preservation | 74 | 0 |
| Existing theme spin | 6 | 0 |
| **Total** | **190** | **0** |

Widths 320, 390, 768, 1440 and 1920 px passed (the full-page suite additionally
checks 1280 px). Six dense native scenes were inspected at 390 and 1440 px with
keyboard scrolling, visible focus, authentic text and axe audits. Before/after
refactoring text remains scrollable at readable font sizes; sources/logs can also
be inspected in the complete evidence view. External curl is visibly separate.
Automated axe audits report zero violations; incomplete checks and screenshots
are retained for manual review. This is not a claim of complete WCAG conformance.

All 103 palette exports remain exact native definitions; browser regression covers
the primary Classic/Dark/Light palettes and existing catalog/cache/spin behavior.
After 100 chapter switches, the final-state maximum was 108 DOM elements and
retained heap growth 154,572 bytes; every recorded checkpoint, including previews,
also passed the ≤250-element bound. No long tasks were observed in that run.
Paused/hidden/offscreen states schedule no continuing replay animation work.

Initial browser reports are retained: five reduced-motion state-flag failures were
corrected without enabling animation, and the first evidence-download check found
Astro's extensionless-file routing. Exact-byte `.txt` download aliases corrected
that while preserving original archive names and bytes. Original reports are in
`demo-initial-run/` and `details-initial-run/`, alongside the passing final reports.

Changed files are limited to the four demo component/model/controller/style files,
new capture fixtures/drivers and evidence normalizer/archive, the new evidence
page, related website tests/package scripts, asset byte-preservation attributes,
and documentation. The IDE, libraries, approved logo, existing theme definitions,
other homepage copy/sections and historical capture datasets have no changes in
this demo changeset.

## Known limits

- **PARTIAL — native shutdown:** Stop Maven cancels with exit 137. Owned-process
  termination and port closure are verified; graceful Spring shutdown is not claimed.
- **PARTIAL — plain test-file diagnostics:** the live single-file editor can
  report missing JUnit/Price symbols, while TurboTest compiles its workspace with
  the testing classpath and passes both tests. Original diagnostics are preserved.
- **UNVERIFIED — cold downloads:** Maven timings describe a warm cache only.
- **UNVERIFIED — physical display/video:** EDT painting is measured, not monitor
  presentation. No desktop recording or frame-accurate browser playback is claimed.
- **UNVERIFIED — cross-browser/assistive technology:** Chromium checks do not
  establish Safari, Firefox, VoiceOver or complete WCAG conformance.
- **UNVERIFIED — field performance:** local lab measurements do not establish INP
  or public-host Core Web Vitals.

## Local performance: PASS

Three baseline and three final Lighthouse 13.5.0 runs used the same installed
Chromium 156, local preview server, 390×844 mobile emulation, 4× CPU slowdown and
simulated mobile networking. Every run is retained; no best-run selection.

| Metric | Previous hero, 3 runs | New hero, final 3 runs |
| --- | --- | --- |
| Performance | 100 / 100 / 100 | 99 / 99 / 100 |
| Accessibility / Best Practices / SEO | 100 throughout | 100 throughout |
| LCP (ms) | 1,658.56 / 1,658.62 / 1,661.15 | 1,805.12 / 1,805.00 / 1,654.42 |
| CLS | 0.006809 / 0.000384 / 0.006809 | 0.000126 throughout |
| TBT | 0 ms throughout | 0 ms throughout |

All runs meet the agreed ≥95 score, <2.5 s LCP and <0.1 CLS budgets. The ranges
show the measured variation and the small LCP increase in two final runs; these
results are not a promise of identical performance on other hosts or devices.
An earlier new-hero run exposed a 0.08349 layout shift when asynchronous controls
appeared. Reserving their layout during loading corrected it; those three earlier
runs are retained in `artifacts/hero-story-qa/lighthouse-before-layout-fix/`.

Production payload accounting (Node 26 gzip, bytes): hero bundle **4,643** plus
**61** for its inline layout bootstrap; all emitted/inline JavaScript **12,847**;
five lazy chapters **20,830**. These satisfy 8/18/30 KiB respectively. Inline
scripts are counted separately conservatively, not claimed CDN transfer sizes.
Native image archives are not downloaded by default. There is no video download.

The previous artifact is frozen at `target/hero-baseline-802f496/`, and reports
are in `website/artifacts/hero-story-baseline/` and
`website/artifacts/hero-story-qa/`. The native capture JAR is also retained locally
at `target/hero-story/frozen-turboj-802f496.jar` before subsequent native UI work.

## Local reproduction

From `website/`, with Node 22.23.4 or newer:

```sh
npm run check
npm run build
npm test
npm run preview -- --port 4323
```

Open `http://127.0.0.1:4323/` and `http://127.0.0.1:4323/demo-session/`.
Browser suites target this URL with `QA_BASE_URL`; use
`QA_ARTIFACT_ROOT=artifacts/hero-story-qa` to preserve historical reports.
Deployment requires separate explicit authorization.
