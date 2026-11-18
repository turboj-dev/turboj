# QFX-10 — semantic templates, 18 November 2026

Implemented after the structured template dialog. Tests below use the actual embedded JDT workspace and compiler;
no template result is inferred from a screenshot or catalog entry.

## Implementation

- `JavaIntelligence.TemplateSemantics` returns immutable bound variables, generic supertypes, known concrete types,
  selected/expected types, common method-result types and actual language/API capabilities. `SharedJavaIntelligence`
  reuses the same workspace/parser/scope rules as completion. `IntelligenceController.submitSemantic` is the only
  application semantic execution boundary. No second index or reflection-based type inference was introduced.
- The expression evaluator supports nested macros, bounded recursion, collection inheritance, expected array
  components, selected expression components, known iterator/list return types and compatible casts. Ambiguity
  remains an editable field. Reference/null templates exclude primitives; scoped/static variables are respected.
- Consumer templates require an actual compatible functional target. IO requires Java 25+ with `IO` resolving to
  `java.lang.IO`. Structured concurrency checks preview settings and the actual available API.
- The pinned upstream `sts` catch is incompatible with Java 25: `join()` declares `InterruptedException`, not
  checked `ExecutionException`. The generated catalog adapts the catch to `StructuredTaskScope.FailedException`;
  pinned upstream XML is preserved and the generated entry records this compatibility change. Expansion was
  compiled successfully with `--enable-preview --release 25`.
- The generator retains upstream formatting/shortening flags. Schema-v1 user entries can enable optional
  `reformat` and `shortenNames`; absent flags stay false. The structured dialog exposes both options.
- On confirmation the current formatter prepares range edits; resolved qualified types can be shortened with
  conflict-aware import insertion. Save/Format wait for this preparation. Source/version/document changes reject
  late edits and the deferred command. Expansion, linked edits and finishing edits coalesce into one Undo.

## Automated evidence

Commands:

```sh
./mvnw -pl app -am -DskipTests install
./mvnw -pl app -Dtest=LiveTemplateSemanticTest,LiveTemplatesTest,LiveTemplateSettingsTest,EditorTemplateTest test
./mvnw -pl app -Dtest=LiveTemplateFinishTest,LiveTemplateSemanticTest,LiveTemplatesTest,LiveTemplateSettingsTest,EditorTemplateTest test
./mvnw -q -pl app -Djava.awt.headless=true -Dtest=LiveTemplateFinishTest,LiveTemplateSemanticTest,LiveTemplatesTest,LiveTemplateSettingsTest test
```

- Reactor compile/install: PASS. Skipping tests is stated explicitly; this is not reactor test qualification.
- Initial selected suite: 30 PASS.
- Initial integrated semantic suite: **33 PASS**, zero failures/errors/skips, including nine new tests.
- Follow-up stale-completion regression: **23 headless tests PASS**. This excludes `EditorTemplateTest`, whose existing mouse/focus test constructs a real JFrame; the first headless attempt correctly reported one `HeadlessException`, and its log is retained. An earlier sandbox attempt terminated its AWT fork before any tests.
- Native smoke review exposed a real stale `finishing` flag after editing/Undo/file switch during automatic formatting. The controller now immediately invalidates that obsolete request, including its deferred Save, so it cannot block a new template or allow AI to win its Tab. The regression checks fresh commands are not queued behind obsolete work.
- Native smokes now wait for completed import/format preparation before comparing source or issuing Undo. A new real-keyboard case verifies qualified types, linked rename, Save, imported/shortened/formatted source, mapped caret, javac and one Undo/Redo; its runtime result belongs to the integrated native report.
- New tests exercise resolved Consumer and static scopes; collection subtyping; nested expected array type;
  actual Java 17/25 and preview capabilities; iterator generics; primitive rejection; selected-expression
  iteration; safe shortening with a conflicting `java.awt.List` import and unchanged string literals; javac
  compilation; caret mapping; deferred Save; one-step Undo/Redo; cancellation and stale asynchronous refusal.
- One interim invocation failed before running tests because the independently developed performance probe
  called an absent `TurboFrame.paintImmediately()` method. The owner corrected it; the failed log is retained.

The semantic test runs used the updated JDT bundle containing `templateExpectedType`; `semantic-bundles.sha256`
records the built and runtime-embedded copies. `source-files.sha256` identifies this scoped implementation.
The application JAR and combined native workflow qualification belong to the final integrated build, not this
module-level report. No separate GUI session is claimed here.

## Explicit boundaries

- Arbitrary script/clipboard macros are never executed. Unknown functions, ambiguous subtype choices, missing
  classpath, unsupported language levels and unsafe casts remain editable or unavailable, with the existing
  explanation. This is not a claim of arbitrary IntelliJ plugin/macro compatibility.
- The settings preview intentionally uses its declared sample context, not the current project's JDT bindings.
- The incompatible legacy structured-concurrency context remains readable in saved JSON but is not offered;
  the modern catalog context is offered only after checking the actual project API and preview flag.
- Formatting failure keeps user text. Name conflicts retain qualification. A new edit during preparation prevents
  late edits and Save; the developer can invoke Save again on the new source.
- Physical keyboard/IME/VoiceOver and live paid-cloud checks remain outside this automated qualification.

## Final integrated result

The final installed JAR `36ae34e6…ea33a9` passed [LiveTemplateWorkflowSmoke](../product-sequence-2026-11-18/native-final/LiveTemplateWorkflowSmoke.txt), [precedence](../product-sequence-2026-11-18/native-final/LiveTemplatePrecedenceSmoke.txt), [dialog](../product-sequence-2026-11-18/native-final/LiveTemplateDialogSmoke.txt) and [inline semantic transformations](../product-sequence-2026-11-18/native-final/InlineSemanticTemplateSmoke.txt). The 976-test reactor also includes the JFrame tests excluded from the earlier headless control. This supersedes the pending integrated gate above without replacing its historical logs.
