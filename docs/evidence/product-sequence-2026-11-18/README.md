# Product sequence — 18 November 2026

This evidence set qualifies the nine requested product groups against baseline
`dd75866`. Production Java sources were frozen for the clean reactor. The final
JAR SHA-256 is `36ae34e651d3a60a47ba988a85ab91dae61d6b0445fd556137293f0504ea33a9`.
The later packaging-script correction reassembled **identical JAR bytes**; it did
not substitute an untested Java binary.

## Implemented behavior and checks

| Group | Delivery | Evidence |
| --- | --- | --- |
| AI-13 | Relevant insertion triggers, bounded cadence, sustained dismissal, validated automatic streams; template/JDT precedence | [AI](ai13/README.md), native AIInteractionSmoke |
| QFX-02/06 | Deterministic diagnostic/caret priority and pending repeated invocation; checked exceptions, constructors and applicable transformations | [Semantic controls](../quick-fix-precision-2026-11-18/README.md), native QuickFixPopupSmoke |
| QFX-10 | JDT macro/context inference, safe imports/shortening, formatted expansion, deferred Save and coherent Undo | [Templates](../template-semantics-2026-11-18/README.md), three live-template and inline-refactoring suites |
| UI-07/08, REF-01 | All-buffer crash checkpoints, concurrent-write checks, native metadata preservation and safe refusal when destructive metadata restoration is unsupported | [Recovery](../recovery-2026-11-18/README.md), [metadata](native-metadata/README.md) |
| CH-03/04/05/07 | Independent collections, exact acknowledged selectors, genuine Cherry JUnit progress and external-repository/runtime identities | [Real executions](../cherry-native/feedback-2026-11-18/README.md), native CherryNativeSmoke |
| REL/GOV | Source/JAR and sealed-app receipts, reproducible local DMG, pinned CI workflow | [Installed package](package/installation.json), [packaging regression](package-receipt-correction/README.md) |
| MVN/FMT | Actual Java 17 compiler/run/test toolchain, modules and explicit codegen/lifecycle; bundled-runtime formatter | [Toolchain](../maven-toolchain-2026-11-18/README.md), native FormatterSmoke |
| PERF/CH-16 | Alternating matched-artifact editor, Git and semantic probes; separate large-report import comparison | [Measurements and limits](performance/README.md) |
| WEB | Native-aligned panes, 103 themes, guarded generated references and four-page PDFs | [Website](../website-alignment-2026-11-18/README.md) |

## Final Java and native verification

`./mvnw --batch-mode --no-transfer-progress -Dturboj.test.java17=/private/tmp/turboj-jdk17/amazon-corretto-17.jdk/Contents/Home clean install`
passed **978 test entries / 142 suites, zero failures, errors or skips**.
[Raw reactor](final-reactor/clean-install.txt), [per-suite counts/hashes](final-reactor/tests.json).

The installed DMG's bundled Java 25 runtime and JAR executed these real-window
checks: AIInteractionSmoke, QuickFixPopupSmoke, LiveTemplateWorkflowSmoke,
LiveTemplatePrecedenceSmoke, LiveTemplateDialogSmoke, InlineSemanticTemplateSmoke,
RecoverySmoke, FormatterSmoke, CherryNativeSmoke, KeyboardProfileSmoke,
SaveAutomationSmoke, RunTargetIsolationSmoke and TestOutputSmoke. All have a
passing final result. [Initial batch](native-final/results.tsv),
[bounded follow-up](native-final-followup/results.tsv),
[actual JUnit output](native-final-followup/TestOutputSmoke.txt).

Earlier unsuccessful controls remain intact. The final batch's Cherry driver
lost host foreground after a worker exited; its follow-up waits for the owned
window without assigning component focus and explicitly asserts source focus.
SaveAutomation's first isolated run lacked frozen resource files on its classpath;
its corrected driver includes and hashes them. macOS Bash 3.2 also rejected an
empty array under `set -u`; the driver now executes a nonempty invocation array.
No production changes were made to hide these failures. Earlier QuickFix
cancellation controls are retained; both profiles passed in the final package.
Raw log whitespace is intentionally preserved; source-only `git diff --check`
passes.

## Package identity and remaining release gates

The corrected DMG was checksum-verified, mounted read-only, copied with `ditto`
to an isolated installation, then passed schema-2 inventory validation and
`codesign --verify --deep --strict`. It contains 123 files. Its receipt is outside
the signed bundle to avoid a self-referential resource seal. The original failed
DMG control proved that a second jpackage signing pass changes the launcher.
No installed user application was replaced. This is a **local ad-hoc signed**
package, not a Developer ID/notarized release; bundle label `1.0.10` is the existing
development label, not source identity.

Finder startup/idle measurements, CommandJ JMH and hosted CI are being completed
separately. They must be recorded before those gates are closed in TODO.md.
Physical keyboard/IME/VoiceOver and paid-cloud inference remain separate postponed
qualifications. Coverage remains aggregate unless explicit attribution exists;
missing instrumentation never becomes uncovered code. Destructive refactorings
that cannot restore ACL/xattrs fail safely instead of losing metadata.

## Companion and production boundaries

Cherry CLI SHA-256:
`01004385b5163b3ef9aad1b55f01b482956640aeee79e8fe7f29e6c72a937f25`.
Its modified reactor has **423 executed passes and two opt-in Docker skips**.
The [source patch](../cherry-producer-feedback-2026-11-18/companion.patch) applies
only this task's 15 files to local base `39af874d3d7c3d2589866aed9ee5d7664d47d0df`.
That repository already had 16 unpublished commits; this task does not silently
publish their history. The installed app needs a compatible configured Cherry
CLI and project build tools; Finder PATH prerequisites are documented in the
[integration architecture](../../architecture/cherry-test-turboj-integration.md).

All 13 preexisting user files were hash-checked and preserved, excluded from the
changeset. The website's production deployment is unchanged. GitHub source pushes
do not deploy it; publication remains a separate explicit action.
