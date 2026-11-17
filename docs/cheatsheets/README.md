# Complete keyboard cheat sheets

Generated from TurboJ's executable CommandJ registry on 17 November 2026.
Each PDF contains **all 309 commands**, plus local caret/template keys, in the
existing four-column layout across **four landscape A4 pages**. Print on two
sheets, double-sided, flipping on the short edge. Every column is used; commands
are never removed to make the layout fit.

- [TurboJ macOS](cheatsheet-turboj-macos.pdf)
- [Eclipse macOS](cheatsheet-eclipse-macos.pdf)
- [IntelliJ IDEA macOS](cheatsheet-intellij-macos.pdf)
- [VS Code macOS](cheatsheet-vscode-macos.pdf)

These PDFs show fresh bundled defaults. Existing shortcuts retain their profile's
meaning. New supplemental bindings use **Ctrl+Cmd+K**, followed by two letters:
release Control and Command before typing the letters, without Shift. Sequences
are TurboJ additions, not claimed official IDE defaults.

To print your own saved bindings, use **Help → Print Keyboard Cheat Sheet…**.
To fill previously empty assignments in your active profile, use **≡ → Assign
Missing Shortcuts**. Custom bindings are retained; collisions require resolution
in Keymap Settings. An explicitly unbound action still appears in a custom PDF.

Regenerate the bundled PDFs:

```sh
./scripts/cheatsheets.sh
```

Reproduce the completeness/layout checks after building and generating the current
registry inventory:

```sh
java --enable-native-access=ALL-UNNAMED -cp app/target/test-classes:app/target/turboj-app.jar dev.turboj.app.KeyboardRegistryAudit docs
java --enable-native-access=ALL-UNNAMED -cp app/target/turboj-app.jar dev.turboj.help.CheatSheetExport app/target/cheatsheet-validation --keep-html
node scripts/verify-cheatsheets.mjs app/target/cheatsheet-validation
```

The validator uses the existing website Playwright dependency. Chrome/Chromium is
needed for PDF generation; without it, the native printer returns printable HTML.
See [validation evidence](../evidence/keyboard-shortcuts-2026-11-17/README.md),
[shortcut usage](../shortcuts.md) and the [full inventory](../keyboard-command-inventory.md).
