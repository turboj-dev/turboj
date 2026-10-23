# Website validation

The production build is the test target. Run with Node 22.23.4 or newer; this
machine also provides Node 26 at `/opt/homebrew/bin/node`.

```sh
npm run check
npm run build
npm test
npm run preview -- --port 4321
# In a second terminal, while preview is running:
npm run test:browser
npm run test:demo
npm run test:performance
```

Set `QA_BASE_URL` to test a different local preview URL. Set `CHROME_PATH` to an
existing Chromium or Chrome executable. The scripts do not download browsers.
Without an override, the scripts locate an installed Playwright Chromium,
system Chrome, or a cached Playwright headless shell. No username or workstation
path is embedded. `LH_RUNS=3 npm run test:performance` records three independent
Lighthouse runs, retaining every run rather than choosing the best result.

The content suite checks the approved English copy against generated HTML,
metadata, section targets, unavailable link suppression, and accurate values
from the available original application capture. That image records a 0.03 ms
cache hit, 1.17 ms execution, and 2.22 ms round trip. These are a single captured
application run, not website performance measurements or general Java benchmarks.

The browser suite checks widths 320, 390, 768, 1280, 1440, and 1920 pixels;
mobile navigation, Escape, anchor navigation, six keyboard-operated FAQs, the
skip link, screenshot access, image loading, external requests and browser
errors. It saves desktop/mobile full-page screenshots and runs axe against
WCAG 2.2 AA tags with FAQ answers exposed. Results are written to
`artifacts/qa/`. Automated accessibility checks cannot prove complete WCAG
conformance; visual inspection and keyboard checks remain necessary.

The separate demo suite runs one complete approximately eight-second replay
using real browser time, observing the empty, typing, compiling, running and
completed phases. It checks exact source/output, final-state hold, Replay,
Pause/Resume, Skip, offscreen suspension, keyboard control, reduced-motion
behavior and the genuine screenshot fallback when JavaScript is disabled.
It also verifies first-viewport placement, code size of at least 14 CSS pixels,
code-line alignment and absence of vertical code clipping. The animation is an
illustration and its duration is never treated as compiler performance. Evidence
is written to `artifacts/demo-qa/`.

Lighthouse uses mobile emulation at 390 × 844, its default simulated mobile
network/CPU throttling, and the actual installed browser. JSON and HTML reports,
environment metadata, scores, LCP, CLS and TBT are written to
`artifacts/lighthouse/`. Local measurements do not represent hosting latency or
field Core Web Vitals. INP is not inferred from TBT or a navigation-only audit.

## Native Java intelligence capability update

On 23 October 2026, after the native desktop acceptance harness passed, the
Features section gained the three approved descriptions for inline Java
completion, source navigation and quick documentation. The existing HelloWorld
replay and its benchmark disclaimers were preserved.

The updated production build passed `npm test` (12 checks), the general browser
suite (38 checks), and Astro type checking (0 errors, 0 warnings; one existing
nonblocking hint in `tests/performance.mjs`). The six responsive widths above
passed without page overflow, failed assets, external requests or console
errors. Desktop and mobile axe checks reported zero violations. The desktop
capture was inspected: the three compact capabilities form a single row below
the existing features, and stack on smaller screens. Fresh captures are in
`artifacts/qa/`.

The demo-specific suite and Lighthouse numbers below are results from the
preceding hero release; they were not remeasured for this static copy/style
update. No new JavaScript or dependencies were introduced.

## Interactive hero v2

Validated on 23 October 2026 against the production build served at
`http://127.0.0.1:4321`, after the mobile code-region accessibility fix. Runtime:
macOS 26.5.1 ARM64, Node 26.3.0, Chromium 156, Playwright 1.64.1,
axe-core 4.14.0 and Lighthouse 13.5.0.

