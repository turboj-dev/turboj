# RefactorJ operation reference

The engine exposes the following 33 operation identifiers. An operation is a
version-pinned semantic implementation, not a promise that every syntactically
possible selection is eligible. `available` checks the selection shape; `prepare`
runs argument-dependent Eclipse conditions and RefactorJ safety checks. A rejected
plan contains diagnostics and cannot be applied. All offsets are UTF-16.

Every successful plan contains source versions, preview edits, explicit file
operations, and diagnostics. Application, complete workspace undo, and redo are
provided by the independent transaction layer. Generated source must pass a
candidate compiler-error comparison before a plan is valid. Reflection, generated
sources outside the snapshot, and external binaries remain outside static proof.

| Operation | Eligible input and behavior | Safety boundaries |
| --- | --- | --- |
| `rename` | Resolved locals, parameters, fields, record components/accessors, methods/override families, types, enums/constants, annotations, type parameters and packages. Updates resolved cross-file references and file names. | Scope/name conflicts rejected; literals and unrelated same-name symbols untouched; public API/reflection risk reported. |
| `extract-method` | Selected statement/expression regions accepted by Eclipse's data/control-flow analysis; explicit name/visibility and optional duplicate replacement. | Invalid selections, escaping control flow, unresolved types and unsafe extraction conditions rejected. |
| `extract-variable` | Selected expression with an Eclipse-valid insertion point; optional final and replacement of equivalent occurrences. | Preconditions govern evaluation context and scope; no textual expression search. |
| `extract-constant` | Proven Java constant expression or null literal, with requested visibility. | Calls, object construction and other nonconstant initialization cannot be moved into class initialization. |
| `extract-field` | Local variable promotion with method, declaration or constructor initialization strategy. | Eclipse checks scope, initialization options, and member conflicts. Arbitrary source fragments are not accepted. |
| `extract-parameter` | Selected expression accepted by Eclipse Introduce Parameter; updates declarations and calls. | Expressions whose local names are not valid at call sites can be rejected. No unbound textual expression is inserted. |
| `inline-variable` | A resolved local with an eligible initializer. | Side-effecting initializers require exactly one immediately following simple return use; duplication/removal or evaluation across statements is rejected. |
| `inline-method` | Resolved invocation/declaration accepted by Eclipse Inline Method; one/all invocations and optional declaration removal. | Eclipse controls local renaming, return substitution and non-inlineable bodies. |
| `change-signature` | Name, return type, visibility and ordered existing/new/deleted parameters; semantic call-site updates. | Reordering/removing observable argument evaluation is rejected, including override-family calls. Type/signature conflicts must pass Eclipse checks and candidate compilation. |
| `move` | Top-level type/package; static method/field/nested type; instance method with a resolved receiver; compatible superclass/sole-subtype instance-field move; inner-to-top promotion. | Arbitrary instance ownership is not guessed. Hazardous constructor/initializer ordering is rejected. Inner-to-top keeps enclosing-instance access; another package requires a subsequent top-level move. |
| `safe-delete` | Resolved source declaration with no accurate references in a clean workspace. | Existing Java errors prevent proving absence of usages. No forced deletion; public/external consumers need review. |
| `extract-interface` | Eligible class members selected by symbol IDs, or all extractable members; optional Eclipse constraint-based usage replacement. | Invalid members, naming conflicts and incompatible usages rejected. |
| `introduce-interface` | Creates an interface and migrates only explicitly selected type usages if requested. | Each usage must resolve to the selected class. Constructors/superclass clauses/class literals are excluded. Changed overload/constructor targets are rejected. |
| `pull-up` | Selected members and resolved superclass/interface accepted by Eclipse hierarchy analysis. | Required members, visibility, abstract contracts and conflicts must pass checks. |
| `push-down` | Selected members to eligible subclasses; optional abstract contract retained. | Eclipse hierarchy and usage analysis determines legal destinations. |
| `encapsulate-field` | Resolved field, explicit accessor names/visibility, semantic usage replacement. | Existing accessors, inherited members and conflicting names are checked. |
| `constructor-to-factory` | Resolved constructor; chosen factory name and optional constructor protection. | Eclipse preconditions and compiler validation apply; reflective/external construction risks reported. |
| `anonymous-to-lambda` | Functional-interface anonymous implementation accepted by JDT's conversion fix. | Rejects Serializable target types and Serializable ancestors, as well as unsupported lexical receiver/super behavior. |
| `lambda-to-method-reference` | Compatible static/type calls, `this`/`super`, an unbound receiver that is the lambda's own parameter, and safe constructor/array references accepted by JDT. | Rejects nullable, effectful or mutable bound receivers: their evaluation/null-check timing would change. Positive `this` and unbound conversions have compilation, runtime-equivalence and undo regressions. |
| `method-reference-to-lambda` | Supported method reference with stable target/receiver. | A bound receiver whose evaluation would move is rejected. |
| `switch-to-expression` | Traditional switch accepted by JDT's switch-expression fix. | Unsupported control flow is rejected, not rewritten heuristically. |
| `type-to-var` | Local declaration with stable inferred type. | Target-typed generic inference, type narrowing and unsupported declaration forms are rejected. |
| `var-to-type` | Local inferred type expressible as a denotable Java source type. | Captures/anonymous or otherwise non-denotable types are rejected. |
| `organize-imports` | JDT import organization over the current compilation unit. | Ambiguous missing imports require an explicit decision; no arbitrary type choice. |
| `add-import` | Explicit resolved classpath type or supported static member. | Missing types and simple-name conflicts rejected. |
| `remove-import` | Selected import proved unused by a JDT diagnostic. | Used or unresolved-status imports are retained. |
| `implement-methods` | Missing abstract/interface methods in a resolved editable type, optionally filtered by method ID. | Already implemented or unavailable methods are not fabricated. Generated stubs are code actions, not behavioral equivalence claims. |
| `create-method` | Unresolved invocation, editable resolved receiver type, resolved argument types. | Binary dependencies cannot be changed; unknown argument types rejected. |
| `create-constructor` | Missing constructor signature in an editable resolved type. | Existing constructors and binary targets rejected. |
| `create-field` | Compiler-confirmed unresolved variable/field with an editable resolved owner. | No invented owner or unresolved declaration target. |
| `create-local` | Compiler-confirmed unresolved variable in a valid local context. | JDT determines the type and legal insertion point. |
| `fix-type-mismatch` | Compiler-confirmed local initializer type mismatch with a denotable actual type. | Other mismatch categories are rejected rather than applying speculative casts. |
| `remove-unreachable` | Compiler-proven unreachable statement directly in a block. | Reachable or merely suspected dead code is never removed. |

## Verification

The real embedded tests exercise modern Java, overloads, inheritance, generics,
records, enums, annotations, package/file operations, Unicode, unsaved buffers,
selected interface migration, side-effect rejection, stale plans, compilation,
observed runtime equivalence, and complete transaction undo/redo. See the actual
Maven reports for current counts; the source tests do not substitute mock Eclipse
processors. TurboJ, CLI, LSP, MCP and external host integration checks are separate
from these engine checks and must be reported separately.
