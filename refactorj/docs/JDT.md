# Embedded JDT/LTK backend

RefactorJ uses Eclipse JDT Core Manipulation 1.23.200 and JDT Core 3.44.0,
with the corresponding Eclipse LTK bundles. The dependency versions are pinned
because the headless refactoring processors include Eclipse internal APIs.
No Eclipse implementation source is copied into RefactorJ.

The Eclipse bundles run in an embedded Equinox framework in the caller's JVM.
There is no server, network transport, subprocess, SWT, or workbench. A runtime
owns its private temporary Eclipse workspace and intact OSGi dependency JARs.
One `LtkWorkspace` holds one Java project, primary working copies, resolved ASTs,
source identities, and the JDT search index. Completion, signatures, navigation,
documentation, and refactoring borrow this same model via `JavaIntelligenceProvider`.
The TurboJ compatibility facade exposes `sharedRefactorJ(Context)` to the editor's
existing serial semantic worker. UI code must not call these blocking operations
on the Swing EDT.

Original source files are never written by this backend. The application supplies
versioned snapshots; their current contents are mirrored into the private Eclipse
workspace so LTK processors reading text file buffers see unsaved edits as well.
Only the transaction layer applies a reviewed plan to actual source buffers/files.

Public symbol IDs are opaque, versioned and scoped to the normalized workspace root.
They encode a Java handle relative to the private project, rather than exposing that
project's random Eclipse identity. Inspect results and selected-member requests can
therefore survive a backend restart for the same workspace. The resolver rejects
IDs from another workspace and raw Eclipse handles. Source versions and preview
validation still apply; a stable ID is not permission to apply stale source edits.
Clients should retain IDs as opaque strings, not reconstruct their encoding.

An intelligence context with no source roots is explicitly in-memory: the facade
does not recursively scan the active buffer's parent directory. Unsaved scratch
identities that are not legal Java filenames receive a legal compilation-unit
alias only inside the private Eclipse workspace. Public API locations retain the
original scratch identity. Renaming its primary type changes source text without
creating a disk rename/save operation; saving remains an editor action.

## Preview and validation

A plan runs Eclipse initial and final conditions, collects `Change` objects, and
converts them to ordered UTF-16 edits and file operations. It never calls
`Change.perform()`. Java-model file operations have implicit source changes:
renaming a compilation unit renames its primary type/constructors; moving a unit
changes its package. The adapter emits those source edits explicitly with JDT
ASTRewrite. Unsupported change shapes fail with a diagnostic.

Candidate snapshots are then staged in the same model, checked for additional
compiler errors, and restored in a finally block. Verification pins all source text
in secondary working copies and parses it in one shared binding environment; it
does not trust stale Eclipse file-buffer ASTs. Baseline diagnostics and exact
immutable plan validations are cached, bounded, and invalidated by workspace versions. Existing errors can remain for
quick fixes, but the transformation must not add new errors. This check is useful,
but is not a proof of whole-program behavioral equivalence. Eclipse semantic
preconditions, binding checks, targeted compilation, runtime tests, and transaction
regressions provide complementary checks.

## Boundaries

- A workspace currently represents one Java project/classpath. Distinct modules
  with the same package/file identity need separate workspaces; Maven/Gradle model
  discovery belongs to the host.
- Static analysis cannot reliably locate reflection strings or unknown external
  binary consumers. Public API changes require review of those consumers.
- Interface extraction uses Eclipse's constraint solver. Introducing an interface
  can migrate explicitly selected, binding-resolved type usages. The candidate is
  rejected if existing overload or constructor targets change; unrelated usages
  remain untouched.
- Static fields and members use Eclipse's move processor. Instance fields can move
  to a compatible superclass or sole direct subtype when construction order is safe.
  Arbitrary object-ownership changes and hazardous initializer timing are rejected.
  Instance method movement requires a resolved receiver target. Nonstatic inner
  classes can move to a top-level declaration while retaining an enclosing-instance
  field; a subsequent type move can relocate that declaration to another package.
- Introducing a parameter from an expression whose local names are not valid at
  call sites may be rejected by Eclipse. No text substitution bypasses that check.
- Runtime extraction/Java-model startup is a cold cost. Warm and cold measurements
  must be reported separately. The design does not guarantee sub-30-ms refactoring.

## Dependencies and licensing

Eclipse JDT, LTK, platform and Equinox artifacts retain their Eclipse Public
License notices (EPL-2.0, with individual artifact notices authoritative). Apache
Felix SCR and its OSGi API dependencies retain Apache-2.0 notices. CommonMark is a
transitive Eclipse dependency; its own bundled license remains authoritative.
Distribution retains the original dependency JARs and their license/notice files.

Primary implementation references:

- https://github.com/eclipse-jdt/eclipse.jdt.core
- https://github.com/eclipse-jdt/eclipse.jdt.ui/tree/master/org.eclipse.jdt.core.manipulation
- https://help.eclipse.org/latest/topic/org.eclipse.jdt.doc.isv/reference/api/org/eclipse/jdt/core/ICompilationUnit.html
- https://help.eclipse.org/latest/topic/org.eclipse.platform.doc.isv/reference/api/org/eclipse/ltk/core/refactoring/Refactoring.html
