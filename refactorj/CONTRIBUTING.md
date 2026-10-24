# Contributing

Use JDK 25. Run `./mvnw verify` in the project; install the sibling javafmt-core artifact first when building the optional formatter adapter. Add a reduced regression fixture for every transformation change. Verify bindings, candidate compilation, source comments/literals, cancellation, stale plans and transaction round trips. Use runtime equivalence checks for evaluation order, overload resolution, receiver capture and side effects. Reject cases without a sound supported transformation.

Never add a menu, protocol capability or descriptor whose handler is a placeholder. Keep Eclipse implementation types out of the public API. Keep editor operations asynchronous, and do not duplicate the semantic index. New adapters call the same API. Preserve third-party license notices and do not copy upstream implementation without an explicit compatible licensing decision.

Run actual external host tests after plugin changes. Protocol tests alone do not establish IDE compatibility. Record benchmark hardware/JDK, workload, warmup, forks, percentiles and allocation, including regressions. Do not infer performance from a single fastest run. Public API/behavior changes must update the changelog and compatibility documentation.
