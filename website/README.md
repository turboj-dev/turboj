# TurboJ website

The official English product website, implemented with Astro 7.4.2, strict
TypeScript 6.0.3 and plain CSS. The output is static HTML, CSS, local images and
fonts, a small shared theme switcher, mobile menu and deterministic TypeScript UI replay. No hydrated framework,
server runtime, analytics, external font CDN or browser network dependency.

## Development

Use Node **22.23.4 or newer** (verified with 26.3.0) and npm. From `website/`:

```sh
npm ci
ASTRO_TELEMETRY_DISABLED=1 npm run dev
```

Open the URL printed by Astro. Dependencies and fonts are pinned in
`package-lock.json`. `npm run assets` regenerates images and local fonts offline
once dependencies have been installed.

## Build and verify

```sh
ASTRO_TELEMETRY_DISABLED=1 npm run check
ASTRO_TELEMETRY_DISABLED=1 npm run build
npm test
ASTRO_TELEMETRY_DISABLED=1 npm run preview -- --port 4321
npm run test:browser
npm run test:demo
npm run test:themes
npm run test:spin
npm run test:scroll
npm run test:latency
npm run test:performance
```

Browser checks require a locally installed Chrome/Chromium. Set `CHROME_PATH`
when needed. See [QA.md](QA.md) for coverage, environment and measured results.
Output goes to `dist/`; test reports go to ignored `artifacts/`.

## Structure

- `src/components/`: semantic page sections, native mobile menu and FAQ.
- `src/data/site.ts`: central navigation, metadata and public URLs.
- `src/data/copy.ts`: repeated feature, workflow and FAQ content.
- `src/scripts/demo-timeline.ts`: the reviewed example and deterministic replay events.
- `src/scripts/turboj-demo.ts`: incremental DOM rendering and playback controls.
- `src/scripts/theme-preference.ts`, `theme-switcher.ts`: initial preference and the single theme state.
- `src/styles/`: native-generated palettes, local font faces and responsive layout.
- `src/pages/`: homepage, generated robots.txt and sitemap.xml.
- `assets/original/`: unmodified application screenshot and provenance.
- `scripts/prepare-assets.mjs`: reproducible image/font generation.
- `scripts/export-themes.mjs`: validated export from the native application theme JSONs.
- `tests/`: copy, asset integrity, browser, accessibility and Lighthouse checks.

## One native and website theme system

The authoritative definitions live in `app/src/main/resources/themes/` in the
parent repository. Its `catalog.json` lists the bundled JSON files and three
primary presets: **Turbo Classic**, **Modern Dark**, **Modern Light**. Their IDs
are `classic`, `dark`, `light`. Add curated themes to that native registry to
publish them on both platforms; do not duplicate their palettes in website CSS.

`npm run themes` generates `src/styles/themes.generated.css`,
`src/data/themes.generated.ts`, `public/themes/turboj-themes.json`, a metadata-only
`public/themes/catalog.json` and individual `public/themes/palettes/*.json` files.
Only the primary presets enter the initial CSS and bundle. Dev, check and production build perform this export automatically. The
generated files are tracked for review, carry a native source hash, and must not
be hand-edited. `npm run themes:check` fails if they have drifted. Native and web
tests check schema, exact token parity and contrast. [The shared schema](../docs/themes.md)
also describes popup, diagnostic, selection and ghost-text colors.

**Options → Themes** lists the primary presets and **More themes…**. The latter
opens a searchable catalog with a vertically scrollable 3×2 viewport of miniature
Java editors. The scrollbar, wheel and touch gestures browse the catalog; arrows
move selection and Page Up/Down move by six entries. Selection previews the appearance.
Apply/Enter commits the choice; Escape/Cancel restores the previous theme.
The same menu moves into mobile navigation below 1000 px. There is no fixed
three-button switcher. Visible rows are virtualized: six complete cards, with at
most nine mounted during partial scrolling. Additional palettes load on demand
into a 24-entry LRU cache. The real catalog contains **103 themes**:
three original presets and 100 curated adaptations from pinned, licensed upstream
editor palettes. Their [inventory, licenses and offline regeneration](../docs/theme-catalog.md)
are documented. Additional 1,000-entry catalogs used in stress tests remain
temporary fixtures. Private native user themes are not uploaded to the website.

