# Reactor workspaces

Opening an aggregator imports its effective active module list, recursively.
Parent inheritance and aggregation are distinct: each immutable module records
its parent ID, child module IDs and resolved reactor dependencies. Duplicate
coordinates, aggregation cycles and dependency cycles produce diagnostics.

Reactor coordinates are supplied to Resolver through a `WorkspaceReader`.
Dependency descriptor POMs are serialized to private temporary files for the
resolution operation and deleted afterward. Public source/output paths continue
to identify the real project. Import never creates a project `target` directory
or installs reactor artifacts into the local Maven repository.

Classpath entries for reactor artifacts point to the module's configured output
path even before that directory exists. `ReactorDependency` also identifies the
module and whether its test artifact is required. TurboJ's native compiler
substitutes its current generation's private compilation output for these paths;
inter-module editing therefore does not require `mvn install`. External JARs
remain read-only dependencies.

Build order is deterministic and respects reactor parents and dependencies.
Source, test, resource and generated roots come from effective configuration.
Existing generated-source subdirectories are discovered, and static
`build-helper-maven-plugin` add-source/add-test-source declarations are modeled.
Unknown generators and plugin executions are diagnosed rather than executed or
claimed to be reproduced by a Java compiler.

Incremental model tests exercise a parent with common/service/unrelated modules.
Changing common's POM rebuilds one effective model and resolves common plus its
dependent service; parent and unrelated resolution snapshots are reused. Changing
a parent property rebuilds the inheriting child models. Source edits do not
refresh the Maven graph. Maven generation IDs let consumers reject obsolete
asynchronous results and preserve open editor buffers.

Importing a child POM directly imports that project's reactor, not every sibling
in an arbitrary parent aggregator. Open the aggregator to obtain cross-module
compilation, navigation, refactoring and test selection for all its children.

See [compiler integration](COMPILER_INTEGRATION.md),
[test integration](TEST_INTEGRATION.md) and
[Apache Maven multi-module guidance](https://maven.apache.org/guides/mini/guide-multiple-modules.html).
