# TurboJ menus: a study and a proposal

Status: approved and implemented in `MenuLayout` (one table builds the menu bar; Find Action's `[Menu]` column reads
it). The inventory below describes the app before the change (26 Oct 2026). The proposal keeps every command and
changes only where it lives.

## Today

TurboJ has 12 menus and about 200 items, in this order: File, Project, Build, Edit, Search, Navigate, Code, Refactor,
Run, Test, Options and Help.

What does not work:
1. **The order is unusual.** Project and Build come before Edit. Every IDE, and every Mac app, opens with File and
   then Edit.
2. **Options is a catch-all of 25 items:**
   - themes, keyboard profile and AI submenus;
   - save automation, font size, full screen and performance;
   - the command palette, keymap and settings;
   - eight "Focus …" panel commands;
   - the output command line;
   - layout restore.

   There is no View menu, so panels and layout end up here.
3. **Edit mixes text editing with code intelligence.** Completion and quick documentation sit next to Cut and Paste.
4. **Code is nearly empty.** It has four items (format and imports). The ten `java.generate…`, surround and override
   commands are not in any menu.
5. **Refactor ends with a "Quick fixes" submenu of nine items** (Add Import, Create Method, Fix Type Mismatch…).
   These run on a compiler problem at the caret, so in a menu they are usually disabled. Quick Fix (⌥↩ / ⌘1)
   already offers them where they apply.
6. **The edit, compile, run and fix loop is spread over three menus** (Build, Run, Test). Build also holds twelve
   Maven lifecycle items, which are project operations.
7. **The Mac conventions are missing.** There is no TurboJ application menu for About, Settings… (⌘,) and Quit, so
   Settings hides inside Options and Quit at the bottom of File.

## What the references do

| Turbo C++ 3.0 | Eclipse | IntelliJ IDEA | VS Code | Xcode |
| --- | --- | --- | --- | --- |
| ≡ (system) | File | File | File | Xcode (app) |
| File | Edit | Edit | Edit | File |
| Edit | Source | View | Selection | Edit |
| Search | Refactor | Navigate | View | View |
| Run | Navigate | Code | Go | Find |
| Compile | Search | Refactor | Run | Navigate |
| Debug | Project | Build | Terminal | Editor |
| Project | Run | Run | Window | Product |
| Options | Window | Tools | Help | Debug |
| Window | Help | Git | | Source Control |
| Help | | Window, Help | | Window, Help |

Lessons:
- **Turbo C:** one menu per stage of the loop (Search, Run, Compile, Debug). The ≡ menu holds the application
  itself. About ten menus, each short enough to scan at a glance.
- **Eclipse:** the clearest split between Source (generate, format, imports) and Refactor (change structure).
- **IntelliJ:** a View menu for windows, and a Navigate menu that is all "go to". Its Code menu holds completion,
  generation and formatting together.
- **VS Code:** Go and Run, short menus, and the command palette for the long tail.
- **Xcode, the Mac model:**
  - one Product menu for build, run, test and stop (⌘B ⌘R ⌘U ⌘.);
  - the app menu for Settings and Quit;
  - a separate Find menu.

## The rules

1. **One menu per question the user has in mind.** Make a file (File), change text (Edit), find text (Search), go
   somewhere (Navigate), write code (Code), restructure it (Refactor), make it work (Run), configure the build
   (Project), arrange the window (View), learn TurboJ (Help).
2. **The menus follow the loop, left to right:** write, find, change, run. As in Turbo C, the most used menus are
   the first ones under the hand.
3. **At most about 15 items per menu**, in groups of three to six separated by lines. A submenu only for a family
   of five or more.
4. **Every item is a registry command,** so the menu, Find Action (`[Menu]` column), the keymap and the cheat sheet
   always agree. Problem-driven operations stay in Quick Fix, not in menus.
5. **The Mac application menu** holds About, Settings, Keymap, Keyboard Profile, Themes, AI and Quit, with the
   Turbo ≡ symbol. Since TurboJ draws its own menu bar, this is the first menu, `≡ TurboJ`.

## The proposal: 11 menus

