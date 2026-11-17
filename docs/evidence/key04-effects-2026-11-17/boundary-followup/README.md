# KEY-04 — remaining automated outcome checks

The user requested completion of everything that did not require their intervention.
This follow-up closes the six branch-only command cases and the external-browser
verification gap in the earlier ledger. Physical keyboard/IME/VoiceOver remain
postponed under KEY-05.

## Verified effects

| Area | Actual operation and assertion | Evidence |
| --- | --- | --- |
| Dependency sources/Javadoc | Keyboard cancellation leaves the attachment absent. Enter retrieves real JARs from an isolated file repository into an initially empty attachment cache. Exact bytes and workspace reload are asserted. | [Maven check](maven.txt) |
| Parent POM | Opens the actual external parent from the resolved repository, with exact contents and read-only protection. | [Maven check](maven.txt) |
| Annotation processing | Cancels project trust, then approves it. A real compiled `javax.annotation.processing.Processor` generates a Java class. Native compilation and execution use that generated class and print `115` (42 + 73). | [Maven check](maven.txt), [stdout](maven-console.txt) |
| Local AI settings | The actual `ai.local` command opens the form; keyboard Apply persists preferences and reaches READY on the existing externally owned Ollama runtime. | [AI check](ai.txt) |
| Local model download | Keyboard approval invokes real Ollama `/api/pull`, observing DOWNLOADING_MODEL and then READY. Uses the **already installed** `qwen2.5-coder:0.5b-base` model: this is a registry/cache refresh, not proof of a fresh large model installation. | [AI check](ai.txt) |
| Cloud settings | All three provider selections: reject HTTP, correct to HTTPS, approve nested consent, save/read an endpoint-specific disposable credential through actual macOS Keychain, persist preferences and execute a synthetic completion through the production TLS/authentication/stream parser. | [AI check](ai.txt), [status](ai-status.txt) |
| Cloud rejection/fallback | A fixture HTTP 401 makes the main completion command use actual local inference, explicitly naming the local route. The single-provider settings probe reports the HTTP 401. It does not call the cloud fixture a successful response. | [AI check](ai.txt) |
| Cherry report | Real Cherry/JUnit run produces the report. `cherry.report` opens the default browser, and OS keyboard address-bar capture matches the exact report file URI. A second real run is cancelled. | [Cherry check](cherry-browser.txt), [URL](browser-location.txt), [report hash](report-artifact.txt) |
| Nested keymap branches | Remove one binding; reset command; approve profile reset; import valid JSON without duplicate profiles; approve both overwrite confirmations; inspect/export reference and restore source focus. | [16 assertions](nested-report.txt) |

Cloud responses are **deliberate deterministic HTTPS fixtures**, not recordings or
claims of OpenAI, Anthropic or xAI vendor inference. They use a one-day self-signed
certificate trusted only in the test JVM. They assert the real application callbacks,
Keychain persistence, consent, provider protocol and error handling without requiring
paid credentials or sending user source elsewhere. Actual local model inference was
also exercised, including the real cloud-to-local fallback.

The disposable Keychain entries were deleted and their absence checked. The owned
HTTPS server stopped; the pre-existing Ollama server was left running. Only synthetic
fixture source was sent. No OS trust settings, real provider credentials or personal
projects were changed. No new model was installed.

The browser check passed through the actual address bar. The C accessibility helper
is a bounded fallback for the focused browser window only; it was **not needed in
this passing run**, so its runtime branch is not claimed as separately verified.
The probe requested closing the tab only after matching its URL; tab closure itself
is not an acceptance assertion.

## Results and limits

- [Results](results.tsv): all five native checks passed, including the prior dialog
  branch regression harness. Native GUI runs are sequential.
- `./mvnw install -pl app`: **321 tests**, zero failures, errors or skips;
  [BUILD SUCCESS log](app-install.txt). Test sources compile in the normal Maven build.
- The application JAR SHA-256 remains
  `34be54b06a7409bd5baea25fa2bb4ba54817f09abce83028efb42b32058e4e7b`, identical to the
  earlier 22-suite native pass. No production code changed in this follow-up.
- The regenerated [309-command ledger](../command-outcomes.md) now has **309 witnessed
  outcomes, zero branch-only commands and zero unverified commands**. Prior guard and
  cancellation evidence remains alongside the positive cases.
- Successful settings application is distinct from live paid-vendor inference;
  cached pull is distinct from first-time model download. The file-repository
  attachment test does not claim public repository/network availability.
- Each assertion certifies its specified case. This is completion of the registered
  command and identified nested-branch audit, not proof of every possible Java input,
  remote infrastructure condition or combination of dialogs.
- KEY-05's physical keyboards, Fn/Globe, IME candidate UI and spoken VoiceOver checks
  remain postponed. No user intervention is needed for the completed automated scope.

## Reproduction

Build first. Run native checks sequentially on an unlocked Mac:

```sh
./mvnw install -pl app -am
bash scripts/verify-keyboard-effects.sh --only KeyboardMavenBoundarySmoke KeyboardNestedDialogSmoke KeyboardBranchEffectsSmoke
```

The explicit AI check requires the existing local Ollama endpoint
`http://127.0.0.1:11434` with `qwen2.5-coder:0.5b-base` already installed. It uses
native macOS Keychain for disposable fixture accounts and performs a real cached
model pull; it is deliberately **excluded from the default runner**:

```sh
bash scripts/verify-keyboard-effects.sh --only KeyboardAIBoundarySmoke
```

The Cherry browser run uses the main report's supplemental reproduction, appending
`--browser` to `KeyboardCherryEffectsSmoke <actual-Cherry-CLI-JAR>`. The optional
read-only focused-browser fallback can be built with:

```sh
clang app/src/test/native/browser-report-probe.c -framework ApplicationServices -o app/target/browser-report-probe
```

Regenerate the ledger from frozen assertions:

```sh
python3 scripts/generate-keyboard-outcome-ledger.py
```

[Source hashes](sources.sha256) identify the harnesses. Initial test-driver failures
are retained under [controls](controls): wrong error wording, transient null focus,
and an incorrect expectation that the main AI command would not use its documented
local fallback. These were test assumptions, not silently fixed product failures.
An initial Maven assertion incorrectly looked for a textual compile banner; the
final test asserts actual compiled/generated bytecode and runtime output instead.
No commit, push, deployment or changes to shortcut defaults/PDF layout were made.
