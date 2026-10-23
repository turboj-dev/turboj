# Command-line interface

`./tj --help` lists only implemented commands. Java 25 is required for development;
the macOS desktop application includes its own runtime.

```
./tj open examples/Hello.java
./tj check examples/Hello.java
./tj compile examples/Hello.java -d out
./tj run examples/Hello.java
./tj run examples/InteractiveInput.java --isolated
./tj run MyProgram.java --classpath lib/example.jar -- argument1 argument2
./tj eval "1 + 2"
./tj repl
./tj doctor
```

Check and run compile in memory. Compile writes real class artifacts with atomic
file replacement where available. Compilation disables annotation processors.
Explicit classpaths are shared by compiler and execution worker. There is no
automatic Maven/Gradle project import or network dependency resolution.

`run` forwards stdin to the separate worker and streams stdout/stderr without
adding success banners to program output. The default execution budget is 30 seconds (paused during blocked stdin waits),
Java heap limit 256 MiB, and output limit 1 MiB. Exit codes distinguish success
(0), compilation/runtime failures (1 or the program exit status), usage/I/O errors
(2), timeout (124), and interruption (130). A process is not a security sandbox.

The CLI is currently a **standalone fallback**: each command starts its own
compiler/controller process. It does not yet connect to a resident desktop
backend, so CLI startup and compilation are cold even if the GUI is open. The
desktop persistent worker's warm-run measurements do not apply to CLI startup.

## Interactive Java

The REPL uses `jdk.jshell` with a persistent, separate execution JVM. Enter submits
complete input; incomplete blocks continue onto the next line. Completion uses
JShell's `SourceCodeAnalysis`, not semicolon matching. Ordinary expression values,
declarations, methods, classes, imports, errors, and output are real JShell results.

```
int a = 5;
int b = 7;
a * b
```

The last expression prints `35`. A 30-second evaluation timeout kills the remote
process; `/reset` starts a new session. Cancellation never uses `Thread.stop` or
JShell's thread-stopping mechanism. Standard JDK JDI/JShell transport uses local
loopback connections; program execution remains outside the CLI/GUI JVM.

Commands: `/help`, `/exit`, `/reset`, `/history`, `/again N`, `/complete PREFIX`,
and `/clear`. History is bounded to 1000 submitted entries and stays in memory.
Output is bounded to 1 MiB per submission. The command reader owns stdin; snippet
`System.in` is EOF. Use `tj run` for interactive console programs.

The initial CLI deliberately uses basic line mode: arrow-key editing and Tab
completion through JLine 4 are not integrated yet. `/complete` exposes actual
JShell completion suggestions. `tj test`, `tj bench`, resident IPC, project import,
and project dependency invalidation are not exposed as unsupported commands.
Repository testing and benchmarking use the documented scripts instead.

API reference: [JShell Builder](https://docs.oracle.com/en/java/javase/25/docs/api/jdk.jshell/jdk/jshell/JShell.Builder.html).
