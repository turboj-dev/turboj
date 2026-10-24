# TurboTest integration

Maven main and test roots compile with separate scope-correct classpaths. Native test execution uses current reactor outputs, resources and the project's runtime dependencies. TurboTest runs in its existing resident worker, selected for the project's JDK, with previous failures prioritized. The worker bridge is compatible with Java 17 and later; the editor remains on Java 25.

Auto Test follows a successful complete source generation. It never runs during POM/refactoring preview or a partially applied refactoring. A failed dependency compilation or stale generation cannot be presented as a current test result. Project execution requires the user's trust grant.

Native JUnit execution and Maven Surefire/Failsafe are separate actions. Custom providers, fork options, integration-test phases and plugin-driven setup are not claimed to be automatically reproduced. The explicit Maven Test/Verify actions execute the real project lifecycle with its wrapper/distribution and selected JDK.

Acceptance tests exercise imported JUnit dependencies, cross-module changes, a real alternate installed JDK worker, and comparison with an explicitly invoked Maven lifecycle. See the validation report for exact commands, counts and remaining platform-specific verification.
