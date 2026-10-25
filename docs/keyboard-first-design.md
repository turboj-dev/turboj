# Keyboard-first interaction

TurboJ keeps its Java/Swing editor, CommandJ registry and four keyboard profiles.
This implementation extends those components; there is no second application
dispatcher, native-window redesign or website demo change.

## Command and focus ownership

`TurboFrame` registers actions once. Menus, the palette, shortcuts and the generated
reference resolve the same descriptor/callback. Legacy IDs such as `goFile`,
`signature`, `references` and `recentFiles` resolve to canonical actions through
CommandJ aliases without duplicate palette rows. The reference includes commands
without defaults, required contexts, active availability explanations and conflicts.

The editor context means the source editor actually owns focus. An explorer or
console selection cannot accidentally trigger source deletion. Menus retain the
previous text target; the palette captures its invocation context before opening.
Cut/Copy/Paste route to the appropriate text component. Read-only attached source
remains protected. Source operations are unavailable during input-method composition.

CommandJ suspends application shortcuts while an embedded dialog, password field,
input method or Swing menu owns input. Changing focus cancels a pending chord.
Local widget navigation is deliberately not duplicated as global commands:

| Surface | Keyboard route |
| --- | --- |
| Menus | Open Menu / Classic F10; arrows, Enter, Escape |
| File picker | Path/name field, Tab to file list, arrows and Enter, Escape |
| Maven explorer | Focus Project Explorer; arrows expand/collapse; Enter opens; Escape returns to source |
| Command Palette | Search title/category/alias/shortcut; arrows, Enter, Escape |
| Test results | Focus Test Results; arrows, Enter to source; Tab to details/rerun/close |
| Diagnostics / navigation results | Select with arrows, Enter opens source, Escape closes |
| Refactoring forms | Tab/Shift+Tab through parameters and actions; preview before Apply |
| Refactoring preview | Keyboard file selector, scrollable before/after text, Tab to Apply/Cancel |
| Theme browser | Search, arrows/page navigation, preview, Enter applies, Escape restores |
| Keymap editor | Search table, Tab to record/reset/import/export/conflicts; special-key selector in recorder |
| Console | Focus Console; native text selection/copy; Enter sends input; Send EOF command |
| JShell | Focus JShell; Enter evaluates, history and completion remain local; Escape returns to source |

Embedded dialogs have one shared traversal policy: Tab/Shift+Tab move between
controls (including text areas and tables); Enter activates a focused button;
text-area Enter remains a newline. A popup gets Escape before its containing dialog.
Closing a surface restores its logical previous focus. All focus decoration uses
the existing ASCII/theme infrastructure.

The ASCII controls use Basic Swing delegates even when the host look and feel is
Aqua. Their local UI input maps must therefore name actions supplied by those
delegates. Combo and tree navigation use compatible Basic actions; text arrows
and paging use the standard editor-kit actions. List Home/End keep the macOS
scroll-only behavior and preserve selection. Component-level custom bindings
remain above these UI maps; the host's global shortcut maps are not replaced.
Text paging is standard Basic editor-kit behavior, not a claim of pixel-identical
Aqua scrolling.

Explicit panel commands focus the editor, explorer, console, JShell, diagnostics
or test results. Next/Previous Panel visits visible main panels. Maximize Editor
remembers explorer/output visibility and divider position; Restore Layout restores
that state. No new tab strip is invented: Next/Previous Open File operate on the
existing buffered documents. Close/Reopen retain document history and external-file
fingerprints. Save All is sequential and retains the original document identity
even if Save As changes its path.

## Text editing and undo

Offsets remain UTF-16. Caret motion and deletion respect code points and CRLF.
Ordinary editing never requests an AST. Line movement uses sparse replacements
over the affected logical lines rather than reparsing or rewriting the whole file.

- Cut/Copy use the exact selection when one exists. Otherwise they use the logical
  line. Line-oriented clipboard metadata is JVM-local; external consumers receive
  ordinary text. Pasting a line into an empty selection inserts at a line boundary.
- Delete Line never changes the clipboard. Duplicate duplicates selected text or
  the current line. Copy Line Up/Down always copy complete selected line ranges.
- Move Line preserves relative indentation and selection direction, handles final
  unterminated lines, and does not claim semantic preservation.
- Insert Above/Below retain indentation. Join, Split, word/line deletion, whitespace
  conversion, comments, case conversion, sorting/reversing, transpose and smart
  Home/End are executable registry actions.
- A user command creates one document transaction. Undo/redo restore selections
  as well as content. Semantic edit application checks its source version and
  proposed positions before mutation.

Multiple carets are actual independent selections. Typing, IME commits, Delete,
Backspace, paste, duplicate/move/copy lines and comments generate normalized sparse
edits, merge overlapping ranges and avoid editing the same text twice. Adjacent
nonoverlapping selections remain independent. Clipboard metadata records each
fragment and supports plain-text fallback. Remove Secondary Carets keeps the primary
selection. Rectangular Selection converts the selected anchor/caret rectangle into
real per-line selections; it is not a persistent rectangular arrow-navigation mode.

AI and semantic inline completion are suspended while multiple carets are active.
Structural Java operations and FormatJ Auto-Indent require one selected range and
explain that limitation instead of silently dropping additional carets. Auto-Indent
uses the existing FormatJ selection formatter, so it can normalize formatting beyond
leading whitespace. With no selection it selects the current line first.

