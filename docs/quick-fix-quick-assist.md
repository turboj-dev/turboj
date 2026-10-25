# Contextual Java Quick Fix and Quick Assist

TurboJ's `refactor.context-actions` command now inspects the current Java source, caret or selection, JDT diagnostics and resolved bindings. It offers applicable source corrections and transformations through CommandJ and the existing RefactorJ workspace. This document describes the implemented contract; native workflow evidence belongs in [first-launch keyboard acceptance](first-launch-keyboard-acceptance.md), and the supplied tutorial is mapped in [Eclipse workflow parity](eclipse-workflow-parity.md).

## Invocation and profile policy

| Selected profile | Bundled contextual-action binding | Meaning |
| --- | --- | --- |
| Eclipse macOS | Cmd+1 | Quick Fix / Quick Assist |
| IntelliJ macOS | Option+Return | Quick Fix / Quick Assist |
| VS Code macOS | Cmd+. | Quick Fix / Quick Assist |
| TurboJ Classic | Cmd+1 | Quick Fix / Quick Assist; intentional TurboJ addition |

All four profiles and existing customized bindings are preserved. IntelliJ's Cmd+1 already focuses Project Explorer, so this change does not add a conflicting default-profile Cmd+1 binding. Users can explicitly customize it in Keymap Settings. The stable command ID remains `refactor.context-actions`; there is no new independent key dispatcher.

