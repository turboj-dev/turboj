# Native workspace layout preview

This is a separate native UI follow-up to the locally completed website hero
commit `997057b`. The user requested testing the native application first and
matching the browser reconstruction only after accepting the native result.
The website replay and its original session evidence are unchanged.

## Changes

- The editor, Project Explorer and Output/JShell workspace frames use single
  box-drawing rules. Existing scroll panes do not add a nested native border.
- A vertical Swing split pane lets Output grow or shrink by dragging the
  separator above it. Hiding and showing Output and maximizing/restoring the
  editor preserve the selected height where the available space allows it.
- `Resize Output` is available through CommandJ's Command Palette. It focuses
  the vertical divider; Up/Down resize it and Escape returns to the editor.
  No universal F8 shortcut is imposed on the four existing keymaps.
- Editor, console, explorer, menu, footer and embedded dialog text use the same
  configured point size. Larger font / Smaller font adjust that shared size.
  Font weight and semantic colors remain appropriate to each text role.

The three workspace regions retain their existing layout and appearance.
This does not introduce an integrated system shell: Output is the application
console, and Java REPL is JShell.

## Open the local preview

The preview is open for review. To reopen it from this checkout:

```sh
./target/native-visual-preview/run-preview.command
```

The launcher starts the real `TurboJ.main` entry point with
`target/native-visual-preview/turboj-native-preview.jar` and the bundled Java 25
runtime. It uses separate preferences in `target/native-visual-preview/preferences`
and an owned example project at `/private/tmp/turboj-native-layout/review`.
It imports that fixture and executes its short Java program to show real output.
Existing example source and preview preferences are retained when reopening.
This is a local review launcher, not a newly signed distribution installer.

Drag the separator directly above Output up or down. For keyboard resizing,
open the Command Palette, execute `Resize Output`, use Up/Down and press Escape
to return to the editor. Try Larger font / Smaller font, switching themes,
opening JShell and hiding/reopening Output.

[Current running preview](../target/native-visual-preview/preview-open.png)
and [previous layout](../target/native-visual-preview/before-04/before-default-18.png).

## Verified behavior

`WorkspaceLayoutSmoke` passes 148 checks in
`target/native-visual-preview/after-03/report.txt`, using the same cross-platform
Swing look and feel as the real TurboJ bootstrap on macOS. It exercises:

- Exact common font sizes at 16, 20 and 24 pt, plus the maximum 48 pt.
- The three single borders, console input, explorer, menu and footer fonts.
- Divider mouse events and Up/Down/Escape through each of the four keymaps.
- Output hide/show, editor maximize/restore, all three bundled themes and JShell.
- Source, caret and real execution-output preservation.
- Compact windows and hiding/resizing/showing the output panel.
- Minimum JShell input height and an explorer row at the opposite divider limit.
- A real `Scanner` program accepting `Ada` and completing after output is shrunk.

Another two checks cover starting directly at 48 pt in a small window, including
the native title-bar insets that only exist after Swing creates the window peer
(`target/native-visual-preview/startup-48/report.txt`).

`InterfaceZoomSmoke` passes 33 checks for open dialog text and tables, menu
overflow, footer contrast, persisted font settings, unchanged input/caret/output
and long filenames (`app/target/interface-zoom-smoke/report.txt`).

The final Maven package succeeds with **165 app tests, zero failures, errors or
skips**, including the updated common-font contract in `AsciiChromeTest`.
The build log is `target/native-visual-preview/package-verified.log` and records
the selected 35 test classes. Reactor dependencies were built; this is not a
claim of rerunning every dependency's test suite. An initial sandbox invocation
aborted in the macOS AWT VM before tests; its `package.log` is retained separately
from the successful run with desktop access.

Built application: `app/target/turboj-app.jar`, SHA-256
`58fcaefef076e96a86a3edc305a7c0870258e5bfb373dd36bda613b3e6b13679`.

These are actual Swing components, focus and injected mouse/key events with
real program execution. They are not a claim of a manual human walkthrough or
new verification of every physical keyboard layout. Screenshots are frame-only
Swing paints, not a desktop video.

The initial compact-window check exposed a divider left outside the resized
window. The final layout clamps the divider to the actual panel minima and
computes the window minimum from editor, explorer, console/JShell, menu, footer
and native frame dimensions. The failed initial report is retained in
`target/native-visual-preview/after-01/`.

Screenshots for local review:

- [Default 18 pt](../target/native-visual-preview/after-03/after-default-18.png)
- [Output enlarged](../target/native-visual-preview/after-03/output-resized.png)
- [16 pt](../target/native-visual-preview/after-03/zoom-100-16pt.png),
  [20 pt](../target/native-visual-preview/after-03/zoom-125-20pt.png),
  [24 pt](../target/native-visual-preview/after-03/zoom-150-24pt.png)
- [Compact window](../target/native-visual-preview/after-03/compact-640.png)

The 100/125/150 screenshot names use 16 pt as the comparison baseline;
TurboJ's unchanged default size is 18 pt.

Changed production files: `TurboFrame`, `MavenProjectController`, `AsciiChrome`
and `ConsolePane`. Verification changes: `WorkspaceLayoutSmoke`,
`InterfaceZoomSmoke` and `AsciiChromeTest`. Unrelated example edits are preserved.

## Review boundary

The user has authorized committing these native changes for review. The local
preview remains available for hands-on testing. Matching the website replay to
these new native visuals still awaits acceptance of the native result; separately
authorized website publication is tracked in the website validation report.
The generated keyboard reference now includes `view.resizeOutput` and records
the current native source and application hashes. This does not recapture or
change the original hero session. Its native JAR is preserved separately at
`target/hero-story/frozen-turboj-802f496.jar`, SHA-256
`50b2c49c3b8329b4812a5dcef7d327ddfaf0845a4b7bf76e5da24ab319302f0c`.

The original website implementation, evidence and operation timings are documented
in [`website-hero-validation-2026-10-25.md`](website-hero-validation-2026-10-25.md).
The subsequent compact-control update and publication are tracked in
[`website-hero-release-2026-10-25.md`](website-hero-release-2026-10-25.md).
