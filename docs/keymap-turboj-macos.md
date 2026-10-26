# TurboJ keymap for macOS: from Turbo C to the Mac

Status: proposal, implemented as the bundled profile `turboj-macos` ("TurboJ macOS"). It replaces TurboJ Classic and
is the default for new installations. Saved selections of `turboj-classic` move to it on load. Eclipse, IntelliJ IDEA
and VS Code stay available as they are.

## What Turbo C got right

Turbo C (and Turbo Pascal) did not have many shortcuts. It had a small *grammar*. One key named a family, and the
modifier chose the scope:

| Key | Plain | Alt | Ctrl |
| --- | --- | --- | --- |
| F9 | Make | Compile file | Run |
| F7 / F8 | Trace / Step | Previous / next error | Watch / Breakpoint |
| F1 | Help | Previous topic | Help on the word at the cursor |
| F2 / F3 | Save / Open | Close window (Alt+F3) | Reset program (Ctrl+F2) |
| F5 / F6 | Zoom / Next window | User screen (Alt+F5) | |
| F10, Alt+X | Menu, Exit | | |

The whole edit, compile, run and fix loop sat on a few keys that you learned once.

## Why it does not port to the Mac one-to-one

These are facts about macOS, checked on this machine where marked.

1. **Function keys are media keys.** On Mac laptops and Magic Keyboards, F1–F12 change brightness and volume unless
   you hold `fn`, or turn on "Use F1, F2, etc. keys as standard function keys". On this Mac the setting is off
   (`com.apple.keyboard.fnState` is unset), so every Turbo F-key needs `fn`.
2. **macOS takes Ctrl+F1…F8** for keyboard navigation: menu bar `⌃F2`, Dock `⌃F3`, window `⌃F4`, toolbar `⌃F5`,
   floating window `⌃F6`, Tab mode `⌃F7`, status menus `⌃F8`. Turbo's `Ctrl+F2` (reset), `Ctrl+F7` (watch) and
   `Ctrl+F8` (breakpoint) cannot work as they did.
3. **Option types characters.** Option plus a letter types `®`, `∂`, `ƒ` and so on. `⌥E`, `⌥U`, `⌥I`, `⌥N` and `` ⌥` ``
   are dead keys, and on Italian and other layouts `@ # [ ] { }` need Option. Turbo's `Alt+X` and the Alt+letter
   menus would type characters instead.
4. **Ctrl+letter is the Mac's text editing.** In every text field `⌃A`/`⌃E` go to line start and end, `⌃K` cuts to
   line end, `⌃D` deletes forward, and `⌃F`/`⌃B`/`⌃N`/`⌃P` move the caret. These are the Emacs bindings in Cocoa.
