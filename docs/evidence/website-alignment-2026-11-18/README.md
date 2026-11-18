# Website alignment — 18 November 2026

The later [queued visibility correction](../website-visibility-2026-11-18/README.md)
supersedes this build's renderer hashes and payload/Lighthouse measurements.
This record preserves the original alignment verification and generated PDF
evidence; its byte-identical-renderer statement applies to that earlier build.

Local verification of WEB-02/03/04/06/07, including the final guarded build and
native registry export. No publication occurred.

## Implemented

- The reconstruction uses one font size across source, Explorer, Output, menu,
  pane titles and status, with 14/16/18 px responsive scales. Each pane has one
  outline; the extra surrounding workspace border and Output outline are gone.
  Native layout reference: `docs/evidence/qfx04-2026-11-16/java-bottom-panels.png`
  and current `TurboFrame.applyTheme`/`refreshTitle`.
- Output and Explorer can be resized with bounded pointer handles or keyboard
  arrows/Home/End. Escape returns to the source region. Labels explicitly say
  “recorded”; these are reconstruction controls, not new captured commands.
  Recorded split proportions are the defaults. User overrides preserve source,
  output, event identity and elapsed playback time, and keep source visible.
- Play/Pause and the one-line explanatory prompt remain the only public
  transport controls. Hidden chapter/evidence controls, native capture links,
  no-JavaScript fallback, approved logo and 103 native palettes remain intact.
- No capture, source revision, output, command, operation interval or editorial
  cut was regenerated. Session details explain the current chrome adaptation;
  immutable original screenshots retain their historical appearance.
- An all-theme keyboard sweep exposed a previously untested dialog race:
  an old queued `close` event could reset a newly reopened theme dialog, making
  Apply/Enter inert. Cleanup now runs only while the dialog is actually closed.
  A deterministic same-turn close/reopen regression verifies the fix.

## Validation

Environment: macOS arm64, Node 26.3.0, Astro 7.4.2, Playwright 1.64.1,
dedicated Chromium headless shell 156.0.8078.4. The existing local production
preview at `http://127.0.0.1:4322` was reused; its HTML matched `dist/` byte for
byte. No visible browser was opened and no unrelated server was stopped.

| Check | Result |
| --- | --- |
| Astro check | PASS, zero errors/warnings; one existing performance-helper hint |
| Unit/content/evidence/payload tests | 53 PASS |
| Homepage, responsive layout, keyboard, axe | 46 PASS |
| Replay, seeking, clocks, lazy loading, bounded DOM, no-JS | 36 PASS |
| Saved Monokai, delayed/failed loads, every distinct dialog/menu, loop | 10 PASS |
| Full source/log downloads, hashes, keyboard/no-JS access | 14 PASS |
| Actual recorded scenes at 320/390/768/1440/1920 px, axe and resizing | 80 PASS |
| Existing theme catalog, preview, cancellation and persistence | 74 PASS |
| Theme spin, reduced motion, cancellation, hidden-page suspension | 6 PASS |
| Every bundled theme selected/applied by keyboard | 103 PASS |
| Queued old-close event after immediate reopen | PASS |

**369 browser checks passed**, plus the deterministic dialog lifecycle control.
The final theme and spin suites were rerun after the close-event fix. Every
theme's actual browser palette matched its native definition; editor, console,
menu, keywords, strings and comments met 4.5:1 contrast. Replay state stayed
unchanged, no Classic fallback appeared, and the hero DOM stayed bounded.

Initial failed controls are retained: two test assertions needed adaptation
(`outline-style: none` can still report a computed 3 px width; responsive stages
have three existing heights), and the aborted all-theme sweep plus six-theme
diagnostic exposed the real queued-close defect. These failures are not counted
as passing checks. A sandbox browser-launch failure was resolved by the approved
dedicated headless browser invocation.

## Measurements

Production gzip bytes: **6,718** hero controller/model including its inline marker
(budget 8,192); **14,865** all emitted and inline JavaScript (budget 18,432).
The five lazy chapter payloads total **21,080 bytes gzip** (budget 30,720).
No video or screenshot archive is eagerly loaded. The unchanged single genuine
fallback screenshot is separate from the archive. Paused/offscreen playback
does not keep an animation frame loop running.

Three sequential Lighthouse 13.5.0 mobile runs, after coordinating a quiet build
window, each scored **100 Performance / 100 Accessibility / 100 Best Practices /
100 SEO**. LCP: **1,656.39 / 1,657.91 / 1,656.92 ms**; TBT **0 ms**; CLS
**0.00049748**. Configuration and all runs are retained in `lighthouse.json`.
These are localhost navigation measurements with simulated mobile throttling;
they do not establish hosting latency, field INP or complete WCAG conformance.
Historical October Lighthouse results remain separate; this is not a controlled
cross-version performance-improvement claim.

## Reproduction and delivery boundary

From `website/`, use Node 22.23.4 or newer:

```sh
npm run check
npm run build
npm test
npm run preview -- --port 4322
QA_BASE_URL=http://127.0.0.1:4322 npm run test:browser
QA_BASE_URL=http://127.0.0.1:4322 npm run test:hero:browser
QA_BASE_URL=http://127.0.0.1:4322 LH_RUNS=3 npm run test:performance
```

Set `CHROME_PATH` to an installed dedicated headless shell when native GUI checks
run on the same desktop. No browser download is performed by these scripts.
The 103-theme sweep is also available as `npm run test:themes:all`.

Final `npm run check`, normal guarded `npm run build`, `npm test` (53 PASS), and
`export-keyboard-reference.mjs --check` all passed after the native registry was
regenerated from JAR SHA-256 `36ae34e651d3a60a47ba988a85ab91dae61d6b0445fd556137293f0504ea33a9`.
The registry has 312 commands and 29 aliases. All three published Markdown
references match their generated native sources byte for byte; the homepage
download points to the verified reference. No stale-manifest bypass was used.
Final HTML/CSS/JS hashes exactly match the previously browser/Lighthouse-measured
files, so those measurements apply to the final renderer without another run.

The four bundled PDF cheat sheets were regenerated from the same final JAR, using
an unshown native frame to produce the actual CommandJ HTML and a dedicated
headless browser to print it. All four profiles contain all 312 commands exactly
once across 16 populated columns with no clipped rows, labels or keycaps. Native
CoreGraphics independently opened every PDF and confirmed four landscape A4
pages each (two sheets, duplex). The existing layout is retained. PDF hashes,
reference URLs/hashes, final logs and layout measurements are recorded in
`final-validation.json` and the accompanying final/cheatsheet files. The
HTML-only export harness avoided launching the printer's default system Chrome
while the main native acceptance checks owned GUI focus.

Compact reports and logs are committed alongside this document. Full local
screenshots, axe details and Lighthouse HTML remain in
`website/artifacts/product-sequence-2026-11-18/`; no new screenshot archive was
added to the site's public assets. `source-files.sha256` identifies website
inputs and `measured-artifacts.json` identifies the measured HTML/CSS/JS.

**Production, deployment configuration and historical evidence are unchanged.**
