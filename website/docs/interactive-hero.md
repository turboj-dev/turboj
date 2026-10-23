# Interactive hero v2

The homepage retains the existing Astro architecture, DOS palette, local fonts,
metadata, native screenshot and independently attributed measurements. The
large static showcase and duplicate introductory/final sales sections are
replaced by a compact heading and the replay. Features and workflow each use
one short description per item.

## Sequence

| Playback position | Illustrated event |
| --- | --- |
| 0 ms | Empty `Untitled.java`, READY, visible cursor |
| 500 ms | `HelloWorld.java *` |
| 800 ms onward | Source prefixes inserted every 25 ms; newline pauses 115 ms |
| 5500 ms | F9 MAKE, COMPILING... |
| 6200 ms | BUILD OK · 0 errors |
| 6700 ms | CTRL+F9 RUN and program heading |
| 7200 ms | Hello TurboJ! |
| 8000 ms | Exit 0, completed; no automatic restart |

These are presentation cues, **not measured compiler/runtime timings**.
`demo-timeline.ts` is the single source of truth for the Java program and events.
The example is not sent to any server or evaluated in the browser.

## Controls and scheduling

The controller separates the timeline phase from user/environmental suspension.
`data-state="paused"` describes a stopped clock; `data-phase` preserves where it
stopped. Only one RAF can be pending. IntersectionObserver uses the application
frame's visible fraction; document visibility independently pauses the clock.
Manual pause is never cancelled by a visibility change. Skip and reduced motion
select the same final state as normal playback. Source rows retain real newline
text nodes, so selecting/copying source preserves the exact example.

## Verification

`npm test` covers static content and timeline boundaries/invariants.
`npm run test:demo` exercises an actual normal-speed playback plus controls,
visibility, layout, reduced motion and the no-JavaScript fallback in Chromium.
The browser report and screenshots are local artifacts. See [QA.md](../QA.md)
for executed results, performance conditions and measured bundle size.
