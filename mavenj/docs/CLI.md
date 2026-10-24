# MavenJ CLI

```sh
mavenj inspect .
mavenj modules . --json
mavenj dependencies . --json
mavenj classpath . --scope test
mavenj sync . --json
mavenj --version
```

`compile`, `test`, `runtime`, `test-runtime` and `processor` select distinct classpath scopes. JSON reports keep classpaths and module paths separate and include generation, resolution state and structured diagnostics.

The default is offline and uses the user's effective local Maven repository. `--settings`, `--global-settings`, `--local-repository`, `--profiles`, `--inactive-profiles`, `--property key=value`, `--java-home` and `--toolchains` select import context. Online resolution requires `--online` plus one or more explicit `--allow-repository URL` values. Mirrors must be allowed at their effective URL.

`sync` requests dependency refresh under Maven's repository policy. All commands call the same core API; none executes lifecycle goals. The library's separate MavenLifecycle API is available for explicit builds, without turning inspection into code execution.

Exit codes: 0 success, 2 invalid usage, 3 unresolved model/dependencies/toolchain, 4 import/I/O failure, 130 cancellation. Output redacts known secret properties and credentials embedded in URLs. Keep normal diagnostics on stderr separate from JSON output when embedding the executable.
