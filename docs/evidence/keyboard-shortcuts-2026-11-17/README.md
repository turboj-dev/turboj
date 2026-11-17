# Every action bound; complete four-page cheat sheets

## Implemented and verified

- 309 registered commands, 29 preserved aliases, all commands assigned in all four
  fresh profiles. No exact/prefix/context collisions and no dangling command IDs.
- Supplemental sequences: Ctrl+Cmd+K, group letter, action letter. Explicit stable
  catalog in `KeyboardDefaults`; new actions do not renumber existing sequences.
  Native profile bindings remain unchanged. Supplements are TurboJ choices.
- Supplemental counts: IntelliJ 210, Eclipse 235, VS Code 226, TurboJ 197.
- Preferences retain custom assignments, explicit unbinding (including aliases),
  selected profile and custom profiles. Safe missing defaults are imported.
  The explicit **Assign Missing Shortcuts** action fills gaps without replacing
  conflicting custom bindings; unresolved collisions remain in Keymap Settings.
- CommandJ retains ownership of dispatch. Chords allow ten seconds between keys;
  Escape cancels without invoking a second action. Unavailable chords report their
  context requirement; invalid/expired suffixes do not become source/input text.
  Single-key widget navigation and modal isolation remain unchanged.
- Menus display multi-stroke labels without installing a misleading single-key
  Swing accelerator. Palette, searchable reference and PDF share the live registry.
- Four PDFs, four landscape A4 pages each, 16 occupied columns per PDF, uniform 9pt
  command text. All 309 commands appear exactly once; unbound custom actions are
  listed explicitly. No trimming or ellipsis hides commands. Long labels have
  concise print names; measured row/key/text bounds pass without clipping.

## Validation commands and results

| Command / check | Result |
| --- | --- |
| `./mvnw install` | PASS: 878 tests, 128 suites, no failures/errors/skips; 3m47s. Full run preceded final alias-unbinding and menu-label regression additions. |
| `./mvnw install -pl app -am -DskipITs -Dtest=CompleteDefaultsTest,KeymapTest,KeyboardAuditTest,ProfileSwitchTest,KeyboardDefaultsTest,CheatSheetTest,MenuLayoutTest,MenuStylerTest -Dsurefire.failIfNoSpecifiedTests=false` | PASS: 42 final regression tests, 8 suites, no failures/errors/skips; final JAR rebuilt. |
| `java --enable-native-access=ALL-UNNAMED -cp app/target/test-classes:app/target/turboj-app.jar dev.turboj.app.KeyboardRegistryAudit docs` | PASS: 309 commands, 29 aliases, 0 dangling bindings. All supplemental sequences resolved to the expected real command IDs; callbacks deliberately not executed in this audit. |
| Same Java classpath, `dev.turboj.app.KeyboardProfileSmoke` | PASS: 104 assertions in actual native windows, all four profiles; profile switching by chord, editor/Output/Tests/JShell/command line routing, cancel/invalid/disabled suffix suppression, menu/combo navigation, custom binding persistence and reload. Fixture setup and custom binding installation are programmatic. |
| `java --enable-native-access=ALL-UNNAMED -cp app/target/turboj-app.jar dev.turboj.help.CheatSheetExport app/target/cheatsheet-validation --keep-html` | PASS: four PDFs from the actual application registry. |
| `node scripts/verify-cheatsheets.mjs app/target/cheatsheet-validation` | PASS: all 309 IDs exactly once in every profile; all 16 columns occupied; no clipped row text or keys; font 12 CSS px = 9pt. |
| Native PDFKit inspection/rendering | PASS: each PDF has exactly four landscape A4 pages; actual printed page 4 retained as `printed-page-4.png`. |
| `node website/scripts/export-keyboard-reference.mjs` | PASS: source/artifact manifest checked and three generated references exported. |
| `node --test website/tests/keyboard-reference.test.mjs` | PASS: six tests. |

Environment: macOS 26.5.1 arm64, Corretto 25.0.4.1, native Swing event dispatch,
headless Chromium/Chrome for layout/printing, macOS PDFKit for actual PDF checks.
The first sandboxed full build could not initialize the native AWT window system;
the full validation was rerun with native-window access. This is not recorded as
an application regression. An initial layout check rejected several wrapped labels;
the final label corrections pass the same check without reducing the type size.

`manifest.json` identifies the final artifact and delivered PDF hashes. The final
native smoke report identifies its artifact separately (same final JAR). Frozen
QFX-04 evidence is unchanged. Logs, layout metrics and PDF counts are retained here.
The registry's complete source manifest remains `docs/keyboard-reference-sources.json`.

## Limits

This proves complete binding coverage and the tested routing/migration/printing
behavior, not successful execution of every language action in every context.
Physical keyboard layouts, Fn/Globe, custom macOS shortcuts, IME and VoiceOver
still require KEY-05's host walkthrough. Existing Ctrl+Space input-source warnings
remain visible; no new internal collisions were found. Explicit user unbindings
and conflicting user shortcuts can leave a saved profile incomplete until the user
chooses Assign Missing Shortcuts or resolves the conflict. The bundled PDFs cannot
predict individual custom mappings; print from the application for those.

No production deployment or publication was performed.
