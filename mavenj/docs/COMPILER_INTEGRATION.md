# Native compiler integration

MavenJ supplies effective source/test/generated roots, encodings, language options, scoped dependency classpaths/module paths, processor configuration and reactor edges. TurboJ's `MavenCompilationWorkspace` compiles modules in dependency order using resident compiler instances. Current output directories are private to the workspace and are substituted for reactor artifact paths. No `mvn install`, `mvn compile` or `mvn test` is used by normal editing.

A source snapshot generation accompanies every result. Unchanged module inputs reuse successful compilation results; a changed dependency invalidates affected consumers. Failed upstream compilation prevents consumers from using stale output. Class removal/renames clear obsolete bytecode before publishing a module's new generation. Main compilation does not see test-only dependencies or test source.

Unsaved buffers override disk source in the compilation snapshot. Resource roots are included with their configured patterns; filtered resources and plugin-dependent transformations are reported as native-path limitations. JPMS compilation and runtime module paths remain distinct from classpaths.

Passive analysis uses `-proc:none`. Running annotation processors is a deliberate trusted action, isolated through the selected JDK compiler with configured processor paths/options and generated source outputs. It is not performed simply by opening a POM or on every keystroke.

Native results do not claim to reproduce arbitrary lifecycle extensions, code generators, bytecode enhancers or resource filters. The UI keeps Fast Compile and Maven Build separate. Use an explicit Maven Verify for the complete lifecycle and project-specific plugins.