**≡ TurboJ** (Turbo's ≡; the Mac app menu)
- About TurboJ · Performance
- Settings… ⌘, · Keymap… ⌘K · Keyboard Profile ▸ (TurboJ, Eclipse, IntelliJ, VS Code)
- Themes ▸ · AI Completion ▸ (Disabled, Local, Cloud, Configure)
- Quit TurboJ ⌘Q

**File**
- New ▸ (Java Class…, Java File ⌘N, Java Project… ⇧⌘N, Maven Project…)
- Open… ⌘O (files and project folders) · Open Recent ▸ · Choose Workspace…
- Save ⌘S · Save As… ⇧⌘S · Save All ⌥⌘S
- Close File ⌘W · Reopen Closed File

**Edit**
- Undo ⌘Z · Redo ⇧⌘Z
- Cut · Copy · Paste · Select All
- Toggle Line Comment ⌘/ · Toggle Block Comment ⌥⌘/
- Line ▸ (Duplicate, Delete ⇧⌘K, Move Up ⌥↑, Move Down ⌥↓, Copy Up/Down, Join, Sort, Insert Below ⇧↩)
- Selection ▸ (Expand, Shrink, Next Occurrence, All Occurrences, Column Selection, Add Caret Above/Below)
- Change Case · Indent / Unindent

**Search** (Turbo's Search; the Mac's Find)
- Find… ⌘F · Find Next ⌘G · Find Previous ⇧⌘G · Replace… ⌥⌘F
- Find in Project… ⇧⌘F (planned)
- Go to Line… ⌘L

**Navigate** (Eclipse and IntelliJ Navigate; VS Code Go)
- Search Everywhere… ⌘P · Go to Class… ⌘T · Go to File… · Go to Symbol… ⇧⌘O
- Declaration ⌘D · Implementation ⌘I · References ⇧⌘D · Type Hierarchy · Call Hierarchy
- Source ↔ Test ⇧⌘T · Test Failure
- Back ⌘[ · Forward ⌘] · Last Edit Location · Recent Files… ⌘E · Recent Locations… ⇧⌘E
- Next / Previous Error ⌘' / ⇧⌘' · Next / Previous Method · Matching Brace

**Code** (Eclipse Source + IntelliJ Code)
- Complete, Import or Run ⌘↩ · Completion ⌃Space · Smart Completion · Complete Statement ⇧⌘↩
- Quick Fix / Assist… ⌥↩ · Quick Documentation ⌘J · Parameter Info
- Generate ▸ (Constructor, Getters, Setters, Getters and Setters, toString, equals, hashCode, Javadoc)
- Implement Methods… · Override Methods… · Surround ▸ (if, try/finally)
- Format Document ⌥⌘L · Format Selection ⌥⇧⌘L · Organize Imports ⌥⌘O · Format on Save ✓
- AI ▸ (Request ⌥Space, Another ⌥⇧Space, Longer)

**Refactor**
- Refactor This… ⌥⌘T
- Rename… ⌥⌘R · Change Signature… · Move… · Safe Delete…
- Extract ▸ (Method ⌥⌘M, Variable ⌥⌘V, Constant ⌥⌘C, Field, Parameter ⌥⌘P, Interface)
- Inline ▸ (Inline ⌥⌘I, Variable, Method)
- Hierarchy ▸ (Introduce Interface, Pull Up, Push Down) · Encapsulate ▸ (Field, Constructor to Factory)
- Modernize ▸ (Anonymous to Lambda, Lambda ↔ Method Reference, Switch to Expression, var ↔ type)
- *Removed:* the "Quick fixes" submenu. Its operations stay in Quick Fix and in Find Action.

**Run** (Turbo's Run + Compile; Xcode's Product; TurboTest)
- Run Java / JUnit ⌘R · Isolated Run · Arguments and Input… ⇧⌘R · Stop ⌘. · Send EOF
- Make Project ⌘B · Fast Compile ⌥⌘B · Compile Project · Go to First Error
- Run Test at Caret ⌘U · Run Test Class · Run All Tests ⇧⌘U · Run Failed Tests · Stop Tests · Test Results…
- Auto Run on Save ✓ · Auto Test on Save ✓ · Auto Test on Changes ✓
- Java REPL ▸ (Open, Reset, Evaluate Selection, Evaluate Statement) · Output Command Line… · Clear Output

**Project** (Eclipse Project; Maven)
- Open Project… · Reload Project · Find Project File… · Find Module…
- Structure… · Dependencies… · Dependency Graph… · Profiles… · Maven Settings…
- POM ▸ (Effective POM, Parent POM, POM Declaration, Add Dependency, Update Version, Remove Dependency, Add Module)
- Maven ▸ (validate, compile, test, package, verify, install, clean, dependency:tree, Run Goal…, Refresh, Stop)
- Attach Sources… · Attach Javadoc… · Annotation Processors…

**View** (IntelliJ View; Xcode View)
- Project Explorer ⌘0 · Console ⇧⌘C · Problems ⇧⌘M · Test Results · JShell
- Focus Editor · Next Panel · Previous Panel
- Expand Editor F5 · Maximize Editor Area · Restore Layout · Resize Output
- Full Screen ⌃⌘F · Larger Font ⌘= · Smaller Font ⌘-
- Show Pressed Shortcuts ✓ · Shortcuts as ⌃⌥⇧⌘ Symbols ✓

**Help**
- Find Action… ⇧⌘A (IntelliJ and Mac apps put it here) · Keyboard Reference… ⇧F1
- Print Keyboard Cheat Sheet… · Keymap Study (opens `docs/keymap-turboj-macos.md`)
- TurboJ Website · Report an Issue

Every menu fits the 15-item rule except Run, at 22. Run is the Turbo loop in one place, and that is the point, as in
Xcode's Product menu. If it proves too long, Test can become its own menu again, right after Run.

## Mnemonics and the keyboard

On the Mac, F10 opens the menu bar and the arrow keys move through it. Mnemonic letters still help on Linux and in
the palette, and each menu gets a distinct one: ≡ (Space), **F**ile, **E**dit, **S**earch, **N**avigate, **C**ode,
Refac**t**or, **R**un, **P**roject, **V**iew, **H**elp. They match Turbo C's Alt+F, Alt+E, Alt+S and Alt+R.

## Implementation, once approved

1. Describe the menu bar as one table (menu → groups → command ids) in place of the hand-built menus in
   `TurboFrame`. Add a test that every registered command appears in a menu or is deliberately palette-only (the
   quick-fix operations, profile switches).
2. Find Action's `[Menu]` column and the cheat sheet sections can then read the same table.
3. Move the toggles (format on save, auto run and test, pressed shortcuts, symbols) into the menus where their
   subject lives.
