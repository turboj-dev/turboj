# TurboJ product demo — feasibility review

Date: **24 October 2026**. Application baseline: **TurboJ 1.0.10**,
`5d801f84a183cc6d6415d6e605d442818d2e3310`. Website baseline:
`4a699241fb4e321426e1a0300b8034d5bcdeaa02`.

**Recommendation:** a lightweight hybrid replay with five independently selectable
chapters belonging to one continuous Calculator project. Reuse the current HTML
editor, theme integration and playback controls. Base the new states and operation
times on a newly captured native session. Keep original captures and full logs as
evidence, available on demand. Do not embed a video or recreate a desktop IDE by
default. Spring Boot must use the existing explicit Maven goal; curl must be
identified as recorded external curl command/output.

This is an engineering proposal. **No application or website implementation was
changed, no new fixture was implemented, and nothing was deployed in this review.**
The proposed Calculator/JSON sequence has not been executed. Code contracts,
durations and implementation budgets below are proposals unless explicitly
identified as existing observations.

## 1. Current deployment and evidence boundaries

The deployment discrepancy in the request is not reproduced by this inspection.
At **21:18:19 UTC on 24 October**, verified HTTPS requests to both
[turboj.dev](https://turboj.dev/) and the supplied
[URL with utm_source](https://turboj.dev/?utm_source=chatgpt.com) returned HTTP 200,
the new 1.0.10 content and **“Four steps. One window.”** Neither response contained
“Three steps. One window.” Both were Cloudflare cache hits with
`Cache-Control: public, max-age=0, must-revalidate`.

Both 38,774-byte responses have SHA-256:

```text
1c10632a458e650e7631e87bb150a4d8f66535ebbc37327ca7a9aa8735ccdf5b
```

This matches the final artifact recorded in [website QA](../website/QA.md).
The previous deployment receipt identifies Cloudflare version
`d84aab76-e5bc-4104-b934-7ac0437d37e5`. No new deployment was needed or attempted.
The cause of the older view reported in the request is unconfirmed; this check
does not establish what every browser, search cache or CDN location previously saw.
The web search fetcher could not access the homepage; direct HTTPS retrieval did.

The hero **does** still show only an eight-second browser illustration. Its
shortcoming is narrative coverage, which this proposal addresses.

Evidence levels used throughout:

- **Inspected now:** current source, live HTML, packaged application hash, fixture
  configuration, original traces, asset sizes and current browser DOM/layout.
- **Previously executed, provenance rechecked now:** the real native session and
  acceptance results for the unchanged 1.0.10 application. These are not new runs.
- **Proposed / unverified:** the new four-operation Calculator, five-test sequence,
  completion/formatting capture, Boot/JUnit combination and JSON endpoint.

The frozen application JAR was rehashed; it still matches the captured session:

```text
87997d6395e5dc5d06f2258ea2ad9572d0f64cf00bf116237ec07dde2feaf893
```

Four existing evidence-integrity tests were rerun successfully with
`node --test tests/recorded-session.test.mjs` from `website/`. They check trace,
source, output and screenshot hashes, intervals, and retained failures. They do
not execute a JVM, establish the new endpoint, or replace native acceptance tests.
No native program, server, compiler build or new screen recording was launched
during this review.

Required references inspected:
[handoff](website-handoff-2026-10-24.md),
[1.0.10](interaction-release-1.0.10.md),
[1.0.9](maven-open-copy-release-1.0.9.md),
[1.0.8](maven-ascii-release-1.0.8.md),
[1.0.7](ecosystem-release-1.0.7.md),
[deployment](../website/DEPLOYMENT.md), [QA](../website/QA.md), and
[TODO](../TODO.md). Earlier release limitations are interpreted in the context of
later releases and actual current source, not copied forward indiscriminately.

## 2. Current website demo architecture

The website is Astro 7.4.2 with plain TypeScript/CSS and static output. It has no
hydrated application framework or Java execution environment.

| Existing file | What it actually does | Reuse / limitation |
| --- | --- | --- |
| `website/src/components/Hero.astro` | Heading, descriptor, supporting copy and demo container | Keep layout and copy; only replace the demo content when approved |
| `website/src/components/TurboJDemo.astro` | One filename, editor/gutter/cursor, bottom output, status line, Replay/Pause/Skip, original-image fallback | Reuse the surfaces; no file-tab strip or shell exists here |
| `website/src/scripts/demo-timeline.ts` | Fixed `DEMO_SOURCE`, success output and 8,000 ms event list | Generalize to chapter checkpoints, file revisions and real intervals |
| `website/src/scripts/turboj-demo.ts` | Incremental text-node rendering, one rAF clock, playback controls | Retain clock suspension and small DOM; add chapter seeking and output states |
| `website/src/styles/turboj-demo.css` | Theme variables, fixed stage geometry, scrollable source and responsive fonts | Preserve geometry; enable readable long-source/output views |
| `website/src/components/ProductScreenshot.astro` | Authentic screenshot when JS is unavailable | Keep intact; it is a historical native capture |
| `website/src/scripts/theme-preference.ts`, `theme-switcher.ts` | Independent persistent theme state, search, preview, random choice | Keep independent of playback |
| `website/src/styles/themes.generated.css`, `tokens.css` | Native palette tokens and typography | Reuse; never introduce another palette system |
| `website/scripts/export-themes.mjs` | Exports the 103 native definitions | No change required |

The current controller uses elapsed active time, not chained timers. It suspends
when the document is hidden, the editor intersection falls below 15%, or the user
pauses. Replay resets the state; Skip advances to the final result. Reduced motion
immediately renders the completed example. There is no automatic loop.

The state model is deliberately narrow: source is only a growing prefix of one
string; time cannot move backward; phases are idle/typing/compiling/running/done.
It has no project state, failing tests, file revisions or random-access chapters.
The tokenizer recognizes strings and only `public`, `class`, `static`, `void`.
Adding event records alone cannot display the requested Java, XML and JSON story.

Source uses `textContent`, not evaluated HTML. Preserve this property for captured
logs and source. Tokenize immutable source revisions once, or prepare token spans
offline; do not parse or rebuild the complete source tree every animation frame.

Reuse the layout, not outdated illustrative menu labels. The current hero's menu
strip predates the native Project/Build/Code/Refactor/Test menus. The new replay
must take menu labels and actions from its actual 1.0.10 capture, including the
native `More` overflow where captured (`app/src/main/java/dev/turboj/ui/AsciiMenuBar.java`).
Keep the outer proportions; do not invent tabs or new menu behavior to fit them.

## 3. Native capabilities and exact sources

The application has one source-editor surface with saved/unsaved buffers and an
optional Maven explorer. There is no source-editor tab strip to imitate.
`JTabbedPane` usages in details/settings do not establish source-file tabs.

| Capability | Current implementation / evidence | Consequence for the story |
| --- | --- | --- |
| New/open/save, buffer switching | `app/src/main/java/dev/turboj/app/TurboFrame.java`, command registration around 890; `openPath` around 2147; save around 2300 | Show the actual filename change and open/explorer action, not invented editor tabs |
| Maven discovery, dependency work, explorer | `app/src/main/java/dev/turboj/project/MavenProjectController.java`, import around 120–177, tree around 236 | Real directory/POM import; asynchronous resolution and configured repositories |
| POM actions | Same controller, around 532–555 | Dependency/module preview edits exist; no “Convert project to Maven” wizard was found |
| Resident compilation | `core/src/main/java/dev/turboj/project/MavenCompilationWorkspace.java`; ordinary Run in `TurboFrame.java` around 1982–2013 | Reactor compilation is project-aware; ordinary loose-file Run compiles its active snapshot |
| Loose-file dependency boundary | `core/src/main/java/dev/turboj/compiler/JavacCompilerBackend.java`, empty source path around 100–101 | Do not assume a separate loose HelloWorld caller resolves Calculator automatically |
| Program execution lifetime | `worker/src/main/java/dev/turboj/execution/WorkerMain.java`, main invocation and residual-thread handling; `worker/src/main/java/dev/turboj/execution/WorkerClient.java`, retirement/kill | Native runners are unsuitable for this persistent Spring server |
| Real Maven execution and Stop | `MavenProjectController.java` around 354–410 | Use Build → Run Maven Goal → `spring-boot:run`, with real trust/command confirmation |
| Output and stdin | `app/src/main/java/dev/turboj/ui/ConsolePane.java` | Integrated output/program input is not a shell; JShell is Java evaluation |
| JUnit | `app/src/main/java/dev/turboj/testing/TurboTestController.java`, around 92–176; `TestOutput.java`, around 47–74 | Current workspace compile, test classpath, outcomes, durations and failure text already exist |
| Save automation | `TurboFrame.java`, save completion around 2347 and pipeline around 2505–2585; `app/src/main/java/dev/turboj/config/Settings.java` | Auto Test on Save requires a successful Java-source save and a successful current compile; it defaults off |
| Formatting | `app/src/main/java/dev/turboj/formatting/FormattingController.java`, around 80–166 | Calls the existing `dev.javafmt` API asynchronously with stale-result checks |
| Semantic completion | `app/src/main/java/dev/turboj/intelligence/IntelligenceController.java`; `core/src/main/java/dev/turboj/intelligence/JdtJavaIntelligenceService.java` | Real JDT completion, independent of AI; capture a genuine accepted suggestion |
| Keyboard / themes | `commandj/commandj-keymap/src/main/java/dev/turboj/commandj/keymap/BundledKeymaps.java`; `app/src/main/resources/themes/catalog.json` | Pick and record one keymap; changing the website theme must not change action labels or progress |

Line numbers refer to this review's checkout and are navigation aids, not API
contracts. Source paths must be checked again when updating to another release.

Existing native controls already show the Spring-specific limitation: main returns
after `SpringApplication.run`, residual server threads cause worker retirement,
and the endpoint closes. Both Fast Run and Isolated Run exhibit this behavior.
The explicit Maven path successfully kept the server alive through an actual HTTP
request. Do not add a keep-alive loop to the fixture or change the IDE to hide this.

Auto Run on Save, Auto Test on Save and Auto Test on Changes are separate settings.
For the test chapter, explicitly enable **Auto Test on Save** and leave the other
two off. The source contains a 150 ms coalescing timer; this is not a guaranteed
save-to-result time and must not be displayed as one.

## 4. Feasibility of the five chapters

| Chapter | Feasibility | What still requires proof |
| --- | --- | --- |
| Hello World | Directly supported by native editor/compile/run | Capture the exact requested `Hello TurboJ!`; the old recorded source prints `Hello world! TurboJ`, so do not relabel its output |
| Calculator | Editing, formatting and semantic completion exist | The supplied four-method class has no `main`. Add a small demonstration main inside that file, then capture real completion, formatting and Run |
| Maven + JUnit | Effective import, native JUnit and save automation are supported | New five-test suite, actual dependency graph, expected single failure and correction must run in this new project |
| Spring Boot | Existing Java 21/Boot project imports and runs through explicit Maven | New controller must reuse the same Calculator; validate POM change, test dependencies, main selection and HTTP readiness |
| curl | External curl was already executed against the old Spring fixture | Capture the new exact endpoint and JSON. The browser must not make a live localhost request |

The existing evidence is **Hello World → Price/two tests → Rename/Extract Method
→ save automation → Spring `/hello` text response**. It has no captured explicit
FormatJ action or IntelliSense acceptance. It is useful infrastructure and prior
feasibility evidence, not footage or timings for this different story.

The new main narrative should omit the old standalone refactoring chapter. Its
multiple dialogs were a source of abrupt switching and it is not one of the five
requested stages. Refactoring remains documented elsewhere on the homepage; a
separate optional chapter can be evaluated later without crowding this sequence.

## 5. Blockers, important issues and mitigations

Here **BLOCKER** means a decision or evidence gate that must be resolved before
implementing/publishing the corresponding replay as real. It does not mean every
item needs an IDE feature or prevents preparatory fixture validation.

| Severity | Problem | Recommended resolution / release gate |
| --- | --- | --- |
| **BLOCKER** | The new complete story has no captured evidence | Validate the fixture and record its native commands before building its public timeline; no substituted successful output |
| **BLOCKER** | Calculator as supplied is not executable | Add a tiny same-file demo main in the proposed fixture, or move Maven import earlier. Recommend the same-file main |
| **BLOCKER if required** | Spring through native Fast Run terminates after main | Choose the existing explicit Maven goal. A native-Run-only requirement cannot be met honestly within this website scope |
| **BLOCKER for recording** | Boot changes the managed JUnit generation | Freeze an aligned test dependency set and prove TurboTest plus real Maven tests still work after Boot is added |
| **IMPORTANT** | Multiple main classes make selection ambiguous | Configure Boot plugin `mainClass`; record the selected ordinary Run target where needed |
| **IMPORTANT** | No automatic Maven conversion wizard | Introduce a real prepared/manual POM and import it; label any pre-created directory layout |
| **IMPORTANT** | No native shell or editor tabs | Chapter navigation is website UI; filename/explorer reflects native UI. curl is an explicitly external transcript |
| **IMPORTANT** | Old “unmeasured gap” is not necessarily human idle | Only cut explicitly classified typing/reading/navigation pauses; preserve unknown and system intervals |
| **IMPORTANT** | Cold downloads and wrapper startup are variable | Main story uses a labeled warm artifact cache; retain separate cold evidence and network conditions |
| **IMPORTANT** | POM synchronization can invalidate source/test generations | Wait for settled model and current compilation; record generation IDs and reject stale captures |
| **IMPORTANT** | A different server may already own the demo port | Check it before launch, verify the owned process and response, and stop only the owned process |
| **IMPORTANT** | Full code and failure logs do not fit mobile | Focus real source lines and a labeled output excerpt; retain full selectable source/logs |
| **IMPORTANT** | Many file/dialog switches obscure continuity | Use one project identity, a small active-file label and clear action/result holds; keep evidence dialogs optional |
| **MINOR** | Historical deployment documentation can lag the receipt | Report live hashes and deployment ID alongside sources; this review does not edit deployment records |
| **MINOR** | Screenshot font/palette differs from adaptive HTML | Label reconstruction; never recolor or retouch the original capture |

## 6. Comparison of implementation approaches

| Criterion | A — Extend HTML replay | B — Native screen recording | C — Hybrid, recommended |
| --- | --- | --- | --- |
| Existing code reuse | High: editor, clock, controls, theme | Low beyond container/controls | High: HTML remains the main renderer |
| Native visual fidelity | Depends on careful reconstruction | Highest for the recorded viewport | Exact evidence for complex interactions; adaptive text for the main story |
| All 103 themes | Native CSS variables apply | Pixels retain the recorded theme | HTML follows themes; original evidence retains its theme |
| Mobile readability | Strong with focused source and output | Full-window recording becomes tiny; crops need separate edits | Focused HTML, optional original full-resolution view |
| Accessibility | Text, keyboard controls and transcripts | Requires captions/transcript and meaningful descriptions | Text-first with accessible evidence/transcripts |
| Payload / CPU | Can remain small and bounded | Video size/decoding must be measured after encoding | No video preload by default; evidence lazy-loaded |
| Main risk | Accidentally building a second IDE or inventing UI | Hard to follow small text; fixed theme; cutting obscures timing | Too many renderer switches if not tightly constrained |
| Maintenance | Schema, rendering and capture fixtures | Re-record and edit affected footage | Refresh changed chapter/checkpoint and its evidence |
| Effort estimate | Medium-high: state generalization and fidelity | Capture is easy; legible edit/accessibility is substantial | Medium: narrow state extension plus evidence pipeline |

Choose **C**, defined narrowly: HTML for source, filename, current action and output;
source-grounded snapshots for menus/completion/formatting/test states; original
captures as optional reference. A short authentic clip is an optional later
alternative, not a required asset or a default switch midway through playback.
There is currently no video recording. Its size, codec quality and mobile
readability cannot be measured until one exists.

## 7. Recommended interaction and state model

Use **one story, five seekable chapters**, not five unrelated recordings. Keep
source hashes at each chapter's start/end. Calculator and its test contract carry
forward unchanged except for the deliberate fault and its correction. Prefer one
full nominated capture, with chapters derived from that run. Independent chapter
refreshes must declare their own session IDs and preserve checkpoint hashes; do
not imply a single measured session if later editions combine runs.

The first short chapter may autoplay once when visible and reduced motion is off.
It then holds its result. Continuing is explicit through Next or a chapter choice.
An optional **Play story** action can traverse all five in order. Replay restarts
the current chapter; Previous/Next select adjacent initial checkpoints; Skip to
Result explicitly seeks to the current chapter's result. Final results remain
visible, with no loop. Direct Spring/curl entry must restore all relevant context.

Keep chapter controls outside the simulated native chrome. Show the active native
filename and, when useful, a small captured project context. Do not add fake IDE
tabs labeled Calculator/Test/POM. At curl, change the provenance label once to
**External curl — recorded command/output**; keep the same overall demo footprint, but do
not place the transcript under a TurboJ console label.

Suggested data contract, not implemented here:

- Immutable session metadata: application hash, source fixture hashes, JDKs,
  keymap, theme, cache state, OS/hardware and capture method.
- Chapter checkpoints: source revision references, active file, actual line/caret
  or scroll context, console revision, relevant model/test generation.
- Typed events: source edit, file activation, captured command, waiting,
  diagnostics, test result, external request and result hold.
- Operations: monotonic start/end, actual output reference and measurement scope.
- Editorial map: source-time interval, playback-time interval, classification and
  reason for every cut. Unknown gaps are retained.
- Capture references/checksums and full transcripts, separate from initial payload.

The reducer must reconstruct any chapter/state from a checkpoint without replaying
earlier chapters. Store source revisions once and reference them; do not duplicate
every file and log on every frame. Render only the active file and output surface.
Reject stale chapter-loading responses after rapid selection so an earlier fetch
cannot replace the currently selected chapter.

Retain one elapsed-active-time clock and binary/event-index lookup. Pause, hidden
document, offscreen state and required asset loading suspend that clock together.
A load delay is never counted as native execution. Theme preview is independent:
it must not implicitly pause, reset, seek or skip the story. A paused state stays
unchanged; active playback continues according to the same elapsed-time clock.

## 8. Storyboard and duration policy

**Editorial target: about 56 seconds**, with roughly 45–65 seconds plausible after
capture. This is a reading/navigation budget, not measured new operation time.
The table's durations include intended result-reading holds. Replace estimates
with the captured intervals and final editorial map before publication. If an
operation exceeds the allocation, lengthen the chapter; do not speed it up to hit
60 seconds. Cold import does not have a credible fixed duration target.

In the table, **capture** means a required new recording, not evidence already
acquired; **presentation** means clearly identified browser reconstruction.

| Chapter / target duration | Visible file | Source change | Native action | Actual output/event required | Transition | Capability / provenance |
| --- | --- | --- | --- | --- | --- | --- |
| 1. Hello World — 7 s | `HelloWorld.java` | Empty file → exact supplied program | New/Save, Compile, Run | Compiler result plus `Hello TurboJ!` and exit 0 | One source surface, then output below; hold result | Native editing/run; new capture, condensed typing presentation |
| 2a. Calculator — 6 s | `Calculator.java` | Four integer operations and small demo main; complete `calculator.a…` in main | Invoke/accept actual semantic suggestion, Format Document | Captured suggestion/acceptance and formatted source | One brief suggestion view, then same-file formatting change | Semantic completion/FormatJ; new capture, no AI substitution |
| 2b. Calculator — 3 s | Same file | No additional logic | Compile/Run same-file main | Actual addition result 42 and successful exit | Keep file; update bottom pane | Fast development path; captured result |
| 3a. Maven/JUnit — 6 s | `pom.xml` → `CalculatorTest.java` | Introduce prepared Maven layout and Jupiter config; five tests | Open POM/import, wait for settled dependencies, Run All Tests | Warm-cache import state; five actual passing tests | One POM-to-test switch with project identity retained | Maven model and JUnit; capture, cache condition visible |
| 3b. Failure — 5 s | `Calculator.java`, focused `add` | Deliberately change addition to subtraction | Enable Auto Test on Save; save Java source | Current compile then one failed test, expected 42 vs actual −2 | Stay on faulty source; show copied failure location below | Real automatic failure after Save; capture |
| 3c. Correction — 5 s | Same file/lines | Restore `a + b` | Save; no manual Run Tests command | Current compile then five passing tests | Red → green via real result; readable hold after completion | Verified automatic rerun; capture |
| 4a. Spring configuration — 6 s | `pom.xml` → `CalculatorApplication.java` | Boot parent/starter/plugin; plain application main | Save POM, settle dependency/model refresh | Real dependency status, Java 21 and any PARTIAL-build notice | One explicit configuration step; full POM available separately | Maven evolution; new capture with warm-cache label |
| 4b. Controller and startup — 12 s | `CalculatorController.java` | Endpoint delegates to the same Calculator | Build → Run Maven Goal → `spring-boot:run`; confirm trust/command if required | Actual Maven/framework logs; observed HTTP readiness | Focus controller, then stable bottom output; avoid cycling dialogs as full-screen slides | Real Spring process via Maven; capture, system interval unaccelerated |
| 5. curl — 6 s | External terminal transcript | None | Real bounded external curl request | HTTP success, JSON `{"result":42}`, exit 0 | One clearly labeled external view; hold final response | Recorded HTTP request; browser reconstruction, not live localhost access |

Do not replay the entire import/dialog/explorer choreography at every chapter.
Keep necessary actions and provenance, but shorten only deliberate human reading,
typing or navigation pauses. A fast result can appear at its real time and then
remain on screen for readability; the extra reading hold is outside its timer.

The old native session spans **68.354524 seconds**, with 61 states and 18
nonoverlapping measured intervals totaling **7.470034 seconds**. The remaining
60.884490 seconds includes presentation pauses **and** capture/UI bookkeeping; it
cannot all be removed as idle time. The old refactoring chapter consumes about
22.019 seconds and is not required in the proposed narrative. None of these
numbers is the duration of the new Calculator story or a human development session.

Existing `prepare-session.mjs` can cap arbitrary unmeasured gaps. Before reuse it
must require explicit human/editorial interval classifications and preserve
unknown waits. The published old recording has no cuts; its raw evidence remains
unchanged. At 1×, every measured system interval keeps its original duration.

## 9. Minimal reproducible fixture design

Preserve `examples/plain-java/`, `examples/maven/` and the frozen website evidence
fixtures. The user-facing calculators currently use static add and floating-point
division with `IllegalArgumentException` on zero. The requested Calculator uses
instance methods, integer division and Java's `ArithmeticException`. Silently
changing the old examples would alter their contract and historical evidence.

Add **one** canonical fixture when implementation is authorized:

```text
website/demo/projects/calculator-story/
  README.md
  .turboj/source-roots.txt
  .mvn/wrapper/maven-wrapper.properties
  mvnw, mvnw.cmd, MAVEN-WRAPPER-LICENSE
  pom.xml
  stages/                         # small source/POM deltas and checkpoint hashes
  src/main/java/HelloWorld.java
  src/main/java/dev/turboj/demo/Calculator.java
  src/main/java/dev/turboj/demo/CalculatorApplication.java
  src/main/java/dev/turboj/demo/CalculatorController.java
  src/main/resources/application.properties
  src/test/java/dev/turboj/demo/CalculatorTest.java
  verify-http.sh                  # or equivalent capture-driver verification
```

Materialize each stage in the **same isolated workspace outside this checkout**,
with private TurboJ preferences. Do not expose the future Boot sources before their
stage. Reuse the existing source-root convention and verified wrapper/licensing;
do not duplicate five complete project trees. Running inside this checkout risks
inheriting its ancestor `.mvn/maven.config`.

### Versions and dependency contract

Conservative initial candidate: **Java 21, Boot 4.1.1, Maven 3.9.11**, matching the
existing successfully captured Spring fixture. Boot 4.1.1 is a real released
version, not a snapshot. [Official release announcement](https://spring.io/blog/2026/08/20/spring-boot-4-1-1-available-now/)

It is not the latest patch: the current requirements page identifies Boot 4.1.2,
Java 17–27 and Maven 3.6.3 or later. That page supports the proposed toolchain
choice, not proof of this new fixture. If adopting 4.1.2, pin it and repeat native
and Maven checks before capturing; do not silently update the frozen evidence.
[Official system requirements](https://docs.spring.io/spring-boot/system-requirements.html)

| Item | Proposed pin / decision |
| --- | --- |
| Demo coordinates | `dev.turboj.demo:calculator-story:1.0.0`; no snapshot dependencies |
| Language/runtime | Java 21; TurboJ itself remains on its bundled Corretto 25 |
| Maven wrapper | 3.9.11, reuse SHA-256 `0d7125e8c91097b36edb990ea5934e6c68b4440eef4ea96510a0f6815e7eeadb` |
| Pre-Boot plugins | Compiler 3.14.1, resources 3.3.1, Surefire 3.5.4, matching the existing Maven example |
| Test platform | Jupiter/BOM 5.13.4 and matching Platform launcher 1.13.4, scope test |
| Boot stage | Parent and `spring-boot-maven-plugin` 4.1.1; `spring-boot-starter-webmvc` from that parent |
| Boot entry point | Explicit `dev.turboj.demo.CalculatorApplication` in plugin `mainClass` |
| Source encoding | UTF-8 |

The exact cached Boot 4.1.1 dependency POM manages Jupiter **6.0.3**. TurboTest
bundles Jupiter **5.13.4** / launcher **1.13.4**, and `JUnitRuntime` injects the
bundled launcher when the project's classpath lacks one. Pinning only the
`junit-jupiter` aggregate could leave mismatched transitive platform artifacts.
For this candidate, retain a full JUnit 5.13.4 BOM, set Boot's
`junit-jupiter.version` accordingly, and include the matching launcher explicitly.
Validate the effective dependency tree and both runners after introducing Boot.
This is a proposed compatibility strategy, not a verified Boot/JUnit result.

No `spring-boot-starter-test` is necessary for five business-logic tests. No database,
security starter, devtools, Docker, frontend or AI is required. Wrapper checksum
behavior is documented by [Apache Maven Wrapper](https://maven.apache.org/tools/wrapper/).

### Source and test contracts

Keep the four supplied integer methods. Add a brief `main` in Calculator that
creates a Calculator and prints `add(20, 22)`. This is a proposed fixture addition,
not an IDE feature. A same-file main avoids the loose-file cross-source assumption.
The final Maven project may retain HelloWorld and Calculator mains because the
Boot plugin explicitly selects its application entry point.

Five ordinary `@Test` methods:

| Test | Expected assertion |
| --- | --- |
| Addition | `add(20, 22) == 42` |
| Subtraction | `subtract(10, 3) == 7` |
| Multiplication | `multiply(6, 7) == 42` |
| Integer division | `divide(7, 2) == 3` |
| Zero divisor | `divide(1, 0)` throws `ArithmeticException` |

The deliberate fault changes only `add` to subtraction. Expected display:
one failed test (42 versus −2), then five passing tests after correction. These
are expected assertions for the future capture, not fabricated test-runner output.

Use a normal `@SpringBootApplication` main and a separate `@RestController`.
The controller holds a Calculator instance, uses
`@GetMapping("/api/calculate/add")`, explicit `@RequestParam("a")`/`("b")`,
and returns `Map.of("result", calculator.add(a, b))`. Explicit parameter names
avoid depending on compiler parameter metadata. The expected JSON is
`{"result":42}`; it has not been obtained from this new application yet.

Bind to loopback. Prefer the proposed port 8080 only if free. If another process
owns it, fail the capture or deliberately configure another port and show that
actual address in every command; never kill an unrelated process. Keep startup
identity logging configured to avoid account/path disclosure before recording.

### Build and verification sequence for the future fixture

1. Materialize stages in a fresh external workspace, record all source/POM hashes.
2. Exercise HelloWorld and the same-file Calculator main in the actual bundled app.
3. Capture a real semantic completion/acceptance and explicit Format Document.
4. Import the JUnit POM; wait for a settled effective model and complete classpaths.
5. Run five tests through TurboTest and through `./mvnw clean verify` on Java 21.
6. Enable only Auto Test on Save; verify the unsaved fault does not start it, then
   save/fail/correct/save/pass with current source generations.
7. Introduce Boot, inspect effective POM/dependency tree and rerun both test paths.
8. Start with TurboJ's explicit Maven goal. Officially, this goal runs the
   application in a separate process. [Spring Maven run documentation](https://docs.spring.io/spring-boot/maven-plugin/run.html)
9. Bounded readiness polling must observe HTTP 200 and parsed JSON with integer
   `result == 42`, while the owned Maven/application process remains alive.
10. Execute the real external `curl -fsS` request, validate stdout, JSON and exit 0,
    then Stop Maven and confirm the owned process/endpoint has stopped.

Use a command deadline and HTTP connect/overall timeout in the verification driver.
The exact recorded command, including any timeout flags or port override, must be
shown honestly. Repository credentials/settings are not copied into the fixture.

## 10. Capturing real logs and measurements

Reuse `website/demo/capture/SessionCapture.java`, `SpringCapture.java` and their
EDT capture/command helpers. Preserve the original drivers/evidence for historical
reproduction; add a focused story driver instead of rewriting the old trace.
The existing capture paints the real Swing frame on the EDT. It is not a video or
a physical-display scanout measurement. Record a video only if useful and available,
with no audio/desktop/private windows; never claim one exists when it does not.

First run functional controls, then nominate a complete representative session
before inspecting its times. Preserve failed controls with explanations. Do not
pick the fastest compile, test and startup from different runs for one timeline.

| Event | Sources / input | Measurement start | Measurement end / validation |
| --- | --- | --- | --- |
| Save | Active file/revision and target path | Before dispatching actual Save | Successful disk write/current buffer publication; retain failure if any |
| Compile | Source hashes, Java level and classpath generation | Before Compile dispatch | Current success/diagnostic result and EDT paint checkpoint; record cache hit separately |
| Run | Main, args and compiled revision | Before Run dispatch | Real process/worker completion and visible output; keep internal execution time separately |
| Completion | Exact source/caret and request | Before semantic request | Actual suggestion visible; acceptance/source change is a separately identified action |
| Format | Before source/version and selected region | Before Format Document dispatch | Applied current formatting result; retain before/after text and undo contract |
| JUnit | Five test methods and fixture sources, dependency graph and generation | Before Run All Tests | Complete current suite result, counts, durations and stack trace; separate runner duration from UI elapsed time |
| Auto Test | Fault/correction source, save and setting state | Before Save | Successful current compile followed by complete corresponding test result; do not infer the trigger delay |
| Maven import/refresh | POM/settings/profile/toolchain fingerprint and cache conditions | Before actual open or configuration-save action | Settled workspace with dependency status, classpaths and model generation, then EDT paint |
| Spring startup | POM, selected JDK, main class, goal, owned PID | Before final Maven command confirmation | First observed successful endpoint response plus checkpoint, not just a “Started” log |
| curl | Actual URI/command, owned running server | Before external process start | stdout EOF, JSON/status verification and process exit; record UI presentation separately |
| Stop | Owned lifecycle ID/PID tree | Before Stop Maven | Owned process exit and endpoint closure |

Use `System.nanoTime` for relative intervals and `Instant.now` only to correlate
wall timestamps. Record nominal polling interval, timeouts, capture overhead,
OS/CPU/RAM, TurboJ JAR hash, project JDK, Maven/Boot/JUnit versions, trust state,
artifact-cache state and complete raw outputs. A warm dependency cache differs
from a cached MavenJ model and from a warm compiler JVM; record all three.

For cold import, use an isolated empty local repository in a separate control,
never clear the user's repository. Record model/JAR resolution and wrapper/plugin
downloads separately; wrapper startup belongs to the explicit Maven path. The
short story can show a warm import with **“Dependencies already cached”**. Do not
show an instant download animation. A separate cold record can explain first use
without dominating the hero or making a network-time promise.

The old session provides feasibility observations only:

| Old-session event | Elapsed |
| --- | ---: |
| Explicit Hello compile, potentially warmed cache | 18.186 ms |
| Hello Run | 38.539 ms |
| Compile/run two Price tests | 282.053 ms |
| Fault Save → compile → failing Auto Test | 251.011 ms |
| Correction Save → compile → passing Auto Test | 260.504 ms |
| Maven open → settled model → paint, cached artifacts | 574.854 ms |
| Explicit Maven start → observed `/hello` readiness → paint | 2,777.520 ms |
| External curl process including startup | 18.132 ms |
| curl plus capture checkpoint | 21.507 ms |

The old Spring log's own startup duration and MavenJ's final 8.187 ms cache-hit
publication are narrower measurements. Neither is the full command-to-result
interval. See [original Spring measurement boundaries](../website/demo/capture/SPRING_SESSION.md).

## 11. Credibility and labeling

Use distinct labels, all conditional on having the corresponding evidence:

- Persistent hero caption: **“Recorded TurboJ session, reconstructed in the
  browser. No Java runs here. Typing and reading pauses shortened.”**
- Timing detail: **“Times from this captured session”**, with hardware/cache/method
  available from one compact evidence link.
- Native operation counter: only its recorded duration, unchanged at 1×. Result
  reading holds do not keep the counter running.
- Warm import: **“Dependencies already cached”**. Explicit Maven startup:
  **“Maven goal: spring-boot:run”**.
- Request view: **“External curl — recorded command/output”** and
  **“No request is sent from your browser.”**
- Describe a Terminal window as recorded only if it was actually captured. The
  existing harness records an external process's command/output, not terminal footage.
- Optional screenshot/clip: **“Original native capture · Turbo Classic”**.
- Any storyboard before validation: **“Concept preview · Not captured execution”**.
  Do not use this concept label to pass invented successes as finished evidence.

HTML colors, responsive framing and condensed typing are presentation. Source,
commands, test outcomes, HTTP output and operation times must come from captures.
If output is shortened, label it **excerpt**, preserve original order/line text,
and link the complete log. Do not erase warnings or failure text to imply a cleaner
native result. Do not claim HTML reconstruction is a pixel-identical recording.

## 12. Mobile, accessibility and theme behavior

Preserve the existing stage heights: **380 px mobile, 464 px desktop/tablet,
510 px wide desktop**, and code sizes **14/16/18 CSS px**. Current required
viewports already fit the whole stage in the first screen; retain that regression
check. At 390 px the source viewport is about 182 px high with 23.1 px lines:
roughly seven full lines. A Calculator, POM or test suite cannot fit in full.

Use actual line numbers and a stable focused viewport around the changed method,
assertion or annotation. Keep full selectable source behind a clearly labeled
transcript/details action. Allow keyboard/touch scrolling without shrinking text.
Long Java lines should scroll horizontally; wrap output/JSON safely. A POM excerpt
must be explicitly partial and point to the full file.

Keep the bottom pane stable, with a few actual result lines plus failure context.
Use the current theme's success/error colors **and** explicit PASSED/FAILED text.
Do not squeeze a source view, explorer, logs and shell into four mobile panes.
When an action needs more vertical context, let the reader open its evidence.

Desktop: a small chapter row plus playback controls. Mobile: compact chapter
selector/current “3 / 5” label and Previous/Play/Next, using the existing control
space as far as possible. Skip and Replay must remain reachable without opening
a faux native menu. Avoid increasing the stage or reducing text to fit controls.

All controls need keyboard focus, accessible names and stable focus after seeking.
Use actual buttons/selects; arrow navigation must not hijack source scrolling.
Announce chapter/action/outcome changes politely, not every character or log line.
Reduced motion presents static checkpoint/result cards with manual navigation;
it must still expose both the failing test and correction. Without JavaScript,
show authentic fallback imagery and links to source/transcript evidence.

Preserve the existing 103-theme export, picker, preview/cancel, random selector and
preferences. Theme changes affect HTML/CSS tokens only, not file state, progress,
keymap, counters or results. Original PNG/video colors remain unchanged. A real
Classic capture must not be digitally recolored to pretend it was recorded in a
different native theme.

## 13. Performance baseline, scope and budgets

Measured from the current unchanged production output during this review:

| Asset / structure | Current measurement |
| --- | ---: |
| Hero JS bundle | 6,166 bytes raw / 2,582 independently gzipped |
| All four initial inline/module JS scripts | 28,503 raw / 10,712 independently gzipped |
| Derived recorded-session JSON, not loaded by current hero | 158,401 raw / 14,172 gzip |
| Original trace JSON | 138,165 raw |
| 61 native PNG captures | 4,230,234 bytes total |
| Entire existing public demo evidence | 4,550,852 bytes |
| Current 390 px page / hero DOM | 455 / 73 elements; 11 source elements for five lines |

Gzip values are asset accounting, not measured CDN transfer totals; inline scripts
compress within HTML. The existing 61 captures must not become eager hero assets.
The current local Lighthouse result is 100 in all four categories, LCP 1,656.982 ms,
CLS 0.000384 and TBT 0; it is a prior local lab result, not a measurement of the
proposed expanded demo or field performance.

Proposed acceptance budgets, to measure rather than promise:

- Hero controller/model **≤8 KiB gzip** and all initial JS **≤18 KiB gzip**.
- Only first-chapter data initially; chapter text data **≤30 KiB gzip** in total
  after optional loading, excluding separately requested evidence.
- No autoplay video download; no bulk PNG loading. At most the current/next needed
  capture is prefetched after an explicit chapter continuation.
- Bounded active source/output DOM, target **≤250 demo elements** and no growth
  across 100 chapter/replay switches. Full transcripts may be on the evidence page.
- No steady work when paused/offscreen/hidden; no per-frame source tokenization or
  full-document replacement. Use a single rAF loop and stable line/token nodes.
- No unbounded transcript or decoded-image cache. Release old optional captures;
  measure heap after repeated switches rather than guessing total memory use.
- Re-run the same Lighthouse conditions with all runs retained. Keep existing
  budget of Performance/Accessibility ≥95, LCP <2.5 s, CLS <0.1; investigate any
  material regression against a same-machine baseline. Also inspect scripting
  long tasks during playback; navigation-only Lighthouse does not measure INP.

Estimated implementation scope: **roughly 6–10 engineering days**, conditional on
fixture compatibility and capture access, not a delivery date. Allow 1–2 days for
fixture/dependency verification, 1–2 for reproducible capture and editorial data,
2–3 for narrow replay/control extensions, and 2–3 for mobile/accessibility/timing
and performance verification. These activities can overlap; native defects or
new compatibility failures would change the estimate. No frontend framework or
IDE production work is included.

## 14. Exact proposed file changes

Only this analysis document is being created now. Future modifications should be
limited to the following demo/evidence surfaces; there is no homepage copy rewrite.

| Future path | Proposed work |
| --- | --- |
| `website/src/components/TurboJDemo.astro` | Chapter controls, captured native menu labels, action/provenance label, structured source/output states, transcript/evidence access |
| `website/src/scripts/demo-timeline.ts` | Typed chapter/checkpoint model, seeking and preserved measured intervals |
| `website/src/scripts/turboj-demo.ts` | Multi-file revision rendering, actual failure/result states, chapter controls and one suspended clock |
| `website/src/styles/turboj-demo.css` | Compact controls, real-line excerpts/scrolling, test statuses and external-terminal distinction, preserving stage sizes |
| `website/src/data/demo-chapters.json` **new** | Lightweight chapter manifest/revision references; no fabricated capture data |
| `website/scripts/prepare-session.mjs` | Generalize the hardcoded old chapter order/menu bounds/project tree; classify cuts and validate checkpoints/evidence |
| `website/demo/capture/CalculatorStoryCapture.java` **new** | External capture driver reusing existing native capture helpers |
| `website/demo/capture/README.md` | New reproduction, isolation, clock/cut and port ownership instructions |
| `website/demo/projects/calculator-story/` **new** | Exactly the canonical source/configuration paths listed in section 9 |
| `website/scripts/package-demo-projects.py` | Include/version the new fixture while retaining old reproducible sources |
| `website/public/demo/calculator-story/` **new** | Immutable trace, manifest, source/checkpoint data, real captures and curated chapter data after validation |
| `website/public/demo/projects.zip` | Regenerated reproducible examples archive |
| `website/src/pages/session.astro` | Identify session edition, new chapter sources/conditions, measured intervals/cuts and actual limitations |
| `website/tests/demo-timeline.test.mjs` | Seek/replay/checkpoint equivalence, invalid states and timing-map invariants |
| `website/tests/demo-browser.mjs` | Five chapters, file/output flow, motion/keyboard/suspension and unchanged stage fit |
| `website/tests/recorded-session.test.mjs` | Keep old integrity checks; add new edition checks or split by fixture without replacing historical assertions |
| `website/tests/session-browser.mjs`, `themes-browser.mjs` | Expanded transcripts/downloads, mobile overflow, all primary palettes and state preservation |
| `website/tests/content.test.mjs`, `copy.mjs` | Replace illustration-specific assertions only when real capture labels become justified |
| `website/README.md`, `QA.md` | Reproduction, actual final verification and measured bundle/performance results |

Keep `Hero.astro` outer layout, `global.css`, navigation, features, ecosystem,
Maven section, performance capture, FAQ and roadmap unchanged unless a demonstrated
demo layout defect requires a narrowly documented adjustment. No changes are
planned to `app/`, `core/`, `worker/`, MavenJ, TurboTest, RefactorJ, FormatJ or
CommandJ. Existing user edits in `examples/` remain untouched.

## 15. Objective acceptance criteria

1. The new fixture compiles on the selected Java 21 toolchain. Both actual
   TurboTest and `./mvnw clean verify` pass all five business tests before and after
   Boot integration; resolved Jupiter/Platform versions are coherent.
2. Calculator Run really executes its declared main. Semantic suggestion acceptance
   and Format Document are captured in the unchanged native application.
3. Exactly the intended test fails after the faulty Java save. Correction Save
   produces a current successful compile and five passing tests without a hidden
   manual test command. Unsaved edits do not trigger the chosen Save option.
4. The Spring controller imports and calls the same Calculator source. Maven
   launches the configured main; HTTP readiness is checked separately from logs.
   Actual external curl returns the expected parsed integer result and exit 0.
   Stop Maven terminates only the owned server.
5. Every presented source/output/result has a real capture reference and checksum.
   Each time has a recorded boundary. Unknown/system intervals are never cut.
   Any shortened human interval is listed; times are not combined as one session.
6. Direct entry into all five chapters, Previous/Next, Replay, Skip and Play story
   produce deterministic valid states; stale chapter loads cannot overwrite newer
   selections. Final states hold. Theme changes never reset, seek or skip playback:
   paused state stays identical; playing state advances only with active elapsed time.
7. At 390×844, 768×1024, 1440×900 and 1920×1080, the existing stage fits and remains
   readable; also retain the 320 px overflow check. Long filenames, five test
   results and expanded evidence introduce no horizontal page overflow.
8. Keyboard-only, reduced-motion, no-JS fallback and readable status text work.
   Automated axe checks pass with controls and transcripts expanded; perform
   manual keyboard/visual checks and add Safari verification on the target Mac.
9. At 1×, measured results become visible only at their mapped event times. Browser
   frames may render them later; no millisecond paint-accuracy guarantee is made.
   Hiding the tab or leaving the viewport pauses the same clock without skipping.
10. Measured payload/DOM/CPU/memory satisfy section 13 budgets, with no new third-party
    requests, analytics, framework, autoplay audio or real localhost fetches.
11. The authentic screenshot, all 103 themes and other homepage sections remain
    functional. No new IDE feature or production modification is needed for the demo.
12. A future deployment, only after authorization, verifies real production HTML,
    asset hashes and browser behavior. A Git push alone is insufficient evidence.

## 16. Recommended implementation sequence

1. **Freeze the decisions:** five chapters, integer Calculator with same-file demo
   main, Java 21, aligned JUnit 5 dependencies, explicit Maven Spring launch,
   external curl, warm-cache hero and no invented native tabs/terminal.
2. **Validate the canonical fixture first:** prove the main, five tests, completion,
   formatting, POM transition, dependency graph and actual JSON endpoint. Resolve
   those gates before producing public replay states.
3. **Capture one representative continuous story:** retain all logs/failures,
   checkpoints and measurement scopes; derive chapter boundaries and explicit cuts.
4. **Extend the existing renderer narrowly:** stable active-file/source/output view,
   small chapter controls, no repeated full-window dialog slides. Original captures
   remain optional evidence. Assign final duration from the captured data.
5. **Verify and review:** native fixture controls, provenance/timing checks, mobile,
   accessibility, themes and performance. Publish only after a separate decision.

The main technical question is resolved: this story is feasible with current
TurboJ capabilities and the explicit Maven path. The outstanding work is a new
verified fixture/capture and a focused replay extension, not another homepage
redesign or a new native terminal/server runner.
