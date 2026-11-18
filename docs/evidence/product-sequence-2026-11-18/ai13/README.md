# AI-13: automatic-completion interaction controls

Baseline dd75866 plus the sources identified in source-hashes.json. The focused controller was compiled
in an isolated Maven test project against the baseline application dependencies while the combined reactor
was being modified. 20 controller tests passed. AIInteractionSmoke then used real focused Swing key events
with the same controller compiled separately; six interaction assertions passed. These are deterministic
provider controls, not paid-cloud/model-quality or physical-keyboard certification. The intermediate app
build failure is retained: another in-progress frame edit caused a syntax error, subsequently repaired.
Final combined-artifact qualification is recorded separately when executed.

Automatic scheduling now requires a small insertion at the previous caret, a nonblank line prefix, editor
focus and no selection/composition/template/semantic choice. File replacement, deletion and bulk paste
are excluded. Automatic requests are at least 1.2 seconds apart, with configured debounce respected up
to 2 seconds. Escape/focus dismissal suppresses automatic proposals on the same line until both two
seconds and three subsequent insertion edits have passed; it does not schedule a retry by itself.
Moving to another line or explicit Request AI Completion remains available. Typed-through ghosts are
retained. Automatic partial streams stay hidden until final Java validation, reducing flicker and invalid
transient proposals; explicit requests retain streaming. Rejected final output also clears ghost state.

No telemetry was added. These scheduling observations do not establish subjective preference or vendor
latency. Single-character programmatic insertions are indistinguishable from typing in this heuristic;
full edit-origin tracking is not claimed. Existing user delay/provider settings were not overwritten.
