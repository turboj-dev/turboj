# Template precedence and matching list follow-up

The initial implementation tested Tab expansion with no AI ghost present. The reported defect was real:
CommandJ resolved Tab to `completion.accept` when a ghost was visible, bypassing `indent` and its template path.

The existing acceptance route now checks live templates first. An applicable exact abbreviation expands even
before the asynchronous suggestion list is ready. If no template applies, the original completion handler runs;
`sout` in a comment does not steal AI acceptance. Explicitly requested Java completion alternatives retain their
behavior. No custom bindings or profile defaults were changed.

Typing two or more prefix characters opens a nonmodal, themed list using the same inline popup surface as Java
intelligence. It includes applicable built-in and user definitions, ranks exact matches first and shows selected
template source. Typing filters; Up/Down selects; Tab/Enter expands; Escape dismisses. The editor retains focus.
Panel navigation, other commands and source/caret changes cancel obsolete suggestions. Display/expansion cancels
competing AI/JDT ghost requests. Prefix scanning is bounded and Unicode-aware, matching is debounced, Java parsing
runs on the worker, and results require the current document/version/caret/request before display or application.

## Verified

* `./mvnw -pl app install`: **330 tests passed**, zero failures/errors/skips. [Build log](app-install.log).
* `LiveTemplatePrecedenceSmoke`: actual typed `sout`, visible AI ghost and Tab in all **four profiles**; exact
  template output/caret/Undo. Monokai prefix filtering, multiple matches, arrows, Enter, Escape and focus; AI
  fallback for comments and non-template words. [Log](LiveTemplatePrecedenceSmoke.txt).
* Existing live-template/custom-definition lifecycle, generated/linked templates and JDT completion/documentation
  native suites all passed on the same JAR. [Suite results](results.tsv), [artifact/source hashes](validation.json).
* Registry regenerated: 312 commands, 29 aliases, no unbound callbacks or bundled collisions. Reference assets
  refreshed locally; six reference exporter tests passed. [Log](reference-tests.txt).

Reproduce after building, on an unlocked native desktop:

```sh
KEYBOARD_EFFECTS_OUTPUT=app/target/template-precedence-suite bash scripts/verify-keyboard-effects.sh --only LiveTemplatePrecedenceSmoke LiveTemplateWorkflowSmoke InlineTemplateSmoke KeyboardIntelligenceEffectsSmoke
```

AI content in the new regression is an explicitly injected, deterministic ghost. The keyboard dispatcher,
AI acceptance handler, template parser/expander, popup, edits and undo are real. This verifies arbitration,
not live model quality, cloud inference or physical keyboard/IME/VoiceOver. The first sandboxed Swing fork exited
134; unrestricted execution passed. A missing import in the new test driver was fixed before native execution.
Archived text uses placeholders for home/repository/temporary path prefixes.

Close the running development application and restart `./turboj` to load this build; the launcher intentionally
runs a private JAR copy, so rebuilding does not replace classes inside an already running editor.
No commit, push or deployment was performed.
