# Eclipse first-launch workflow parity

The interaction specification is the supplied **TurboJ - First Launch.docx**, inspected on 2026-10-25. Its 27 embedded screenshots were individually inspected. A local Pages PDF export contains exactly 20 pages; image pixel hashes match the original embedded images. Page numbers below refer to that rendering, rather than guessed XML paragraph boundaries. The original DOCX was not modified.

- DOCX SHA-256: `ed220da064132ccda935f334b4731c8e92a15cddf0dc6844473f863605bf620d`.
- Local rendering SHA-256: `8a4b79f689e0cd2bc9256bc523f58d44c8d2c11e8bce379f057e34111bac4aa5`.
- Local inspection artifacts: `target/first-launch-spec/first-launch.pdf`, `page-map.json`, `document-map.json`, `rendered-text.txt`, and original `image1.png` through `image27.png`.
- The screenshots are specification evidence, **not evidence that TurboJ already performs the operations**. They are retained locally because they contain personal filesystem paths. Native verification must come from the separate isolated TurboJ acceptance run.

Status vocabulary: **PASS** means the stated behavior has direct evidence; **PARTIAL** means a named subset works; **FAIL** means an observed behavior violates the requirement; **UNVERIFIED** means it has not been exercised. The final native run09 passed the Java and Maven development sequences through green JUnit. Evidence and limits are in [the keyboard acceptance report](first-launch-keyboard-acceptance.md). This does not establish every optional Eclipse wizard feature or physical keyboard layout.

## Page-by-page implementation map

`refactor.context-actions` is the existing stable CommandJ entry for contextual actions. New first-launch commands have stable IDs shown below; their presence is not counted as native verification. Local widget navigation is recorded explicitly and does not require a second dispatcher.

| DOCX page | Eclipse action | Shortcut | TurboJ command | Implementation | Native verification |
| --- | --- | --- | --- | --- | --- |
| 1 | Choose workspace; optionally remember it; launch | Tab / Shift+Tab, Space, Return | `workspace.select`; local launcher controls | PASS | PASS — workspace checkpoint |
| 2 | Sponsor page / welcome; enter project creation | Keyboard link/menu navigation | `file.newJavaProject` | PARTIAL — sponsor/welcome pages not reproduced | PARTIAL — workspace proceeds to native editor |
| 3 | File → New → Java Project | Menu navigation; no project shortcut shown | `file.newJavaProject` | PASS | PASS — F10 → File → New → Java Project |
| 4 | Configure project, then New → Class from source root | Tab, Space, Return; contextual menu | `file.newJavaProject`, `file.newJavaClass` | PASS — selected project/class flow | PASS — project location and source root |
| 5 | Configure HelloWorld class/main stub; content assist for print | Class form; Ctrl+Space for content assist | `file.newJavaClass`, `complete` | PARTIAL — main generation implemented; completion pre-existing | PARTIAL — main wizard PASS; print completion not exercised |
| 6 | Enter println and compile | Prose requests Cmd+Return; not proven as Eclipse default | `compile` | PASS — existing compile command/profile retained | PASS — Cmd+3 palette Compile; no Cmd+Return claim |
| 7 | Run | Screenshot shows Shift+Cmd+F11 | `run` under selected profile | PASS | PASS — Shift+Cmd+F11, actual stdout and exit 0 |
| 8 | Save and Launch; then type unresolved Calculator reference | Return confirms save; Cmd+1 next | `run`, save flow, `refactor.context-actions` | PARTIAL — Save before Run Yes/No/Cancel; no per-file checklist | PASS — Yes saves dirty sources before Run |
| 9 | Create class Calculator from unresolved type; confirm class form | Cmd+1 → arrows → Return; form Finish | `refactor.context-actions` → Create class | PASS | PASS — inferred class form, actual file, editor focus |
| 10 | Open generated Calculator; request JUnit test at declaration | Cmd+1 → arrows → Return | `refactor.context-actions` → Create new JUnit test | PASS | PASS — declaration-context JUnit assist |
| 11 | New JUnit Test Case wizard | Tab / Shift+Tab, Space, Return | Contextual JUnit generation flow | PARTIAL — Jupiter 5 flow; legacy/framework choices not copied | PASS — inferred name/package/test root |
| 12 | Missing JUnit library confirmation | Arrows / Space → Return | Contextual JUnit dependency configuration | PARTIAL — supported Jupiter dependency configuration | PASS — explicit decision, plain library / Maven pom change |
| 13 | Generated failing test; execute and inspect real assertion failure | Run; local result navigation | `run` / `tests.current` | PASS | PASS — compiled test, one actual assertion failure |
| 14 | Replace fail stub with test calling missing add method | Editor typing; Cmd+1 next | Editor editing; `refactor.context-actions` | PASS | PASS — current missing-method diagnostic; previous report unchanged |
| 15 | Create method add(int,int); enter linked editing | Cmd+1 → arrows → Return; Tab / Shift+Tab | `refactor.context-actions` → Create method | PASS — body, parameter names, return type, method-name stops | PASS — inferred int arguments; body/parameter linked edits |
| 16 | Edit generated method name in linked template | Tab to name; type addition | Linked-edit widget action | PASS | PASS — addition updates declaration/caller; one Undo/Redo |
| 17 | Implement addition; save changed files | Editor typing; Shift+Cmd+S in screenshot menu | `file.saveAll`, editor editing | PASS — existing Save All command | PASS — source edit and actual Cmd+Shift+S Save All |
| 18 | Rerun to green JUnit result; prepare Maven repetition | Run | `run` / `tests.current` | PASS | PASS — Run from Calculator repeats previous JUnit; one passing test |
| 19 | File → New → Maven Project | Keyboard menu navigation | `file.newMavenProject` | PASS | PASS — F10 → File → New → Maven Project |
| 20 | Maven project creation; repeat prior Java/test flow | Form Tab / Space / Return | `file.newMavenProject`, existing Maven import | PASS — minimal Maven project; no archetype UI claim | PASS — resolved offline import; repeated full flow reaches green |

