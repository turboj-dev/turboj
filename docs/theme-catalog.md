# Curated editor themes

TurboJ bundles **100 imported editor palettes plus its three original presets**, for **103 selectable themes**. The native application and website consume the same schema-2 JSON definitions. Turbo Classic, Modern Dark and Modern Light remain unchanged and stay first in the catalog.

This is a curated selection of recognizable families and varied dark/light palettes, **not a ranking of the “100 best” themes**. A light or dark variant counts only when it is a separately published upstream palette. The importer rejects duplicate complete 49-color palettes, so the catalog does not inflate its count with differently named copies.

| Family | Imported palettes |
|---|---:|
| Rainglow | 58 |
| Visual Studio Code built-ins, including Monokai and Solarized | 19 |
| Catppuccin | 4 |
| GitHub | 4 |
| Ayu | 3 |
| Rosé Pine | 3 |
| Tokyo Night | 3 |
| Gruvbox | 2 |
| One Dark and One Light | 2 |
| Dracula | 1 |
| Nord | 1 |

The [complete generated inventory](theme-catalog-inventory.md) links every theme to its pinned original source or versioned package. Rainglow Peacock is imported directly from the official **Sublime Text `.tmTheme`** file; Rainglow Earthsong comes directly from the official **JetBrains/IntelliJ XML scheme**. The remaining Rainglow entries use its official VS Code JSON files. These are actual inputs from all three editor ecosystems, not claims that TurboJ runs their extensions or reproduces each editor's complete UI.

## Sources and licenses

Every imported source has a permissive **MIT** notice verified in its authoritative upstream repository or publisher's versioned package. Full, unmodified license texts are vendored with the originals. The importer also bundles all notices into [`app/src/main/resources/themes/THIRD_PARTY_NOTICES.txt`](../app/src/main/resources/themes/THIRD_PARTY_NOTICES.txt); the website exports the same document to `/themes/THIRD_PARTY_NOTICES.txt`.

Authoritative source projects:

- [Rainglow VS Code](https://github.com/rainglow/vscode), [Sublime Text](https://github.com/rainglow/sublime), and [JetBrains](https://github.com/rainglow/jetbrains), by Dayle Rees.
- [Visual Studio Code](https://github.com/microsoft/vscode), including its built-in theme sources and registered color defaults.
- [One Dark](https://github.com/akamud/vscode-theme-onedark) and [One Light](https://github.com/akamud/vscode-theme-onelight).
- [Ayu](https://github.com/ayu-theme/vscode-ayu), [Gruvbox](https://github.com/jdinhify/vscode-theme-gruvbox), and [GitHub themes](https://github.com/primer/github-vscode-theme).
- [Dracula](https://github.com/dracula/visual-studio-code), [Nord](https://github.com/nordtheme/visual-studio-code), [Rosé Pine](https://github.com/rose-pine/vscode), and [Tokyo Night](https://github.com/tokyo-night/tokyo-night-vscode-theme).
- [Catppuccin's canonical palette](https://github.com/catppuccin/palette) and its [VS Code Java/TextMate token mappings](https://github.com/catppuccin/vscode).

[`third-party/theme-sources/manifest.json`](../third-party/theme-sources/manifest.json) records complete commit hashes, original paths, SHA-256 hashes, local license paths and the selection of exactly 100 inputs. GitHub and Gruvbox generated JSON files are extracted from fixed-version Open VSX packages; their package URL, version and archive SHA-256 are recorded alongside the corresponding release commit. All other inputs are pinned repository files. Supporting token/default source files are retained for provenance; no extension runtime is executed, and no upstream plugin binaries, fonts or icons are included in the adaptations.

The source collection is small enough to vendor locally. Normal builds and regeneration require **no network access**. The application loads bundled files; the website loads only its own catalog and requested palette assets. Theme browsing does not contact the upstream editors or theme authors.

## Reproduce and verify

From the repository root, with Python 3.10 or later:

```sh
python3 scripts/import-editor-themes.py
python3 scripts/import-editor-themes.py --check
python3 scripts/test-theme-import.py
```

The first command regenerates the 100 native JSON files, registry, adaptation report, inventory and bundled notices from the vendored originals. `--check` performs the same conversion without writing files and fails if any generated result differs. Both verify every source-file hash and the hashes of the three unchanged TurboJ presets. They also reject duplicate palettes, invalid color fields, oversized output and missing/unpinned VS Code includes.

Then run the website's existing native-theme export/build workflow. The exporter copies these native definitions into the site's palette assets; neither platform maintains a separate hand-edited copy of the imported colors.

## Mapping into TurboJ

The native schema has 49 color tokens rather than the hundreds of scopes and workbench roles available in full IDEs. These are **documented adaptations**, not pixel-identical ports of every upstream control.

| Input | Extraction |
|---|---|
| VS Code JSON/JSONC | Resolve local pinned `include` files, then read editor/workbench colors and the most specific matching Java/TextMate scope for each supported syntax role. |
| Sublime Text `.tmTheme` | Read the plist's global settings and token scopes; use its original caret and selection colors where provided. |
| JetBrains XML | Read `TEXT`, `DEFAULT_KEYWORD`, `DEFAULT_STRING`, `DEFAULT_LINE_COMMENT`, `DEFAULT_NUMBER`, `DEFAULT_OPERATION_SIGN`, annotation attributes, and console/selection/caret colors. No inherited Darcula values are silently invented. |
| Dracula YAML | Read only the named six-digit palette anchors and map the published Dracula roles. No general YAML loader or code execution is used. |
| Catppuccin palette | Map each of Latte, Frappé, Macchiato and Mocha using the pinned VS Code roles: mauve keywords, green strings, overlay2 comments, peach numbers/Java annotations and teal operators. |

VS Code High Contrast Light deliberately omits its base foreground/background in the theme file. Its exact defaults, `#FFFFFF` and `#292929`, come from the pinned Microsoft `editorColors.ts` and `baseColors.ts` sources recorded in the manifest.

Editor backgrounds retain their original source colors. Alpha colors are composited over that background because TurboJ's schema requires opaque RGB values. Missing UI roles derive from the source foreground/background or published sidebar, panel and menu colors. Documentation, completion and general website surfaces then use those same roles. Fonts are the existing TurboJ monospaced system fallback, not upstream font assets.

To keep the adapted editor and website readable, text tokens are minimally blended toward black or white when needed to reach a calculated **4.5:1** contrast ratio on their target backgrounds; cursor, focus and border tokens use **3:1**. General UI text is checked against both the base and secondary surface. Selection text uses the more legible black/white foreground. The exact original color, adapted color, target background and threshold for every correction are recorded in [`adaptations.json`](../third-party/theme-sources/adaptations.json). These numeric token checks do not replace an audit of every rendered control or constitute a blanket accessibility certification.

## Updating the collection

Use a new fixed upstream commit or package version, review its license and original files, update the corresponding hashes/selection in the manifest, and run the offline importer and tests. Adding a new family requires an authoritative source and retained notice. Do not use an unlicensed screenshot, a paid theme download, or an aggregator whose individual palette permissions are unclear.

Keep the imported selection at exactly 100 genuine, distinct palettes for this release. The runtime catalog remains extensible independently; the count is a curation decision, not an editor limit. Changes to the original three presets require an intentional update of their manifest hashes after review.
