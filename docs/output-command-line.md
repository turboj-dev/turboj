# Output command line

From the source editor, press **Escape**, then type **:**. TurboJ opens a small
command row at the bottom of Output, keeping the editor and explorer visible.
Type `! curl --include http://localhost:8080/hello` and press Return. The actual
command, stdout, stderr and exit status appear in Output. Nothing runs merely
because the row opens or text is typed.

The same action is available as **Output Command Line (:! command)** in the
Command Palette and Options menu, with stable CommandJ ID `console.commandLine`.
It can receive a customized shortcut in any profile. The local Escape/colon
gesture does not replace any profile binding: Escape retains its current
CommandJ action, and an ordinary colon still inserts source text. Escape first
dismisses active completion/templates; invoke it again before colon when needed.
Typing another character, moving the caret, invoking another command or leaving
the editor cancels the armed gesture. It uses the typed colon character rather
than a US physical key position. Input-method composition retains its input.

Escape closes the row and restores editor focus without clearing an unfinished
draft. Up/Down recall the last 100 commands. Cmd+C remains Copy; Ctrl+C in the command row
or **Stop Output Command** (`console.cancelCommand`) stops only the command
owned by this controller. A separate Maven goal, including a Spring server,
continues running. Application shutdown also cancels the owned command.

The working directory is the active Maven module, otherwise the imported Maven
root, the currently created plain Java project, the saved file's directory, or
the selected workspace for an unsaved file. A missing directory is reported as
a launch failure. Commands use `/bin/sh -c`, support shell quoting and pipes,
and run asynchronously. The row is a **noninteractive command launcher**, not a
PTY or persistent shell: stdin receives EOF; `cd` and environment assignments
do not persist between commands. Java program stdin and JShell remain separate.
One command runs at a time, with a ten-minute timeout, bounded output and cleanup
of its tracked child processes. Background daemons and full-screen terminal
applications are outside this interface's scope.

The website's recorded command line reconstructs this native behavior. It does
not execute shell commands, Java, or localhost requests in a visitor's browser.
The replacement Spring/curl chapter has its own capture provenance; the first
four chapters retain their historical session evidence.

## Verification

`OutputCommandRunnerTest` exercises real child processes, stdout/stderr, nonzero
exit, Unicode/quoting, cwd, stdin EOF, cancellation, timeout, output bounds and
preservation of an unrelated process. `OutputCommandControllerTest` exercises
EDT state, draft/history and separation from Java stdin. `OutputCommandGestureTest`
covers typed-character dispatch, ordinary colon input, cancellation and composition.

`OutputCommandKeyboardSmoke` runs an actual native frame with isolated settings,
all four profiles, Swing key/focus events, real command execution and cancellation.
Profile/fixture setup is programmatic; command interactions use keyboard events.
A separate synthetic mouse-event probe checks that clicking Java program output
still focuses its stdin while the command row is visible.
Physical international keyboard layouts and OS-level input-method behavior require
separate hardware testing and are not inferred from those events.