## Context, offered actions, edits, and focus

The screenshot names below are exact archive member basenames. A static screenshot does not record every keypress or the complete focus history. Where the document omits an intermediate transition, the required continuation is identified without inventing observed events.

| Page / screenshot | Current context and caret | Offered actions and selected operation | Source/project effect | Next focus and subsequent action |
| --- | --- | --- | --- | --- |
| 1 / image24 | Before any editor; workspace path field focused | Workspace path, Browse, remember/default checkbox, Cancel, Launch | Selected workspace becomes project/preferences context; prose specifies default `~/Workspace` | Launch; startup then welcome. Checkbox persistence must be optional. |
| 1 / image26 | Eclipse 2026-09 startup splash | Progress only | Application initializes | Workspace/welcome; not an interactive wizard to reproduce cosmetically. |
| 2 / image6 | External browser sponsor page | Sponsor navigation | No Java/project mutation | Return to TurboJ welcome/project creation; prose requests sponsor then welcome but supplies no distinct welcome screenshot. |
| 3 / image23 | File menu with New submenu open | Java Project selected; Maven Project, Project, Package, Class, several other type/file entries, JUnit Test Case also visible | Opens Java-project flow | Project-name input. Only selected operations are acceptance requirements; menu presence is not proof of every entry. |
| 4 / image7 | New Java Project form; name HelloWorld | Default/custom location, JavaSE-25 environment / project JRE / default JRE, source/output layout, working set, optional module-info; Finish | Create project with source root; screenshot selects separate source/output folders and no module-info | Project explorer/source root, then create class. Prose permits custom directory outside workspace. |
| 4 / image21 | Project src selected in Package Explorer | Context menu New → Class selected | Opens class wizard with source-root context | Class-name field; no manual source-root rediscovery. |
| 5 / image15 | New Java Class; HelloWorld name focused | Source folder, package, enclosing type, name, visibility/modifiers, superclass/interfaces, main/constructor/inherited-method stubs, comments, Finish | HelloWorld.java in selected package/source root | Editor at useful generated source position. Main checkbox exists but is unchecked in this screenshot. |
| 5 / image17 | HelloWorld.main; after `System.out.print` | Resolved PrintStream overload proposals and selected-proposal documentation | Accept matching println overload and enter string | Code continuation after accepted proposal; no Quick Fix substitution for completion. |
| 6 / image12 | Caret after `System.out.println("Hello World!");` | Build requested by prose | Current source compilation | Editor/output result; no compile-result screenshot on this page. |
| 7 / image25 | Run menu | Run selected; displayed accelerator Shift+Cmd+F11 | Launch selected application or test | Save confirmation if needed. Unrelated debug/menu entries are not implementation claims. |
| 8 / image8 | Dirty HelloWorld; Save and Launch dialog | Dirty-file checklist, Select All/Deselect All, remember-save checkbox, Cancel/OK | Save chosen dirty buffers before launching; cancellation leaves source intact | Return to editor/output; then type unresolved Calculator declaration/construction. |
| 9 / image1 | `Calculator calculator = new Calculator();`, unresolved Calculator highlighted at construction; status says cannot resolve type | Create class Calculator selected; Create record Calculator; change to Collator; Fix project setup; contextual explanation/preview | Selection opens new-class flow; no mutation merely by opening popup | Class form, preserving origin for back navigation. Console still shows previous Hello World output. |
| 9 / image4 | Context-generated Java Class form | Source root HelloWorld/src focused; blank default package; Calculator prefilled; public; Object superclass; Finish | Create actual Calculator.java; not a textual replacement of the unresolved reference | Generated class editor. Form’s inferred context is essential; full Eclipse chrome is not. |
| 10 / image5 | New empty `public class Calculator {}` | No popup; generated class is open | Explorer now includes Calculator.java and HelloWorld.java | Move caret to Calculator declaration. |
| 10 / image18 | Calculator declaration identifier selected | Rename in file, Rename in workspace, Create new JUnit test case for Calculator.java | User chooses test generation; no generic File/New/Other detour | Test wizard with Calculator as class-under-test. |
| 11 / image14 | JUnit form with CalculatorTest name focused | JUnit 3, JUnit 4, Jupiter (selected); source folder, package, name, superclass, BeforeAll/AfterAll/BeforeEach/AfterEach stubs, comments, class under test Calculator, Finish | Generate test at inferred/configured test location | Missing-dependency confirmation if required; otherwise test editor. |
| 12 / image16 | Jupiter absent from build path | Not now; build-path property page; perform action with Add JUnit 5 (selected) or JUnit 6; Cancel/OK | Explicit approved dependency change | Test editor only after configuration; do not label an unresolved import as a test assertion failure. |
| 13 / image9 | CalculatorTest with Jupiter imports and `@Test void test(){ fail("Not yet implemented"); }` | Normal editor/Run | A valid compiled test designed to fail | Run, then results. Import region is folded; absence of visible Test import is not proof it is missing. |
| 13 / image27 | Completed JUnit run | Result tree shows Runs 1/1, Errors 0, Failures 1; AssertionFailedError at CalculatorTest.java | Real failed assertion, not compilation failure | Return to test method and replace fail stub. |
| 14 / image10 | `assertEquals(0, new Calculator().add(0, 0));`; add underlined; test renamed testCalculatorAddition | No popup yet | Unsaved test now refers to missing method | Cmd+1 at add. Old red JUnit result remains from prior run; it is not a result for the new uncompilable revision. |
| 15 / image20 | Same missing add invocation; status explicitly says method add(int,int) undefined for Calculator | Create method add(int,int) in Calculator selected; Add cast, Rename in file, Disable test with Disabled also visible; preview shows method | Create actual target method with inferred argument and contextual return type | Generated method, linked template active. |
| 15 / image11 | Calculator generated method | Editable placeholders for public, Integer, add, both int types and i/j names; TODO body returns null | Target source modified; caller context retained | Tab / Shift+Tab between placeholders, then body; no extra method wizard. |
| 16 / image2 | Method name placeholder changed to addition | Linked editing continues | Method declaration renamed; caller must remain consistent | Continue placeholder/body editing. Screenshot alone does not show updated caller; acceptance must verify it. |
| 17 / image13 | Body selected after `return i + j;` | Normal editing, Save All, Run | Implement true addition; save changed source and calling test | Rerun current test, not previously compiled source. |
| 18 / image19 | Addition method visible; JUnit result green | Runs 1/1, Errors 0, Failures 0 | Actual passing run | Begin Maven variant. Screenshot timing is not a TurboJ performance measurement. |
| 19 / image22 | File → New submenu | Maven Project selected | Open Maven-project flow | Project coordinates/location. |
| 20 / image3 | New Maven Project wizard | Simple project / skip archetype option, default workspace/custom location, working set, Advanced, Next | Prose requires Maven layout plus pom.xml and import | Repeat same class/test/quick-fix flow. Later Maven wizard pages and Maven test run are not present in the DOCX. |

