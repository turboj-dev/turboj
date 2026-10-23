# TurboJ website assets

The product image is the real capture from `../docs/images/turboj-classic.png`.
The input is retained at `assets/original/turboj-editor.png`, byte-for-byte.
Its SHA-256 is
`27e0f5c593105574e60abd6e3055db525f119a20f80e3ddcf01ce3727cd9bcc8`.

The capture is **1060 × 740**, not the approximate 2048 × 1285 described in
the website brief. It displays `Hello TurboJ!`, a **0.03 ms cache hit**,
**1.17 ms execution**, and **2.22 ms round trip**. These values describe this
single captured run. They are not fresh compilation timings or benchmark
averages. The website copy should match the supplied file.

## Generate assets

With the locked project dependencies installed, run from this directory:

```sh
npm run assets
```

The underlying command is `node scripts/prepare-assets.mjs`. Generation is
entirely local and needs no network access. It uses the pinned `sharp` and
Fontsource packages in the project lockfile.

The script checks the source hash, generates the images, copies the font
files and their licenses, and writes `assets/original/provenance.json` with
dimensions, sizes, hashes, and the exact observed capture values. The full-size
WebP is decoded and compared pixel-for-pixel against the original PNG.

## Images

| Path under `public/` | Dimensions | Purpose |
| --- | --- | --- |
| `images/turboj-editor.png` | 1060 × 740 | Unchanged original and full-resolution link |
| `images/turboj-editor.webp` | 1060 × 740 | Pixel-identical, lossless primary image |
| `images/turboj-editor-795.webp` | 795 × 555 | Medium responsive derivative |
| `images/turboj-editor-530.webp` | 530 × 370 | Small responsive derivative |
| `images/turboj-social.png` | 1200 × 630 | Original branded social card containing the complete scaled capture |

Resizing is the only transformation to responsive screenshots. No code,
line numbers, status values, output, or editor branding is retouched. The
social card adds a separate border and TurboJ text around the complete
screenshot. Its branding uses system monospace glyphs rasterized during the
build; no system font is distributed.

Use explicit `width="1060" height="740"` on the primary image and preserve
its aspect ratio. Link it to `images/turboj-editor.png` for inspection of the
original. Do not present the social card as an additional product screenshot.

The full-size lossless WebP is only **15,308 bytes**. The anti-aliased resized
variants are larger (17,888 and 28,622 bytes respectively), so the homepage
should serve the full-size WebP at all widths. The variants remain available
for other contexts; a `srcset` using them would increase transfer size here.
The original PNG is 34,679 bytes and the social PNG is 74,883 bytes on the
validated build machine. The manifest records current generated sizes.

## Fonts and licenses

Both normal-style variable webfonts are self-hosted. No browser request to a
font CDN is needed. Fontsource packages retain the original **SIL Open Font
License 1.1** text, copied into `public/licenses/`:

| Font | Public font path | License |
| --- | --- | --- |
| JetBrains Mono | `fonts/jetbrains-mono-latin-variable.woff2` | `licenses/jetbrains-mono-OFL.txt` |
| IBM Plex Sans | `fonts/ibm-plex-sans-latin-variable.woff2` | `licenses/ibm-plex-sans-OFL.txt` |

The Latin subset covers the English website, punctuation, and standard
technical labels. Use `font-display: swap` and normal style. Verified variable
weight ranges are **100–800** for JetBrains Mono and **100–700** for IBM Plex
Sans, as declared by the installed Fontsource package's `index.css`. Good fallback
families are `ui-monospace, SFMono-Regular, Menlo, Consolas, monospace` and
`system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif`.

The website does not redistribute Borland assets or Oracle's Java logo.
