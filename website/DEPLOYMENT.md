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