## Corrections to illustrative source and ambiguous evidence

- Page 14 prose misspells `assertTrue` and supplies arguments inconsistent with that assertion; the screenshot uses `assertEquals`. The acceptance scenario uses `assertEquals(42, new Calculator().add(20, 22))` as the corrective prompt requests.
- Page 17 prose says `return j + j`; the screenshot says `return i + j`. Test nonzero, different operands so a constant zero or doubled operand cannot pass accidentally.
- Eclipse's shown missing-method proposal chooses boxed `Integer` because of its actual overloaded JUnit context. TurboJ must infer a compatible return type from real bindings/context; a compiling primitive `int` in the corrected scenario is not evidence of a failure to copy the screenshot’s precise text.
- The screenshots use Java 25 and an argumentless `public static void main()`. The requested TurboJ tutorial can generate the conventional `public static void main(String[] args)`; this is a source-template choice, not grounds to call the screenshot invalid Java 25.
- Page 6 prose requests Cmd+Return for build; page 7 actually displays Shift+Cmd+F11 for Eclipse Run. Neither implies that Cmd+Return is an official Eclipse build binding. Preserve the selected CommandJ profile and record actual keys used.
- The default-package warning is visible in both class and test wizards. Preserve context and permit the tutorial; do not silently relocate files into an invented package.
- The Maven screenshot shows a Java-file-looking location despite its “default Workspace location” setting. Create a real directory, never a project inside a `.java` file. No Maven success state is supplied by the document itself.