5. **The system reserves** `⌘Space` (Spotlight), `⌘Tab`, `` ⌘` ``, `⌘H`, `⌥⌘H`, `⌘M`, `⌘Q`, `⌥⌘D` (Dock),
   `⌃⌘D` (Look Up), `⌃⌘Q` (lock), `⌃⌘Space` (emoji), `⇧⌘3/4/5` (screenshots), and `⌃←/→/↑/↓` (Spaces and Mission
   Control). Two more are taken on this Mac:
   - **`⌃Space`** is "Select the previous input source" (hotkey 60, enabled), even though this Mac has a single
     layout (U.S.). That is why completion on Ctrl+Space never reached TurboJ.
   - **`⌃⌥Space`** is "Select next source".
6. **The Mac has four modifiers where Linux has two.** On Linux, Ctrl means "application command" and Alt means
   "menu or alternative". On the Mac, ⌘ is the application command, ⌥ is a typing modifier, ⌃ belongs to text and
   the system, and fn is a hardware layer. Copying Linux bindings with Ctrl→⌘ gets the verbs right and the rest
   wrong. That is the main reason the vendor Mac keymaps feel odd: each one moved a Windows or Linux map key by key.

**Verdict:** Turbo's grammar ports, but its keys do not. Put the grammar on ⌘ + letter, keep the Turbo function keys
as second bindings, and never depend on ⌥+letter, ⌃+letter or ⌃F-keys.

## The modifiers, Mac and PC

Apple's own correspondence comes first, and TurboJ follows it:

| PC (Windows, Linux) | Mac | Role in TurboJ |
| --- | --- | --- |
| Ctrl | ⌘ Command | The command key: almost every shortcut |
| Alt | ⌥ Option | The alternative: as ⌥⌘, or with ↩, arrows, Space and ⌫, never with a letter |
| Ctrl+Alt | ⌥⌘ | The code-transform family (refactor, format, imports) |
| Windows / Super | ⌃ Control | Left to the system and to text editing; only ⌃Space and ⌃Tab |
| — | fn | Hardware layer: needed for F1–F12 on most Macs |

There is one exception, on the PC side. A literal Ctrl+Alt+letter collides with desktop shortcuts on Linux
(Ctrl+Alt+T, Ctrl+Alt+L, Ctrl+Alt+arrows) and with AltGr on European Windows layouts. So the Linux column below uses
Eclipse's Alt+Shift for the ⌥⌘ family.

## The rules

1. **⌘ + letter is the verb, chosen by its initial:**
   - **B**uild, **R**un, **U**nit tests;
   - **D**eclaration, **T**ype, o**P**en quickly, **I**mplementation;
   - **J**avadoc, **L**ine, r**E**cent, **K**eymap.
2. **⇧ widens or reverses the verb:** `⇧⌘U` runs all tests, `⇧⌘D` finds every reference, `⇧⌘G` finds the previous
   match, `⇧⌘'` goes to the previous error, `⇧⌘T` jumps from source to test.
3. **⌥⌘ transforms code.** Refactoring and source: **R**ename, extract **M**ethod, **V**ariable, **C**onstant,
   **P**arameter, **I**nline, **L**ayout (format), imp**O**rts, and **T**his (the refactor menu). On Linux this
   family becomes Alt+Shift, as in Eclipse.
4. **↩ is the "do it" family:** `⌘↩` does the next sensible thing (complete, import or run), `⌥↩` lists the choices
   (quick fixes and assists), and `⇧⌘↩` completes the statement.
5. **Mac conventions win where they exist:** `⌘S`/`⇧⌘S`, `⌥⌘S` (save all, since ⌥ means "all"), `⌘W`, `⌘Q`, `⌘,`
   (settings), `⌘.` (stop), `⌘F`/`⌘G`, `⌥⌘F` (replace), `⌘[`/`⌘]` (back and forward), `⇧⌘[`/`⇧⌘]` (tabs),
   `⌃⌘F` (full screen).
6. **Only two Ctrl bindings, never with a letter:** `⌃Space` (completion, used by every IDE) and `⌃Tab`. macOS
   gives ⌃Space to the input-source switch, so completion is also on `⌥Space`. Plain ⌥ goes only with arrows,
   Enter, Space, Backspace and Delete, never with a letter.
8. **Keys a MacBook does not have are written with fn:** `fn⌫` forward delete, `fn←`/`fn→` Home/End, `fn↑`/`fn↓`
   Page Up/Down. Line start and end are the Mac's `⌘←`/`⌘→`.
7. **Turbo C stays as a second binding on every loop key:** `F9` make, `⌥F9` compile, `⌃F9` run, `⌥F7`/`⌥F8`
   errors, `F2` save, `F3` open quickly, `F1` help at the caret, `⇧F1` keyboard reference, `F5` zoom, `F6`
   editor/console, `F10` menu, `F12` declaration.

## The map

