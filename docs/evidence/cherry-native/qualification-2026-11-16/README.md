# CH-02/08/09 — real-project qualification, 16 November 2026

This is a qualification of actual executions, not completion of all Cherry integration milestones. No production implementation, personal examples, JUG source, or original Cherry sample source was changed. QFX-04 is still queued.

## Baselines and isolation

| Component | Revision |
| --- | --- |
| TurboJ | `a03254c3a7b27eec8fafc18bacce697f195451fd` (JAVA-06 published) |
| Cherry native-protocol CLI | `302325e8e003b155a97367d0b91623650db46330` |
| mini | `9398fcb91e8ca30f367d5032915030e57894a21e` |
| shop-java | `c08ce772d8718ef92a7d1c6baf7ebb7a5e7b305d` |
| JUG | `8cc859de0264ca4c2f205a7e615bfe84fb543339` |
| Cherry sidecars CLI required by current JUG | `459c5ed71942ace9db5426b2ac5a35886fd4af21` |

Host: macOS 26.5.1 arm64, Amazon Corretto 25.0.4.1, Maven 3.9.16, Docker 29.5.3. Cached dependencies/images; these are correctness controls, not cold-start performance measurements. mini keeps Java release 21, Spring Boot 4.1.1 and its existing JaCoCo 0.8.14. No dependency upgrade was made.

mini/shop were exported from their Git revisions into `/private/tmp/turboj-qualification-20261116`. `mini-provenance` is a second mini export with the opt-in compiler/process profile from TurboJ's existing native fixture. Its exact POM and four-phase configuration are retained in `configuration/`. That configuration selects the real Java Spring package, **not every polyglot package in mini**.

The Rust API used by mini's browser tests needs sibling Cherry crates. The pinned `test/` crates, Cargo.toml and Cargo.lock were exported into the temporary `cherry/` directory; relative Cargo paths were adjusted from `../../../test/` to `../../cherry/test/` only in the disposable mini copies. `pnpm install --frozen-lockfile` supplied the existing frontend dependencies. External Binance access was disabled (`CHERRY_TEST_LIVE_BINANCE=0`). The sample adapter owns its disposable database/upstream containers.

JUG ran in its existing checkout with its current prescribed preparation and isolated-container launcher: readonly sources, synthetic resources, no network inside test jobs, no production service. Preexisting AGENTS.md/ATTIVITA.md changes were preserved. Build artifacts were produced normally; JUG configuration was not rewritten.

## Executed controls

| Control | Observed result | Qualification |
| --- | --- | --- |
| Original mini Java unit suite | 3 passed; precise JaCoCo counters, no compiler/process proof | PASS neutral freshness; not certified current |
| mini with opt-in provenance, unit only | 3 passed; 39 current lines across Prices and RateFailure, including 1 partial line | VERIFIED javac/class/probe/process chain |
| Original mini integration | 25 passed against real instrumented Java server | VERIFIED execution; binary coverage stays UNKNOWN freshness |
| Initial mini functional/visual | No actual browser tests; missing sibling Rust crates | Retained setup failure |
| Retry after Cargo path correction | No actual browser tests; frontend Vite module missing, health timeout | Retained setup failure |
| Native Save after frontend installation | 3 unit + 25 integration + 4 functional + 4 visual passed | VERIFIED all configured categories dispatched by Save, no manual Run Tests |
| Original shop-java unit | 51 passed, 1 failed; Maven stopped before coverage export | VERIFIED missing coverage stays unavailable |
| shop-java unit with report-continuation flag | Same 51 passes/1 failure; precise coverage exported | VERIFIED failure and coverage are independent; no provenance, stays neutral |
| Initial shop integration without DB endpoint | Zero tests; explicit missing Postgres endpoint | Retained infrastructure failure, not a failed test assertion |
| shop integration with disposable Postgres | 12 passed; 3 separate JaCoCo `.exec` files | VERIFIED server execution; no native source/process proof |
| JUG full configured isolated-container run | CLI exit 0; summary 1,719 passes, 0 failures, 1 skip | VERIFIED standalone run; native integration BLOCKED |
| TurboJ focused regressions | 69 passed, no failures/errors/skips | VERIFIED |

Shop's real assertion failure is `ServiceTest$catalog_service::reports_a_price_mismatch`: “listed price did not match the expected 1500”. It was not repaired or hidden. The disposable configuration adds `unit.args: [-Dmaven.test.failure.ignore=true]` only so the JaCoCo report goal can run; Cherry still reports the assertion failure.

