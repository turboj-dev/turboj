# Shared themes and settings

TurboJ ships 103 bundled palettes: three primary presets and 100 curated editor palettes with their upstream MIT notices. The primary presets are **Turbo Classic** (`classic`), **Modern Dark** (`dark`), and **Modern Light** (`light`). Native Java2D and turboj.dev consume the same authoritative JSON files and [`catalog.json`](../app/src/main/resources/themes/catalog.json) registry in [`app/src/main/resources/themes/`](../app/src/main/resources/themes/).

Choose **Options → Themes** for the three presets. Its last command, **More themes…**, opens a compact 720×480 searchable browser with three columns and two rows of miniature Java editor previews. Selecting a card or moving through the catalog immediately previews its palette in the main window. **Apply theme / Enter** commits the selection; **Cancel / Escape / closing the dialog** restores the original theme. A real vertical scrollbar and mouse-wheel/trackpad scrolling move through a virtual two-row viewport containing only six preview components. Scrolling preserves the selected theme; clicking a card or keyboard navigation previews a choice. Arrow keys and Page Up/Down scroll the selection into view. No preview changes preferences. Left/Right navigate cards, Up/Down move three places, and Page Up/Down move six. In the search field, Left/Right retain normal caret editing while vertical/page keys browse without taking focus. Search and page controls remain keyboard accessible.

**Random theme** starts an explicit DOS-style text drum: five real theme names scroll past a fixed center arrow, and each center crossing previews that theme across the whole native window. The selected result is uniform across the complete current search result, not just the current page. The active spin lasts a randomly chosen 3–5 seconds, with continuous vertical motion and a cubic ease-out. For the full catalog it starts around 24 rows per second and gradually slows through 24–40 actual theme crossings. Wheel candidates are ordered by UI-background luminance to avoid repeatedly alternating bright/dark palettes; the normal catalog stays alphabetical. Small filtered pools traverse at most once, and a single matching theme lands immediately. A short traversal is shown, with the actual candidate count stated. The final palette is loaded before animation begins, the next six are prefetched on the catalog worker, and the LRU remains capped at 24. Unexpected slow reads pause active elapsed time rather than skipping palette previews; ordinary focus changes do not pause the drum, and closing the dialog stops its Swing timer. **Skip spin** lands immediately; **Reduce motion** omits animation. Landing remains a preview: **Apply** commits, and **Cancel/Escape** restores the pre-dialog theme and stops the timer.

Switching preserves source text, caret, viewport, selection, undo, semantic proposals, documentation content, console output and unsent stdin. It does not compile, execute or restart the current program. Confirmed selections are saved asynchronously from an immutable preference snapshot and restored at next launch. New installations default to Classic. Existing `modern` preferences migrate to `dark`; the retired `amber` preset and invalid IDs fall back to `classic`.

Classic uses exact DOS blue `#0000AA`, console cyan `#05AAAA`, yellow keywords and aqua strings. The editor and stdin cursors have separate tokens to remain visible against their different surfaces. Swing's logical Monospaced font is supplied by the operating system; no proprietary historical font is distributed. Classic disables Java2D text antialiasing. Font size is a separate editor preference and does not change when choosing a palette.

## Authoritative schema

Bundled themes use `schemaVersion: 2`, a stable `id`, a display `name`, and these required objects:

| Object | Required color fields |
|---|---|
| `editor` | background, foreground, cursor, selection, selectionForeground, error |
| `syntax` | keyword, string, comment, number, identifier, operator, annotation |
| `menu` | background, foreground, activeBackground, activeForeground |
| `messages` | background, foreground, errorForeground, inputForeground, cursor |
| `diagnostics` | error, warning, success |
| `chrome` | border, statusBackground, statusForeground |
| `ui` | background, foreground, surface, muted, heading, border, accent, accentForeground, link, focus |
| `documentation` | background, foreground, border, code, link |
| `intelligence` | ghost, aiGhost, selectionBackground, selectionForeground, definition, activeParameter |

`font` contains `family`, `size` (10–48), and `antialias` (boolean). The family field documents Monospaced; custom font-family loading is not implemented. IDs match `[a-z][a-z0-9_-]{0,63}`; names contain 1–120 characters. Every color is a six-digit `#RRGGBB` string. Unknown fields are ignored. Missing required v2 fields, invalid IDs/colors or unsupported schema versions are rejected. The `aiGhost` token reserves a consistent presentation color; it does not imply that AI code generation is implemented. Likewise, diagnostic success/error colors do not imply a project JUnit runner.

Java accesses the immutable token map through `Theme.color(section, field)` and existing typed accessors. Editor, console, navigation hints, ghost text and documentation all use those tokens. Console restyling retains stdout/stderr identity, source and pending input rather than flattening the transcript into one color.

## Website export

The repositories share this checkout, so no second hand-maintained palette exists. Run:

```sh
cd website
npm run themes
npm run themes:check
```

[`website/scripts/export-themes.mjs`](../website/scripts/export-themes.mjs) validates the native registry/palettes and generates website CSS variables, TypeScript selector metadata, `public/themes/catalog.json`, individual palette JSON files and `public/themes/turboj-themes.json`. To bundle a genuine additional palette, add its native JSON and an ID/file entry to the registry, then regenerate. The three primary menu choices remain fixed; additional entries appear through the catalog. Registry files are local JSON basenames, without paths or URLs. Website dev/check/build commands run the exporter; the test command rejects stale generated outputs. Edit the native JSON first, then regenerate and commit both source and generated assets. The static website has no Java runtime dependency.

