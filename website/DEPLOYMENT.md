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
