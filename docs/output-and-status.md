# Output and status: a study and a proposal

Status: revised and implemented (26 Oct 2026). The first version wrote every action as a Maven-style log in one
pane; in use, TurboJ's own lines (`[INFO]` headers, phases, rules, Surefire summaries, stack traces from JUnit)
buried the program's output. The current rules:

| Where | What | Never |
| --- | --- | --- |
| **Terminal** tab | What the program prints, verbatim; real commands (`:!`, Maven goals) echoed after `$ ` as a shell shows them, then their output verbatim | Headers, phases, rules, summaries, timings, notices |
| **Tests** tab | One run: its title and verdict, each suite with its tests (✔ ✖ ○ !), and detail only for problems: the failure message, the stack frames of the project's own code, compiler errors, engine errors, and what the tests printed when the run did not pass | JUnit and JDK frames, passing tests' detail, output of a passing run |
| **Status line** | Each action's outcome (`Run HelloWorld.java done · exit 0 · 67 ms (15 ms running)`, `Run HelloWorld.java failed · 1 error · src/main/java/HelloWorld.java:[3,43] ';' expected`), hints, the TESTS and AI segments; the message history keeps them | — |
| **JShell** tab | The REPL | — |

- `OutputLog` is the terminal's writer: `program` and `command` write, everything else becomes a `Notice` for
  the status line. Overlapping actions still never interleave.
- `TestOutput` feeds `TestResultsPane` (the Tests tab); a compile-only run (Compile Project) reports in the status line.
- An explicit test run shows the Tests tab; automatic runs (on save, after refactoring) update it without taking
  the panel. The tab title carries the verdict: `Tests ✔` or `Tests ✖ 2`.
- **Next / Previous Failed Test** (⌥⌘' / ⇧⌥⌘' in the TurboJ keymap, Run menu, Find Action) select the failure in
  the Tests tab, open the failing line in the editor and name it in the status line (`CalculatorTest#fails (1 of 2)
  · expected: <5> but was: <4>`); they wrap at both ends. In the tab, ↑/↓ move between tests and Enter or a double
  click opens one.
- Clear Output Before Each Run (Run menu) is off by default.

The rest of this document is the original study, kept for its inventory and reasoning; where it describes the
Maven-style log in the output, the table above replaces it.

The inventory below describes the code before the change: about 150 places wrote to the output (68 in `TurboFrame`,
26 in `MavenProjectController`, 20 in `TestOutput`, the rest in the controllers).

## Today

**One output pane receives five different kinds of text:**

| Kind | Examples | Places |
| --- | --- | --- |
| The program's own output | `Hello World!`, stack traces, stdin echo | run, tests, REPL, `:!` |
| TurboJ's log of an action | `BUILD OK \| 0 errors \| 13.35 ms`, `FAST RUN · HelloWorld`, `[exit 0 · execution 1.85 ms · round trip 4.48 ms] Completed`, `[PASSED] CalculatorTest#testAdd · 2.75 ms` | build, run, test, Maven |
| UI feedback | `Saved HelloWorld.java`, `Formatter: AOSP…`, `Imports are already organized`, `Source changed; invoke Quick Fix again`, `Workspace selected: …` | about 60 |
| Hints | `JUnit is not on this project's build path: Cmd+1 on @Test adds JUnit 5…` | about 10 |
| TurboJ's own errors | `Worker startup: …`, `Unable to start Maven project: …` | about 15 |

What this causes:
- **Program output is mixed with notices.** "Saved HelloWorld.java" or "Formatter: Google" lands between two lines
  of the program's output.
- **A run has no clear start and end.** The user's own paste shows `FAST RUN · HelloWorld` inside a test run, and
  `Hello World!` printed between "Running JUnit tests…" and the test results.
- **Every action writes in its own format.** `BUILD OK | …`, `FAST RUN · …`, `[exit 0 · …] Completed`,
  `PASSED · 1 test · 1 passed · …` and `  [PASSED] …` share no grammar.
- **Durations are written three ways:** `13.35 ms`, `execution 1.85 ms`, `round trip 4.48 ms`.
- **The same line appears twice.** The build state is printed in the output and shown in the status line.

**The status line is one label:** ` BUILD OK | Java project · hello-world`.
- The shortcuts and the details hide in a tooltip.
- It shows nothing about tests, the running program, the caret, the formatter or the keymap.
- Short feedback such as "Saved" has nowhere to go but the output.