**Random theme** spins a five-name ASCII drum for 3–5 seconds of active
playback. It starts with rapid continuous motion and decelerates; each center
crossing previews its real palette across the page and editor demo. The wheel
orders candidates by background brightness to limit abrupt light/dark changes,
while the final choice remains uniform over the current search results.
The catalog itself stays alphabetical. Apply keeps it; Cancel restores the original.
Skip finishes immediately, and reduced motion skips the animation. No choice is
saved during the spin. Pending palette loads and hidden tabs suspend playback.

The website applies `data-theme` and validated color variables to `<html>`;
the full page and the one demo inherit the same state. A synchronous head
script restores `localStorage['turboj.theme']` before rendering. Additional
themes keep their validated palette in `localStorage['turboj.theme.palette']`
so reloading does not flash a different appearance. New/invalid preferences
use Classic; storage denial is caught, and the current page still switches.
System dark mode never overrides the choice. Changing color does not call the
demo controller or modify its source, timing, cursor, output or pause state.
Browser and desktop preferences are independent, with no account or network
synchronization.

The original application screenshot is never recolored. AI-generation and
project-JUnit execution are not advertised as implemented; their reserved token
names do not make those native capabilities available.

## Assets and factual corrections

The available authentic capture is 1060 × 740. Its values are a **0.03 ms cache
hit**, **1.17 ms execution**, and **2.22 ms round trip**. The supplied marketing
brief described a different capture (0.08/2.60/8.27 ms), which was unavailable.
The page therefore uses the values actually visible in the repository image.
The v2 hero and repeated feature text have been shortened to prioritize the
interactive example. These values still describe one native application run,
not a benchmark, fresh compilation, or the illustrated `HelloWorld.java` replay.

The main lossless WebP is 15,308 bytes and is decoded/pixel-compared against the
original in every asset build. The larger original PNG opens in a separate tab.
The screenshot remains accessible from the demo caption and is the visible
fallback when JavaScript is unavailable. Font
licenses and full asset provenance are documented in [ASSETS.md](ASSETS.md).

## Interactive hero

The eight-second sequence is an **illustration**, not a Java runtime. It reveals
the fixed `HelloWorld.java` source, shows compilation status, then output and
successful exit. Animation timestamps never appear as compiler measurements.
The independent performance section continues to describe the original native
`Hello.java` screenshot.

One `requestAnimationFrame` scheduler advances an immutable event model using
monotonic elapsed time. Source tokens are created once and only changed text is
updated; there is no HTML injection, editor library or per-character timer.
Replay resets source, filename, output, cursor, status and progress. Pause
preserves position, and Skip renders the exact terminal state. Playback starts
once when the frame enters view, suspends outside view or in a hidden document,
and stops after completion. A manual pause survives scrolling and visibility
changes. Reduced-motion users receive the completed example immediately,
including after pressing Replay.

Animated visual text is excluded from the accessibility tree so typed characters
do not create repeated announcements. A static description and complete source
accompany keyboard-operated buttons and concise state messages. The source pane
is an accessible, focusable region, so mobile code retains readable text and
supports arrow-key horizontal scrolling without scrolling the overall page.
The screenshot fallback and replay share a reserved stage to prevent layout
shifts. No native trace import/export is implemented yet; future measured traces
must remain explicitly distinguished from this illustrative event sequence.

## Static deployment

Production: **https://turboj.dev**, served through Cloudflare Workers Static
Assets. There is no application Worker handler: Cloudflare serves `dist/`.
`wrangler.toml` declares the static directory and the verified custom domain.

Public source: https://github.com/turboj-dev/turboj (desktop and website).

After a successful build, an authenticated maintainer can redeploy with:

```sh
npx wrangler@4.152.0 login
WRANGLER_SEND_METRICS=false npx wrangler@4.152.0 deploy
```

Credentials remain in the maintainer's local Wrangler profile. They are never
part of this repository. This is an explicit deployment workflow; pushing to
GitHub alone does not trigger a deployment.

Upload the contents of `dist/` to a static host. Set `SITE_URL` before building
to configure the actual canonical origin, Open Graph URLs, robots.txt and
sitemap.xml. There is no SPA routing fallback or runtime server to configure.

On a host with Git builds, use `website/` as the root, `npm ci && npm run build`
as the build command, and `dist` as the output directory. Set Node 26.3.0 and
`ASTRO_TELEMETRY_DISABLED=1` in the build environment. Do not upload dependency
directories or local test artifacts.

The site exposes only working destinations. Public source, documentation or
download links must be added only once those destinations have been verified.
No download, release date, affiliation, benchmark average or user count is
invented. Automated accessibility checks supplement manual keyboard and visual
inspection; they do not establish full WCAG conformance.
