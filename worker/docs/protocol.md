# TurboJ worker protocol v2

The desktop never invokes user bytecode. `WorkerClient` launches `WorkerMain` in
`java.home/bin/java`, with a configurable heap limit (256 MiB by default). The
application's bundled runtime therefore also starts the execution worker.

## Transport and framing

The parent owns the child's anonymous stdin/stdout pipes. There is no listening
TCP port, filesystem socket, authentication secret, or remotely accessible
endpoint. Java stdout and stderr are redirected inside the child to framed
messages; the protocol retains its original file descriptors. Native/JVM stderr
is drained separately and bounded.

All integers use Java `DataInputStream` big-endian encoding. Every frame is:

```
magic:u32 = 0x544A5731
type:u8
payloadLength:i32 (0..64 MiB)
payload:bytes
```

Strings are a nonnegative i32 byte length followed by UTF-8, capped at 1 MiB.
Trailing bytes, invalid magic, excessive counts and malformed sizes close the
connection. There is no Java object serialization or deserialization.

| Type | Direction | Payload |
|---|---|---|
| 1 READY | worker → parent | version:i32 = 2 |
| 2 RUN | parent → worker | runId:i64, sourceVersion:i64, mainClass:string, outputLimit:i32, argc:i32, args:string[], classpathCount:i32, classpath:string[], classCount:i32, classes:(name:string, byteLength:i32, bytecode:bytes)[] |
| 3 INPUT | parent → worker | runId:i64, byteLength:i32, bytes (≤64 KiB) |
| 4 EOF | parent → worker | runId:i64 |
| 5 OUTPUT | worker → parent | runId:i64, stderr:boolean, byteLength:i32, UTF-8 bytes (≤8192) |
| 6 RESULT | worker → parent | runId:i64, sourceVersion:i64, exitCode:i32, durationNanos:i64, retire:boolean, detail:string |
| 7 PING | parent → worker | empty |
| 8 PONG | worker → parent | empty |
| 9 INPUT_STATE | worker → parent | runId:i64, sourceVersion:i64, waiting:boolean |

Only one RUN may be active. `WorkerClient.run` rejects overlapping submissions.
At most 8192 classes, 4096 program arguments and 1024 dependency paths are
accepted. The frame and count limits are validated before allocation.

## Lifecycle

Warmup is asynchronous. A READY handshake establishes protocol compatibility;
PING checks an existing process before dispatch. A fresh URL class loader with
the platform loader as parent loads each program. This prevents ordinary user
classes from accessing application libraries through classpath inheritance.
Runtime dependency paths may be passed explicitly. Java 25 non-private static
and instance `main(String[])` and `main()` methods are supported; the array form
takes priority, and an instance main needs a non-private no-argument constructor.

Output and input-state callbacks run on reader threads. Callers must enqueue concise GUI updates
and return promptly. UTF-8 decoding preserves code points split across frames.
The output budget is shared by stdout and stderr and defaults to 1 MiB per run;
one truncation notice is emitted, and later output is discarded. The input queue
holds at most 64 chunks of 64 KiB. The parent write queue holds at most 64 jobs.

`System.exit` and crashes terminate only the child. An EOF reports the actual
exit status when available, and the next run starts a new worker. Stop and the
configurable execution-time budget (30 seconds by default) forcibly terminate the process and
its currently discoverable descendants. No `Thread.stop` is used. Stop returns
immediately; completion is delivered asynchronously. Isolated Run always uses a
fresh process and retires it on completion.

Fast Run retires a process after detecting new live platform threads, child
processes, changed system properties, changed default locale/timezone, changed
default uncaught-exception handler, or replaced system streams. Retirement
occurs before publishing the result so a subsequent run cannot reuse it.
When the operating system denies process inspection, a successful invocation is
still reported normally and the worker is conservatively retired. Infrastructure
failures also produce a completion event rather than leaving a pending run hung.

## Limits and isolation

Fast Run is for trusted local code. A class loader does not reset all JVM state.
Native libraries, all forms of global state, shutdown hooks, shared-library
caches, and every virtual-thread lifecycle cannot be reliably detected through
these APIs. A program that directly accesses original OS descriptors can corrupt
the protocol; the parent rejects it and retires the child. User code may access
files and the network with the user's permissions in either execution mode.
Neither mode is a security sandbox.

Process-tree termination is best effort: children can race creation or detach
before the descendant snapshot. The heap flag limits Java heap, not total
resident memory, native allocation, or an entire process tree. The execution-time
budget includes worker startup/dispatch and invocation, but pauses while a read
from the program's stdin is blocked waiting for input. INPUT_STATE true/false
transitions surround that read; cached/queued bytes do not pause the timer. When
input or EOF arrives, the remaining budget resumes instead of resetting. Stop
still terminates a waiting program immediately. A program with other threads
running while one waits on stdin can continue during that wait; this is an
interactive development policy, not a CPU quota or security boundary.
A normal RESULT duration measures
invocation and cleanup in the child; abnormal termination duration measures
elapsed dispatch in the parent. These quantities are deliberately not conflated
in latency benchmarks.

Relevant platform APIs: [ProcessHandle](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/ProcessHandle.html),
[Process](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/Process.html).