JUG evidence includes **33 adapter phase results from 32 packages**, containing **1,701 passed test records and one skip**; the CLI aggregate reports 1,719 passes. These are distinct reporting layers and their difference is **not reconciled here**. Do not treat the CLI total as 1,719 independently verified native identities. `jug-results.json` retains package/phase/test names and original result-file hashes. Every retained adapter result has an empty coverage array, consistent with `--no-coverage`; that is not zero coverage. The skip is `iosProofUnitTestsRunOfflineWithoutSigningOrAnAppleAccount`.

## Source, bytecode, staleness and attribution

`CherryProjectQualification` ingests the actual schema-v1 JSONL via TurboJ's `CherryEvents`, then calls the actual `LineIntelligenceController.snapshot` off the EDT. It does not manufacture normalized fixtures or parse HTML colors.

For mini's certified unit run (`20261116-061624-8a2e48`):

- Prices: 5 covered, 1 partial, 29 uncovered executable lines with CURRENT freshness; RateFailure: 4 covered current lines.
- Actual javac compilation UUID, source hashes, class hashes, JaCoCo identity/probes, process PID/start instant, session and completion receipt support the proof. See `provenance-acceptance.log.gz` and retained sidecars.
- Appending an unsaved comment withholds historical line positions. Restoring the original source restores eligibility.
- Changing one byte in a captured class causes `BYTECODE_MISMATCH`; restoring the exact bytes restores current coverage. The driver restricts this destructive control to disposable `/private/tmp` projects and restores bytes in `finally`.
- Wholly unloaded Api/Application/SecurityHeaders classes remain UNKNOWN freshness. An absent execution record cannot prove instrumentation scope, even if raw XML contains zero counters.
- Aggregate coverage produces no fabricated dynamic test associations. A package failure does not make production lines red.

Uncertified raw counters may retain COVERED/UNCOVERED/PARTIAL with **UNKNOWN freshness** for inspection. `LineGutter` paints those as neutral `?` and never applies coverage row tints; they are not current uncovered claims. Missing reports have UNKNOWN state, no executable-line claim. The controls separately exercise unavailable reports, binary server counters and precise but uncertified JVM counters.

The full four-phase mini workflow is intentionally more conservative than the certified unit-only control: the external server adapter converts XML to binary LCOV and has no compiler/process attestation. Overlapping independent collections are not blindly added or promoted to current. Passing all 36 tests therefore does **not** imply all source rows may receive trustworthy current coverage colors.

## Processes, modules and actual remaining blockers

1. **Server provenance remains incomplete.** shop records `shop-probe-missing-database.exec`, `shop-probe-bind.exec`, and `shop-api.exec`; mini records separate server `.exec` files per phase. Raw files are distinct, but these adapters export merged/binary LCOV without the native process/compilation proof. TurboJ does not expose individually certified process coverage from them. Preserve those identities and precise counters at the adapter boundary before claiming CH-02/03 complete.
2. **JUG's required branch and native protocol are incompatible today.** The sidecars branch supports JUG's isolation contract but contains no `CHERRY_TEST_IDE_EVENTS` producer. The published native CLI supports events but not that required execution setup. The run created no requested `jug-events.jsonl`. No HTML/ANSI-to-native workaround was used.
3. **TurboJ currently forces `--execution local`.** `CherryTestProcess.command` cannot safely launch JUG's container-only configuration. A supported execution policy and a CLI containing both capabilities must precede a native JUG Save acceptance. Do not silently run JUG locally or count standalone results as native results.
4. **Runtime separation is demonstrated in standalone JUG results**, e.g. distinct Spring/Quarkus contract, admin, journey and Discord packages. Preservation through the native protocol remains unverified until (2) is solved. No same-name tests were collapsed in the retained per-package summary.
5. Kotlin/Rust provenance, multi-repository native identity, higher-level JUG coverage overrides, uninstrumented backend provenance, and generalized incremental compiler certification remain unqualified. This run does not close that broader matrix.

## Native acceptance method

`CherryProjectSaveQualification` creates isolated settings, imports the actual Maven module, checks its resolved workspace, explicitly accepts the disposable project’s session-trust dialog, opens Prices.java, edits the actual editor buffer, waits for old coverage to disappear, dispatches CommandJ `save`, waits for the completed Cherry run, and asserts all required categories appear. It records source, console, event stream, completion receipt and a native screenshot. Finally it closes its frame and restores the source file. It does not modify personal settings.

This is native Swing/application acceptance with programmatic setup and command dispatch, **not QFX-04 keyboard-only certification**. Earlier controls are retained: the first driver needed a null-snapshot wait guard; an earlier file-only invocation lacked the Maven classpath and showed native compiler errors despite Cherry passing. The final driver imports the module and explicitly accepts its session-trust dialog first. A separate untrusted-module control emitted no events; it was stopped after confirming the existing `scheduleCherryAll` trust gate. Save must not silently grant trust. Screenshots are actual captures, not fabricated displays.

