# Hosted CI — 18 November 2026

## Latest verification

Run https://github.com/turboj-dev/turboj/actions/runs/44378326946 at
`48815c83e6f5b5c81c7e7aa99e8c413b3cfbe3cc` completed **SUCCESS** on all three
jobs. `final-summary.json` contains the compact authoritative result and hashes.

| Job | Current outcome | Evidence |
| --- | --- | --- |
| Ubuntu 24.04 x64 | PASS: 976 entries/141 suites; 966 executed passes, 10 documented skips, zero failures/errors | `accepted-ubuntu-results.json`, `accepted-ubuntu-reports.zip`, `accepted-ubuntu/` |
| Website | PASS: 53 unit tests and 375 browser checks, including six visibility regressions | `website-135063378018.log` |
| macOS 15 Intel | PASS: 976 entries/141 suites; 975 executed passes, one documented skip, zero failures/errors; app and DMG built | `accepted-macos-intel-results.json`, `accepted-macos-intel-reports.zip`, `accepted-macos-intel/` |

Both Java platforms executed the actual Corretto 17 qualifications. The shared
single skip is the older worker fixture requiring a hardcoded local Corretto 21
installation. Linux additionally skips nine macOS-specific metadata/recovery
controls. No skipped test is counted as an executed pass. Linux's real Swing
Quick Fix and JUnit output controls passed under Xvfb.

The Intel package receipt verifies 123 regular files and 99 symlinks, identifies
its bundled x64 Temurin runtime, and binds its JAR to its build receipt. The DMG
was created successfully. Bounded artifact discovery then uploaded and finalized
the 528,256-byte Mac evidence archive in seconds. No Developer ID signing,
notarization or production deployment is claimed.

Final hosted JavaScript payloads are 6,713 bytes gzip for the hero and 14,872
bytes for all initial JavaScript, within their 8/18 KiB budgets. All 103 theme
controls and the automated accessibility checks are included in the browser
count. Physical keyboards, IME and VoiceOver are separate qualifications.

The latest commit changes only the platform-aware local-model test expectation.
The hosted Intel failure that exposed it, the local correction checks, and all
earlier controls are preserved below. No production Java code changed during
these CI corrections. The latest artifacts are independently rebuilt at the
listed commit; they are not the locally qualified installed application.

## Historical controls

Initial run: https://github.com/turboj-dev/turboj/actions/runs/44361417640

Source: `c7d08485a30a02ddc321c360ff02a3ab38872ccb`.

The workflow runs independently on macOS 15 and Ubuntu 24.04, with a separate
website job. Actual Corretto 17 is installed before the Java 25 build runtime.
Website checks use the downloaded Playwright browser through its explicit path.
Linux native effect controls use Xvfb and injected Swing events; they do not
certify physical keyboard or macOS accessibility behavior.

The initial run completed with the **website job passing** and both Java jobs
failing at the same isolated settings fixture described below. The website's
actual hosted install, Astro checks, production build, unit tests, pinned
Playwright browser and browser/keyboard/accessibility scripts all passed.
Final status and raw logs are adjacent. Failed controls remain preserved rather
than replaced by a successful rerun.

## Initial hosted failure

Both Java jobs failed in
`MainTest.actualCommandsInspectSharedModelWithoutLaunchingProjectWrapper`, before
the app module. The fixture implicitly read setup-java's generated user Maven
settings. Its unused publishing credential placeholders produced the actual
`settings.credentials` diagnostic, so the CLI correctly returned an unresolved
model and exit 3 instead of the fixture's expected success.

The corrective test supplies explicit empty user/global settings files. The
workflow also directs setup-java's generated publishing configuration into its
own runner-temporary directory, so installing a JDK does not alter the Maven
import settings under test. No credential validation or production Java behavior
was weakened. The original macOS/Linux job logs remain byte-for-byte archives.
These corrections were verified by subsequent runs; they are not recorded as
passing execution in this initial failed control.

Correction commit: `ede0821ed2613147250aa0d3d8b91784af36172b`.
Follow-up run: https://github.com/turboj-dev/turboj/actions/runs/44363620856.
Only workflow/test isolation changed; the qualified production JAR inputs remain
those of `c7d08485`. Follow-up outcomes are recorded separately.

## Corrected run: completed Linux and website controls

The Ubuntu job passed the complete reactor: **976 test entries across 141
suites; 966 executed passes, 10 skips, zero failures or errors**. Nine skips are
explicit macOS metadata/recovery controls. The remaining skip is the older
`WorkerClientTest.selectedProjectJdkRunsResidentWorkerWithoutUsingEditorRuntime`
fixture, which expects a particular local Corretto 21 installation. The new
actual Corretto 17 qualifications both ran and passed; neither was skipped.
The isolated Maven CLI settings test also passed. The standalone ecosystem
entry points, real Quick Fix effects and real JUnit output checks passed under
Xvfb. Its accessibility-bus warning is retained in the raw log; Xvfb does not
verify a physical keyboard or a screen reader.

