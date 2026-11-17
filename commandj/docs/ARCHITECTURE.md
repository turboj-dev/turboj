# Architecture

`core` owns immutable command metadata/context/result, guarded dispatch, bounded asynchronous
execution, cooperative cancellation and recent successful commands. It has no runtime dependencies.
A registry uses a concurrent ID map and an immutable registration-order snapshot. Duplicate IDs
are rejected. A handler is invoked once per execution. Availability is evaluated again at dispatch.
Close cancels work and rejects new dispatch; an upstream task ignoring cancellation can continue,
but bounded active capacity prevents unbounded enqueueing.

`keymap` owns logical keys, up-to-four-stroke sequences, required/forbidden context tags,
profile inheritance and JSON persistence. Overrides replace a command's complete inherited list;
an empty list explicitly unbinds it. Conflicts include identical strokes and ambiguous chord
prefixes in overlapping contexts. Changes refuse conflicts unless the caller explicitly names
which conflicting bindings may be removed. Import is validated before replacing state.
Unregistered commands are inert. Disabled single-key commands fall through to widget navigation;
registered multi-key sequences remain reserved so dispatch can explain why an action is unavailable.
Pending chords allow ten seconds between keys, consume invalid/expired suffixes, cancel on Escape
and reset on profile or focus changes.

`palette` builds a local normalized search index when registry revision changes. It ranks exact,
prefix, substring and subsequence matches across title, ID, category and aliases, then applies
recent-command preference. It includes unavailable matches with explanations. Ordering is stable
and locale-independent. No external index or inference exists.

`swing` owns Action wrappers, a window-scoped KeyEventDispatcher, nonmodal palette/keymap
surfaces and a shortcut recorder. Key events in other windows remain available
to their controls. The bridge rejects dead-key/AltGraph/undefined events to avoid interfering with
input methods. It uses logical AWT key codes rather than claiming physical layout independence.

TurboJ also rejects commands in its `input-method` availability context. Deferring
in the dispatcher alone is insufficient: a Swing menu accelerator can still invoke
the same Action after an unconsumed key press. The execution-boundary guard covers
that route without consuming keys owned by an IME. Native matrix tests include this
accelerator bypass as well as modal isolation.

The host owns command semantics, source versions, undo operations and view state. CommandJ's
optional undo label is metadata, not a generic source editor undo implementation. Registering a
command does not imply a corresponding product capability exists. Bundled profiles can contain
bindings for IDs absent from a host; such bindings are inert and are absent from its palette.

## Embedded Swing surfaces

`commandj-swing` provides one `OverlayHost` in the application's existing root layered pane.
`EmbeddedDialog` is a plain `JPanel` with its own `JRootPane` and accessible dialog role.
Palette, keymap, shortcut recording, confirmation, input and file browsing create no additional
native window or desktop-style internal frame. The optional synchronous modal
API runs a Swing secondary event loop, so painting and background completion delivery remain
live. Nested overlays block their parent's controls, Escape closes only the top overlay,
and closing restores the previous focus. Bounds are clamped when the host resizes.

A host application must suspend global command routing while `OverlayHost.active(owner)` is
true; `CommandKeyDispatcher` does this automatically. A handled shortcut's pending typed event
is still consumed if that shortcut opens a surface or moves focus in the same active window.
The guard is installed before callbacks enter a modal secondary loop, and is never re-armed
when that loop returns. A new key press belongs to the new context, so dialog typing remains local. Applications can observe the root-pane
property `commandj.overlay.active` to dismiss transient documentation/completion previews.
The library does not import application themes: `setTheme(AsciiPalette)` supplies the host's
editor background, foreground, selection, muted text, border and monospaced font. Foreground
contrast is normalized to 4.5:1 and muted/border contrast to 3:1. `AsciiBorder` paints terminal
box-drawing glyphs and embeds titles in their top row. `AsciiStyle.installDefaults` and `apply`
use flat Basic Swing delegates, bracket buttons, text-cell rules and block scrollbars. The
transparent overlay does not dim the editor. Close buttons stay keyboard accessible and
`onOpened`, `onClosing`, `onClosed` preserve explicit lifecycle and preview-cancel handling.
The three-color `setTheme` overload remains available for existing embedders.

`EmbeddedFileChooser` uses the familiar selection/filter API
but its own lightweight browser; directory enumeration and file checks run on Swing workers,
and folder/overwrite prompts use the same overlay host.
