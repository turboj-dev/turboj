# Native AI completion — 1.0.5 verification

Verified on 24 October 2026, Apple M5 / 16 GB / macOS 26.5.1 / Corretto
25.0.4.1. This is an unsigned development preview, not a notarized public release.

## Implemented behavior

One completion coordinator gives selected Java proposals and semantic completion
priority over optional AI. AI uses the existing Java2D editor as a single-line or
multiline overlay. It never changes source before acceptance. Tab inserts one
undoable edit; Escape cancels; Ctrl+Alt+Enter accepts the next word. Alt+Space
requests generation; Alt+Shift+Space bypasses the cache for another request.

The worker has one active request and one pending slot. Buffer identity, version,
caret, settings and coordinator generation guard every result. Atomic token
replacement prevents an older request from losing the next request's cancellation
handle. Disabled mode does not instantiate an HTTP provider or collect context.

Local Ollama and llama.cpp FIM adapters and an opt-in OpenAI Responses streaming
adapter share bounded transport and insertion validation. Context uses only the
current Java buffer; exclusions, original/canonical source paths and Git ignore
rules are checked off the EDT. Basic secret detection is additional protection,
not a guarantee. Cloud credentials use endpoint-bound macOS Keychain entries.
No key is stored in JSON, subprocess arguments, the application bundle or the site.

Options → AI Completion configures mode, endpoints/models, automatic suggestions,
limits, exclusions and consent to local diagnostics. Cloud sharing and automatic
cloud requests each require explicit opt-in. The three primary themes and the
existing shared theme catalog supply AI ghost colors. No AI sidebar was added.

## Executed checks

`./mvnw -q -Djava.awt.headless=true package` passed **171 tests**:
71 core, 80 app, 15 worker and 5 CLI; zero failures, errors or skipped tests.
Tests cover real loopback HTTP/NDJSON/SSE transport, malformed/truncated events,
timeouts, cancellation, cloud consent, source exclusions, symlink aliases,
stale delivery, semantic priority, multiline insertion and one-step undo.

The real-window `AICompletionSmoke` passed with a clearly identified protocol
fixture: disabled startup, all three themes, Tab/Undo/Redo, explicit compile/run
with output `9`, semantic precedence, stale cancellation and unavailable-provider
fallback. No fixture response is presented as a model-quality measurement.

`AISettingsSmoke` passed real-window settings interactions, source-free connection
testing, Local/Cloud apply, consent changes and Cancel. Its background-provider
fixture had a 300 ms delay; one EDT round trip during it took 3.45 ms. This is not
input-to-display latency. A real disposable Keychain entry passed create, read,
update, endpoint isolation and delete using both development and bundled runtimes.

After the user explicitly authorized the model/runtime download, the same Java
provider ran Qwen2.5-Coder 1.5B Base through Ollama 0.40.10 on loopback with cloud
disabled. **12/12** generated candidates compiled and produced expected results
for square, array sum, prime checks and insertion between existing statements.
Every outcome was retained. See [the full local model report](ai-local-validation.md)
for hashes, licensing, commands, sample distributions and memory observations.

`AILocalSmoke` then used the **packaged application's own runtime and JAR** with
that actual model. It verified ghost text without source mutation, all three
themes, Tab, exact prefix/suffix preservation, one Undo and explicit compile/run
returning `9`. This one GUI request recorded 283.57 ms to the first validated
candidate and 295.30 ms total generation, including context preparation and
transport. These are individual observations, not guaranteed latencies.

## Editing measurements

The final automated blocked-provider fixture measured 100 edit-and-offscreen-paint
samples per size:

| Source size | p50 | p95 | maximum |
| --- | ---: | ---: | ---: |
| 1,000 lines | 0.19 ms | 0.55 ms | 1.68 ms |
| 10,000 lines | 0.12 ms | 0.14 ms | 1.61 ms |

This isolates the editing path while inference is stalled. It excludes the OS
event queue, compositor and screen scanout; it does not establish the product's
input-to-visible-character targets. An earlier targeted run had a 26.94 ms maximum
on 1,000 lines; it is not discarded from the record. All timings vary with JIT,
GC and other work on the machine.

A separate [real-model contention probe](ai-local-editing-latency.md) retained
200 samples per size while Ollama generated code. Edit/paint p95 was 0.407 ms
for 1,000 lines and 0.366 ms for 10,000; maximum 2.658 ms. The document also
retains the initial longer-generation pilot. These are offscreen synthetic
workloads, not OS input-to-screen measurements.

## Website verification

The site adds one compact AI capability article and a documentation link. It
identifies AI as optional/off by default, local Ollama as verified, and live
cloud validation as pending. The HelloWorld replay is unchanged.

Astro type checking and the production build passed, followed by 27 web tests.
The existing Playwright suite passed all 40 checks across 320, 390, 768, 1280,
1440 and 1920 px widths: responsive layout, keyboard navigation, anchor links,
FAQ controls and original screenshot access. Axe WCAG 2.2 AA scans at 390 and
1440 px found zero automated violations. No console errors, failed asset requests
or third-party browser requests were observed. Automated checks are not a complete
accessibility audit. Mobile and desktop screenshots are in ignored web QA artifacts.

## Artifact and reproduction

The existing script created:

- `target/release-ai-1.0.5/TurboJ.app`
- `target/release-ai-1.0.5/TurboJ-1.0.5.dmg` (about 63 MiB)
- DMG SHA-256: `35223bc342598a2acb8ede369317a7ccc93e5f53222671f8021ae5712bdbda07`

The app includes its Java compiler/runtime. The model and inference runtime are
separate ignored local caches and are not included in the DMG or Git repository.
No global Ollama application or launch service was installed.

```sh
./mvnw -q -Djava.awt.headless=true package
java --enable-native-access=ALL-UNNAMED \
  -cp app/target/test-classes:app/target/turboj-app.jar \
  com.turboj.app.AICompletionSmoke
TURBOJ_SKIP_BUILD=1 ./scripts/package-macos.sh dmg target/new-ai-build
# With an explicitly started local FIM server:
java --enable-native-access=ALL-UNNAMED \
  -cp app/target/test-classes:app/target/turboj-app.jar \
  com.turboj.app.AILocalSmoke http://127.0.0.1:11439 qwen2.5-coder:1.5b-base
```

## Boundaries

Live inference was verified for the stated Ollama model only. llama.cpp and the
OpenAI adapter passed protocol fixtures; no live llama.cpp model or paid OpenAI
credential was used. The cloud adapter must still be checked against a user's
chosen account/model before claiming live cloud validation. Other local models
can have different FIM templates, quality and resource requirements.

Generated source is not guaranteed correct, secure or semantically valid.
Project-wide context gathering, automatic imports and AI refactoring are not
implemented. No source is executed automatically. Signing/notarization and Intel
macOS validation remain outstanding. See [setup and controls](ai-completion.md).