The corrected website job passed independently: **53 unit tests and 369 browser
checks**, including all 103 themes and automated accessibility checks. Its raw
log is `website-135011359663.log`. The macOS build, reactor and packaging steps
passed, but their final evidence upload remained active for over ten minutes.
That historical run was cancelled and is not recorded as a complete pass.

## Artifact discovery correction

The package script intentionally leaves the owned DMG staging directory with an
`Applications` directory symlink. The recursive report glob in the upload action
can follow that link into the runner's applications directory. A controlled
fixture using the action's exact `@actions/glob` 0.5.0 dependency reproduces this:
the original recursive pattern includes an XML file outside the checkout, while
bounded one-level/two-level reactor report patterns include only the two owned
reports. See `artifact-glob-control.cjs` and `artifact-glob-control.log`.

The workflow now bounds report discovery to those module depths and limits its
upload step to five minutes. Packaging code and qualified product inputs remain
unchanged. `run-44363620856-before-upload-fix.json` retains the successful build
and package stages with the outstanding artifact step. This diagnosis establishes
the traversal defect. The final run verified successful complete macOS packaging
and bounded artifact upload as well as the Linux upload.

The stalled run ultimately concluded **CANCELLED**. Its pre-cancellation step
states remain in the snapshots; the job-log endpoint returned HTTP 404 after
cancellation, so no unavailable raw macOS log has been fabricated. The later
force-cancel request returned HTTP 409 because the run had already completed and
did not change its state.

## Bounded collector run

Run https://github.com/turboj-dev/turboj/actions/runs/44369480625 at
`98002efc291df11ec1a2ccfe2eb843bd7976536c` completed with the Linux job passing
again: 976 entries/141 suites, 966 executed passes and the same 10 documented
skips. Its independently built JAR/receipt and XML reports are preserved under
`final-ubuntu/`, `final-ubuntu-results.json` and `final-ubuntu-reports.zip`.

The macOS job never acquired a runner and executed no steps. GitHub's exact
annotation is **“The job was not acquired by Runner of type hosted even after
multiple attempts”**, accompanied by macOS arm64 capacity notices. These are
archived in `macos-135035257521-annotations.json`. It is not a test failure or a
successful macOS verification. The website job failed and its separate
reproduction/investigation is retained; the run as a whole is not green.

The next workflow uses **`macos-15-intel` (x64)**, verified against the official
`actions/runner-images` supported-image table (`runner-images-README.md` and its
source/hash record). The table distinguishes that label from ARM `macos-15`.
This avoids repeating an unavailable hosted pool and adds an independent Intel
Mac build; it does not establish a hosted ARM pass. The installed ARM application
continues to have its separately recorded local qualification. Java sources and
package logic are unchanged by this infrastructure selection.

The website failure was traced to a real visibility race: an
`IntersectionObserver` callback contained an offscreen observation followed by
the newer onscreen observation, while the controller consumed the first entry.
The correction consumes the latest observation. Six deterministic regressions
fail against the previous implementation and pass after the correction; the
separate evidence is in `../../website-visibility-2026-11-18/`.

The combined correction is commit
`ee98ac8381f52d466dd917bb8028e1fac94734ae`, run
https://github.com/turboj-dev/turboj/actions/runs/44375265812. Linux and website
passed. Intel macOS reached the TurboAI tests and failed only
`LocalModelManagerTest.aHealthyRunningServerIsReusedAndNeverStopped`: the
assertion expected the version alone, while production correctly retained its
existing ` (Intel Macs are not verified)` warning. The full failed log is
`macos-135052633028-failed.log`; the terminal run record is
`run-44375265812-api.json`. Downstream app and package stages did not execute.

Commit `48815c8` corrects that exact expected string according to the host
platform, preserving the warning and the separate explicit Mac Intel/Mac ARM/
Linux policy assertions. The isolated ten-test suite passed on the native ARM
host (`local-model-native-control.log`). It also passed with an explicit
`-Dos.arch=x86_64` property (`local-model-simulated-arch-control.log`); the latter
checks the label branch only and is not an Intel runtime qualification.

## Local and hosted counts

The original local broad XML collector reported 978 entries/142 suites because
it also found the preexisting example report
`examples/maven/target/surefire-reports/TEST-dev.turboj.examples.calculator.CalculatorTest.xml`.
That example is outside the 39-module root reactor and its existing report does
not establish a current execution. The corrected local reactor total is
**976 entries/141 suites, all passing with no skips**. The derived
`../final-reactor/reactor-results.json` records the exact exclusion and source
hash; the raw `tests.json` remains unchanged. This is a collector-scope
correction, not a missing tracked CI test.

Hosted artifact receipts identify their actual build commits: `ubuntu/` is
`ede0821`; `final-ubuntu/` is `98002ef`; `hosted-ubuntu/` is `ee98ac8`;
`accepted-ubuntu/` and `accepted-macos-intel/` are `48815c8`. Their hashes must be read as independent build
identities, separate from the locally installed and qualified JAR, even though
the production Java source inputs are unchanged. Both final hosted receipts
match all 743 production input hashes of the locally qualified ARM artifact.
