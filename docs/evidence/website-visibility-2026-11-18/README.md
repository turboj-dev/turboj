# Website visibility regression — 18 November 2026

Follow-up to [website alignment](../website-alignment-2026-11-18/README.md).
This record supersedes that record's renderer hashes and measurements after a
real failure in hosted run [44369480625](https://github.com/turboj-dev/turboj/actions/runs/44369480625/job/135035258282).
The source correction and six regression controls were committed in
`ee98ac8381f52d466dd917bb8028e1fac94734ae`; `source-identity.json` verifies the
three changed website source files against that commit.
Native JAR `36ae34e651d3a60a47ba988a85ab91dae61d6b0445fd556137293f0504ea33a9`,
the captured sessions, source/output/timing data, generated references and PDFs
are unchanged. No deployment occurred.

## Failure and cause

The hosted 320 px keyboard Play control timed out after loading the next chapter.
The 390 px control passed. This was a real playback defect, not a reason to retry
the job or extend its timeout. The original job log and downloaded artifact are
retained in `hosted-failure.txt` and `hosted-artifacts/`.

A local reproduction using genuine browser observations failed on its fifth
iteration. The stage was fully visible, `manualPaused=false`, but elapsed time
stopped at 16.2 ms. The browser delivered one callback containing two ordered
IntersectionObserver entries: ratio 0 at 1643.5 ms and ratio 1 at 1660.3 ms.
The renderer consumed only the first entry and incorrectly retained the hidden
state. No later threshold crossing occurred to repair it. `natural-before.jsonl`
preserves that observation; `natural-reproduction.mjs` is the executed diagnostic.

The renderer now consumes the latest entry for its observed stage before
synchronizing the playback clock. Ordinary offscreen suspension and explicit
user pause remain distinct.

## Regression control

`website/tests/demo-visibility-browser.mjs` temporarily holds real browser-created
entries through scroll-away/reveal rendering frames and delivers the original
ordered objects together, as a browser is permitted to do. It fabricates neither
intersection geometry nor source/output/timing data. At both 320 and 390 px it
checks the latest visible state, the latest hidden state, and explicit pause
followed by keyboard resume.

All six cases failed against the unchanged prior production output
(`before-1.txt`, `before-report.json`). All six passed after the renderer fix.
The new control runs in the normal hosted `test:hero:browser` suite.

## Validation

Local environment: macOS arm64, Node 26.3.0, Astro 7.4.2, Playwright 1.64.1,
dedicated Chromium headless shell 156.0.8078.4. Existing production preview:
`http://127.0.0.1:4322`. No visible browser or native application was opened.

- `npm run check`: PASS, zero errors/warnings, one existing helper hint.
- Normal guarded `npm run build`: PASS; no native-manifest bypass.
- `npm test`: 53 PASS.
- Full browser suite: **375 PASS** (previous 369 plus six visibility controls),
  zero failures. The original 320/390 px Play controls pass. All 103 themes,
  keyboard controls, reduced motion, no-JS, responsive scenes, axe checks and
  source/evidence links were exercised again. Nine result files are preserved in
  `browser-reports/`; `browser-1.txt` and `browser-2.txt` retain the full output.
- Three fresh sequential Lighthouse 13.5.0 mobile runs: **100 Performance /
  100 Accessibility / 100 Best Practices / 100 SEO** each. LCP:
  **1,654.095 / 1,657.857 / 1,655.499 ms**; TBT **0 ms**; CLS **0.00049748**.
  Other local builds were paused during this measurement window. `lighthouse.json`
  retains settings/environment/results; compressed complete reports are in
  `lighthouse/`. These are localhost navigation runs with simulated throttling,
  not evidence of hosting latency, real-user INP or a performance improvement.

The new production payload is **6,727 gzip bytes** for the hero controller/model
including its inline marker, and **14,874** for all emitted/inline JavaScript.
Both remain below the 8/18 KiB budgets; the correction adds nine gzip bytes.
Lazy chapter data remains 21,080 gzip bytes. `measured-artifacts.json` identifies
the new built HTML/CSS/JavaScript bytes. Previous byte-identical-renderer claims
apply only to the preceding alignment build, not this correction.
`validation.json` aggregates the completed checks. The four PDF hashes and three
native-generated downloadable references are rechecked in `unchanged-pdfs.json`
and `unchanged-references.json`.

Run from `website/` using a supported Node version:

```sh
npm run check
npm run build
npm test
QA_BASE_URL=http://127.0.0.1:4322 npm run test:browser
QA_BASE_URL=http://127.0.0.1:4322 npm run test:hero:browser
QA_BASE_URL=http://127.0.0.1:4322 LH_RUNS=3 npm run test:performance
```

Set `CHROME_PATH` to an installed dedicated headless browser if necessary.
These local checks do not assert hosted CI success, production hosting
performance, field interaction latency or complete accessibility conformance.