The Eclipse profile also assigns Cmd+Shift+S to **Save All**, matching page 17 of the supplied macOS tutorial. Save As remains accessible through the File menu and Command Palette without a dedicated Eclipse default. The other three profiles retain their existing bindings. Explicit user bindings take precedence; an incompatible new default is suppressed with a migration diagnostic. Eclipse documents Save All and Save As as separate actions, with no default shortcut listed for Save As. [Eclipse File actions](https://help.eclipse.org/latest/topic/org.eclipse.jdt.doc.user/reference/ref-menu-file.htm).

The Eclipse reference distinguishes diagnostic corrections from context-dependent assists. Both use the same invocation; a second invocation exposes assists when errors would otherwise hide them. The generic documentation displays Ctrl+1; the supplied macOS tutorial and TurboJ's Eclipse profile use Cmd+1. [Eclipse Quick Fix](https://help.eclipse.org/latest/topic/org.eclipse.jdt.doc.user/reference/ref-java-editor-quickfix.htm), [Eclipse Quick Assist](https://help.eclipse.org/latest/topic/org.eclipse.jdt.doc.user/reference/ref-java-editor-quickassist.htm).

TurboJ opens fixes first when the selected context has supported fixes. Otherwise it opens assists. Invoking the command again switches between the two groups without changing source selection. Its correction guards use diagnostics intersecting the caret or selection; they do not offer every correction for unrelated errors elsewhere on the line or in the file. This narrower targeting is an intentional precision choice, not a claim to implement every Eclipse proposal.

## Popup interaction

`QuickFixController` shows an inline popup anchored at the captured caret. The filter field receives focus; Up/Down changes the selected proposal, Return applies it, and Escape closes it and restores editor focus. Typing filters proposal titles and explanations locally. The selected explanation is visible and accessible without hovering. Tab and Shift+Tab cycle through filter, proposals and explanation; this is local popup navigation.

The popup owns a dedicated `quick-fix-popup` command context. Only the contextual-action command also accepts this context. Source-editor commands such as Delete Line and indentation are not enabled merely because the popup is attached to the editor. Normal text input, copying and selection belong to the focused popup widget.

Escape also cancels an inspection or preparation that has not reached transaction dispatch, so its delayed response cannot reopen the popup. Other global commands dismiss and cancel this contextual popup before continuing; Cut, Copy, Paste and Select All remain local to the focused text control. Once an accepted change has entered workspace publication, the committer's validation and rollback rules govern it.

Simple generated methods apply directly and move into linked editing. Creating a class opens the small, inferred-context confirmation form. Creating a test opens the contextual JUnit form, including an explicit dependency decision where necessary. Rename/extraction proposals that need user parameters use the existing RefactorJ forms and preview. No potentially destructive action auto-applies merely because it is the sole proposal.

## Provider architecture

`JavaQuickActions` in `refactorj-api` defines immutable inspection, proposal, preparation and placeholder records. `JdtQuickActions` runs on the same serial semantic worker and shared `LtkWorkspace` as Java intelligence and RefactorJ. The managed runtime forwards this API; it does not start another Java model or parse on the Swing EDT.

The provider pipeline separates missing types, missing methods, other diagnostic corrections, test generation, refactoring assists and assignment assists. A provider owns its real semantic applicability guard and preparation. A new correction can be added without changing CommandJ dispatch or creating a tutorial-specific UI branch. JDT's documented extension model likewise separates problem-ID-driven corrections from assists, and allows source changes or contextual wizards. TurboJ adapts this model to its existing Swing/RefactorJ architecture. [JDT extension guide](https://help.eclipse.org/latest/topic/org.eclipse.jdt.doc.isv/guide/jdt_api_contributing_a_quickfix.htm).

Each proposal includes an opaque ID, fix/assist kind, action, title, explanation, source location, optional RefactorJ operation and immutable contextual parameters. IDs are bound to the exact inspected snapshot and proposal value. The provider cache retains at most 128 entries and removes entries from obsolete snapshots. Forged, expired or stale proposals cannot be prepared.

Preparation produces the existing transactional `Model.Plan`, an optional destination range and ordered linked-edit ranges. UTF-16 offsets are end-exclusive, matching the editor and JDT. Inspection and preparation do not write user files. Direct candidates are validated before they become selectable; contextual forms revalidate their final parameters through the corresponding implementation.

## Implemented proposals and boundaries

| Context | Offered behavior | Current boundary |
| --- | --- | --- |
| Undefined class type | Create a public class in the inferred package and nearest configured source root; preserve CRLF and generic arity | Refuses an existing destination, escaping the workspace, an interface requirement, constructor arguments and anonymous-class creation; does not advertise unsupported constructor/interface generation |
| Undefined type that resolves through the project search scope | Import the selected qualified type | Uses real source/library matches and accessibility, caps candidates at 30, validates each candidate; ambiguous alternatives remain user choices |
| Undefined method or compiler-confirmed parameter mismatch | Create method in the resolved editable source type, including a missing overload | Requires resolved argument types and a source target in the snapshot; does not edit binaries or invent unresolved target types |
| Unresolved variable/name/field | Create applicable local variable or field | Shown only when the existing JDT correction and candidate validation succeed |
| Type mismatch | Change variable type to match its initializer | Only the supported initializer correction, not arbitrary incompatible-type repair |
| Unreachable code | Remove an eligible unreachable statement | Compiler-ID and RefactorJ validation guarded |
| Required abstract methods missing | Implement inherited abstract methods | Uses the existing semantic implementation operation |
| Resolved class declaration identifier | Create new JUnit test | Supplies actual class, source/test roots, package, symbol ID and eligible declared methods to the contextual form |
| Existing renameable symbol | Rename through RefactorJ | Parameter form and existing preview; not a text-only rename |
| Eligible selection | Extract local variable or method | Semantic applicability is probed; final parameters are validated in the existing flow |
| Resolved non-void invocation/construction statement | Assign to local or field | Uses JDT assignment correction and validates the result |
| Eligible source structure | Explicit type ↔ `var`, lambda ↔ method reference, switch to expression | Only available when the corresponding RefactorJ operation validates |

For `assertEquals(42, new Calculator().add(20, 22))`, method inference produces `int` parameters and a compatible `int` return type. This is not a hardcoded Calculator or JUnit branch: when JDT initially guesses a boxed return type, the implementation checks a compatible primitive overload on the resolved enclosing invocation and requires exact matching types for the other arguments. All final source changes still undergo compiler validation. Non-tutorial names, String arguments, overloads and separate main/test source sets are covered by runtime tests.

Create JUnit Test is an assist on a resolved type declaration, rather than a permanently visible global suggestion. Standard Maven `src/main/java` maps to the sibling `src/test/java`; the native form uses project context for other layouts. It generates Jupiter tests, checks both API and engine availability and requests explicit missing-dependency configuration. It preserves a known existing API version when adding its matching engine, and rejects unresolved or unidentifiable dependency configurations instead of duplicating them. The implementation's bundled Jupiter version is recorded in `FirstLaunchProjects.JUNIT_VERSION` (currently 5.13.4).

Uncaught-exception corrections, arbitrary type conversions, all Eclipse syntax transformations, every constructor/type kind, and complete Eclipse proposal parity are **not implemented by this change**. These are future provider work. An unsupported or unsafe context has no fake proposal; the popup explains that no applicable proposal was found.

## Snapshots, cancellation and transaction safety

The controller captures source identity, workspace state and selection before asynchronous inspection. A ticket cancels superseded work. Source or caret changes discard the response; document switching dismisses the context. Preparation is checked against the inspection snapshot and again against native buffers before publication. A stale response is never silently retargeted to a new editor location.

For a directly applied Quick Fix, navigation to the generated code has a separate continuation guard. Explicit dismissal or another command invalidates that navigation intent; a different active document or focus outside the editor also prevents it. The successful commit's own source refresh does not count as a user dismissal. Internal publication restores a previously focused, still-visible non-editor control after its temporary buffer switches.

Unsaved buffers are included in the shared workspace snapshot. Existing source roots, classpath, project dependencies and generations participate in validation. A proposal therefore acts on what the developer is editing, rather than an older disk-only copy. Ordinary text entry is not blocked by semantic preparation.

`NativeWorkspaceCommitter` applies prepared source/configuration changes through the existing workspace transaction and journal. It checks open-buffer identity/version and disk fingerprints, and coordinates file creation and buffer edits with workspace undo/redo. Newly created Java buffers participate in that history, so opening a generated file does not by itself make its creation impossible to undo. Existing unrelated edits and unsaved POM content are not silently replaced. This is journaled, validated publication; it is not described as filesystem-wide atomicity.

## Generated method template and semantic linked names

After Create Method, focus selects the generated first body statement. The ordered template stops are:

1. Body statement (or insertion position for an empty body).
2. Each parameter name in declaration order.
3. Return type.
4. Method name.

Tab advances, Shift+Tab goes backward and wraps from the first stop to the last; advancing beyond the last stop ends linked editing. These are actual selected source ranges, not decorative highlights. This deliberately prioritizes useful body/name edits; it does not reproduce every Eclipse modifier/type placeholder shown in the tutorial screenshot.

Using Tab or Shift+Tab to leave an edited method-name or parameter-name stop invokes `renameLinked`. Escape ends the template; it does not independently complete or roll back an ordinary typed identifier edit. The host records a fresh baseline on entry into that stop, including any body edits already made. The semantic service accepts only a complete non-constructor method declaration name or a parameter declared directly in that method. It rejects fields, local variables, constructor parameters, partial identifiers, project changes and any simultaneous edit beyond that one name replacement.

The shared model temporarily returns to the baseline to resolve the original symbol. A real JDT Rename updates bound references, including parameter uses in the body or method calls in other sources; text in unrelated strings and unrelated same-named parameters is left intact. The model is restored to the actual current buffers in `finally`, even after rejection. The semantic result is rebased onto the already typed declaration and validated again. Fresh template ranges are parsed from the resulting method, allowing the host to continue at the next requested stop.

The host checks active document identity, template context and source captures before publishing the rebased plan. It preflights the original undo checkpoint and reserves its history before further buffer changes. Local name typing and the subsequent semantic reference update are coalesced into one undoable transaction. Publication records both caller buffers and the renamed declaration. History that was trimmed, an invalid rename or conflicting source changes produces an explicit failure rather than a partial semantic rename. Return-type and body stops are ordinary editing; they are not a general Change Signature implementation.

## Running the generated test

The Run command asks the shared JDT service whether the current resolved class is a test. It recognizes Jupiter test annotations and composed annotations, JUnit 4 tests, inherited tests, eligible nested tests and the JUnit 3 TestCase hierarchy. A class name ending in `Test` is insufficient. Unresolved or unrelated annotations, private/static test methods, and non-instantiable primary types do not masquerade as runnable tests.

The asynchronous result is tied to the originating active document and source capture. Switching files cannot apply the old class's test classification to a different editor. A test target is sent to TurboTest; a normal Java application follows the existing compile/run route. Compilation failure is reported as compilation failure, separately from a compiled test's failed assertion.

In the Eclipse profile, Run from an implementation source without a main candidate can rerun the previously launched JUnit selection in the same workspace and Maven module. This preserves the tutorial's final gesture while remaining in Calculator's implementation. A current test takes precedence; a current main takes precedence over the previous test. The rerun uses the current semantic workspace and compilation, not stale compiled test output, and does not navigate the source editor back to the test file. Other profiles retain their existing Run behavior.

`hasMainMethod(Context)` resolves actual method bindings in types declared in the file, including nested types and inherited members. It recognizes conventional public static `main(String[])`/varargs, and Java 25 no-argument or instance forms when the configured target supports them. Inherited access and non-inheritance of static interface methods are respected. A string containing `main`, a wrong return/parameter type or a private method is insufficient. This is a semantic candidate query; the compiler and launcher remain authoritative for erroneous source, construction and execution. The expanded Java 25 forms and inheritance rules follow [JLS 12.1.4](https://docs.oracle.com/javase/specs/jls/se25/html/jls-12.html#jls-12.1.4).

## Verification and remaining scope

The semantic runtime tests use real embedded Equinox/JDT, real workspace transactions and independent javac compilation of accepted generated sources. They are not mocks of a fixed proposal list.

Verified on the local macOS/JDK 25 environment on 2026-10-25:

| Evidence | Result | Coverage |
| --- | --- | --- |
| `QuickActionsIT` | **PASS: 24 tests, no failures/errors/skips** | Unsaved-source class creation, package/root inference, CRLF/generics, unsafe-target refusal, method inference and overload selection, imports, contextual assists, stale/forged proposals, cancellation, true test recognition, semantic linked method/parameter rename, conflicts, refreshed placeholders, main/test dependencies and main detection across Java 21/25 targets and inheritance |
| Existing `ModernizationIT` | **PASS: 20 tests, no failures/errors/skips** | Regression coverage of existing semantic modernization operations |
| Native keyboard/focus end-to-end workflow | See acceptance report | Runtime success alone does not establish native accessibility or an actual green UI result |
| Proposal latency and comparative Eclipse performance | **UNVERIFIED** | No measured claim of faster proposal generation is made |

Reproduce semantic verification from the repository root:

```sh
./mvnw -q -pl refactorj/refactorj-runtime -am \
  -Dit.test=QuickActionsIT,ModernizationIT \
  -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false verify
```

Reports are written under `refactorj/refactorj-runtime/target/failsafe-reports/`. The latest captured local run log is `target/quick-actions-main-routing.log`; the prior linked-parameter run is retained in `target/quick-actions-linked-parameters.log`. Native popup focus, real Cmd+1 events, linked-template keys, workspace undo and the plain/Maven first-launch sequences require the separate Swing acceptance evidence; they are not inferred from the runtime test count.
