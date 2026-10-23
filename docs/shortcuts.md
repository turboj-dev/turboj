# Keyboard shortcuts

| Shortcut | Action |
|---|---|
| F1 | Help |
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
| Tab / Shift+Tab | Indent / unindent |
| Alt+Left / Alt+Right | Move by word (Shift extends selection) |
| Cmd+Left / Cmd+Right | Start / end of line |
| Cmd+Up / Cmd+Down | Start / end of document |
| Cmd+/ | Toggle line comments |
| Cmd+Shift+P | Search command palette |
| Alt+X | Quit |
| Escape | Dismiss menu / return to editor |

macOS may reserve function keys for hardware controls; hold Fn or adjust macOS keyboard settings. System shortcuts are not globally intercepted. On other operating systems Ctrl substitutes for Cmd in application bindings.

Menus also expose Isolated Run, Send EOF, clear output, replace selection, themes, font size and performance samples. Run → Java REPL switches the bottom pane to persistent JShell; Enter submits, Up/Down recall history, Tab completes, Reset starts a fresh session, and Stop terminates a hanging evaluation. Evaluate Selection and Evaluate Current Statement submit explicitly; context-dependent code receives real JShell diagnostics. Unimplemented debugger/test commands are absent.

`~/.config/turboj/keymap.json` maps command IDs to Swing KeyStroke strings. It is loaded at startup; invalid entries are ignored. Example:

```json
{
  "run": "meta shift R",
  "compile": "F9",
  "stop": "meta PERIOD"
}
```

Application command IDs include new, open, save, saveAs, quit, undo, redo, cut, copy, paste, selectAll, indent, unindent, comment, find, findNext, findPrevious, replace, goto, compile, run, isolated, stop, eof, clear, error, zoom, focus, repl, output, replReset, evaluateSelection, evaluateStatement, fontUp, fontDown, fullscreen, metrics, palette, help and theme.classic/theme.amber/theme.modern. Editor movement/editing defaults remain local to the editor and take precedence over application key bindings; use an unmapped shortcut for custom commands.

Program Run focuses the output text itself, with a steady block cursor immediately after the last prompt. There is no separate stdin row: `Inserisci un numero: 20` is edited on one line. Click the output pane to focus input (dragging still selects output for copying), type and press Enter. Values are echoed in the transcript. F6 returns to the editor. Run again replaces the previous process, discards old pending output and clears unsent input. The execution timeout excludes time blocked waiting for stdin; Stop remains available while waiting.
