# Theme catalog implementation — 1.0.4

The native editor and website share **Options → Themes**, three primary presets
and **More themes…**. The compact browser has a search field and a vertically
scrollable 3×2 viewport of miniature Java editors. Arrow keys preview selections;
Page Up/Down move by six entries. Enter/Apply confirms, while Escape, Cancel or
closing the dialog restores the original appearance. Previewing never persists a
preference or changes the source, cursor, undo history, program output or website
demo position.

**Random theme** uses a five-name ASCII drum with a fixed center pointer. It
starts with rapid continuous motion, then decelerates using cubic ease-out and
stops after 3–5 seconds of active playback. Each centered candidate applies its
real palette. The final choice is uniform over the current search results;
brightness ordering limits repeated light/dark alternation during the traversal.
Skip and reduced motion land immediately. A single result needs no animation.
Pending palette loads pause active time; hidden browser tabs suspend animation.
The final palette and first seven previews are ready before motion starts.

The authoritative native registry contains **103 genuine themes**: Turbo Classic,
Modern Dark, Modern Light and 100 licensed adaptations from pinned upstream
editor themes. This is a curated selection, not a claimed popularity ranking.
The [catalog documentation](theme-catalog.md) and [inventory](theme-catalog-inventory.md)
record provenance, licensing, mappings and minimal contrast adaptations. Full
third-party notices ship with both application and website. The importer verifies
122 original file hashes and reproduces all generated files offline.

Only the three primary palettes enter initial website CSS. Other palettes load
on demand. Both implementations retain a 24-palette LRU; native browsing reuses
six preview widgets, while web virtualization mounts six complete cards and at
most nine during partial scrolling. Metadata/indexing and native file reads run
off the Swing event thread. Old asynchronous results cannot replace a newer
selection. Custom native themes stay local; there is no marketplace, automatic
network retrieval or upload service.

## Native validation

The final `./mvnw -q package` completed on macOS ARM64 with Corretto 25.0.4.1:
**116 tests**, zero failures, errors or skips (core 42, worker 15, app 54, CLI 5).
Eight offline theme-import tests also passed. No dependency was added.

The native real-window acceptance harness passed vertical scrollbar and wheel
navigation, offscreen selection preservation, keyboard navigation, preview/cancel,
custom palette reload, Skip, reduced motion and cancellation during rotation.
The fast natural spin completed in **3.08 seconds** in that run. The same harness
checked live stdin, source/undo preservation, inline completion and documentation
while changing themes.

`ThemeBrowserLatency` measured the actual 103-theme catalog in a 720×480 dialog:

| Operation | Samples | p50 | p95 |
| --- | ---: | ---: | ---: |
| First open | 1 | 100.81 ms | — |
| Warm open | 5 | 24.84 ms | 31.74 ms |
| Search | 18 | 5.86 ms | 28.50 ms |
| Cached arrow selection | 48 | 4.46 ms | 16.85 ms |

These are local action-to-ready timings including EDT dispatch, not compositor
paint latency or cross-hardware guarantees. Hardware: Apple M5, 16 GB RAM.
The 3–5-second wheel duration is an interaction choice, not compiler performance.
Reports and screenshots are under ignored `app/target/`.

## Website validation

The production Astro build and type checking passed: zero errors, zero warnings,
one existing nonblocking test-file hint. All **27 unit checks** passed, including
exact native token exports, bounded metadata/cache handling, preference restoration,
content integrity and deterministic spin timing.

The final browser, accessibility and performance results are recorded in
[website/QA.md](../website/QA.md), alongside reproduction commands and local
artifact paths. Earlier slow-wheel measurements are marked historical and do
not describe this revision.

Final browser counts: **74 theme, 6 spin, 3 scrolling, 40 general-page and
25 demo checks**, all passing. One final Lighthouse mobile run at
2026-10-23 22:25:15 UTC measured 100/100/100/100, LCP 1,653.75 ms,
CLS 0.000383 and TBT 0 ms under the documented simulated mobile profile.
These are local lab results; field INP was not measured.

The production HTML references four scripts totaling **28,503 raw bytes** or
**10,712 bytes when each script is independently gzipped**. Theme bootstrap plus
controller contributes 22,101 raw / 7,952 gzip bytes. Inline scripts compress
within HTML in production, so these figures are accounting measurements rather
than observed CDN transfer sizes. No framework hydration or external asset
request was introduced.

## Distribution

The version remains a development preview. macOS packaging includes a private
Java runtime; no separate JDK is required. Developer ID signing, notarization
and Intel validation remain incomplete. See [Themes](themes.md) for the schema,
custom-theme workflow and native-to-web export contract.

The static website was deployed to **https://turboj.dev/** as Cloudflare version
`a4480c94-0098-467c-9375-a7a0b7a83d54`. The public homepage and 103-theme metadata
matched the local build byte-for-byte. A subsequent production browser check at
390 and 1440 px passed vertical scrolling, natural random rotation, global
palette changes, source preservation, Apply and reload persistence with no page
errors. The two configured spin durations were 3,743 ms and 3,579 ms.

`target/release-themes-1.0.4-final/TurboJ-1.0.4.dmg` was generated with the final
JAR. The complete theme acceptance harness passed again using its bundled Java
runtime; that run completed a natural spin in **3.25 seconds with 26 real palette
crossings**. Its SHA-256 is:

```text
d1d5cddcaf12ac4a0717afa7a3c812f9134255060fdf8ff25bc8939232e09baa
```
