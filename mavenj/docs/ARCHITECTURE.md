# Architecture

`mavenj-api` exposes immutable records and no Maven implementation types. `DefaultMavenJ` embeds the official Maven Model Builder and Resolver in the consumer JVM. Model building does not invoke lifecycle plugins or extensions. The same imported snapshot feeds Java intelligence, refactoring, compilation and test scheduling.

`mavenj-core` separates settings/model/resolver implementation packages while retaining one authoritative project graph. An import request contains settings paths, profiles, user properties, offline/repository permission, selected JDK and optional unsaved POM overlays. Workspace generations change when relevant metadata changes; source text changes are owned by the consumer compiler/semantic workspace.

`mavenj-workspace` provides optional host services: toolchain selection, a bounded coalescing metadata watcher, XML-position-preserving POM edits and explicit real Maven process execution. It has no GUI dependency. CLI and MCP depend on these same public APIs and contain no Maven semantic parser.

## Concurrency

An engine serializes its imports and Resolver access. Cancellation is checked during model traversal, resolution and transfer progress. GUI hosts use background workers and reject results whose request generation is stale. Immutable model/classpath access does not run dependency resolution. The metadata watcher reads only the configured observed paths and invokes one debounced callback; normal Java buffer edits do not trigger it.

TurboJ's `MavenSemanticAdapter` describes each module's main/test source roots and classpaths to the existing RefactorJ JDT workspace. Compiler/index internals do not escape through MavenJ. `MavenCompilationWorkspace` consumes the same snapshot, stores current outputs privately and invalidates dependent modules after a changed upstream compilation.

## Process boundaries

Ordinary metadata import and native compilation run in-process. Explicit Maven lifecycle uses a bounded cancellable process. Trusted annotation processing uses an isolated selected-JDK compiler operation. Test execution uses the existing resident TurboTest worker on the selected runtime. Optional MCP is only for external consumers; TurboJ does not depend on or launch it.
