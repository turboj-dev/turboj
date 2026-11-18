# Actual JDK17 and Maven lifecycle qualification — 2026-11-18

PASS: two actual integration tests, no skips. Base `dd75866` with current changes. The test is `app/src/test/java/dev/turboj/testing/MavenToolchainQualificationTest.java`; environment and source hash are recorded separately.

```sh
./mvnw -q -pl app -Djava.awt.headless=true -Dturboj.test.java17=/path/to/actual/jdk17 -Dtest=MavenToolchainQualificationTest test
```

Set `TURBOJ_TEST_JDK17` alternatively. Without a supplied actual runtime, these qualification tests explicitly skip rather than substituting `--release`. In cold CI use `-Dturboj.test.mavenOffline=false` to permit dependency acquisition; the local successful replay used default offline mode with a warm dependency cache.

The first fixture imports an actual parent plus three modules, inherited dependency management and an explicitly activated profile. A real toolchains.xml selects Corretto17. Native compiler runs inside the editor JDK25 with Java17 API/release configuration; the separate application and JUnit worker execute under actual17. Output is `17:42:resource-ok`; two JUnit tests assert runtime17 and dependency behavior. Unsaved library change produces43 in its dependent, leaves disk42 and unrelated cache intact. Reverting restores valid generation, removing a module prunes access. Explicit Maven verify uses the declared compiler/Surefire17 toolchain (actual Maven log checked) and reports two passing tests.

The second fixture verifies import/native compilation cannot execute configured antrun/build-helper generators. Its unresolved generated class remains an error until an explicit Maven lifecycle is requested. Actual offline verify under JDK17 generates source, filters the resource, and passes one Surefire unit test plus one Failsafe integration test. Resource filtering and plugin-dependent output are not advertised as native lifecycle parity.

The first and warm model measurements are one sample of model-session behavior, with dependencies already cached; they are not cold dependency-download benchmarks or a performance guarantee. Exact measured nanoseconds are in the qualification log.

Retained failed control: the offline lifecycle initially lacked transitive Ant/plexus dependencies. Pinned generator dependencies were then downloaded explicitly and the same offline workflow passed. An earlier assertion expected the wrong exception type for an already pruned module; the contract is NoSuchElementException. No application behavior was changed to make these fixtures pass.

Named-module worker, BOM/profile resolution, isolated annotation-processor trust/refusal, failed generation publication and cancellation are covered by existing WorkerClientTest, DefaultMavenJTest, MavenCompilationWorkspaceTest and the final full-reactor run. This fixture does not claim arbitrary third-party plugin emulation, Gradle support or a universal module-path JUnit workflow. Packaged formatter qualification belongs to the final native artifact evidence.
