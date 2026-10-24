# CLI and resident protocol

The standalone CLI uses Java 25 and the same in-process core API as an editor.
It does not require TurboJ, invoke another formatter, contact a service or run
the source being formatted. The ordinary CLI starts one JVM per command. Batch
mode reuses its formatter; IDE clients can keep `--server` alive.

From the independent javafmt project root:

```sh
./mvnw -q -pl javafmt-cli -am package
./javafmt HelloWorld.java
./javafmt -w src/
./javafmt --check .
./javafmt --diff HelloWorld.java
./javafmt --stdin < HelloWorld.java
./javafmt --version
```

`javafmt.cmd` supplies the Windows launcher; it is included but requires testing
on Windows. `--help` lists supported operations. A runtime-bundled distribution
uses its own `runtime/bin/java`; development uses `JAVA_HOME` or `java` on PATH.
`JAVAFMT_JAVA` explicitly selects an executable. No style flags exist.

## Output and exit codes

| Operation | stdout | Writes files | Exit on formatting differences |
| --- | --- | --- | --- |
| one file / `--stdin` | formatted source | No | 0 |
| `-w PATH...` | empty | Changed files only | 0 |
| `--check PATH...` | sorted paths requiring formatting | No | 1 |
| `--diff PATH...` | unified diffs with file headers | No | 0 |

Every mode uses exit **2** for invalid arguments, malformed UTF-8, parsing
failure or I/O failure. A failure has precedence over check differences.
Diagnostics go to stderr. Invalid source produces no formatted stdout output.
In batch modes, independent valid files continue after a per-file failure; a
failed directory traversal stops before processing its collected input list.

Printing multiple files or a directory is rejected because concatenated Java
source would be ambiguous. `-w`, `--check` and `--diff` accept several files and
directories. Recursion uses deterministic sorted absolute paths, skipping
symbolic links, hidden entries, `target`, `build`, `out` and `node_modules`.
Explicit symlink files are rejected. Explicit Java files in an excluded directory
can still be formatted by naming that file. Git ignore rules are not implicitly
consulted; pass the intended source directories in CI. `--` ends option parsing.

`--stdin` can combine with `--check` or `--diff` (label `<stdin>`), but never
`-w` or filesystem paths. The CLI reads at most 16 MiB; the core additionally
limits decoded source to 4 Mi UTF-16 units. It uses LF
for generated whitespace; protected comment/literal contents follow the core
preservation contract.

Unified diffs use one hunk spanning changed lines and up to three context lines
at each end. This bounded-memory algorithm may include a large middle region
when differences are far apart; core API edits remain separate and minimal.
Missing final newlines are identified with the standard unified-diff marker.

## Safe writes

`-w` first formats and validates, then writes a temporary file in the same
directory and flushes it. It preserves POSIX permissions when supported. Before
atomic replacement it checks file identity, symlink identity and original bytes.
No-op inputs are not rewritten. Syntax/encoding failures leave the original
untouched. If atomic replacement is unavailable, the command fails instead of
falling back to partial overwrite. Temporary files are removed on ordinary
failures; a killed process can leave a harmless `.javafmt-*.tmp` file.

As with ordinary path-based filesystem APIs, a narrow external-write race can
remain between the last comparison and rename. This is not a filesystem lock or
a power-loss durability guarantee. Ownership, ACLs, extended attributes and
hard-link identity are not promised to survive replacement.

## Independent bundled runtime

```sh
./scripts/package-cli.sh
# After an already verified build:
JAVAFMT_SKIP_BUILD=1 ./scripts/package-cli.sh target/my-javafmt-distribution
```

The script uses the selected JDK's `jdeps` and `jlink`, retains the JDK legal
notices and creates a host-specific runtime beside the executable and shaded
JAR. Existing output directories are not overwritten. It requires a JDK with
`jlink`/`jdeps` and is not a GraalVM Native Image. Build and verify separately on
each target OS/architecture. Runtime and dependency licenses remain their own.

## Resident stdio protocol version 1

`javafmt --server` reads UTF-8 JSON Lines from stdin and writes one JSON response
per request to stdout. It never opens a network port. JSON lines are bounded to
16 Mi UTF-16 characters. Request IDs are unique nonempty strings, at most 128
characters, and must not contain controls. Each response includes
`protocolVersion: 1` and the request `id`; a malformed request without a valid ID
uses `null`. Requests may complete out of order relative to cancellation/errors.

```json
{"protocolVersion":1,"id":"format-17","sourceVersion":17,"source":"class X{int x=1;}","lineEndings":"PRESERVE"}
```

`source` is the complete unsaved editor buffer. `sourceVersion` is an integer
(default zero). `lineEndings` is `LF` (default) or `PRESERVE`. Optional
`range: {"start": 10, "end": 18}` uses half-open UTF-16 offsets. The core may
expand that range to a safe syntax unit or explicitly fail; clients must use the
returned edits and `formattedRange`, not assume the request range was exact.

Successful responses include `success`, `formattedText`, `edits`,
`sourceVersion`, `formatterVersion`, `styleVersion`, `diagnostics` and
`formattedRange`. Edits have `start`, `end` and `replacement` in the original
source version. Apply them only if the document identity/version still matches
the request, in one undo transaction. A parse failure reports `success: false`
and the unchanged source with diagnostics. Protocol failures report
`success: false`, an `error` code and a concise `message`.

One background formatting task and one pending task bound work. Further
requests receive `error: "busy"`; duplicate active IDs receive `duplicate-id`.
Clients should coalesce requests and cancel obsolete work:

```json
{"protocolVersion":1,"op":"cancel","id":"format-17"}
```

The reply has `cancelled: true` and `found`. Cancellation removes a pending task
or suppresses delivery from an active one. It does not unsafely interrupt JDT
parsing. A completed result may arrive before the cancellation acknowledgement;
the client must still reject obsolete IDs/versions. The process is resident
until stdin EOF, then waits for existing work with a bounded shutdown deadline.
No client should start a fresh JVM per document-format action.
