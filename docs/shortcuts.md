# Keyboard shortcuts

| Shortcut | Action |
|---|---|
| F1 | Quick documentation for the Java symbol at the caret |
| Cmd+Click / Cmd+B / F12 | Go to the resolved declaration |
| Ctrl+Space | Completion alternatives, signatures and overloads |
| F2 / Cmd+S | Save |
| F3 / Cmd+O | Open |
| F5 | Expand editor / restore messages |
| F6 | Editor / console input |
| F9 / Alt+F9 | Make current buffer |
| Ctrl+F9 / Cmd+R / Cmd+Enter | Stop previous program, compile if needed and Fast Run |
| F10 | Open menu; arrows / Enter / Escape navigate |
| Cmd+. | Stop program |
| Cmd+N | New file |
| Cmd+Shift+S | Save as |
| Cmd+Z / Cmd+Shift+Z | Undo / redo |
| Cmd+C / Cmd+X / Cmd+V / Cmd+A | Copy / cut / paste / select all |
| Cmd+F / Cmd+G / Cmd+Shift+G | Find / next / previous |
| Cmd+L | Go to line |
| Tab / Shift+Tab | Accept inline suggestion when present; otherwise indent / unindent |
| Alt+Left / Alt+Right | Navigate back / forward, restoring selection and viewport |
| Ctrl+Left / Ctrl+Right | Move by word (Shift extends selection) |
| Cmd+Left / Cmd+Right | Start / end of line |
| Cmd+Up / Cmd+Down | Start / end of document |
| Cmd+/ | Toggle line comments |
| Cmd+Shift+P | Search command palette |
| Alt+Space | Request optional AI completion |
| Alt+Shift+Space | Request AI again without the completion cache |
| Ctrl+Alt+Enter | Accept next word of an AI suggestion |
| Alt+X | Quit |
| Escape | Dismiss inline suggestion, alternatives, documentation or menu |

macOS may reserve function keys for hardware controls; hold Fn or adjust macOS keyboard settings. System shortcuts are not globally intercepted. On other operating systems Ctrl substitutes for Cmd in application bindings.

Menus also expose Isolated Run, Send EOF, clear output, replace selection, themes, font size and performance samples. Run → Java REPL switches the bottom pane to persistent JShell; Enter submits, Up/Down recall history, Tab completes, Reset starts a fresh session, and Stop terminates a hanging evaluation. Evaluate Selection and Evaluate Current Statement submit explicitly; context-dependent code receives real JShell diagnostics. Unimplemented debugger/test commands are absent.

`~/.config/turboj/keymap.json` maps command IDs to Swing KeyStroke strings. It is loaded at startup; invalid entries are ignored. Example:

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