## Commands and reproduction

First build the published Cherry CLI/provenance artifact and TurboJ using the instructions in the [integration architecture](../../../architecture/cherry-test-turboj-integration.md). Put Maven on PATH; the sample adapter uses Maven independently from TurboJ's wrapper. Use disposable copies and the pinned revisions above, install mini's frozen frontend lockfile, restore sibling Rust crates, and apply the retained opt-in POM/configuration only to the disposable copy.

```sh
export PATH=/Users/luca/.m2/wrapper/dists/apache-maven-3.9.16/56ba1f9f/bin:$PATH
export CHERRY_TEST_IDE_EVENTS=/absolute/disposable/events.jsonl
java -jar /absolute/cherry-test/java/cherry-test-cli/target/cherry-test-java.jar \
  --root /absolute/disposable/mini-provenance --execution local --no-open --no-banner

# Original mini controls used package mini-java-spring-mcap and --kinds unit,
# then integration,functional,visual; original shop used shop-java-api.
# Unit controls set CHERRY_TEST_SKIP_DOCKER=1; mini service tests also set
# CHERRY_TEST_LIVE_BINANCE=0. Do not set SKIP_DOCKER for JUG.

java -cp app/target/test-classes:app/target/turboj-app.jar \
  dev.turboj.testing.CherryProjectQualification \
  /private/tmp/disposable-project /absolute/events.jsonl verified
# Use unverified for controls without a certified CURRENT line.

CHERRY_TEST_LIVE_BINANCE=0 java -cp app/target/test-classes:app/target/turboj-app.jar \
  dev.turboj.app.CherryProjectSaveQualification \
  /absolute/cherry-test/java/cherry-test-cli/target/cherry-test-java.jar \
  /private/tmp/disposable-project/mini-java-spring-mcap/src/main/java/mini/prices/Prices.java \
  /private/tmp/native-evidence unit,integration,functional,visual

./mvnw -pl app -am test \
  -Dtest=CherryEventsTest,CherryTestProcessLifecycleTest,CherryExecutionReceiptTest,LineIntelligenceControllerTest,CherrySaveWorkflowTest,LineIntelligenceEditorTest,LineIntelligenceThemeTest \
  -Dsurefire.failIfNoSpecifiedTests=false
```

For shop integration, an owned `postgres:16-alpine` container supplied a random localhost port and synthetic `SHOP_DB_USER/PASSWORD/NAME`; pass `CHERRY_TEST_SHOP_DB_HOST/PORT`, `CHERRY_TEST_SKIP_DOCKER=1`, `--kinds integration`. The owned container was stopped afterward. The initial missing-endpoint control is retained.

JUG, from its checkout and only with its required sidecars CLI:

```sh
JUG_CHERRY_TEST_DIR=/absolute/cherry-test-sidecars bash scripts/test-prepare.sh
CHERRY_TEST_IDE_EVENTS=/absolute/disposable/jug-events.jsonl \
  java -jar /absolute/cherry-test-sidecars/java/cherry-test-cli/target/cherry-test-java.jar \
  jug --execution container --jobs auto --no-coverage --no-crap --no-open --no-banner
```

Preparation exited 0; full run `20261116-061434-700ee0` exited 0. The events environment variable was ignored by this older protocol branch. Do not infer native support from its exit code.

The first focused Maven test attempt aborted in macOS AWT inside the execution sandbox; the same command outside that sandbox passed 24 core + 45 app tests. Both logs remain. No full reactor rebuild is claimed for this qualification-only change; both new drivers were compiled, and the native driver executed against the built JAVA-06 application.

## Evidence handling

`events/` contains actual CLI streams, `logs/` includes failures as well as successes, and `native-save-*` retain native execution/receipt evidence. `real-artifacts.tar.gz` contains selected original source/class/coverage/provenance/result artifacts; `artifact-hashes.json` hashes their uncompressed bytes. `capture-manifest.json` links copied evidence to the actual files; `SHA256SUMS` covers the delivered files. No caches, database volumes, generated compose credentials, browser archive, or personal configuration were copied.

Absolute temporary paths inside events/proofs are authentic. These are evidence archives, not transparently relocatable signed certificates: replay requires the original root and retained source/class/artifact bytes, or a fresh execution. Do not rewrite recorded paths/hashes and call them original evidence. Source restored after Save can differ from the captured saved revision; use `saved-source.java` and the source hashes in that run.
