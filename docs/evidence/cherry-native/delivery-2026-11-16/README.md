# CH-01 — published-source reproduction

This delivery pairs TurboJ `1414347dd000c5f66595496b5e1f7e7a74bc6921` with
Cherry Test `302325e8e003b155a97367d0b91623650db46330`. Both revisions were
downloaded from their GitHub remotes into clean checkouts. No local working-tree
patches, build outputs or installed project artifacts were copied into them.

Cherry's Java CLI and provenance module retain version `0.3.16`; therefore the
**commit IDs**, rather than that version number alone, identify this compatible
pair. This is a source delivery, not a Maven Central publication or a macOS
application release. Rust reporting fixes are included, but the Rust CLI does
not implement the native JSONL protocol.

## Reproduction

Requires JDK 25, Git, Maven on PATH, Rust/Cargo for the Rust checks, and a macOS
desktop session for the Swing smoke. The fixture's Java release is 21. No
containers or external services are needed for this two-test fixture.

Use a new directory and dependency repository:

```sh
delivery=$(mktemp -d /tmp/turboj-ch01-XXXXXX)
git clone https://github.com/turboj-dev/turboj.git "$delivery/turboj"
git clone https://github.com/lmrrcc/cherry-test.git "$delivery/cherry-test"
git -C "$delivery/turboj" checkout 1414347dd000c5f66595496b5e1f7e7a74bc6921
git -C "$delivery/cherry-test" checkout 302325e8e003b155a97367d0b91623650db46330
mkdir "$delivery/m2"

cd "$delivery/cherry-test/java"
./mvnw -Dmaven.repo.local="$delivery/m2" -pl cherry-test-cli -am clean install

cd "$delivery/cherry-test"
cargo test -p cherry-test -p cherry-test-core --locked

cd "$delivery/turboj"
./mvnw -Dmaven.repo.local="$delivery/m2" clean install

# Propagate the SAME repository to Maven launched by Cherry's adapter.
# This is necessary for the newly built provenance agent/compiler plugin.
MAVEN_OPTS="-Dmaven.repo.local=$delivery/m2" \
  java -cp app/target/test-classes:app/target/turboj-app.jar \
    dev.turboj.app.CherryNativeSmoke \
    "$delivery/cherry-test/java/cherry-test-cli/target/cherry-test-java.jar"
```

Keep the native window available during the smoke. The harness dispatches real
Swing events; fixture creation and caret placement are programmatic. It does
not certify physical keyboard layouts or assistive-technology interaction.

The recorded run used an isolated Maven repository seeded with **third-party
dependencies only**, excluding `com/cherrytest`, `dev/turboj` and `dev/javafmt` before either
build. Maven wrapper distributions and Cargo's downloaded dependencies were
warm. This verifies independence from unpublished project artifacts, not an
offline build or cold-download timing. The commands above use an empty cache
and may download more dependencies.

An initial verification cache excluded the first two groups but still contained
the formatter group. The final Java builds were repeated with all three excluded;
the initial build log is retained and is not the artifact-isolation acceptance.
That first TurboJ build also exposed three failing Maven acceptance tests which
read `.cache/m2` even when another repository was selected. Commit `b90d5db`
fixes their repository resolution; the three targeted tests passed before the
full clean build was repeated from the published commit. The next run passed
those compilation/JUnit checks but exposed missing parent-directory creation
when writing two acceptance reports. Commit `1414347` creates the report
directory. Both failed logs are retained; final acceptance uses that published
commit and a cache without preinstalled TurboJ or formatter artifacts. Cherry
artifacts in that final cache came only from the successful isolated build.

## Scope

The native acceptance exercises Run All Tests, real JUnit/JaCoCo partial
coverage with matching compiler/class/process evidence, coverage background
visibility, keyboard inspection, unsaved-source invalidation, save-triggered
failure, Undo/save-triggered recovery, Git restoration, independent gutter
inspection and duplicate-run suppression on unchanged saves. It checks that
aggregate coverage creates no production-line test association and that a
failed suite does not color unrelated production lines red.

This does not close CH-02/08/09: mini, shop-java and JUG still need their own
current real-project qualification, including multiple processes and absent
instrumentation. JAVA-06 Find Usages and QFX-04 remain separate work.

## Recorded outcome

All final checks passed on macOS 26.5.1 / arm64, Corretto JDK 25.0.4.1:

| Check | Result |
| --- | --- |
| Cherry Java CLI reactor and dependencies, clean install | 308 tests / 47 suites, no failures, errors or skips |
| Cherry Rust CLI/core, locked dependencies | 241 tests passed |
| TurboJ full clean install | 858 tests / 123 suites, no failures, errors or skips |
| Native Swing + Cherry + JUnit + JaCoCo | Pass → saved failure → Undo/save → pass; 26 assertions logged |
| Source state after execution | Both verification checkouts clean |

The final clean TurboJ build took 3:13 and the isolated Cherry Java build
25.071 seconds. These are build observations on this host, not performance
benchmarks or product latency claims.

- [Validation manifest](validation.json): exact commits, environment, commands,
  test totals, artifact hashes, failed attempts and cache provenance.
- [Native report](native-report.txt): checks and focus transitions from the
  real editor workflow.
- [Native evidence archive](native-evidence.tar.gz): complete owned fixture
  output and isolated settings/history, excluding `.git`; includes events,
  completion receipts, compiler/process captures, JaCoCo data, bytecode,
  source, logs and screenshots. Paths refer to the recorded temporary
  workspace; extracting it does not make historical coverage current in a
  different workspace. Reproduce the run to obtain new current evidence.
- `*-install.log.gz`, `cherry-clean-rust.log.gz`,
  `repository-fix-targeted.log.gz`, `native-smoke.log.gz`: full execution logs,
  including the two unsuccessful TurboJ build attempts.
- `turboj-test-suites.json` and `cherry-test-suites.json`: per-suite totals
  extracted from final Surefire/Failsafe XML.
- [SHA-256 manifest](SHA256SUMS.json): integrity hashes for retained evidence.

The archive and logs retain their original bytes, including absolute capture
paths, so recorded artifact hashes remain checkable. No historical
`real-executions.json` result was promoted into this acceptance. No site deploy,
macOS packaging, new binary release or Maven Central publication was performed.
