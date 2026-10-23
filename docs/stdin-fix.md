# Interactive input and Run restart — bundle 1.0.2

The exact reported program is retained as `examples/Menu.java`, unchanged. Its `Scanner` calls work when input reaches the worker. Two application problems were reproduced: keyboard focus stayed in the source editor, and the execution deadline expired even while waiting for a human to answer.

The output pane now has an editable input suffix immediately after the program prompt and a steady high-contrast block cursor. There is no separate input row: `Inserisci un numero: 20` is one line. Previously printed output is protected from editing, and asynchronous output preserves unsent input. Run focuses it; genuine stdin waits focus it again. Enter sends a full line and echoes it beside the prompt. Clicking unselected output focuses input; F6 still switches to source or output.

Run immediately stops the old program and asynchronously waits for its completion before starting current bytecode. Every requested run has a generation token: superseded compilation callbacks, old output and old completion events cannot take over the new run. Unsent input is cleared when a program is replaced. Both Fast Run and Isolated Run use this behavior.

Protocol v2 reports actual blocked reads with run/version identifiers. The client suspends its remaining execution budget while waiting for stdin and resumes it when input or EOF arrives. A loop after input still times out; Stop kills a waiting process normally. This is an execution budget excluding stdin waits, not a CPU-time or security sandbox limit.

Validation: 58 Maven tests passed (22 core, 15 worker, 16 app, 5 CLI). Six EDT console tests cover same-line input, protected output, Enter, asynchronous output, truncation and restart. New worker regressions cover the exact numeric validation loops without EOF, waiting longer than a short execution deadline, timeout after input, and Stop/recovery while waiting. A new desktop smoke first failed against the old app on stdin focus, then passed the corrected app: invalid values, votes 3/4/5, total 12.0/media 4.0, echoed input, unchanged source, same-line prompt+20 and visible cursor, replacing loops, rapid Run requests and actual Cmd+R from the stdin field clearing unsent 99. The existing full desktop smoke also passed, including saves and JShell.

[Desktop regression report](verification/stdin-restart-smoke.txt) · [Before-fix failure](verification/stdin-before-fix.txt) · [Input screenshot](images/turboj-stdin.png)

The final inline-input desktop smoke also passed using only the bundled runtime and application JAR. Bundle 1.0.2 was installed to `/Applications/TurboJ.app`; the previous installed app was retained as `.TurboJ-before-stdin-fix-20261023-213721.app`. Existing editor processes were not terminated, so reopening the application is required to load the update. Updated local artifacts are `dist/TurboJ.app` and `dist/TurboJ-1.0.2.dmg`.
