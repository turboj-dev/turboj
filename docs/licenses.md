# Third-party components

TurboJ's own source is MIT licensed. Third-party components retain their licenses; the MIT license does not relicense them.

| Component | Pinned direct version | License / source |
|---|---|---|
| Eclipse JDT Core / ECJ transitively | 3.44.0 | [Eclipse Public License 2.0](https://projects.eclipse.org/projects/eclipse.jdt); [source](https://github.com/eclipse-jdt/eclipse.jdt.core) |
| Eclipse JDT Core Manipulation / LTK | 1.23.200 / 3.15.100 | EPL 2.0; original embedded refactoring bundles; [inventory](../refactorj/docs/LICENSES.md) |
| Apache Felix SCR | 2.2.18 | Apache 2.0; headless component lifecycle |
| Gson | 2.13.2 | [Apache 2.0](https://github.com/google/gson); [release](https://github.com/google/gson/releases/tag/gson-parent-2.13.2) |
| Maven Wrapper | 3.3.4 | [Apache 2.0](https://maven.apache.org/tools/wrapper/) |
| Maven | 3.9.11 | Apache 2.0; build tool only |
| JUnit Jupiter / Platform | 5.13.4 / 1.13.4 | EPL 2.0; bundled for TurboTest and used by tests; [release notes](https://docs.junit.org/5.13.4/release-notes/) |
| JMH | 1.37 | GPLv2 with Classpath Exception; benchmark module only |
| Amazon Corretto runtime | build JDK's installed version | [GPLv2 with Classpath Exception](https://aws.amazon.com/corretto/faqs/); original `legal/` notices retained by jlink |

Java 25 is used without preview features. JDT's Java 25 AST level begins in 3.44: [official AST API](https://help.eclipse.org/latest/topic/org.eclipse.jdt.doc.isv/reference/api/org/eclipse/jdt/core/dom/AST.html). Runtime compatibility is verified by the repository tests, not inferred only from version numbers.

No Borland binaries, logos, fonts or proprietary source are included. Fonts use the platform's logical Monospaced mapping. ECJ arrives as a JDT parser dependency; it is not exposed as an alternative TurboJ compiler backend. JLine is not bundled in this initial release.

The local application is not a signed or notarized public distribution. Redistribution should retain the individual Eclipse, OSGi, Gson and JDK notices and make the corresponding upstream source available under their original terms. See the Maven dependency report and packaged legal resources for exact transitive artifacts.

AI adapters use the JDK HTTP client and stable Foreign Function & Memory API;
no inference runtime or model is redistributed in the app. The optional local
validation setup uses Ollama (MIT) and Qwen2.5-Coder 1.5B Base (Apache 2.0).
Its downloaded runtime retains upstream notices in the ignored cache. See the
[local validation report](ai-local-validation.md) for exact artifacts and sources.

The [exact runtime dependency lock](dependencies.md) lists all transitive versions and corresponding source archives. Original artifact-specific notices are bundled under `third-party/` inside the application jar. Regenerate them with `scripts/collect-notices.py` after dependency changes.

The separate static website uses self-hosted JetBrains Mono and IBM Plex Sans under SIL OFL 1.1. Original font licenses are distributed with the site; see [website assets and provenance](../website/ASSETS.md). Astro, TypeScript and image/test tools are build-time dependencies pinned in the website lockfile.

The bundled theme catalog includes 100 adapted editor palettes from MIT-licensed upstream sources in addition to TurboJ's own three presets. [Theme curation and provenance](theme-catalog.md) documents the selected families, direct Sublime Text/JetBrains/VS Code inputs, exact pinned sources, and contrast adaptations. All original notices are retained in [`themes/THIRD_PARTY_NOTICES.txt`](../app/src/main/resources/themes/THIRD_PARTY_NOTICES.txt) inside the application resources and exported to `/themes/THIRD_PARTY_NOTICES.txt` on the website. The [source manifest](../third-party/theme-sources/manifest.json) records every file hash and the versioned GitHub/Gruvbox package artifacts. Regenerate and verify offline with `python3 scripts/import-editor-themes.py --check`; theme names identify upstream palettes without implying endorsement or affiliation.
