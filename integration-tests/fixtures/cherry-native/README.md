# Native Cherry Test fixture

Two deterministic integer-price tests, one intentionally untested delivery method, and a branch with partial JaCoCo coverage. Java21 source; JUnit6.1.3; JaCoCo0.8.15. Maven must be available to Cherry's Maven adapter.

Run the modified Cherry **Java** CLI with a unique event file:

```sh
CHERRY_TEST_IDE_EVENTS=/tmp/cherry-native-events.jsonl \
  java -jar /path/to/cherry-test-java.jar --root "$PWD" --execution local --no-open --no-banner
```

For a real failing control, change the subtraction in `Price.discounted` to addition: `appliesDiscount` fails and the no-discount test passes. Restore subtraction and rerun. `testFailureIgnore` lets Maven finish collecting coverage; Cherry still reports the actual failed test and a nonzero result.

`CherryNativeSmoke` copies this fixture into a temporary Git repository, exercises the real native TurboJ/Cherry pipeline and restores the change with keyboard Undo. It does not change these fixture files. See `docs/architecture/cherry-test-turboj-integration.md` for build, evidence and known provenance limitations.

The sidecar counts are actual aggregate instrumentation data. They do not provide per-test production-line attribution or certify a source-to-executed-bytecode identity.
