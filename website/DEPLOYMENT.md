# Production deployment

The static production build was deployed on 2026-10-23 to Cloudflare Workers
Static Assets, project `turboj`.

- Custom domain: https://turboj.dev
- Public repository: https://github.com/turboj-dev/turboj
- Cloudflare fallback: https://turboj.luca-marrocco.workers.dev
- Initial deployment version: `8b79054e-6e8e-4faf-b552-c6f6c522c010`
- Source/output: `website/src/` → `website/dist/`
- Runtime: static assets only; no application server or Worker request handler.

Cloudflare confirmed the custom-domain binding. The public DNS-over-HTTPS
resolver returned Cloudflare edge addresses for `turboj.dev`; an HTTPS request
using the returned address passed certificate verification and returned HTTP
200. The workstation's default DNS resolver still cached the earlier missing
record immediately after deployment. No TLS verification was disabled.

The fallback host passed all 38 browser checks, including all six viewport
sizes, keyboard controls, screenshot access, local asset loading and axe. See
[QA.md](QA.md) for results and the separate local Lighthouse measurement.

This is an explicit Wrangler deployment. GitHub pushes do not automatically
publish a new site. Build, check, then run `npx wrangler@4.152.0 deploy` from
`website/`, using the owner's authenticated Cloudflare account. The configuration
is in `wrangler.toml`; no credentials are committed.

## Ecosystem update — 2026-10-24

After native verification, commit `117714c` added the RefactorJ/CommandJ ecosystem
section. Build, 27 source tests and 40 browser checks passed before deployment.
Wrangler deployed version `ecc21a02-e532-4bae-8664-30daa49485cc` to both hosts.
The public `https://turboj.dev/` HTML was fetched with TLS verification and matched
the verified local build byte for byte (SHA-256
`1a8f6415dfad86229afd6b71b8fa7bce6cc4ef365b487d952d9c9b7cd92f53dc`).
The new copy links to the independent source projects and does not claim
marketplace publication or unmeasured performance.

## MavenJ update — 2026-10-24

Commit `09f0a16` adds the independently reusable MavenJ library and native TurboJ
project integration. The site build, 27 source tests, 40 browser checks and 74
theme checks passed. Native integration passed the 462-test reactor verification,
subsequent Auto Test regression and 25 real-window Maven checks before deployment.
Wrangler version `39262b70-f9ae-4985-bb2b-a0419356edc7` publishes the Maven section.
The HTTPS response from `https://turboj.dev/` matches the tested local HTML byte
for byte: SHA-256 `b1de50e7b465137c71f349a7a2dbd6a1837f84ecfdead721b21f437de3a48354`.
The page links to source, distinguishes Fast Compile from Maven Build, and makes
no marketplace, Maven Central or unmeasured performance claim.

## Recorded five-chapter hero and compact controls — 2026-10-25

The user explicitly authorized publication after requesting only Play/Pause and
a one-line typewriter explanation beneath the demo. The old secondary controls
and evidence captions are retained hidden; the original Classic screenshot link
is removed. Native workspace border/font changes remain a separate preview and
are not mirrored in the hero pending user acceptance.

Wrangler **4.152.0** deployed static assets to `turboj`:

- Version: `1f1ad544-2954-4641-aee5-bef96eb9f198`.
- Hosts: `https://turboj.dev` and `https://turboj.luca-marrocco.workers.dev`.
- Assets: 835 scanned, 370 new/modified uploads; dry run and deployment PASS.
- Homepage SHA-256: `f9dd01db80fa6030135e0600d6d0690ca796b999ef1e81115ce005079424898e`.
- Session details SHA-256: `f23c0af7f3462d864e985ef8086b40fea0286580b820b18f53e36af6cacabbe1`.

Fourteen HTTPS responses were checked against the tested `dist/` bytes: homepage,
details page, hero manifest, all five chapter files, both JavaScript bundles,
three generated keyboard downloads and the original screenshot. Every response
returned HTTP 200 with the exact expected SHA-256; TLS verification stayed enabled.
The original native capture and operation measurements were not regenerated.
The homepage suite was then run against `https://turboj.dev`: **46 browser checks
PASS**, including all six viewport sizes, visible controls, keyboard navigation,
axe audits and original-image integrity. Reports: `artifacts/hero-minimal-qa/live/page/`.

