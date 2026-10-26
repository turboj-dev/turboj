# Keyboard shortcuts

New installations use **TurboJ macOS**; TurboJ Classic was retired and a saved
Classic selection moves to it. Eclipse, IntelliJ IDEA and VS Code profiles stay
available in Options → Keymap, independent of the visual theme. The menus and
Help → Shortcuts use the live CommandJ registry and show current bindings.
See [profiles, verified sources and customization](../commandj/docs/KEYMAPS.md).

Find Action defaults to Cmd+Shift+A in IntelliJ, Cmd+3 in Eclipse,
Cmd+Shift+P/F1 in VS Code, and ⇧⌘A in TurboJ.

Options → **Show Pressed Shortcuts** (`showPressedShortcuts`) adds a brief,
theme-accent indicator in the editor's bottom-right corner. It displays actual
executed bindings, including remapped shortcuts and multi-stroke sequences;
ordinary typing and password input are excluded. The preference defaults off.

Options → **Shortcuts as ⌃⌥⇧⌘ Symbols** (`settings.shortcutSymbols`) writes
shortcuts the macOS way in menus, Find Action, Keymap and hints: ⌃⌥⇧⌘ in that
order, then the key, without plus signs (`⌃⌥⌘O`). It defaults on for macOS.

Code → **Formatter** chooses the Java style that Format Document, Format Selection and Format on Save apply:
IntelliJ IDEA (default), Eclipse and AWS (Smithy) on the Eclipse engine; AOSP, Classic 80, Wide 120 and Google on google-java-format. The editor indents as the chosen style
does. See [Java formatting](formatting.md).

**Smaller Font / Larger Font** (`fontDown` / `fontUp`) scale the editor, menus,
output, project explorer, status line and embedded dialogs together. The font
preference persists independently of the selected theme.

Options → **Auto Run on Save** (`run.onSave`) and **Auto Test on Save**
(`tests.onSave`) enable the corresponding actions after saving and successful
workspace compilation. They default off and are independent of Test → **Auto
Test on Changes** (`tests.auto`). All three actions can receive custom shortcuts.

## TurboJ macOS bindings

The default map applies Turbo C's grammar to the Mac ([study and full table](keymap-turboj-macos.md)):
⌘+letter is the verb, ⇧ widens it, ⌥⌘ transforms code, and Turbo's function keys stay as second bindings.
Help → **Print Keyboard Cheat Sheet…** prints the active map on two A4 pages; the bundled sheets are in
[cheatsheets](cheatsheets/).

| Shortcut | Action |
|---|---|
| ⌘↩ | Complete, Import or Run: accept the shown completion; else import what the line misses (type, static member such as `assertEquals`, JUnit); else complete the name being typed; else Run |
| ⌥Space / ⌃Space | Completion alternatives, signatures and overloads (macOS gives ⌃Space to the input-source switch) |
| ⌥↩ / ⌘1 | Quick Fix / Quick Assist |
| ⌘J / F1 | Quick documentation for the Java symbol at the caret |
| ⌘B / F9 | Make project |
| ⌥⌘B / ⌥F9 | Fast compile |
| ⌘R / ⌃F9 | Stop previous program, compile if needed and run (Java or JUnit) |
| ⌘U / ⇧⌘U | Run the test at the caret / all tests |
| ⌘. | Stop program |
| ⌘' / ⇧⌘' (⌥F8 / ⌥F7) | Next / previous error |
| ⌘P / F3 | Search everywhere: classes, files, methods, fields |
| ⌘T / ⇧⌘O / ⇧⌘T | Go to class / symbol / between source and test |
| ⌘D / F12 / ⌘-click | Go to declaration; ⇧⌘D references, ⌘I implementation |
| ⌘[ / ⌘] | Navigate back / forward, restoring selection and viewport |
| ⌘L / ⌘E | Go to line / recent files |
| ⌘S / F2, ⌥⌘S | Save, save all |
| ⌘N / ⌥⌘N | File ▸ New menu / Code ▸ Generate menu |
| ⌥⌘F | Find and Replace dialog (Replace All is one undo) |
| ⌘← / ⌘→ (fn← / fn→) | Smart line start / end |
| ⌥⌫ / ⌥fn⌫ | Delete previous / next word |
| ⌥⌘T / ⌥⌘R | Refactor menu / rename |
| Shift+F10, ⌫ (Project Explorer) | Item menu, delete the selected item; ⌥⌘R and ⌥⌘⌫ rename and safe-delete it ([explorer](project-explorer.md)) |
| ⌥⌘' / ⇧⌥⌘' | Next / previous failed test (Tests tab and editor) |
| ⌥⌘M / ⌥⌘V / ⌥⌘C | Extract method / variable / constant |
| ⌥⌘L / ⌥⌘O | Format / organize imports |
| ⌥↑ / ⌥↓, ⌥⌘↑ / ⌥⌘↓, ⇧⌘K | Move, copy, delete line |
| ⌘/ / ⌥⌘/ | Line / block comment |
| ⌘0 / ⇧⌘C / ⇧⌘M | Explorer / console / problems |
| F5 / F6 | Expand editor / editor ↔ console input |
| ⇧⌘A / F10 | Find Action / open menu |
| ⌘, / ⌘K / ⇧F1 | Settings / keymap / keyboard reference |
| ⌥⇧Space | Request optional AI completion |
| ⌥→ / ⌥⇧→ | With an AI suggestion shown: accept its next word / next line |
| Tab / Shift+Tab | Accept inline suggestion when present; in an [inline template](inline-templates.md), next / previous field; otherwise indent / unindent |
| Return | In an inline template, confirm it from any field |
| Escape | Dismiss inline suggestion, alternatives, documentation or menu; in an inline template, take its edits back |

