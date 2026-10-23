# TurboJ website

The official English product website, implemented with Astro 7.4.2, strict
TypeScript 6.0.3 and plain CSS. The output is static HTML, CSS, local images and
fonts, a small mobile menu script and a deterministic TypeScript UI replay. No hydrated framework,
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
- `src/styles/`: exact DOS palette, local font faces and responsive layout.
- `src/pages/`: homepage, generated robots.txt and sitemap.xml.
- `assets/original/`: unmodified application screenshot and provenance.
- `scripts/prepare-assets.mjs`: reproducible image/font generation.
- `tests/`: copy, asset integrity, browser, accessibility and Lighthouse checks.

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
