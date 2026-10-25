# Compact recorded hero and native workspace follow-up

The native-verified five-chapter hero from `997057b` now has one visible
Play/Pause control and a single explanatory line with a typewriter cursor.
The user explicitly authorized committing and publishing this website update.

## Website behavior

- Hello World autoplays once when visible and holds its result. Explicit Play
  continues through Tests, Refactoring, AutoRun/AutoTest and Spring Boot/curl;
  Play after the final chapter restarts the story.
- The short editorial captions explain recorded phases without modifying source
  snapshots, logs, screenshots, operation times or the original evidence hashes.
  Caption typing and cursor blinking share the existing active playback clock.
- Pause, hidden documents and offscreen playback suspend animation. With reduced
  motion, the replay starts paused and captions appear complete; explicit Play
  remains available.
- The former chapter/checkpoint controls, timing details and provenance captions
  remain in the DOM under a hidden container, outside the keyboard focus order.
  The complete `/demo-session/` page remains available. Only the specifically
  requested Classic screenshot link was removed; the original image survives.
- Play retains keyboard focus across chapter loads without stealing a newer
  focus choice. An explicit Play brings an offscreen stage into view; ordinary
  scrolling does not restart playback. Load failures appear in the one prompt.

The native workspace changes are described separately in
[`native-layout-preview-2026-10-25.md`](native-layout-preview-2026-10-25.md).
Their single borders, unified fonts and adjustable console are **not yet mirrored
in the website reconstruction**: that visual synchronization still awaits the
user's acceptance of the native preview. No new native installer was published.

## Verification

Astro check: zero errors and warnings, one existing nonblocking hint in the
Lighthouse helper. Production build: PASS. Unit/content/evidence/payload tests:
**51 PASS**. Native validation remains **165 app tests** and **183 Swing checks**
(148 layout, two startup-size checks and 33 zoom/dialog checks).

Browser results on the local production build, Chromium 156:

| Suite | Passed | Failed |
| --- | ---: | ---: |
| Homepage, responsive layout and accessibility | 46 | 0 |
| Recorded replay and minimal controls | 34 | 0 |
| Evidence page and exact downloads | 14 | 0 |
| Recorded dialogs, menus, Maven and curl scenes | 26 | 0 |
| Themes | 74 | 0 |
| Theme spin | 6 | 0 |
| Total | **200** | **0** |

Checks include widths 320, 390, 768, 1280, 1440 and 1920, keyboard-only playback,
full story at measured intervals, reduced motion, no-JavaScript fallback,
hidden/offscreen clocks, async focus restoration, 503 failures, all original
checkpoints, exact source/log hashes and unchanged native theme exports.
Automated axe checks found no violations; this does not certify complete WCAG
conformance or physical assistive-technology testing.

Reports and screenshots: `website/artifacts/hero-minimal-qa/`. The first replay
run is retained under `demo-initial-run`: it exposed the offscreen mobile Play
case and a test counter that included earlier audit navigation. The controller
now scrolls only for explicit Play, and the caption observation counter is reset
at the beginning of the full-story test. The final suite passes both mobile
cases and every caption/clock assertion.

Measured production payload: **5,682 bytes gzip** for hero JavaScript including
its inline bootstrap, **13,825 bytes** for all emitted/inline JavaScript, and
**20,830 bytes** for the five lazy chapter payloads. The 8/18/30 KiB budgets pass.
The screenshot archive remains lazy; no video or browser-side Java was added.

One final Lighthouse 13.5 mobile run on the local production build scores
**Performance 99, Accessibility 100, Best Practices 100, SEO 100**. LCP is
1,807 ms, CLS 0.000697 and TBT 0 ms under simulated mobile throttling. This is
a local navigation measurement, not field INP or a hosting-latency guarantee.
The prior three-run hero baseline remains documented in the earlier validation.

The native command reference was regenerated from artifact
`58fcaefef076e96a86a3edc305a7c0870258e5bfb373dd36bda613b3e6b13679`:
264 commands, 29 aliases, zero bundled bindings without callbacks. The added
`view.resizeOutput` is present in the generated downloads; conflict output is
unchanged. The six drift/export checks pass.

## Build identity

- Homepage SHA-256: `f9dd01db80fa6030135e0600d6d0690ca796b999ef1e81115ce005079424898e`.
- Session details HTML: `f23c0af7f3462d864e985ef8086b40fea0286580b820b18f53e36af6cacabbe1`.
- Hero manifest: `69e1e3e44e1f25fe50757c90ba6c172403e9205e103740b998ec1edad092a4d8`.
- Native session trace remains `909cb7499ab78cc5b4cab6ee96d352867d08f5ffe721f04c2005ce0713d48dfb`.

Wrangler 4.152.0 dry run passed for all 835 static assets. Production verification
and deployment identity are recorded in `website/DEPLOYMENT.md`. Authorized
publication succeeded as version `1f1ad544-2954-4641-aee5-bef96eb9f198`.
Fourteen public HTTPS assets returned HTTP 200 and matched the tested local build
byte for byte, including both pages, the manifest, five chapters, two script
bundles, three keyboard downloads and the original screenshot.
The public-domain browser run also passes all **46 homepage checks** after
deployment, including mobile/desktop layout, keyboard controls and axe audits.