macOS may reserve function keys for hardware controls; hold Fn or adjust macOS keyboard settings. System shortcuts are not globally intercepted. On other operating systems Ctrl substitutes for Cmd in application bindings.

Menus also expose Isolated Run, Send EOF, clear output, replace selection, themes, font size and performance samples. Run → Java REPL switches the bottom pane to persistent JShell; Enter submits, Up/Down recall history, Tab completes, Reset starts a fresh session, and Stop terminates a hanging evaluation. Evaluate Selection and Evaluate Current Statement submit explicitly; context-dependent code receives real JShell diagnostics. Test commands run real JUnit tests; unimplemented debugger commands are absent.

Options → Keymap supports recording shortcuts/chords, multiple bindings, profile
duplication, inheritance, reset, conflict resolution and JSON import/export.
Preferences are saved in `~/.config/turboj/commandj-keymap.json`. The former
`keymap.json` format below is imported for existing installations; new customizations
should use the editor or export/import schema instead. Legacy example:

```json
{
  "run": "meta shift R",
  "compile": "F9",
  "stop": "meta PERIOD",
  "documentation": "F1",
  "declaration": "F12",
  "complete": "ctrl SPACE",
  "back": "alt LEFT",
  "forward": "alt RIGHT",
  "acceptCompletion": "TAB",
  "dismissCompletion": "ESCAPE"
}
```

Application command IDs also include declaration, documentation, complete, back, forward and theme.light. `acceptCompletion` and `dismissCompletion` configure the editor and completion popup keys. Other command IDs include new, open, save, saveAs, quit, undo, redo, cut, copy, paste, selectAll, indent, unindent, comment, find, findNext, findPrevious, replace, goto, compile, run, isolated, stop, eof, clear, error, zoom, focus, repl, output, replReset, evaluateSelection, evaluateStatement, fontUp, fontDown, fullscreen, metrics, palette, help and theme.classic/theme.dark/theme.light. Help remains available through the Help menu and command palette. Ordinary editor movement/editing defaults remain local; semantic shortcuts are routed before word movement so Alt+Left/Right navigate source history.

Program Run focuses the output text itself, with a steady block cursor immediately after the last prompt. There is no separate stdin row: `Inserisci un numero: 20` is edited on one line. Click the output pane to focus input (dragging still selects output for copying), type and press Enter. Values are echoed in the transcript. F6 returns to the editor. Run again replaces the previous process, discards old pending output and clears unsent input. The execution timeout excludes time blocked waiting for stdin; Stop remains available while waiting.

**Options → Themes** offers Turbo Classic, Modern Dark, Modern Light and **More themes…**. The compact catalog contains a search field and a vertically scrollable viewport of six miniature editor previews. Use the scrollbar or wheel to browse; arrow keys move selection and Page Up/Down move by six entries. Selection previews the theme, Enter/Apply keeps it, and Escape/Cancel restores the previous appearance. **Random theme** spins the ASCII drum rapidly, then decelerates to a result over 3–5 seconds of active playback. Each centered theme previews immediately; Skip finishes the spin. Confirmed choices persist. Legacy `modern` preferences migrate to `dark`; retired `amber` preferences fall back to `classic`.

Formatting: `Cmd+Option+L` formats the document; `Cmd+Option+Shift+L` formats the selection. On non-macOS hosts use Ctrl in place of Cmd. Both actions are configurable in the keymap. Code → Format on Save is off by default.