| Validation | Actual v2 result |
| --- | --- |
| Content and timeline unit tests | 12 passed, 0 failed |
| General browser suite | 38 passed, 0 failed |
| Interactive demo browser suite | 25 passed, 0 failed |
| General responsive sizes | 320 × 844, 390 × 844, 768 × 1024, 1280 × 800, 1440 × 900, 1920 × 1080 |
| Demo-specific sizes | 390 × 844, 768 × 1024, 1440 × 900, 1920 × 1080 |
| axe WCAG violations | 0, desktop and mobile |
| Console errors / failed assets / external page requests | 0 / 0 / 0 |
| Demo JavaScript bundle | 6,166 bytes raw / 2,583 gzip / 2,305 Brotli |

The real-time journey took 8,121 ms including page navigation and observed 129
distinct source/output/state samples: idle → typing → compiling → running →
completed. `Hello TurboJ!` appeared during the running phase before the exit
marker. This is the duration of an intentional animation, not a Java execution
benchmark. Code and output matched the exact reviewed example. The final state
remained stable and Replay, Pause/Resume and Skip worked with the keyboard.
Scrolling offscreen froze progress; returning resumed it unless the user had
paused. A separately labeled simulated `document.hidden` / `visibilitychange`
test verified the background-visibility handler; an OS background-tab test was
not performed.

Reduced motion displayed the complete result immediately, including after
Replay. Disabling JavaScript showed the genuine application screenshot and hid
the inactive demo controls. The original PNG still passed the byte-for-byte
integrity check. The rendered replay contains no invented millisecond timings;
the native capture's numbers remain a separate, explicitly identified example.

The whole editor fits in the first viewport at each demo-specific size. At
390 × 844 it begins at approximately 297 px and ends at 677 px. Code sizes are
14, 16, 16 and 18 CSS pixels respectively. Code lines align with the cursor's
line height without vertical clipping. On mobile, the 413 px source region
inside a 342 px viewport is reachable with Shift+Tab from Replay and scrolls
with ArrowRight. It has an accessible region name and no hidden ancestor.
Computed syntax colors matched the required yellow keywords (`#ffff55`) and
aqua strings (`#56ffff`), including a partially typed keyword.

Desktop/mobile screenshots were inspected for hierarchy, clipping and console
visibility. The real screenshot fallback was also captured with JavaScript off.
The sole incomplete axe category concerns decorative glyph contrast and was
reviewed visually; automated checks do not prove complete assistive-technology
support across browsers.

### V2 Lighthouse mobile measurement

One run on the final build at 20:31:53 UTC, after the browser suites finished,
using the same 390 × 844 mobile simulation, 150 ms RTT, 1,638.4 Kbps throughput
and 4× CPU slowdown documented above. No poor runs were discarded.

| Metric | Actual v2 result |
| --- | --- |
| Performance / Accessibility / Best Practices / SEO | 100 / 100 / 100 / 100 |
| Largest Contentful Paint | 1,508 ms |
| First Contentful Paint / Speed Index | 1,208 ms / 1,208 ms |
| Cumulative Layout Shift | 0.000384 |
| Total Blocking Time | 0 ms |
| INP | Not measured by this navigation audit |

These are local laboratory results, not field Core Web Vitals or a performance
guarantee for the public host. Current evidence: `artifacts/qa/`,
`artifacts/demo-qa/` and `artifacts/lighthouse/`. Earlier static-release reports
were retained separately in `artifacts/qa-local/`, `artifacts/qa-v1-published/`
and `artifacts/lighthouse-v1/`. The historical results below describe that
earlier release and are not reused as v2 measurements.

## Initial static release results

Measured on 23 October 2026 against the production build served by Astro preview
at `http://127.0.0.1:4321`. Environment: macOS 26.5.1, ARM64, Node 26.3.0,
Chromium 156.0.8078.4, Playwright 1.64.1, axe-core 4.14.0, Lighthouse 13.5.0.