| Action | TurboJ macOS | Turbo C | Linux (proposed) |
| --- | --- | --- | --- |
| Complete, import or run | ⌘↩ | — | Ctrl+Enter |
| Completion list | ⌥Space or ⌃Space | — | Ctrl+Space |
| Quick fix / assists | ⌥↩ or ⌘1 | — | Alt+Enter or Ctrl+1 |
| Quick documentation | ⌘J or F1 | Ctrl+F1 | Ctrl+J or F1 |
| Request AI completion | ⌥⇧Space | — | Alt+Shift+Space |
| Complete statement | ⇧⌘↩ | — | Ctrl+Shift+Enter |
| Make project | ⌘B or F9 | F9 | Ctrl+B or F9 |
| Compile project (full) | ⇧⌘B | Compile › Build all | Ctrl+Shift+B |
| Compile file | ⌥⌘B or ⌥F9 | Alt+F9 | Alt+F9 |
| Run | ⌘R or ⌃F9 | Ctrl+F9 | Ctrl+R or Ctrl+F9 |
| Program arguments and input | ⇧⌘R | Ctrl+F7 (args) | Ctrl+Shift+R |
| Run test at caret | ⌘U | — | Ctrl+U |
| Run all tests | ⇧⌘U | — | Ctrl+Alt+U (GTK takes Ctrl+Shift+U) |
| Rerun failed tests | ⌥⌘U | — | Alt+Shift+U |
| Stop | ⌘. | Ctrl+F2 | Ctrl+F2 |
| Next / previous error | ⌘' / ⇧⌘' or ⌥F8 / ⌥F7 | Alt+F8 / Alt+F7 | F8 / Shift+F8 (GNOME takes Alt+F7/F8) |
| Next / previous failed test | ⌥⌘' / ⇧⌥⌘' | — | — |
| Open quickly (classes, files, methods) | ⌘P or F3 | F3 (open) | Ctrl+P or F3 |
| Go to class | ⌘T | — | Ctrl+T |
| Go to file | ⇧⌘P | — | Ctrl+Shift+P |
| Go to symbol | ⇧⌘O | — | Ctrl+Shift+O |
| Type / call hierarchy | ⇧⌘H / ⌥⇧⌘H | — | Ctrl+Shift+H / Alt+Shift+H |
| Declaration | ⌘D, F12 or ⌘-click | — | Ctrl+D or F12 |
| References | ⇧⌘D | — | Ctrl+Shift+D |
| Implementation | ⌘I | — | Ctrl+I |
| Source ↔ test | ⇧⌘T | — | Ctrl+Shift+T |
| Back / forward | ⌘[ / ⌘] or ⌃⌘← / ⌃⌘→ | — | Alt+← / Alt+→ |
| Previous edit location | ⇧⌘⌫ | — | Ctrl+Shift+Backspace |
| Matching brace | ⇧⌘\ | — | Ctrl+Shift+\ |
| Go to line | ⌘L | Ctrl+Q G (later Alt+G) | Ctrl+L |
| Recent files / locations | ⌘E / ⇧⌘E | Alt+0 (window list) | Ctrl+E / Ctrl+Shift+E |
| Next / previous tab | ⇧⌘] / ⇧⌘[ or ⌃⇥ | F6 | Ctrl+Tab |
| Save / save all | ⌘S or F2 / ⌥⌘S | F2 | Ctrl+S or F2 / Ctrl+Alt+S |
| Find / find and replace | ⌘F / ⌥⌘F | Ctrl+Q F / Ctrl+Q A | Ctrl+F / Ctrl+H |
| New (File ▸ New menu) / Generate (Code ▸ Generate) | ⌘N / ⌥⌘N | — | Ctrl+N / Alt+Shift+N |
| Comment line / block | ⌘/ / ⌥⌘/ | — | Ctrl+/ / Ctrl+Shift+/ |
| Move line | ⌥↑ / ⌥↓ | — | Alt+↑ / Alt+↓ |
| Copy line | ⌥⌘↑ / ⌥⌘↓ | — | Ctrl+Alt+↑ / ↓ |
| Delete line | ⇧⌘K | Ctrl+Y | Ctrl+Shift+K |
| Expand / shrink selection | ⌃⇧→ / ⌃⇧← | — | Alt+Shift+→ / ← |
| Select next / all occurrences | ⌥⌘G / ⌥⇧⌘G | — | Ctrl+Alt+G / Ctrl+Alt+Shift+G |
| Line start / end (smart) | ⌘← / ⌘→ or fn← / fn→ | Home / End | Home / End |
| Delete next word | ⌥fn⌫ | Ctrl+T | Ctrl+Delete |
| Refactor menu | ⌥⌘T | — | Alt+Shift+T |
| Rename | ⌥⌘R | — | Alt+Shift+R |
| Extract method / variable / constant / parameter | ⌥⌘M / V / C / P | — | Alt+Shift+M / V / C / P |
| Inline | ⌥⌘I | — | Alt+Shift+I |
| Safe delete | ⌥⌘⌫ | — | Alt+Shift+Delete |
| Format document / selection | ⌥⌘L / ⌥⇧⌘L | — | Alt+Shift+L / Alt+Shift+Ctrl+L |
| Organize imports | ⌥⌘O | — | Alt+Shift+O |
| Explorer / console / problems | ⌘0 / ⇧⌘C / ⇧⌘M | Alt+F5 (user screen) | Ctrl+0 / Ctrl+Shift+C / Ctrl+Shift+M |
| Show or hide the console | ⇧⌘Y (as in Xcode) | Alt+F5 | Ctrl+Shift+Y |
| Editor ↔ console | F6 | F6 | F6 |
| Zoom editor | F5 | F5 | F5 |
| Find Action / menu | ⇧⌘A / F10 | F10 | Ctrl+Shift+A / F10 |
| Settings / keymap | ⌘, / ⌘K | — | Ctrl+, / Ctrl+K |
| Keyboard reference | ⇧F1 | Shift+F1 (index) | Shift+F1 |
| Quit | ⌘Q | Alt+X | Ctrl+Q |

What did not make the first cut, and why:
- Turbo's **breakpoint and watch keys** (`Ctrl+F8`, `Ctrl+F7`) wait for a debugger. On the Mac they would follow
  Xcode's `⌘\`.
- **Ctrl+Y** (Turbo's delete line) is the Mac's "yank".

## Linux

Linux gets the same grammar and is not implemented yet. ⌘ becomes Ctrl, ⌥⌘ becomes **Alt+Shift** (Eclipse's Linux
refactor family), and the Turbo function keys work without `fn`. A few desktop shortcuts need avoiding:
- GNOME and Xfce: Ctrl+Alt+T (terminal), Ctrl+Alt+←/→ (workspaces), Alt+F1/F2, Alt+F4, Alt+F7/F8 (move and resize
  the window), and Super+anything.
- GTK and IBus: Ctrl+Shift+U (Unicode input).

That is why the Linux column changes errors and all-tests.

## What TurboJ does now

- `BundledKeymaps.turbo()` is this map. `BundledKeymaps.RETIRED` moves a saved `turboj-classic` selection and its
  children to `turboj-macos` and drops the stored Classic copy.
- New installations start on TurboJ macOS. Existing saved choices (Eclipse, IntelliJ, VS Code, custom) do not change.
- **Help › Print Keyboard Cheat Sheet…** prints two A4 pages for the active keymap, with the user's own bindings and
  notes for this Mac (fn, ⌃Space). It uses Chrome, Chromium, Edge or Brave headless to make a PDF. Without one of
  those, it opens the page in the browser and starts printing.
- `docs/cheatsheets/` holds the bundled sheets. Regenerate them with `scripts/cheatsheets.sh`.

## Open points

1. Make Cocoa's ⌃ text keys (`⌃A`, `⌃E`, `⌃K`, `⌃D`…) work in the editor, as Mac text fields do.
2. On first launch, detect ⌃Space owned by the input-source switch and offer to explain it, or bind completion to
   `⌘↩` alone.
3. Add a `turboj-linux` profile from the Linux column, chosen from `os.name`.
4. Decide whether ⌘↩ (complete, import or run) should also go into the IntelliJ and VS Code profiles. In VS Code it
   is "insert line below" today.
