# Verified hero replay contract

This contract is for the new five-chapter hero. Historical session assets and
`prepare-session.mjs` remain unchanged. The public data is generated only from a
complete, newly executed capture; this document is not evidence of a successful run.

`/demo/hero-story/manifest.json` contains `schemaVersion: 1`, `id`, `verified`,
`keymap`, `menus`, `detailsUrl: "/demo-session/"`, and exactly five `chapters` with
`id`, `title`, `url`, `durationMs` and `sha256`. Additional provenance fields are
for inspection, not playback. Chapters are loaded on demand.

Each chapter JSON contains:

- `id`, `title`, `durationMs`.
- `sources`: immutable SHA-keyed `{file, text, sha256}` objects.
- `outputs`: immutable SHA-keyed strings. Full text is preserved; no invented logs.
- `dialogs`: SHA-keyed arrays of actual native dialogs, each with `title` and
  `components` (`type`, exact `text`, optional `enabled`/`selected`). Preview
  before/after text and warnings remain verbatim; the replay is read-only.
- `events`: ordered complete render checkpoints with `id`, `atMs`, `kind`, `label`,
  `sourceId`, `outputId`, `filename`, `cursorOffset`, `selectionStart`,
  `selectionEnd`, `topLine`, `status`, `external`, optional `command`,
  `operationId`, `dialogLabel`, `dialogId`, plus a direct original `screenshot`
  URL and provenance IDs. Each chapter starts at 0.
  `topLine` is one-based (the native zero-based value is converted once). Real
  line-entry checkpoints use `kind: "typing"` so they are not announced as results.
  Optional `explorer` rows and `menuItems` are extracted from actual native Swing
  controls; absent captures never produce an invented project tree or menu.
- `operations`: `{id, label, startMs, endMs, durationMs, boundary}` with optional
  recorded internal-duration fields. Start/end are chapter-relative playback times.
- `checkpoints`: `{id, label, atMs}` for static reduced-motion navigation.

An external event uses the actual external-process transcript as its `outputId`.
Its Java source checkpoint remains available in the data. The renderer identifies
external curl separately from TurboJ output. Source is complete captured text;
no inferred typing timestamps or synthetic success checkpoints are inserted.

The raw capture has `metadata`, `events`, `measurements` and
`editorialIntervals`. Explicit intervals have `id`, `kind` (`typing`, `reading` or
`navigation`), `startNs`, `endNs` and a reason. Only those classified intervals may
be shortened, and never where they overlap a measured system operation. Unknown
gaps are preserved at 1×. The piecewise mapping retains every measurement boundary
and records every cut. Native operation durations come from the monotonic trace;
HTML animation, image decode and editorial holds never supply measurements.

The normalizer rejects incomplete/misordered chapters, inconsistent durations,
invalid source or screenshot provenance, unsafe paths and private home paths.
`verified` is set only after all required native outcomes and exported evidence
have been validated. Sources, logs, raw trace, checksums, full operation boundaries,
editorial cuts and original frame captures are accessible from `/demo-session/`.

Editorial caps are 70 ms per explicitly recorded typing interval, 650 ms per
reading interval and 200 ms per navigation interval. Shorter intervals retain
their original duration. The exported cut map records the original start/end,
classification, reason and retained duration. All unknown intervals remain at 1×.
`dependencyCache: "warm"` is emitted only when native metadata establishes that
the wrapper and dependencies were downloaded before the representative session.

The details index is `/demo/hero-story/evidence.json`. The raw trace, source
revisions, final project files, effective POM, test reports and native screenshots
are archived under `evidence/<session-id>/`. Independent controls are archived in
their own directories and never supply hero operation times. `checksums.json`
hashes every exported evidence file except itself. Screenshots are linked on
demand, never loaded by the hero or details page automatically.

Project downloads use byte-identical `project-downloads/<sha256>.txt` aliases so
static hosts also serve extensionless wrapper/license files and hidden `.mvn`
configuration. The original names and archive URLs remain in the evidence index;
download links retain the original file name. Aliases have the same content hash.