Website selection changes the root theme attribute, so the whole page and the single active HelloWorld replay inherit one palette. Preference is stored locally when browser storage is available and applied before page styles render. Blocked storage keeps switching usable for the current page. Theme changes never reset the replay's source, cursor, elapsed time, compile state or output. The genuine native screenshot remains unchanged.

## User themes and configuration

Copy [classic.json](../app/src/main/resources/themes/classic.json) into `~/.config/turboj/themes/my-theme.json`, edit its ID/name/colors, then choose **Options → Themes → More themes…**. **Refresh** rescans changed files. **Load theme JSON…** also opens palettes outside that directory. Existing schema-v1 custom files remain supported; new tokens are expanded from their own colors. Confirming a custom theme records its absolute path in `config.json` as `externalThemePath`; the next launch loads it asynchronously. If that file is missing or invalid, the application retains a bundled fallback and reports the error. Choosing a bundled preset clears the external path. Local user files stay private: the app and website never upload, synchronize or automatically download palettes.

`ThemeCatalog` indexes actual names/IDs on one background worker retained by the native window. The first page warms asynchronously at application startup. Closing/reopening the browser preserves its metadata and palette cache; cancelling only supersedes pending deliveries. Directory changes trigger reindexing off the EDT, and Refresh explicitly reloads edited metadata. It keeps at most one running and one pending request, rejects superseded results, examines at most 10,000 entries per scan, and limits ordinary browse requests to parsing the six visible palettes; the explicit random action uses the bounded prefetch described above. An LRU cache retains at most 24 palettes; the UI has six reusable preview components regardless of catalog size. Search/filtering, filesystem reads and refresh happen off the Swing event thread. Invalid metadata is skipped; an invalid visible palette gets a clear error and cannot be applied. The distributed catalog contains 103 real palettes. The 100 imported palettes, their adaptations, source hashes and MIT licensing are documented in [theme-catalog.md](theme-catalog.md); their complete notices ship with the native resources. Thousand-theme scalability fixtures exist solely in temporary test directories.

Invalid custom themes produce an error while the previous theme remains active. Invalid bundled resources fall back to Classic; if the installation's Classic resource itself is damaged, a minimal black/white emergency palette keeps the editor usable. User theme files are limited to 64 KiB. No theme scripts or network resources are loaded.

`config.json` also stores font size, tab width, auto-close brackets, window size and up to ten recent paths. Recent-file UI and workspace restoration are not implemented. Configuration location can be overridden with `-Dturboj.config=/path` for tests or portable use. Recoverable source goes in `sessions/buffer.java` after a two-second quiet interval; recovery is best effort, one window at a time.

## Native verification

`ThemeContractTest` checks token validation, WCAG contrast ratios for the implemented text/cursor pairings, legacy preference migration, immutable queued settings snapshots, source/ghost/undo preservation, and stdout/stderr/stdin preservation. `ThemeCatalogTest` exercises 1,000 local metadata fixtures, paging, filtering, coalesced requests, cache bounds, malformed JSON, saved external selection, filtered random selection and bounded prefetch. `ThemeWheelPlanTest` checks every supported duration, cubic slowdown, luminance ordering, the full-pool random bound, single-theme handling, nonrepeating small pools and deterministic motion once a choice is made. `ThemeSmoke` runs the real macOS window and an actual Scanner program while changing all three themes; it also checks live semantic ghost text, genuine JDK documentation, six browser previews, search, keyboard paging, Escape rollback without persistence, Enter commit and reopening the selected external JSON in a new window. It also runs a complete natural drum spin, observes actual native palette crossings while checking source/output continuity, then checks Skip, reduced motion and cancellation during an active spin.

```sh
./mvnw package
java --enable-native-access=ALL-UNNAMED -cp app/target/test-classes:app/target/turboj-app.jar com.turboj.app.ThemeSmoke
java --enable-native-access=ALL-UNNAMED -cp app/target/test-classes:app/target/turboj-app.jar com.turboj.app.ThemeBrowserLatency
```

Screenshots and the actual native report are emitted to `app/target/theme-{classic,dark,light}.png`, `app/target/theme-browser-grid.png`, `app/target/theme-random-drum.png` and `app/target/theme-smoke.txt`. `ThemeBrowserLatency` writes actual first-open, warm-open, search and cached-arrow action-to-ready timings to `app/target/theme-latency.txt`; these are local measurements, not input-to-paint or hardware-independent guarantees. Automated contrast checks cover explicit token pairings, not a claim of complete application accessibility conformance.

### Measured compact browser session

On 2026-10-24, the local macOS ARM64 / Corretto 25.0.4.1 run loaded 103 genuine bundled palettes into a 720×480 browser. `ThemeBrowserLatency` reported:

| Operation | Samples | p50 | p95 |
|---|---:|---:|---:|
| First dialog open | 1 | 100.81 ms | — |
| Warm dialog open | 5 | 24.84 ms | 31.74 ms |
| Search | 18 | 5.86 ms | 28.50 ms |
| Cached same-page arrow action | 48 | 4.46 ms | 16.85 ms |

These are action-to-ready timings in one local run, including EDT dispatch. They do not measure compositor paint, real keyboard delivery or other hardware. The compact layout and persistent index/cache are covered by the executable harness and cache-reuse regression. This run includes the final vertical-scroll controls and fast cubic animation. The separate native smoke completed a natural spin in 3.08 seconds and observed seven actual editor-background changes while the full real-theme sequence was applied; several neighboring palettes share a background. Spin duration is an interaction choice, not compiler performance. The same smoke verified Skip, reduced motion, cancellation during motion and unsaved source/output continuity.
