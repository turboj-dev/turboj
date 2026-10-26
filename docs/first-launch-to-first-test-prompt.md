# First launch to first tested Java program — working prompt

This file holds the full assignment prompt (below) and the order in which we implement and verify it.
We work **one step at a time**: each step is implemented, then tried in the real TurboJ window, before the next one starts.

Reference document (not in the repository): `~/Downloads/TurboJ - First Launch.docx`, 20 pages, Eclipse as the interaction reference.

## Decisions that refine the prompt

These come from the product owner and take precedence over the prompt text where they differ.

- **Launch without a project shows an empty screen**, never a sample buffer such as `Hello.java` / `HelloWorld.java`.
- **A new project is created inside the selected workspace.** Project name `HelloWorld` → directory `~/Workspace/hello-world`
  (the prompt's `~/Documents/hello-world` is only the documented tutorial location).
- **The new project opens immediately with a main class named after the project**: `HelloWorld` → `HelloWorld.java`,
  with an empty `main` and the caret inside it, ready to type `System.out.println` (as in the Eclipse reference, page 5).

## Steps

| # | Step | Prompt sections | Status |
|---|------|-----------------|--------|
| 1 | Launch without a project: empty screen, no sample buffer | §4 (Welcome comes later) | done — `FirstLaunchStepsSmoke` |
| 2 | Workspace selection: default `~/Workspace`, Remember, `~` expansion | §3 | done — `FirstLaunchStepsSmoke` (Switch Workspace still to check) |
| 3 | New Java Project inside the workspace (`HelloWorld` → `hello-world/`), opened, `HelloWorld.java` with empty `main`, caret inside | §5, §6 | done — `FirstLaunchStepsSmoke` |
| 4 | Compile and Run, real output | §7 | |
| 5 | `Calculator calculator = new Calculator();` → Cmd+1 → Create class 'Calculator' | §8, §9 | |
| 6 | Cmd+1 on the Calculator declaration → Create JUnit Test, JUnit setup | §10, §11, §12 | |
| 7 | Cmd+1 on `add` → Create method, implement, run the test green | §13, §14 | |
| 8 | Same journey with Maven | §15 | |
| 9 | Welcome screen, keyboard/focus audit, edge cases, walkthrough and docs | §4, §16–§22 | |

Already done before this plan: Cmd+1 jumps to the closest problem on the caret's line (Eclipse behavior), and
`@Test` with Ctrl+Space or Cmd+1 adds JUnit Jupiter to a plain project or a Maven POM (`JUnitLibrarySmoke`).

---

## The prompt

Ti ripropongo il prompt completo per GPT-6 Astra Max Ultra, basato sul documento aggiornato TurboJ - First Launch(1).docx.

La priorità assoluta è replicare fedelmente il flusso Eclipse che conosci, soprattutto `Cmd+1` (Quick Fix / Quick Assist). Vogliamo mantenere la logica delle azioni contestuali di Eclipse, migliorarne velocità e fluidità e arrivare al primo test JUnit verde senza mai usare il mouse.

Il prompt comprende sia Java con build system Built-in sia Java con Maven, inclusi workspace, creazione progetto, `main`, `System.out.println`, classe Calculator, generazione dei test, creazione dei metodi mancanti e navigazione da tastiera.

# TURBOJ — FIRST LAUNCH TO FIRST TESTED JAVA PROGRAM

## COMPLETE ECLIPSE-STYLE KEYBOARD WORKFLOW IMPLEMENTATION

### FOR GPT-6 ASTRA MAX ULTRA

You are acting as a Principal Java IDE Engineer, Eclipse JDT Expert, Java/Swing Architect, CommandJ Engineer, RefactorJ Engineer, MavenJ Engineer and macOS UX Specialist.

Your mission is to implement and verify the complete TurboJ first-run development experience.

**Primary visual and functional specification:**

`TurboJ - First Launch(1).docx`

Read and inspect all 20 pages, including screenshots, menu states, project wizards, Java editing, Quick Fix menus, JUnit creation and Maven project setup.

The screenshots show Eclipse as a functional interaction reference, not as a visual design to copy.

**Preserve Eclipse's keyboard interaction model, especially Cmd+1. Improve execution speed and contextual continuity without changing the meaning of learned gestures.**

The entire workflow must be executable using only the keyboard.

Do not deliver a mock tutorial.

Implement actual source generation, compilation, contextual code actions, JUnit execution and Maven project creation inside TurboJ.

---

## 1. FUNDAMENTAL PRODUCT PRINCIPLES

TurboJ's interaction goals:

- Keyboard first.
- Mouse optional.
- No unnecessary dialogs.
- Eclipse-grade contextual Quick Fix / Quick Assist.
- Real Java semantic analysis, independent of generative AI.
- One consistent interface.
- Native Java execution.
- Fast, predictable navigation.
- Complete undo and error recovery.

Core product statement:

**Minimal interface. Maximum Java intelligence.**

The complete journey:

```
First Launch
    ↓
Select Workspace
    ↓
Welcome
    ↓
New Java Project
    ↓
Choose Built-in or Maven
    ↓
Generate HelloWorld.java
    ↓
Compile and Run
    ↓
Write unresolved Calculator reference
    ↓
Cmd+1 → Create Calculator class
    ↓
Cmd+1 → Create JUnit Test
    ↓
Write test using missing add(int,int)
    ↓
Cmd+1 → Create Method
    ↓
Implement add(int,int)
    ↓
Run Tests
    ↓
ALL TESTS PASSED
```

Both project types must support this journey.

---

## 2. INSPECT EXISTING IMPLEMENTATION FIRST

Read the current repository.

Inspect:

- TurboJ application.
- CommandJ.
- RefactorJ.
- FormatJ.
- MavenJ.
- TurboTest.
- Java/JDT intelligence.
- Existing editor model.
- Workspace management.
- Keymap settings.
- Native Swing focus management.
- Existing Quick Fix and code actions.
- Current project creation and test-generation commands.

Read the relevant release and architecture documentation.

Do not duplicate working functionality.

Do not create a second command registry, Java parser, project model or test runner.

Use direct native Java APIs where appropriate.

Preserve existing user preferences and custom shortcuts.

---

# PART I — FIRST LAUNCH

## 3. WORKSPACE SELECTION

On the first launch, request the workspace directory.

Default:

`~/Workspace`

Provide:

- Editable directory path.
- Browse action.
- Remember Workspace checkbox.
- Continue.
- Cancel.

When Remember Workspace is selected, use the saved workspace automatically on subsequent launches.

Provide a Switch Workspace command.

Correctly expand `~` to the current user's home directory.

Do not confuse the workspace with the project directory.

### Wireframe

```
╔══════════════════════════════════════════════════════╗
║ TurboJ — Select Workspace                            ║
╠══════════════════════════════════════════════════════╣
║                                                      ║
║ Workspace                                             ║
║ ~/Workspace                                    Browse║
║                                                      ║
║ [x] Remember this workspace                          ║
║                                                      ║
║ Tab: Next     Enter: Continue     Esc: Cancel        ║
╚══════════════════════════════════════════════════════╝
```

All controls must be keyboard-accessible.

Validate directory permissions and prevent accidental data loss.

---

## 4. STARTUP AND WELCOME

Show a lightweight startup indicator only when initialization requires it.

Do not impose an artificial splash-screen delay.

Do not automatically open a sponsor page or external browser.

Show the TurboJ Welcome screen in the native application.

Actions:

- New Java Project.
- New Maven Project.
- Open Existing Project.
- Open File.
- Recent Projects.
- Keyboard Shortcuts.
- Command Palette.

### Wireframe

```
╔══════════════════════════════════════════════════════╗
║ TURBOJ                                               ║
║                                                      ║
║       Minimal interface. Maximum Java intelligence.  ║
║                                                      ║
║ > New Java Project                                   ║
║   New Maven Project                                  ║
║   Open Existing Project                               ║
║   Open File                                           ║
║   Recent Projects                                     ║
║                                                      ║
║   Keyboard Shortcuts                                 ║
║                                                      ║
║ Workspace: ~/Workspace                               ║
╚══════════════════════════════════════════════════════╝
```

Arrow keys move selection.

Enter activates the selected action.

Esc returns or cancels appropriately.

All actions must be available in CommandJ.

---

# PART II — JAVA PROJECT CREATION

## 5. NEW JAVA PROJECT

Implement File → New → Java Project.

Also expose the action through CommandJ and the Welcome screen.

The user must be able to create a Java project without using a mouse.

Required choices:

- Project name.
- Project location.
- Build system.
- Java version.
- Generate main class.

Defaults for the documented tutorial:

```
Project Name:  HelloWorld
Location:      ~/Documents/hello-world
Build System:  Built-in
Java Version:  Java 25 LTS
Main Class:    HelloWorld
```

### Wireframe

```
╔══════════════════════════════════════════════════════╗
║ New Java Project                                     ║
╠══════════════════════════════════════════════════════╣
║                                                      ║
║ Project Name   HelloWorld                            ║
║ Location       ~/Documents/hello-world               ║
║                                                      ║
║ Build System   (*) Built-in   ( ) Maven              ║
║ Java           Java 25 LTS                           ║
║                                                      ║
║ [x] Generate HelloWorld.java                         ║
║ [x] Open main class after creation                   ║
║                                                      ║
║ Enter: Create                       Esc: Cancel      ║
╚══════════════════════════════════════════════════════╝
```

Use existing JDK detection.

Validate source roots and existing directories.

For the normal case, one dialog and one confirmation should be sufficient.

---

## 6. GENERATE HELLOWORLD

Generate a real Java file:

`HelloWorld.java`

```
public class HelloWorld {

    public static void main(String[] args) {
        System.out.println("Hello TurboJ!");
    }
}
```

Use the real project source root.

Apply FormatJ conventions.

Open the file automatically.

Place the caret inside `main`.

Restore editor focus.

The user must be able to edit the actual generated source immediately.

Do not create a read-only onboarding example.

---

## 7. COMPILE AND RUN

Support keyboard commands for:

- Save.
- Save All.
- Compile.
- Run.
- Stop.
- Focus Output.
- Return to Editor.

Follow the active CommandJ keymap.

The reference tutorial uses Cmd+Return for Build, but do not overwrite existing bindings without checking conflicts.

TurboJ Classic may use F9 and Ctrl+F9.

Do not advertise any shortcut as universal across all keymaps.

When necessary, handle unsaved buffers coherently.

Never execute obsolete compiled code after compilation fails.

Run the actual program.

Expected output:

```
Hello TurboJ!
```

The output must come from the real Java execution worker.

---

# PART III — CMD+1 QUICK FIX / QUICK ASSIST

## 8. CMD+1 IS THE CORE INTERACTION

**Cmd+1 must reproduce Eclipse's contextual Quick Fix / Quick Assist behavior on macOS.**

It is not the global Command Palette.

It must use the current:

- Caret position.
- Selection.
- Java source.
- Diagnostics.
- JDT AST.
- Project and classpath.
- Applicable RefactorJ actions.

Register it through CommandJ.

Ensure Cmd+1 works while the Java editor has focus.

### Interaction

```
Cmd+1      Open contextual proposals
Up/Down    Select proposal
Enter      Apply
Esc        Close
```

If diagnostics exist at the caret, show relevant Quick Fixes first.

Quick Assists must remain accessible, including through repeated invocation when the Eclipse interaction model requires it.

Do not show unrelated generic commands.

The popup must use the current TurboJ theme and remain close to the source.

### Wireframe

```
Calculator calculator = new Calculator();
                            ^^^^^^^^^^

┌─ Quick Fix / Quick Assist ──────────────────┐
│ > Create class 'Calculator'                 │
│   Create class in another package…         │
│   Show problem details                     │
└────────────────────────────────────────────┘

↑↓ Select   Enter Apply   Esc Cancel
```

The selected action must perform a real semantic change.

---

## 9. CREATE CALCULATOR CLASS

In `HelloWorld.java`, the developer writes:

```
Calculator calculator = new Calculator();
```

`Calculator` does not yet exist.

Press Cmd+1 on the unresolved type.

Offer:

**Create class 'Calculator'**

Selecting the action must:

1. Infer the appropriate Java source root.
2. Infer the package.
3. Check for existing classes.
4. Create Calculator.java.
5. Register it in the semantic workspace.
6. Keep HelloWorld.java unchanged.
7. Open Calculator.java.
8. Position the caret inside the class.
9. Preserve navigation history.

Generated source:

```
public class Calculator {
}
```

Do not force the user through the full New Class wizard when the class name and location are unambiguous.

If a choice is needed, provide a compact keyboard-accessible selector.

---

# PART IV — JUNIT TEST CREATION

## 10. CMD+1 → CREATE JUNIT TEST

With the caret on the Calculator class declaration, press Cmd+1.

Offer:

**Create JUnit Test Case**

The action must inspect:

- Current source class.
- Package.
- Project build system.
- Available test source roots.
- Existing JUnit dependencies.
- Existing matching test class.

Default generated test name:

`CalculatorTest`

### Wireframe

```
╔══════════════════════════════════════════════╗
║ Create JUnit Test                            ║
╠══════════════════════════════════════════════╣
║                                              ║
║ Source Class     Calculator                  ║
║ Test Class       CalculatorTest              ║
║ Framework        JUnit Jupiter               ║
║ Test Source Root [project test root]         ║
║                                              ║
║ Enter: Create              Esc: Cancel       ║
╚══════════════════════════════════════════════╝
```

Use the same workflow for Built-in and Maven projects.

Do not create fake tests disconnected from TurboTest.

---

## 11. JUNIT DEPENDENCY DETECTION

If JUnit is missing, offer to configure it.

Do not ask when it is already available.

### Built-in projects

Set up the real test compilation and runtime classpaths.

Use a compatible JUnit Jupiter version.

Respect existing local dependencies and user consent requirements.

### Maven projects

Use MavenJ.

Inspect the effective POM.

Respect dependency management and BOMs.

Add compatible JUnit dependencies only when necessary.

Preserve unrelated POM content.

Show a preview before modifying Maven configuration.

Do not hardcode JUnit 6 into a project that uses an incompatible JUnit Platform or launcher.

Verify the actual resolved dependencies.

### Wireframe

```
╔══════════════════════════════════════════════╗
║ JUnit Dependency Required                    ║
╠══════════════════════════════════════════════╣
║                                              ║
║ JUnit is not configured for this project.    ║
║                                              ║
║ Suggested: JUnit Jupiter                     ║
║                                              ║
║ [View changes]                               ║
║                                              ║
║ Enter: Add JUnit           Esc: Cancel       ║
╚══════════════════════════════════════════════╝
```

---

## 12. GENERATE CALCULATOR TEST

Create:

`CalculatorTest.java`

Start with a valid JUnit template.

Example:

```
import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class CalculatorTest {

    @Test
    void testCalculatorAddition() {
        assertEquals(42, new Calculator().add(20, 22));
    }
}
```

This intentionally references the not-yet-implemented `add(int,int)` method.

The compiler must report the missing method.

**An unresolved method is a compilation error, not a JUnit assertion failure.**

Do not fabricate a JUnit failure when the test cannot compile.

The developer must be able to use Cmd+1 to generate the missing method.

---

# PART V — CREATE MISSING METHOD

## 13. CMD+1 → CREATE METHOD

Place the caret on:

```
new Calculator().add(20, 22)
                 ^^^
```

Press Cmd+1.

Offer:

**Create method 'add(int, int)' in Calculator**

Infer from JDT:

- Target class.
- Method name.
- Return type.
- Parameter types.
- Visibility.
- Static versus instance method.

For the current expression, the required signature is:

```
public int add(int a, int b)
```

The method must be generated in the actual Calculator.java file.

### Wireframe

```
┌─ Quick Fix / Quick Assist ──────────────────────┐
│ > Create method 'add(int,int)' in Calculator   │
│   Show diagnostic details                     │
└────────────────────────────────────────────────┘
```

Generate valid Java:

```
public int add(int a, int b) {
    throw new UnsupportedOperationException("Not implemented");
}
```

Do not generate `return null` for an `int` method.

After generation:

- Open Calculator.java.
- Place caret in the method.
- Activate editable placeholders if supported.
- Preserve test references.
- Refresh diagnostics.
- Keep the change undoable.

Tab and Shift+Tab must navigate template placeholders.

---

## 14. IMPLEMENT THE METHOD

Replace the placeholder body with:

```
public int add(int a, int b) {
    return a + b;
}
```

Save the source.

Navigate back to CalculatorTest.java using a keyboard shortcut.

Run the actual JUnit test.

Expected:

```
Tests: 1
Passed: 1
Failed: 0
```

Only show this result after the real TurboTest execution succeeds.

The test results panel must support keyboard navigation and returning to source.

---

# PART VI — MAVEN VERSION

## 15. CREATE THE SAME PROJECT USING MAVEN

Repeat the workflow with Maven selected instead of Built-in.

The user should not need to learn a different editor interaction model.

### Maven Project Wizard

```
╔══════════════════════════════════════════════╗
║ New Java Project                             ║
╠══════════════════════════════════════════════╣
║                                              ║
║ Name          HelloWorld                     ║
║ Location      ~/Documents/hello-world        ║
║                                              ║
║ Build System  ( ) Built-in  (*) Maven        ║
║ Java          Java 25 LTS                    ║
║                                              ║
║ Group ID      dev.example                    ║
║ Artifact ID   hello-world                   ║
║ Package       dev.example.helloworld         ║
║                                              ║
║ [x] Generate Main Class                      ║
║ [x] Generate pom.xml                         ║
║                                              ║
║ Enter: Create       Esc: Cancel              ║
╚══════════════════════════════════════════════╝
```

Use a different output directory from the Built-in scenario during tests.

Do not overwrite the previous project.

### Expected layout

```
hello-world/
├── pom.xml
└── src/
    ├── main/
    │   └── java/
    │       └── dev/
    │           └── example/
    │               └── helloworld/
    │                   ├── HelloWorld.java
    │                   └── Calculator.java
    └── test/
        └── java/
            └── dev/
                └── example/
                    └── helloworld/
                        └── CalculatorTest.java
```

The package declarations and imports must be correct.

### MavenJ Integration

After creation:

- Import the effective POM.
- Configure Java source/test roots.
- Resolve dependencies.
- Respect Java release level.
- Keep compilation and execution functional.
- Use TurboJ's native fast compiler during editing.
- Execute the full Maven lifecycle only when explicitly requested.

JUnit test creation must update Maven configuration only if needed.

Cmd+1 must behave identically at the Java semantic level.

The Maven test must pass through both TurboTest and a real Maven test invocation.

---

# PART VII — KEYBOARD AND USER EXPERIENCE

## 16. ALL OPERATIONS MUST BE KEYBOARD ACCESSIBLE

Required:

- Workspace selection.
- Welcome.
- Project creation.
- Source editing.
- Compilation.
- Run.
- Save All.
- Quick Fix.
- Create Class.
- Create Test.
- Add JUnit.
- Create Method.
- Template editing.
- Source navigation.
- Test execution.
- Test result inspection.
- Maven import.
- Maven build.
- Error recovery.

Use CommandJ for command dispatch.

Do not duplicate shortcut handling.

Do not overwrite customized bindings.

**Cmd+1 is the central shortcut for contextual Java development.**

When implementing IntelliJ or VS Code profiles, preserve their established semantics except where the explicit TurboJ product requirement makes Cmd+1 available without collision.

Eclipse macOS must follow the reference workflow particularly closely.

---

## 17. FOCUS MUST BE PREDICTABLE

After:

- Project creation → focus main editor.
- Create Class → focus created class.
- Create Test → focus generated test.
- Create Method → focus method body.
- Quick Fix cancellation → restore previous caret.
- Run → preserve editor focus unless explicitly changed.
- Test failure navigation → focus failing source.
- Dialog dismissal → return to previous context.

Escape closes contextual popups.

Enter applies the focused action.

Tab navigates forms and templates correctly.

No mouse required.

---

## 18. AVOID UNNECESSARY INTERRUPTIONS

Preserve the developer's learned Eclipse workflow.

Improve it by removing genuinely redundant confirmations.

Do not replace semantic actions with a generic wizard.

Do not change the meaning of Cmd+1.

Do not make the workflow depend on generative AI.

Do not require the user to configure Maven, JUnit or Java manually when safe existing project defaults can be inferred.

Never modify dependency configuration silently when permission or preview is required.

---

# PART VIII — TESTS AND VERIFICATION

## 19. FULL KEYBOARD-ONLY ACCEPTANCE

Run two complete real scenarios.

### Scenario A — Built-in

1. Start TurboJ with clean settings.
2. Select `~/Workspace`.
3. Create Java 25 Built-in project.
4. Generate HelloWorld.java.
5. Compile and run.
6. Type the unresolved Calculator reference.
7. Press Cmd+1.
8. Create Calculator class.
9. Press Cmd+1 on the Calculator declaration.
10. Create CalculatorTest.
11. Configure JUnit if necessary.
12. Write the addition test.
13. Observe actual missing-method diagnostic.
14. Press Cmd+1 on `add`.
15. Generate method.
16. Implement `return a + b`.
17. Save.
18. Run test.
19. Verify one passing JUnit test.

### Scenario B — Maven

Repeat with:

- Maven project generation.
- POM import.
- Maven source roots.
- Maven dependency resolution.
- JUnit dependency setup.
- Cmd+1 code generation.
- TurboTest run.
- Real Maven test execution.

All steps must work without a mouse.

---

## 20. TEST THE EDGE CASES

Verify:

- Existing project directory.
- Missing JDK.
- Unwritable workspace.
- Unresolved type in a package.
- Multiple source roots.
- Existing Calculator class.
- Existing CalculatorTest.
- JUnit already configured.
- JUnit missing.
- Failed dependency resolution.
- Ambiguous method target.
- Incompatible inferred return type.
- Source with unsaved changes.
- Stale compiler diagnostics.
- Cancel Quick Fix.
- Undo generated source.
- Restore keyboard focus.
- Restart TurboJ and reopen workspace.

Test default and customized keymaps.

---

## 21. CAPTURE THE ACTUAL USER EXPERIENCE

Produce a documented keyboard-only walkthrough.

For each step record:

- Shortcut.
- Active command.
- Current file.
- Caret position.
- Available proposals.
- Selected action.
- Resulting source.
- Compiler/test result.
- Focus destination.

Capture real application screenshots.

Compare the implemented workflow against the 20-page Eclipse reference.

Do not use fake screenshots.

Measure the number of non-coding interactions.

Document where TurboJ improves on Eclipse without disrupting its keyboard conventions.

---

# PART IX — DELIVERY

## 22. REQUIRED DOCUMENTATION

Create or update:

- `docs/first-launch-workflow.md`
- `docs/eclipse-quick-fix-parity.md`
- `docs/first-run-keyboard-acceptance.md`
- `docs/project-creation.md`

Include:

- Built-in workflow.
- Maven workflow.
- Workspace lifecycle.
- Cmd+1 implementation.
- JUnit integration.
- Source generation.
- Shortcut tables.
- Screenshots.
- Automated test results.
- Actual end-to-end validation.
- Remaining limitations.

---

## 23. IMPLEMENTATION COMPLETION

Do not stop after planning.

Do not stop after the workspace selector.

Do not stop after project creation.

Do not stop after implementing a static Quick Fix popup.

Do not stop after generating Calculator.

Do not stop after creating a test file.

Do not stop until a real JUnit test passes in both project modes.

Use the actual TurboJ Java services.

Preserve the current Swing/Java2D architecture and CommandJ integration.

Do not modify the website as part of this assignment.

Do not deploy or publish a new release without explicit authorization.

---

# FINAL ACCEPTANCE CONTRACT

The assignment is successful only if:

- First launch and workspace selection work.
- New Java Project works.
- Built-in and Maven project generation work.
- HelloWorld runs.
- Cmd+1 is genuinely semantic.
- Missing classes can be generated.
- JUnit tests can be created.
- Missing JUnit dependencies can be configured.
- Missing methods can be generated.
- Generated methods support immediate editing.
- The program compiles.
- JUnit tests execute.
- Both project variants pass.
- Every interaction can be completed without a mouse.
- Existing keymaps and user customizations remain intact.
- Source edits remain persistent and undoable.
- The workflow is tested in the actual native application.

**Preserve Eclipse's proven keyboard interaction model. Improve the execution, not the gestures.**

**Cmd+1 is the central contextual development command.**

**From first launch to a working, tested Java application — entirely from the keyboard.**

START IMPLEMENTATION.

La verifica che considero decisiva: Astra deve riuscire a partire da due directory vuote e completare entrambi i percorsi, Built-in e Maven, senza usare il mouse. Non bastano test unitari sui singoli comandi: voglio che `Cmd+1 → Create Class → Create Test → Create Method → Run Tests` funzioni davvero come un unico flusso continuo.

Il documento originale mostra questi passaggi alle pagine 8–17 e la variante Maven alle pagine 18–20.

TurboJ - First Launch.docx
