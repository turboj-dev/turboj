# Native Cherry Test fixture

Two deterministic integer-price tests, one intentionally untested delivery method, and a branch with partial JaCoCo coverage. Java21 source; JUnit6.1.3; JaCoCo0.8.15. Maven must be available to Cherry's Maven adapter.

Run the modified Cherry **Java** CLI with a unique event file:

```sh
CHERRY_TEST_IDE_EVENTS=/tmp/cherry-native-events.jsonl \
  java -jar /path/to/cherry-test-java.jar --root "$PWD" --execution local --no-open --no-banner
```

For a real failing control, change the subtraction in `Price.discounted` to addition: `appliesDiscount` fails and the no-discount test passes. In TurboJ, save the change to trigger Cherry automatically; restore subtraction and save again to rerun. No manual Run command or native Auto Test on Save toggle is required. `testFailureIgnore` lets Maven finish collecting coverage; Cherry still reports the actual failed test and a nonzero result.

`CherryNativeSmoke` copies this fixture into a temporary Git repository, invokes Run All Tests, then exercises failure and correction using Save alone (with keyboard Undo for the correction). It also checks that an unchanged save does not launch duplicate suites. It does not change these fixture files. This fixture executes two JUnit unit tests, not real browser or service suites. `CherrySaveWorkflowTest` separately checks unfiltered unit/integration/functional/UI protocol transport with synthetic runners. See `docs/architecture/cherry-test-turboj-integration.md` for build, evidence and known provenance limitations.

The POM includes an opt-in `cherry-provenance` profile, activated only when the modified Cherry adapter supplies `CHERRY_TEST_PROVENANCE_TOKEN`. First install `cherry-test-provenance` 0.3.16 and the CLI from the sibling reactor (`./mvnw -pl cherry-test-cli -am install` in its `java/` directory). The profile uses the compiler hook and places the process capture agent **after** JaCoCo. Ordinary Maven runs without that environment retain their existing behavior.

With those hooks, the sidecar contains verified aggregate source/class/process evidence. TurboJ still requires its process completion receipt and matching editor source before displaying current coverage. Missing hooks, reused class outputs, mismatched classes, missing sessions or incomplete collection stay neutral. The sidecar never provides per-test production-line attribution. See the CH-02 section of the integration architecture for the contract and limits.
