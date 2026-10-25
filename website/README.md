# TurboJ website

The official English product website, implemented with Astro 7.4.2, strict
TypeScript 6.0.3 and plain CSS. The output is static HTML, CSS, local images and
fonts, a small shared theme switcher, mobile menu and an illustrated HelloWorld replay. No hydrated framework,
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
node tests/session-browser.mjs
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
- `src/scripts/demo-timeline.ts`: the eight-second illustrative HelloWorld sequence.
- `public/demo/hero-story/`: verified five-chapter manifest, lazy checkpoints and native evidence.
- `scripts/prepare-hero-story.mjs`: validates the new trace and preserves measured intervals while shortening only explicitly classified pauses.
- `src/pages/demo-session.astro`: accessible sources, logs, captures and measurement definitions for the hero.
- `src/data/recorded-session.json` and `scripts/prepare-session.mjs`: preserved historical evidence and its original preparation tool.
- `demo/`: reproducible projects, capture harness and control evidence.
- `src/pages/session.astro`: public measurement conditions and source captures.
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

The original application screenshot is never recolored. A compact feature article
describes the optional native AI completion implementation, disabled by default.
Local Ollama FIM inference was verified on macOS against 12 compiled and executed
examples covering method bodies and insertion between existing statements. The
OpenAI Responses adapter has protocol-fixture coverage; live cloud validation
remains pending. The article links to [setup, privacy, and validation](../docs/ai-completion.md)
and does not add AI behavior to the browser replay. The separate recorded session
exercises actual bundled JUnit tests. Reserved theme token names alone do not
establish a native capability.

## Assets and factual corrections

The available authentic capture is 1060 × 740. Its values are a **0.03 ms cache
hit**, **1.17 ms execution**, and **2.22 ms round trip**. The supplied marketing
brief described a different capture (0.08/2.60/8.27 ms), which was unavailable.
The page therefore uses the values actually visible in the repository image.
These values describe an earlier native prototype run, not a benchmark of
TurboJ 1.0.10 or a fresh compilation. The hero and historical recorded session
each use their own measured operation times.

The main lossless WebP is 15,308 bytes and is decoded/pixel-compared against the
original in every asset build. The larger original PNG opens in a separate tab.
The historical screenshot remains accessible from the performance section and is the visible
fallback when JavaScript is unavailable. Font
licenses and full asset provenance are documented in [ASSETS.md](ASSETS.md).

## Native-verified five-chapter hero

The homepage reconstructs one newly captured native session: **Hello World →
Tests → Refactoring → AutoRun / AutoTest → Spring Boot + curl**. It executes no
Java and makes no localhost requests in a visitor's browser. The capture driver
enters source into the real editor and invokes existing commands and dialogs;
it does not alter TurboJ or its libraries. The price example uses integer cents
and two deterministic JUnit tests throughout refactoring and automation.

Each chapter loads independently from a checkpoint. Play/Pause, Replay,
Previous/Next, Skip to Result and Play Full Story control a single active-time
clock. Initial Hello World playback runs once when visible and holds its result.
Reduced motion starts at the result; the checkpoint selector also exposes the
recorded intermediate states. Hidden/offscreen playback suspends the clock.
Theme changes preserve playback state. The external curl scene is explicitly
separate from the native console.

`/demo-session/` exposes complete source revisions, output, original frame
captures, environment, hashes, command-to-result measurements and editorial cuts.
Only explicitly recorded typing, reading or navigation intervals may be shortened;
system operations and unclassified gaps retain their measured duration at 1×.
These are times from one session, not performance guarantees. Frame captures are
actual Swing painting on the EDT, not desktop video; browser reconstruction is
not pixel-identical footage. No video recording is claimed.

For reproduction, see [the new capture instructions](demo/capture/HERO_STORY.md).
Build and verify locally before any separately authorized deployment:

```sh
npm run build
npm test
npm run preview -- --port 4323
# In another terminal, from website/:
QA_BASE_URL=http://127.0.0.1:4323 QA_ARTIFACT_ROOT=artifacts/hero-story-qa npm run test:browser
QA_BASE_URL=http://127.0.0.1:4323 npm run test:demo
QA_BASE_URL=http://127.0.0.1:4323 QA_ARTIFACT_ROOT=artifacts/hero-story-qa npm run test:themes
QA_BASE_URL=http://127.0.0.1:4323 QA_ARTIFACT_ROOT=artifacts/hero-story-qa npm run test:performance
```

## Preserved historical native evidence

The supporting `/session/` page documents one complete run of the **unmodified
TurboJ application**, commit `5d801f84a183cc6d6415d6e605d442818d2e3310`.
Its 61 captures and 18 measured intervals cover Hello World, two JUnit tests,
Rename and Extract Method, tests after refactoring, separate automation on save,
Maven import, Spring Boot and a real external curl request. This captured sequence
is preserved independently of the new hero session.

The session was nominated before its times were known, after control runs.
Automated source entry and actual reading pauses remain in the data, alongside
monotonic nanoseconds, wall timestamps, authentic output, source snapshots,
unchanged screenshots, environment and cache conditions. See the
[capture instructions](demo/capture/README.md), [control notes](demo/capture/CONTROL_RUNS.md),
and [Spring measurement boundaries](demo/capture/SPRING_SESSION.md).
The [earlier replay verification](demo/VERIFICATION.md) is a historical report,
not validation of the current homepage.

The Spring server uses the explicit Maven `spring-boot:run` command: Fast Run and
Isolated Run retire its worker after main returns. Dependencies and the wrapper
were cached; this is not a cold-download measurement. Live single-file diagnostics
could not resolve the plain Java test classpath, while Run All Tests compiled and
passed both tests. These limitations and the original output remain in the evidence.
Internal compiler/test-runner durations are distinct from command-to-capture time.

To replace the evidence, run controls and then capture a new full session using
`demo/capture/README.md`. Inspect every screenshot for private data, then, from the
repository root:

```sh
node website/scripts/prepare-session.mjs target/hero-session/your-unique-session
python3 website/scripts/package-demo-projects.py
```

This refuses incomplete captures or private home paths in UI states. It copies
unchanged PNGs, writes a SHA-256 manifest, and derives the public timeline. The
selected evidence lives in `public/demo/session-2026-10-24/`; `/session/` explains
measurement conditions and links the trace, screenshots and example archive.
No video was recorded. This historical dataset and preparation command do not
replace the new hero dataset. Run the website build and checks before publishing
either change.

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
