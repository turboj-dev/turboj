# CLI and VS Code validation

Executed on 2026-10-24, macOS 26.5.1 arm64, Corretto OpenJDK
25.0.4.1+10-LTS. These are actual test results, not claims about untested
platforms or general performance.

## Independent CLI

```sh
./mvnw -q -f javafmt/pom.xml -pl javafmt-cli -am package
```

The CLI's 9 JUnit tests passed, with zero failures, errors or skips. They cover
stdin, malformed input, deterministic directory checks, safe writes, external
changes, permissions, UTF-8 errors, symlinks, diffs and resident JSONL requests.
The same build runs the parser and core correctness tests.

From the independent javafmt directory:

```sh
JAVAFMT_SKIP_BUILD=1 ./scripts/package-cli.sh
python3 scripts/test-cli-distribution.py target/distribution/javafmt-0.1.0-Darwin-arm64
```

The jlink distribution was built and its own Java runtime passed version,
stdin, check, diff, safe in-place write, POSIX permissions, invalid-source exit
status and resident protocol checks. It includes dependency notices and the
runtime manifest. This is a macOS arm64 JVM distribution, not Native Image.
Windows, Linux and Intel packaging are not validated here.

## Real VS Code extension host

```sh
cd javafmt-vscode
npm ci --ignore-scripts
npm run package
npm run test:host
npm audit
```

The extension was built into `javafmt-0.1.0.vsix` and tested inside the installed
VS Code **1.138.0** application with an isolated profile. Eleven checks passed:

1. Native Format Document on an unsaved Java buffer.
2. One native Undo restoring the original source exactly.
3. Multiple whitespace edits and reuse of one resident JVM.
4. Native range formatting confined to the selected method.
5. A request canceled before submission returns no edits.
6. Cancellation suppresses an already submitted 2,000-method request.
7. Typing invalidates an in-flight result.
8. Malformed Java remains unchanged.
9. Native format-on-save uses the same resident JVM.
10. A terminated backend restarts on the next request.
11. An invalid Java executable fails without a dangling request, then recovers
    after the Java setting is corrected.

The real extension host exited with code 0. The scripts retain the textual
report and host log in ignored `javafmt-vscode/artifacts/`. No Marketplace
publication or user-profile installation was performed. `npm audit` reported
zero vulnerabilities after pinning the build tool's transitive MSAL dependency.
The VSIX includes no npm runtime dependencies; its Java backend notices are
embedded in the JAR and summarized beside the extension.

No VS Code latency benchmark, Windows host run or remote extension host test
has been performed. Core formatter performance is recorded separately.