| Validation | Actual result |
| --- | --- |
| `npm test` | 6 passed, 0 failed |
| Browser checks | 38 passed, 0 failed |
| Tested viewport sizes | 320 × 844, 390 × 844, 768 × 1024, 1280 × 800, 1440 × 900, 1920 × 1080 |
| Horizontal overflow | None at any tested size |
| Failed images / requests / browser errors | 0 / 0 / 0 |
| Third-party page requests | 0 |
| axe WCAG 2.2 AA checks | 0 violations on mobile and desktop; 24 rules passed at each size |
| Original full-resolution screenshot | Byte-identical to the repository capture; linked image opens with the keyboard |

Keyboard validation covered the mobile menu, Escape and focus return, every
navigation anchor, all six FAQ controls, and the skip link. Visual inspection of
the desktop and complete mobile captures found no clipped text, overlapping
sections or missing console area. The screenshot remains an original product
capture; mobile readers can open the full-resolution image.

The initial axe run requested manual review for decorative arrow contrast and
the performance panel's accessible name. The arrows are black on gray or yellow
and supplementary to text labels. The performance panel was subsequently given
`role="group"` so its heading supplies a valid accessible name. A final repeat of
all six content tests and 38 browser checks passed after that fix and the real
canonical URL were built. The portable browser resolver successfully selected
the installed Chrome for Testing without a workstation-specific path. The final
axe reports leave only the manually reviewed decorative-glyph contrast checks
incomplete. Automated results do not cover assistive-technology behavior on every
platform.

### Lighthouse mobile measurement

One measured run, started at 19:54:04 UTC, using the installed Chromium headless
shell. All results are retained. Settings: 390 × 844 CSS pixels, device scale 1,
simulated 150 ms RTT / 1,638.4 Kbps throughput and 4× CPU slowdown. The browser's
reported host user-agent is an emulation string, not evidence of Intel hardware.

| Metric | Measured result |
| --- | --- |
| Performance | 100 / 100 |
| Accessibility | 100 / 100 |
| Best Practices | 100 / 100 |
| SEO | 100 / 100 |
| Largest Contentful Paint | 1,503 ms |
| First Contentful Paint | 1,203 ms |
| Speed Index | 1,203 ms |
| Cumulative Layout Shift | 0 |
| Total Blocking Time | 0 ms |
| INP | Not measured by this navigation audit |

The tested local build meets the stated Lighthouse, LCP and CLS budgets in this
run. These are laboratory results from a local server, not a guarantee for the
public host, every device or every network. No field Core Web Vitals have been
collected. The later semantic-role and canonical-URL changes do not constitute a
second Lighthouse measurement.

Detailed evidence is generated locally in `artifacts/qa/report.json`,
`artifacts/qa/axe-390.json`, `artifacts/qa/axe-1440.json`,
`artifacts/qa/homepage-390.png`, `artifacts/qa/homepage-1440.png`, and
`artifacts/lighthouse/run-1.{json,html}`. The original local browser reports were
preserved in `artifacts/qa-local/` before the live-host verification below.
Generated reports are ignored by Git; rerun the commands above to reproduce them.

### Published host verification

On 23 October 2026, the complete 38-check browser suite also passed against
`https://turboj.luca-marrocco.workers.dev`, the published Cloudflare static-assets
deployment (version `8b79054e-6e8e-4faf-b552-c6f6c522c010`). All six viewport sizes,
mobile menu/Escape, navigation, FAQ keyboard interaction, skip link and original
screenshot access passed. The live page produced no failed image or network
requests, no console errors, no horizontal overflow and no third-party requests.
Mobile and desktop axe audits again reported zero WCAG violations.

The published HTML has canonical URL `https://turboj.dev/`; its fonts and images
are served from local asset paths. HTML inspection and browser request monitoring
found no injected Cloudflare analytics or other tracking script. The domain
`turboj.dev` itself was awaiting DNS availability during this verification, so
these results apply to the Workers deployment URL, not a completed custom-domain
check. The Lighthouse scores above remain the separate local-server measurement;
Lighthouse was not rerun against the published host.

```sh
QA_BASE_URL=https://turboj.luca-marrocco.workers.dev npm run test:browser
```
