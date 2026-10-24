# Capture controls, 24 October 2026

The selected complete session was nominated as `session-2026-10-24` before it
ran. No comparison of its timings was used to choose it. Private full evidence
for the controls is retained under `target/hero-session/`.

| Run | Scope | Outcome |
| --- | --- | --- |
| control-01 | A–D, no presentation pauses | Hello World, tests, both refactorings and preserved tests passed. Capture assertion incorrectly required completely empty output after an unsaved edit; TurboJ legitimately ran live compilation and displayed `BUILD OK`. Capture stopped. |
| control-02 | A–D, no presentation pauses | All actual execution, refactoring and save automation checks passed after distinguishing live compilation from program/test execution. |
| control-03 | A–D, no presentation pauses | All checks passed with generic private workspace paths and additional provenance metadata. |
| control-04 | A–D, presentation pauses | The new explicit Compile step correctly returned `CACHE HIT`; capture initially waited only for `BUILD OK`. This control was stopped and the capture predicate corrected. No IDE source was changed. |
| control-05 | A–D, presentation pauses | Full A–D workflow passed with actual reading pauses outside measurement intervals; explicit compilation correctly identified a cache hit. |
| control-06 | A–E, presentation pauses | Complete workflow passed: 61 states, 18 operation intervals, approximately 68 seconds including actual reading pauses. Maven import, real Spring HTTP readiness, external curl and Stop Maven succeeded. |

Separate Spring feasibility controls proved native compilation works but both
Fast Run and Isolated Run retire their worker after Spring's main method returns.
The complete captured workflow therefore uses TurboJ's existing explicit
`spring-boot:run` Maven goal for this persistent server. Dependencies and the
verified Maven wrapper distribution were cached by these controls.

Presentation pauses naturally give the resident live compiler time to finish.
For that reason the fast controls' timing values are not interchangeable with
the selected presentation session. Only the selected session's measurements
belong in its replay.