## What Maven gets right

```
[INFO] Scanning for projects...
[INFO]
[INFO] -----------------------< dev.turboj:hello-world >-----------------------
[INFO] Building hello-world 1.0
[INFO] --------------------------------[ jar ]---------------------------------
[INFO]
[INFO] --- compiler:3.14.1:compile (default-compile) @ hello-world ---
[INFO] Compiling 3 source files with javac [debug release 25] to target/classes
[INFO]
[INFO] --- surefire:3.5.4:test (default-test) @ hello-world ---
[INFO] -------------------------------------------------------
[INFO]  T E S T S
[INFO] -------------------------------------------------------
[INFO] Running CalculatorTest
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0, Time elapsed: 0.021 s -- in CalculatorTest
[INFO]
[INFO] Results:
[INFO]
[INFO] Tests run: 1, Failures: 0, Errors: 0, Skipped: 0
[INFO]
[INFO] ------------------------------------------------------------------------
[INFO] BUILD SUCCESS
[INFO] ------------------------------------------------------------------------
[INFO] Total time:  1.234 s
[INFO] Finished at: 2026-10-26T09:12:03+01:00
[INFO] ------------------------------------------------------------------------
```

1. **Every line of the tool says who is speaking, and how serious it is:** `[INFO]`, `[WARNING]`, `[ERROR]`. What
   has no prefix is the program's own output.
2. **An invocation has a header, phases and a footer.** The `--- goal @ module ---` phases, then a fixed summary:
   outcome, total time, finished at. You can scan a long log for the separators.
3. **Results are counted the same way everywhere:** `Tests run: N, Failures: F, Errors: E, Skipped: S`.
4. **Errors point at a place:** `[ERROR] /src/main/java/HelloWorld.java:[3,18] ';' expected`.
5. **There is no chatter.** Maven never writes "Saved" or "settings changed" into a build log.

## The rules

1. **Each message has exactly one home:**
   - The **output** is the log of actions the user started: build, run, test, Maven goal, `:!` command.
   - The **status line** is the state right now, plus short feedback that fades.
   - **Problems** and **Test Results** hold the lists to work through.
   - **Dialogs** are only for decisions.
2. **Program output is verbatim and unprefixed.** stderr is in the error color; nothing is ever inserted between
   two lines of it.
3. **TurboJ's own lines follow Maven's grammar:**
   - a level prefix: `[INFO]`, `[WARN]`, `[ERROR]`;
   - `--- phase ---` headers;
   - the same summary block at the end of every action.
4. **One format for each fact:**
   - durations as `13 ms` or `1.234 s`;
   - locations as `path:[line,col]`, relative to the project and clickable;
   - counts as `Tests run: 1, Failures: 0, Errors: 0, Skipped: 0`.
5. **No duplicates.** The status line shows the state; the output shows the log that led to it.

## The output, proposed

**Run** (⌘R):

```
[INFO] ------------------------------------------------------------------------
[INFO] Run HelloWorld · hello-world · Java 25
[INFO] --- compile ---
[INFO] Compiled 3 files in 13 ms (2 unchanged)
[INFO] --- run HelloWorld.main ---
Hello World!
[INFO] ------------------------------------------------------------------------
[INFO] RUN SUCCESS · exit 0
[INFO] Total time: 4 ms (run 2 ms) · Finished at 09:12:03
[INFO] ------------------------------------------------------------------------
```

**Test** (⌘U, ⇧⌘U):

```
[INFO] ------------------------------------------------------------------------
[INFO] Test CalculatorTest · hello-world · JUnit 5.13.4
[INFO] --- compile ---
[INFO] Compiled 1 test file in 9 ms
[INFO] --- test ---
[INFO] Running CalculatorTest
[INFO] Tests run: 2, Failures: 1, Errors: 0, Skipped: 0, Time elapsed: 3 ms -- in CalculatorTest
[ERROR] CalculatorTest.testAdd:8 expected: <4> but was: <5>
[INFO]
[INFO] Results:
[ERROR] Failures:
[ERROR]   CalculatorTest.testAdd:8 expected: <4> but was: <5>
[INFO] Tests run: 2, Failures: 1, Errors: 0, Skipped: 0
[INFO] ------------------------------------------------------------------------
[ERROR] TEST FAILURE
[INFO] Total time: 52 ms · Finished at 09:13:40
[INFO] ------------------------------------------------------------------------
```