## Java structure and semantic safety

`JavaStructure` exposes immutable ranges, edits and navigation targets through the
existing Java intelligence API. `SharedJavaIntelligence` uses `LtkWorkspace.ast`;
there is no separate project index. `KeyboardJavaController` uses the existing
serialized semantic lane, immutable source snapshots, request generations and
publication checks. Project/source changes invalidate pending work. Analysis and
candidate validation stay off the EDT.

Selection expands through AST ranges and contracts through a recorded selection
history. Explicit statement, method, block, enclosing-type and bracket selections
use syntax boundaries; source edits invalidate obsolete selection history.

Statement/block movement operates on complete adjacent syntax within one existing
scope. Attached comments move with the code when their ownership is unambiguous.
Variable dependencies, control transfers, initialization ordering and unresolved
bindings constrain applicability. Methods can move as complete declarations.
Field reordering is restricted to compile-time constants. Moves that deliberately
change observable execution order require an explicit warning/preview; unsafe or
ambiguous scopes are refused. Physical Move Line remains a separate action.

Element movement handles eligible argument/initializer/enum lists. It refuses
side-effecting expressions, mutable reads and overload changes. Even pure value
reordering can change results and is presented as a deliberate change. Parameter
movement prepares a typed RefactorJ Change Signature request, updating applicable
call sites through the existing preview and transaction/undo machinery.

Generation actions include constructor, accessors, equals/hashCode/toString,
implement/override methods, Javadoc, surround-with and statement completion.
They inspect actual declarations/bindings, reject duplicate or unsupported members
and validate candidates. Member generation targets resolved ordinary classes;
records, enums and interfaces are not silently rewritten. Static fields are excluded,
final fields do not receive setters, and an existing matching declaration is kept.
Generated equality uses exact-class comparison and includes a missing hashCode;
a non-Object superclass requires an explicit equality policy and is refused.
Implement Methods emits clearly identified throwing stubs for missing abstract
members; Override Methods delegates eligible concrete superclass methods and does
not guess how to resolve competing interface defaults. All generated changes have
a preview, and warnings identify stubs and equality-contract review requirements.
Surround With currently supports an eligible complete
statement in `if (true)` or `try/finally`; Complete Statement completes eligible
missing semicolons, not arbitrary incomplete Java. These are eligibility limits,
not promises to generate every conceivable Java construct.

Smart Completion filters resolved proposals by assignment compatibility where an
expected type is available from an initializer, assignment, return or resolved call
argument. An unresolved expected type produces an explanation rather than a guessed
ranking. Parameter Information has an explicit command; keyboard Quick Documentation
focuses selectable text that can be scrolled and closed without hovering.

Navigation includes source symbols/types, implementations, references, type
hierarchy, callers/callees, adjacent methods and matching braces. Binary targets
without source are not fabricated as writable workspace source. Source/Test
navigation uses the `Foo.java` / `FooTest.java` convention and offers a choice if
several files match; it is not a universal test association system. Diagnostics,
recent files/locations, edit locations and open-file cycling are separate commands.
Callers/callees list statically resolved workspace invocation sites. They are not a
complete runtime call graph: reflection, dynamic dispatch and deferred callbacks
are not proven by these queries. Parameter movement uses Change Signature for
ordinary method parameters; constructor-parameter changes remain available through
the existing Change Signature operation.

## Profiles, migration and discoverability

Switch profiles through **Options → Keyboard Profile** or search **Keyboard
Profile** in the Command Palette. IntelliJ IDEA macOS, Eclipse macOS, VS Code
macOS and TurboJ Classic each have a registry action (`keymap.profile.<id>`).
The active bundled profile is checked in the menu. The existing **Options →
Keymap** dialog also switches profiles and manages custom profiles and bindings.
Changes take effect immediately and are saved by the existing asynchronous
preferences path. Switching does not reset any profile's customizations. When a
custom profile is active, no bundled profile is falsely marked as selected.

New installations retain IntelliJ macOS as default. Eclipse, VS Code and Classic
remain distinct profiles: Eclipse Cmd+D deletes a line; IntelliJ Cmd+D duplicates.
Classic F1/F2/F3/F9/Ctrl+F9/F10 are preserved. Official sources and deliberate TurboJ
choices are documented in [the comparison](keymap-comparison.md).

Saved bindings, selected profile and explicit unassignments survive updates. New
defaults are added only if they do not shadow existing customizations. Legacy
imports preserve conflicting user bindings and display diagnostics; they do not
choose a winner based on map iteration order. Alias-aware customization/reset
cannot leave a hidden old binding active. Exact/prefix/context collisions and
reserved-key advisories are available in Keymap Settings and the generated reference.

Palette matching is local and deterministic. Descriptor aliases include familiar
terms such as “remove row” and “clear line”; binding labels are searchable. There
is no model inference or network request. Documentation is generated by
`KeyboardRegistryAudit` from the initialized application registry, not a handwritten
parallel command list.

## Verification boundaries

See [the interaction audit](keyboard-accessibility-audit.md) for executed workflows
and [performance measurements](keyboard-performance.md) for workloads and limits.
Synthetic Swing/OS key injection is not a human usability study or exhaustive
physical keyboard-layout certification. Function-key modes, VoiceOver and Italian,
Swiss Italian, Swiss German and US physical input methods require separately
identified host verification. The website must not claim broader coverage than
that audit demonstrates.
