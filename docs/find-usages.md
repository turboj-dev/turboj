# Find Usages and related-test navigation

## Commands

- **Navigate → Find Usages…** (`navigation.findReferences`, preserved alias
  `navigation.findUsages`) searches the selected symbol in the shared JDT
  workspace. Existing keymap bindings and user customizations remain unchanged.
  Even a single usage opens a resizable horizontal split: callers and relative
  source paths/lines on the left, the actual TurboJ editor component on the right.
  The preview contains the full captured source, Java highlighting and line
  numbers, with the exact usage selected and nearby context visible. It is
  read-only, supports scrolling/selection/copy, and follows the editor theme,
  font size and tab width. The divider can be moved to balance the panes.
  Initial focus is in the filter; Up/Down
  select usages from either the filter or the results list without wrapping.
  Filter by caller or path; Enter opens the
  selected occurrence, Escape returns to the editor.
- **Navigate Between Source and Test** (`navigation.goToTest`) opens a unique
  conventional counterpart directly when its package and compilation/module
  identity match. `ClassTest.java` / `Class.java` is a naming convention, not
  proof of a test relationship. Other filename matches remain explicit,
  unverified choices. A dependency on another module does not establish the
  default counterpart.
- **Choose Related Tests / Source…** (`navigation.relatedTests`) always opens
  the chooser. It includes the default counterpart and verified source usages
  inside recognized test methods. When no unique default exists, the ordinary
  source/test action opens this same chooser. The new command has no assigned
  default shortcut; it is available through Navigate, Command Palette and
  custom bindings.

Place the caret on a production method declaration or call, or within its body,
to inspect related test usages. Selecting a verified alternative opens the exact
call/reference, not merely the test file. Within a recognized test method, the
chooser also offers source declarations of methods that it uses. Navigation
history preserves the previous caret. Closing the chooser leaves the source
selection intact.

## Evidence and boundaries

References come from the existing JDT SearchEngine with accurate matches only.
No text search, filename heuristic or coverage counter establishes a verified
test usage. Overloads and same-named types in distinct modules retain their
binding identities. Open unsaved sources participate in the shared working
copies; searches refresh after source changes. Publication checks document and
caret context. Activating an already-open chooser rechecks source versions and
open buffers; changed results must be requested again. Navigation to an unopened
file also checks its disk text against the captured source.

Test classification uses resolved JUnit 4/5 annotation identities, including
Jupiter parameterized/repeated/template/factory annotations and resolved
composed annotations. The model also recognizes public, non-static, zero-arg
`void test*` methods inheriting JUnit 3 `TestCase`. Unresolved or same-simple-name
annotations are not accepted. A call in a nested non-test method is not
attributed to the surrounding test. Javadoc references and nested type initializers
are likewise excluded from verified test-body usages. A lambda/method reference in a test is a
static usage, without any claim that it executed.

Inherited test contracts are shown at their actual source declaration. They are
not duplicated for concrete subclasses or labeled as Spring/Quarkus executions.
The chooser does not perform transitive test-impact analysis through helper
methods. Reflection, binary-only callers, unresolved symbols, generated sources
outside the workspace and unavailable classpaths can leave results incomplete.
An empty list never proves that a method has no tests. TestNG and other framework
classifiers are not added by this change.

**Static usages do not change Cherry test attribution or coverage.** A resolved
call inside a test does not prove that the test ran, passed or covered a line.
All current/stale/unknown coverage policies remain independent.

## Implementation

`JdtKeyboardNavigation` reuses the existing references provider and enriches
caller labels. `JdtTestNavigation` resolves test classification, conventional
counterparts and method-to-test/source relationships. `JavaStructure.Target`
adds an explicit kind while retaining its original four-argument constructor.
`KeyboardJavaController` owns asynchronous preparation, stale-request guards
and the filtered, keyboard-accessible chooser. `TurboFrame` and `MenuLayout`
expose the actions through CommandJ; `runCommand` now resolves registered aliases.

Queries run on the existing serial semantic worker. Painting does not parse Java
or search references. Result labels and line numbers are prepared off the EDT;
the UI filters the prepared result list. No persistent index, new runner, new
dispatcher or third-party dependency is introduced.

## Validation

16 November 2026: final full `./mvnw install` **PASS**, 866 tests in 124 suites,
zero failures/errors/skips. Native plain and imported Maven acceptance **PASS**.
[Retained logs, control failures and source/artifact hashes](evidence/find-usages-2026-11-16/README.md).
The 16 November JAVA-06 delivery includes the implementation, arrow-key fix,
split editor preview and retained validation evidence. Evidence manifests record
the uncommitted source state at capture time; they are historical records.

Follow-up: the first native harness forced list focus and missed arrow navigation
from the filter. This was reproduced against the old build, fixed locally and
verified with native Up/Down events in both plain and Maven projects, including
boundary/empty results and Enter from the filter. See
[arrow-key regression evidence](evidence/find-usages-2026-11-16/arrows.json).

Split-view follow-up: 17 focused tests passed (preview, menu and editor rendering),
plus native plain/Maven checks for the real editor preview, exact selection,
theme/font synchronization, read-only typing and Tab traversal. The preview uses
the captured search snapshot, including unsaved source; it does not open a new
editable buffer or update Cherry attribution. Enter opens the selected usage
from the list, filter or preview; Tab moves between controls. Existing stale-result
checks still apply when opening the usage. No full-reactor rerun was performed
for this UI change. [Evidence and screenshot](evidence/find-usages-2026-11-16/split-view.json).

`UsageNavigationIT` exercises real embedded JDT queries: overloads, look-alike
annotations, multiple test callers, composed annotations, inherited contracts,
method references, unsaved buffers and reference refresh, distinct Maven-style
compilation boundaries, dependent test modules, Unicode/CRLF offsets and nested
non-test methods, Javadoc and deferred nested initializers. These are static-analysis tests, not proof of runtime discovery
for every supported JUnit annotation.

`UsagesNavigationAcceptance` exercises the native app with real JUnit classpaths
and both plain and imported Maven projects: direct default navigation, Find
Usages alias, filtering, exact call offsets, history, explicit alternatives,
reverse navigation, Enter/Escape and stale-result rejection. Fixture creation,
command dispatch and caret placement are programmatic; Enter/Escape/focus use
real Swing events. The driver may request application foreground on macOS.
`SourceTestNavigationAcceptance` delegates to this expanded acceptance.

```sh
./mvnw clean install
java -cp app/target/test-classes:app/target/turboj-app.jar \
  dev.turboj.app.UsagesNavigationAcceptance
```

A desktop session is required for the native check. This does not certify every
physical keyboard layout or the separate first-launch Quick Fix workflow.