Output the tests print themselves appears under `Running CalculatorTest`, unprefixed, as in Surefire.

**Compile error:**

```
[INFO] --- compile ---
[ERROR] COMPILATION ERROR
[ERROR] src/main/java/HelloWorld.java:[3,18] ';' expected
[INFO] ------------------------------------------------------------------------
[ERROR] BUILD FAILURE · 1 error
[INFO] Total time: 16 ms · Finished at 09:14:02
[INFO] ------------------------------------------------------------------------
```

**Maven goals** (Project ▸ Maven) already print Maven's own output, which stays exactly as Maven writes it.
**`:!` commands** get the same header and footer, with the command's output verbatim in between.

Each action begins with a ruled header, so a long session reads as a list of runs. An option, **Clear Output Before
Each Run** (off by default), keeps only the latest one.

## The status line, proposed

```
 ● BUILD OK 13 ms │ ✔ 1/1 tests │ Saved HelloWorld.java              Ln 12, Col 5 │ LF UTF-8 │ Google │ TurboJ │ hello-world · Java 25
```

| Segment | Shows | Click |
| --- | --- | --- |
| State | `● BUILD OK 13 ms`, `◐ COMPILING…`, `✖ 2 errors`, `▶ RUNNING HelloWorld 0:03`, `MAVEN SYNC…` | the first error, or the output |
| Tests | `✔ 1/1 tests`, `✖ 1 failed`, or empty before the first run | Test Results |
| Message | UI feedback (`Saved HelloWorld.java`, `Imports are already organized`, `Formatter: AOSP`); it fades after 5 s | the history of recent messages |
| Caret | `Ln 12, Col 5` (with a selection: `3 lines, 42 chars`) | Go to Line |
| File | line ending and encoding | — |
| Formatter | `Google`, `AOSP`, `Eclipse`, `JavaFMT` | Code ▸ Formatter |
| Keymap | `TurboJ`, `Eclipse`… | Keyboard Profile |
| Project | `hello-world · Java 25`, or `Maven · 3 modules` | Project Structure |

The colors come from the theme's diagnostic colors: success, warning and error.

## Where today's messages go

| Today | Proposed home |
| --- | --- |
| Program stdout and stderr, test stdout, `:!` output | output, verbatim |
| Build, run and test progress and results | output, Maven grammar; the state goes to the status line |
| Compiler diagnostics | output (`path:[line,col]`) and Problems |
| `Saved …`, `Formatter: …`, `Workspace selected: …`, `Imports are already organized`, keymap and profile changes, refactoring notices ("Source changed; invoke … again") | status message, with history |
| Hints (`JUnit is not on this project's build path…`) | status message with the action's shortcut; the output only when they explain a failed run |
| TurboJ's own failures (`Worker startup: …`, `Unable to start Maven project: …`) | output `[ERROR]` when they stop an action the user started, otherwise the status message in the error color |

## Implementation, once approved

1. **`OutputLog`** replaces the direct `console.append` calls:
   - `start(action, subject)`, `phase(name)`;
   - `info`, `warn`, `error`;
   - `program(text, stderr)`;
   - `finish(outcome, timings)`.

   It writes the prefixes, rules and summaries, so their format exists once.
2. **`StatusBar`** replaces the single label: the segments above, plus `flash(text, level)` for UI feedback, with a
   bounded history.
3. **The controllers move to the new homes.** Their `notice` callbacks become `status::flash`; build, run and test
   results become `OutputLog` calls; `TestOutput` adopts Surefire's counts and failure summary.
4. **A test checks the grammar:** every line the log writes is either prefixed or program output, and every action
   ends with a summary.

## Decisions taken

1. **Prefixes:** `[INFO]` on every TurboJ line, exactly like Maven. INFO text is dimmed, so the program's own
   output stands out.
2. **Clear Output Before Each Run:** off by default, so the history stays scrollable; each action begins with a
   ruled header.
3. **Test counts:** "Failures" are failed assertions, "Errors" are engine failures, and "Skipped" adds skipped and
   aborted tests, since JUnit's report does not tell assertion failures from unexpected exceptions. Passed tests
   are counted, not listed, as in Surefire; Test Results lists them all.
