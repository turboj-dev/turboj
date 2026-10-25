# Keyboard profiles and official reference audit

Reviewed 24 October 2026. This is a source-level comparison, not a claim that every
OS keyboard layout or every upstream IDE action has been exercised on hardware.
The executable command inventory is generated from TurboJ's CommandJ registry;
the application’s **Shortcut Reference** also exports the active profile and an
all-profile Markdown comparison, including unassigned commands.

## Sources and interpretation

- [IntelliJ IDEA macOS keymap](https://www.jetbrains.com/help/idea/reference-keymap-mac-default.html):
  current editing, structure, selection and navigation conventions.
- [VS Code macOS reference card](https://code.visualstudio.com/shortcuts/keyboard-shortcuts-macos.pdf)
  and [default bindings](https://code.visualstudio.com/docs/reference/default-keybindings):
  its separate line-copy, occurrence selection, panel and chord conventions.
- [VS Code keyboard configuration](https://code.visualstudio.com/docs/configure/keybindings):
  contexts, chords and customization are independent of theme.
- [Eclipse text-editor bindings](https://github.com/eclipse-platform/eclipse.platform.ui/blob/master/bundles/org.eclipse.ui.workbench.texteditor/plugin.xml)
  and [JDT bindings](https://github.com/eclipse-jdt/eclipse.jdt.ui/blob/master/org.eclipse.jdt.ui/plugin.xml):
  actual command IDs, generic declarations, platform overrides and removals.
- [Eclipse binding extension contract](https://help.eclipse.org/latest/topic/org.eclipse.platform.doc.isv/reference/extension-points/org_eclipse_ui_bindings.html):
  M1 means Command on macOS, M2 Shift, M3 Option and M4 Control.
- [Eclipse binding persistence](https://github.com/eclipse-platform/eclipse.platform.ui/blob/master/bundles/org.eclipse.ui.workbench/eclipseui/org/eclipse/ui/internal/keys/BindingPersistence.java):
  the current loader also applies historical `carbon` declarations to Cocoa. A
  generic Windows-oriented table is consequently insufficient for macOS.
- [Eclipse official reference card](https://www.eclipse.org/community/eclipse_newsletter/assets/public/images/Eclipse%20IDE%20Keybindings.pdf):
  distinguishes Run from Debug on macOS. The source declarations take precedence
  for current commands when a historical reference differs.

No external implementation code was copied. Shortcut facts were used to configure
CommandJ; transformations remain TurboJ's own implementations.

## Significant differences preserved

| Action | IntelliJ macOS | Eclipse macOS | VS Code macOS | TurboJ Classic |
| --- | --- | --- | --- | --- |
| Delete line | Cmd+Backspace | Cmd+D | Cmd+Shift+K | Find Action / customizable |
| Duplicate / copy lines down | Cmd+D duplicates selection or line | Cmd+Option+Down copies lines | Option+Shift+Down copies lines | Find Action / customizable |
| Select next occurrence | Ctrl+G | Find Action / customizable | Cmd+D | Find Action / customizable |
| Move physical lines | Option+Shift+Up/Down | Option+Up/Down | Option+Up/Down | Find Action / customizable |
| Structural selection | Option+Up/Down | Ctrl+Shift+PageUp/PageDown | Cmd+Ctrl+Shift+Right/Left | Find Action / customizable |
| Find Action | Cmd+Shift+A | Cmd+3 | Cmd+Shift+P or F1 | Cmd+Shift+P |
| Format document | Cmd+Option+L | Cmd+Shift+F | Option+Shift+F | Cmd+Option+L, a TurboJ addition |
| Save / Open / Compile / Run | Profile-specific registry bindings | Profile-specific registry bindings | Profile-specific registry bindings | F2 / F3 / F9 / Ctrl+F9 |

These describe fresh defaults. Saved profiles are intentionally retained, including
older defaults, until the user explicitly resets or changes their bindings.

Use **Options → Keyboard Profile** to switch immediately between the four bundled
profiles, or search for **Keyboard Profile: IntelliJ IDEA macOS**, **Eclipse
macOS**, **VS Code macOS** or **TurboJ Classic** in the Command Palette. The
profile selector in **Options → Keymap** also supports custom profiles. Selecting
a profile retains its overrides and saves the choice for the next session;
switching is separate from the explicit **Reset profile** action.

The Eclipse audit corrected the former generic Option+Shift refactoring shortcuts
to the macOS Command+Option declarations. Run uses Cmd+Shift+F11; Cmd+F11 belongs to
Debug upstream. Eclipse’s current JDT metadata explicitly removes the generic
Change Signature binding on macOS without supplying a replacement in that
declaration. TurboJ therefore leaves that fresh default unassigned. The macOS
text-editor metadata also removes Join Lines at Cmd+Option+J; assigning it would
confuse the JDT Javadoc action. Both operations remain discoverable in Find Action.

IntelliJ's current documented history bindings Cmd+[ / Cmd+] are present alongside
TurboJ's retained Cmd+Option+Left/Right alternatives. Double-modifier gestures
such as IntelliJ's double-Option caret clone are not relabeled as ordinary key
chords: CommandJ's 1–4-stroke model represents actual key strokes. Caret commands
remain accessible without requiring those gestures.

TurboJ's rectangular-selection command converts an existing anchor/caret range
into real row selections. It does not enable a persistent mode where subsequent
arrow keys extend a rectangle; matching a familiar binding does not claim an
identical persistent-mode interaction.

## Vocabulary coverage

The following audit groups describe equivalent concepts, not a promise of complete
upstream feature parity. The generated registry is authoritative about executable
TurboJ commands. Local widget actions such as tree arrows and dialog Tab are
documented in the accessibility audit instead of registering hundreds of buttons.

| Category | IntelliJ terminology | Eclipse terminology | VS Code terminology | TurboJ route / boundary |
| --- | --- | --- | --- | --- |
| Text editing | Join, split, case, indent | Text editor actions | Editor actions | Editor commands; source edits are undoable |
| Line manipulation | Duplicate / Move Line | Copy / Move Lines | Copy / Move Line | Distinct duplicate, copy, move and delete callbacks |
| Blocks / statements | Move Statement / Element | Source and refactoring actions | Language-dependent code actions | Java-aware actions separate from physical line edits |
| Clipboard | Cut / Copy | Cut / Copy | Cut / Copy line when empty | Selection wins; otherwise logical-line semantics |
| Selection | Extend / Shrink | Enclosing / Restore Selection | Expand / Shrink | Java structure plus reversible selection history |
| Multiple carets | Clone Caret / Occurrences | Block selection | Add Cursor / Occurrences | Actual multiple selections, not painted-only cursors |
| Search / replace | Find / Replace | Find / Replace | Find / Replace | Existing dialogs and registry commands |
| Navigation | File, Symbol, Declaration | Resource, Type, Declaration | Quick Open, Symbol, Definition | Registered routes; unavailable results explain why |
| Code structure | Structure / Hierarchy | Outline / Hierarchy | Outline / Call Hierarchy | Depends on supported semantic queries |
| Completion | Basic / Type Matching | Content Assist | Trigger Suggest | Existing Java engine; no AI requirement |
| Documentation | Quick Documentation | Hover / Javadoc | Hover | Caret action, no mouse-hover requirement |
| Refactoring | Refactor This | Refactor menu | Refactor code actions | Existing RefactorJ operations and preview |
| Quick fixes | Intentions | Quick Fix | Quick Fix | Existing semantic code actions |
| Formatting | Reformat | Format / Correct Indentation | Format document / selection | FormatJ; independent save option |
| Generation | Generate / Override | Source / Implement | Java extension actions | Only implemented semantic generators are registered |
| Compilation | Build / Rebuild | Build | Tasks / extension builds | TurboJ native compile and explicit Maven builds stay distinct |
| Execution | Run | Run | Run without debugging | Existing runner and run configuration |
| Tests | Run tests / Rerun failed | JUnit view | Testing view | TurboTest commands, results and failure navigation |
| Debugging | Debugger | Debug perspective | Run and Debug | No fake debugger bindings or callbacks |
| Project navigation | Project tool window | Project Explorer | Explorer | Keyboard tree and file/module discovery |
| Maven | Maven tool window | Maven integration | Maven extension | Existing MavenJ and lifecycle commands |
| Console | Run console / Terminal | Console | Output / Terminal | TurboJ output, program input and JShell; not a shell terminal |
| Panels | Tool windows | Views | Views / editor groups | Focus and toggle commands; no invented split-editor UI |
| Settings | Settings | Preferences | Settings | Existing embedded settings dialogs |
| Themes | Appearance | Appearance | Color Theme | Existing searchable shared theme catalog |
| Accessibility | Focus and keyboard settings | Keys / accessibility | Accessibility / keyboard navigation | Focus policy plus explicit host-verification limits |

## Contexts, conflicts and migration

The four profiles remain separate. New installations select IntelliJ macOS;
existing installations retain their selected profile. A binding's context and a
command's availability both apply. New editor-specific bindings require editor
focus. Normal text input, dead keys and AltGraph events remain outside the
dispatcher; an `input-method` context suspends command matching during composition.
Active menus and embedded dialogs own their navigation before global matching.
Palette actions run after the previous surface closes. `OverlayHost.closeThen`
carries the logical return-focus target into an immediately opened dialog, even
when native focus events have not yet caught up with the Swing event queue.

Existing persisted IDs can resolve through `CommandRegistry.alias` without
creating duplicate palette entries. The resolver compares canonical identities,
so an alias and its target invoke the same callback. Aliases are also searchable.

Loading an older full-snapshot JSON keeps every stored binding and explicit empty
binding. Missing newly introduced defaults are added only if the complete profile
graph remains conflict-free. Conflicts with inherited custom profiles also prevent
installation. Skipped defaults produce visible migration diagnostics. Reset and
conflict resolution remain explicit user actions; unrelated shortcuts are retained.

The legacy per-command settings importer processes all user overrides together.
User/user collisions are preserved and displayed; only conflicting defaults are
removed. Ambiguous shortcuts do not execute either action. The persisted JSON marks
these retained conflicts explicitly so restarting cannot silently discard a user
binding. The reference identifies them for deliberate resolution.

The reference and Keymap Editor expose exact/prefix conflicts, context overlap,
unregistered binding IDs and OS reservations. OS warnings are advisory: users may
have reassigned system shortcuts. Ctrl+Space input-source reservations, Fn/Globe
function-key behavior and Italian/Swiss/US hardware layouts require host checks;
portable logical-key tests are not presented as physical-layout verification.

## Implementation and tests

- `BundledKeymaps` defines defaults; `KeymapManager` handles inheritance and safe
  migrations; `ShortcutResolver` indexes prefixes and invalidates on map/registry
  revision. It does not scan all commands or call all availability predicates on
  every ordinary typed character.
- `CommandSearch` caches title/category/aliases and caller-provided shortcut terms;
  changing bindings invalidates those terms. Disabled matches retain their reason.
- `ShortcutReference` generates active/all-profile Markdown and diagnostics from
  executable descriptors; `ShortcutReferenceDialog` provides search and export.
- `KeyboardAuditTest`, `CommandRegistryTest`, `CommandSearchTest`, `KeymapTest` and
  Swing bridge/overlay tests cover the library behavior. Desktop workflow evidence
  and unverified host cases belong in `keyboard-accessibility-audit.md`.

Do not publish a copied static shortcut table as a universal TurboJ keymap. Export
the actual profile, including customization and unassigned actions, and retain its
generation/version context.
