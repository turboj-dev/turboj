# Keymaps

Profiles: IntelliJ IDEA macOS, Eclipse macOS, VS Code macOS and TurboJ Classic.
They are app command mappings, not wholesale emulation of every unrelated IDE feature.
Unimplemented host command IDs never execute and never appear as fake palette actions.

IntelliJ is the new-install default. Classic preserves TurboJ F2 Save, F3 Open, F9 Make,
Ctrl+F9 Run, F5 Zoom, F6 pane focus and its prior command-palette binding. Migration from a
pre-CommandJ installation retains Classic and translates old explicit JSON overrides. Imported
preferences and explicit profile selection take precedence. A malformed import cannot replace
working preferences.

Options → Keymap searches live registry titles, aliases and categories. Add/Replace records
up to four logical strokes; Remove deletes a selected binding; Reset command restores inherited
or bundled bindings; Reset profile restores its base. Duplicate creates a user profile inheriting
the current profile. Import/Export exchanges schema-1 JSON. A conflict dialog asks before removing
only the overlapping bindings. Reserved macOS shortcuts are reported, not overridden.

Palette: Cmd+Shift+A in IntelliJ; Cmd+3 in Eclipse; Cmd+Shift+P/F1 in VS Code;
Cmd+Shift+P in Classic. Up/Down navigate, Enter executes, Escape closes. Shortcuts and disabled
reasons reflect the registry. The recorder uses unmodified Escape to cancel and Enter to finish recording; its Add special key
control records Escape, Enter, Tab, Backspace or Forward Delete explicitly, including inside chords.

Sources checked 2026-10-24:

- [JetBrains predefined macOS keymap](https://www.jetbrains.com/help/idea/reference-keymap-mac-default.html), including Shift+F6 Rename, Cmd+F6 signature, Cmd+Option+M/V/F/P/N,
  F6 Move, Cmd+Forward Delete, Ctrl+T, Option+F7, Cmd+B, Cmd+Option+L and Cmd+Shift+A.
- Installed IntelliJ IDEA 2026.1.3 `intellij.platform.ide.impl.jar` keymaps
  `Mac OS X 10.5+.xml` and inherited macOS map were inspected as additional primary evidence.
- [VS Code official macOS reference](https://code.visualstudio.com/shortcuts/keyboard-shortcuts-macos.pdf), including Cmd+K Cmd+S, Cmd+K Cmd+F, Cmd+K Cmd+I.
- [Eclipse JDT UI command bindings](https://github.com/eclipse-jdt/eclipse.jdt.ui/blob/master/org.eclipse.jdt.ui/plugin.xml) and [Eclipse keys documentation](https://help.eclipse.org/latest/topic/org.eclipse.platform.doc.user/concepts/concepts-keys.htm).
  The Eclipse profile follows current generic Cocoa-compatible bindings, not obsolete `platform=carbon` overrides.

TurboJ-only additions (AI invocation, completion acceptance, wheel/theme actions when registered)
are not attributed to those IDEs. Ctrl+Space can be reserved for macOS input-source switching;
the editor warns and leaves system preference control to the user. Forward Delete is distinct
from Backspace. AWT supplies logical layout-aware codes; actual non-US physical keyboards and
OS-reserved shortcut interception were not hardware-tested. AltGraph and dead keys bypass the
command bridge. The profile is independent of the theme.
