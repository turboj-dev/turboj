# QFX-02 / QFX-06 qualification — 2026-11-18

Base revision: `dd75866`, with current uncommitted changes. macOS aarch64, Corretto 25.

Commands:

```sh
./mvnw -q -pl refactorj/refactorj-runtime -am -Dtest=ProtocolTest -Dit.test=QuickActionsIT,ModernizationIT -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false install
./mvnw -q -pl refactorj/refactorj-cli -am -Dtest=ProtocolTest -DfailIfNoTests=false -Dsurefire.failIfNoSpecifiedTests=false test
```

PASS: 49 QuickActionsIT, 22 ModernizationIT, 10 ProtocolTest; no skips/failures/errors. Counts and measured suite times are in results.json. Coverage includes exact half-open diagnostic selection, deterministic same-line fallback, real constructor/lambda transformations, checked exceptions, comments/CRLF, compiler validation, transaction Undo/Redo and actual InterruptedException execution preserving interruption.

A failed control is retained: adding throws initially transferred the same diagnostic from callee to caller and fooled generic count-based validation. Exception-specific validation now checks file and mapped source position as well as diagnostic identity; the caller and override regressions pass. Earlier compile-only and test-helper errors are not performance data.

Native pending-inspection double invocation and caret restoration are added to QuickFixPopupSmoke. They require the separate shared native qualification run; these unit/integration results do not certify physical keyboard behavior.

Safety boundaries: contextual exceptions require actual bindings. No unsafe lambda/initializer/anonymous-boundary propagation, declaration-scope-changing catch, generic exception inference, or changed caller/override error is offered. Catch explicitly wraps with RuntimeException and cause; it is a user-selected semantic choice, never silent handling.

## Final integrated result

[QuickFixPopupSmoke](../product-sequence-2026-11-18/native-final/QuickFixPopupSmoke.txt) passed on the final installed JAR `36ae34e6…ea33a9`: both profiles, pending double invocation, real Create Class, caret movement, Monokai contrast and focus. Earlier cancellation controls remain retained in the product evidence. Physical keyboard behavior is not inferred from these Swing events.
