# Cherry producer feedback qualification — 2026-11-18

Companion Java CLI/JUnit changes preserve standalone behavior and prior Cherry history. Exact source hashes and the CLI artifact SHA256 are in build.json; no sibling history reset or source commit was performed by this task.

```sh
cd ../cherry-test/java
./mvnw --batch-mode --no-transfer-progress -pl cherry-test-cli,cherry-test-junit -am install
```

PASS: 425 test entries: 423 passed, zero failures/errors, two explicit opt-in Docker skips. Formatting, Error Prone and NullAway checks pass. Full raw build log retained. Focused tests cover exact selectors and acknowledgement hashes, selected phase filtering, broad/absent/mismatched inventory refusal, duplicate/dynamic/ordered test refusal, genuine JUnit lifecycle observations, declaration aliases, backward-compatible negotiation, external repository source hashes, symlink escape rejection and real probe union across external source roots.

New JSONL kinds are negotiated with `test-events-v1`; old clients retain original kinds. Native exact selection revalidates phase/runtime/adapter/manifest before execution, acknowledges request bytes only after application, and compares actual results against requested inventory. Maven and single Cargo selectors are supported only when precise; unsupported cases retain a deliberate broader-suite route.

Real per-test starts/completions come from updated @CherryTest JUnit callbacks, not fabricated timings. The native declaration name is based on Java reflection and reconciled only against explicit callback aliases. Raw reported names remain in displayName; original adapter/HTML results remain unchanged. Other adapters remain phase-only. No production-line attribution is inferred.

External source/class roots are explicitly configured modules. Report artifacts remain in the run directory. Coverage union uses actual JaCoCo probes, preserves hashes and accepts only the configured canonical roots; no counter addition or broad parent-directory trust.

Real two-repository and progress/exact-run process evidence is maintained by the consumer qualification in [the native feedback evidence](../cherry-native/feedback-2026-11-18/README.md). Initial failed controls are retained there: a fixture manifest used project-relative rather than workspace-relative paths; subsequent real execution exposed the old root-only LCOV normalization, missing package environment and different XML/callback method names. The latter three implementation defects were fixed with regression tests.

Limits: no universal test discovery inventory or dynamic per-test production coverage; no fabricated progress for final-report-only adapters; ambiguous Java display/parameterized/ordered cases use broader explicit execution. Docker smoke was not implicitly claimed from Java unit results.
