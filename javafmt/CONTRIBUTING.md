# Contributing

Use JDK 25 and run `./mvnw verify` from this directory. Keep the formatter independent
of TurboJ and all editor APIs. Add a minimized regression fixture before changing
layout behavior. Run idempotence, token/comment/literal preservation and edit
application tests; add compile/runtime checks where values or lexical boundaries
could be affected. Never replace a failed formatting result with a guess.

JavaFMT Style 1 is specified in `docs/STYLE.md`. Output changes need an explicit
specification/changelog decision. Do not add user-facing style preferences.
Measure performance with the documented workloads and retain all samples.

Third-party test sources need provenance, original notices and compatible license
handling. Do not copy another formatter's implementation. Adapter changes require
the real supported IDE host test, not only mocked API compilation.

No public issue tracker or contribution endpoint is configured yet. Project
naming and publication remain separate release decisions.