Predeployment validation: 51 unit/content/evidence checks and 200 browser checks
PASS; Astro production build PASS. Lighthouse mobile: 99/100/100/100, LCP
1,807 ms, CLS 0.000697, TBT 0 ms. See
[`website-hero-release-2026-10-25.md`](../docs/website-hero-release-2026-10-25.md).
Deployment and live verification logs are in `artifacts/hero-minimal-qa/deployment/`.

## Native Output command-line finale — 2026-10-25

After local review, the user explicitly authorized publication of implementation
commit `61208bc039d769a96b98c5e8b103766685e5c065`. The final chapter now records
Escape → colon → `! curl` inside native TurboJ Output, retaining the editor,
real HTTP 200 JSON and exit status. Full-story playback repeats after a separate
three-second reading hold. Earlier chapter recordings remain unchanged.

Wrangler **4.152.0** dry run and deployment passed:

- Version: `1f91273a-6b28-4537-9d40-8defcbad21b8`.
- Hosts: `https://turboj.dev` and `https://turboj.luca-marrocco.workers.dev`.
- Assets: 1,003 scanned; 84 uploaded; Wrangler reported 803 already uploaded.
- Homepage SHA-256: `1d2eec79cabe2ffd3fbc2b605c95f8e0043a24f3a9e652ad7b3739fd2ec0ae85`.
- Details page SHA-256: `a0f92cc210089a919d1e93bb5ae8fab5857fef950286cf06bee5df32a0b04a75`.

Twenty public HTTPS assets returned HTTP 200 and matched the validated build
byte for byte: both pages, manifest, evidence/checksum indexes, five chapters,
both JavaScript bundles, keyboard references, original screenshot and final
native trace/nomination/artifact index/screenshot. TLS verification remained on.
The homepage suite then passed **46 live browser checks**, including responsive
layouts, keyboard navigation, axe audits and request/console-error checks.

Predeployment validation: **53 website tests and 200 browser checks PASS**;
hero JavaScript 6,175 bytes gzip and all JavaScript 14,318 bytes gzip.
Native commands and the actual Spring/curl recording were verified separately.
See [`website-native-console-finale.md`](../docs/website-native-console-finale.md)
and the committed [deployment evidence](../docs/evidence/output-command-line/deployment.json).
Local deployment logs and live browser artifacts remain under
`artifacts/native-console-qa/deployment/` and `artifacts/native-console-qa/live/`.

## Saved-theme flash correction — 2026-10-25

Commit `294984a` prevents the immutable Classic screenshot from appearing during
JavaScript startup, chapter loading or fetch failures. The first loading surface
uses the saved palette; later chapter loads retain the preceding themed frame.
The real screenshot remains untouched for no-JavaScript use.

Wrangler **4.152.0** dry run and production deployment passed:

- Version: `fdc04d6c-9e29-4bbf-a754-7598a8cabf12`.
- Hosts: `https://turboj.dev` and `https://turboj.luca-marrocco.workers.dev`.
- Assets: 1,003 scanned; three uploaded (homepage, hero JavaScript, CSS).
- Homepage SHA-256: `0706193e7d0007d2a640e728983b17b9f1abd53f08e3b29f7da3b61b958332e5`.

Twenty-two public HTTPS assets, including both JavaScript and CSS bundles,
returned HTTP 200 and matched the verified local build byte for byte.
Predeployment checks: **53 source/evidence tests and 92 browser checks PASS**.
The focused suite then passed **all 10 checks on the public site**, including
saved Monokai startup, slow/failed loads, every distinct menu/dialog and the loop.
Live reports are in `artifacts/theme-first-paint-qa/live-confirmed/demo-theme/`.
The preceding live run is retained in `live/`: its loop assertion observed a
transient flag before the final chapter loaded; the test now waits for that
chapter's ready state. No further production-code change was needed.
Reports, frame observations and deployment logs are retained under
`artifacts/theme-first-paint-qa/`; source and timing evidence are unchanged.
