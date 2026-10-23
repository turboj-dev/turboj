# TurboJ website

The official English product website, implemented with Astro 7.4.2, strict
TypeScript 6.0.3 and plain CSS. The output is static HTML, CSS, local images and
fonts, plus a small script for closing the mobile menu. No hydrated framework,
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
npm run test:performance
```

Browser checks require a locally installed Chrome/Chromium. Set `CHROME_PATH`
when needed. See [QA.md](QA.md) for coverage, environment and measured results.
Output goes to `dist/`; test reports go to ignored `artifacts/`.

## Structure

- `src/components/`: semantic page sections, native mobile menu and FAQ.
- `src/data/site.ts`: central navigation, metadata and public URLs.
- `src/data/copy.ts`: repeated feature, workflow and FAQ content.
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
All other supplied English marketing copy is preserved. These values describe
one application run, not a benchmark or fresh compilation.

The main lossless WebP is 15,308 bytes and is decoded/pixel-compared against the
original in every asset build. The larger original PNG opens in a separate tab.
The complete screenshot and console remain visible at every viewport. Font
licenses and full asset provenance are documented in [ASSETS.md](ASSETS.md).

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