## Official interaction contract

Eclipse Quick Fix targets diagnostics at the caret, includes a proposal preview, and supports creation of unresolved types/methods and adding resolvable missing imports. These are contextual source operations, not global command search. [Official Quick Fix reference](https://help.eclipse.org/latest/topic/org.eclipse.jdt.doc.user/reference/ref-java-editor-quickfix.htm).

Quick Assist shares the invocation command and acts on the caret or selection. With errors on the same line, the first invocation prioritizes fixes; a second invocation exposes assists. Rename, assign-to-local/field, extraction, and syntax transformations have context-specific applicability. [Official Quick Assist reference](https://help.eclipse.org/latest/topic/org.eclipse.jdt.doc.user/reference/ref-java-editor-quickassist.htm).

The extensibility precedent is separate quick-fix and quick-assist processors using problem identifiers and proposals that may apply AST changes or open a relevant wizard. TurboJ must retain its CommandJ/Swing architecture while exposing an equivalent provider contract rather than importing a second UI dispatcher. [Official JDT extension guide](https://help.eclipse.org/latest/topic/org.eclipse.jdt.doc.isv/guide/jdt_api_contributing_a_quickfix.htm).

The DOCX screenshots independently show macOS Cmd+1 in the popup footer and the author explicitly requests that gesture. The existing Eclipse CommandJ profile already binds `META+1` to `refactor.context-actions`; the required work is semantic implementation and native interaction verification, not merely adding that binding.

## Intentional differences and remaining parity limits

- Native forms retain the source-root/package/name and explicit dependency decisions needed by the tutorial. They do not reproduce Eclipse working sets, module-info, superclass/interfaces, all framework generations, or Maven archetype selection. The demonstrated projects use `src/main/java` and `src/test/java` in both build modes.
- The missing-class form uses inferred read-only values, so accepting it cannot accidentally create an unrelated type. The JUnit flow supports the bundled/pinned Jupiter 5.13.4 stack; Maven receives a test-scoped dependency after confirmation. Unsupported JUnit versions are not offered.
- Method generation starts at the editable body, as the corrective prompt requests, then exposes parameter names, return type and method name through Tab. Visibility and parameter-type editing remain ordinary source edits, not additional linked stops. Changing a parameter name updates references already typed into the body; changing the method name updates the caller semantically.
- Save before Run is an explicit Yes/No/Cancel decision over dirty sources. The Eclipse per-file checklist and remember-save checkbox are not implemented. Eclipse Cmd+Shift+S now invokes Save All, matching the supplied macOS screenshot and [official Eclipse File actions](https://help.eclipse.org/latest/topic/org.eclipse.jdt.doc.user/reference/ref-menu-file.htm). Save As has no Eclipse default binding. Other profiles and explicit custom bindings are preserved. The final run09 exercised the actual shortcut; run08’s palette route remains an earlier control.
- The plain and Maven native runs both returned to the caller with one navigation-back gesture, then forward to Calculator. Shift+Cmd+F11 from the implementation repeated the last JUnit launch and passed, matching page 18’s active-source behavior.
- Sponsor/browser and welcome pages on page 2 are outside the implemented native project/test path. Content assist shown on page 5 was not exercised in this acceptance run. These rows remain PARTIAL rather than borrowing evidence from another feature or older release.
- This was automated native Swing keyboard/focus testing. A human walkthrough, OS-dispatched Robot replay, physical macOS layout/IME behavior and accessibility-service interaction remain UNVERIFIED for this change.
