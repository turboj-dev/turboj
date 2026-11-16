# JAVA-06 validation — 16 November 2026

**PASS:** final `./mvnw install`, 866 tests in 124 suites, zero failures,
errors or skips, 03:33. Includes eight real JDT `UsageNavigationIT` cases.
The earlier clean install passed before the final eighth regression case.
The totals exclude an old report in the personal `examples/maven/target` tree.

**PASS:** native plain and imported Maven projects: default counterpart,
Find Usages alias, two actual callers, filtering, exact call offset, history,
explicit alternatives, reverse navigation, Enter/Escape and stale-result rejection.
See [native log](native.log), [manifest and source hashes](validation.json)
and [feature contract/reproduction](../../find-usages.md).

Evidence was captured against the published baseline `56d702f` plus local
JAVA-06 changes, before the delivery commit. The retained manifests describe
that capture-time state, rather than the later Git publication status. The manifest identifies the source and built JAR hashes.
No Cherry, website or packaged macOS release is made here.

The native driver creates fixtures and dispatches commands programmatically;
it uses real Swing keyboard/focus events, with macOS foreground requests when
needed. This is not a human physical-keyboard walkthrough. No test execution or
coverage is inferred from a static source reference. Performance was not benchmarked.

Retained controls:

- `control-alias-failure.log.gz`: native dispatch did not resolve the existing
  command alias. Fixed in `TurboFrame.runCommand`; final native check passes.
- `control-focus-failure.log.gz`: host focus was outside TurboJ. Driver now
  explicitly reactivates its own window, recording that action.
- `control-range-assertion-failure.log.gz`: the test expected only the name,
  whereas JDT's accurate reference range included invocation parentheses.
  Corrected the assertion while preserving exact Unicode/CRLF start-offset checks.

`install.log.gz` is the complete final reactor output. Generated registry audit:
307 commands, 29 aliases, zero bindings without callbacks. Regeneration also
includes the already implemented coverage-background toggle, absent from the
previous generated reference; no keymap bindings were changed by JAVA-06.
