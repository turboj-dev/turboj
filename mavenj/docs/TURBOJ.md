# TurboJ integration

Open a project folder or its POM. Maven import runs on a background worker and publishes one immutable generation to the editor. A compact status shows project name, modules, Java level, dependency state and native build mode. The project explorer can collapse completely and exposes modules, source/test/generated/resource roots and dependencies.

All Project and Maven Build actions use CommandJ's registry and are available from Find Action. Existing keymaps remain independent of the visual theme. Maven actions do not silently replace shortcut bindings. Dialogs, POM previews, trust prompts, settings and output stay inside TurboJ's main frame.

Fast Compile/Run uses current unsaved source and the native module compilation workspace. Maven Validate/Compile/Test/Package/Verify/Install/Clean/Dependency Tree/Effective POM are explicit real lifecycle actions with command, directory, selected distribution/JDK, profiles, output, exit status and cancellation. Wrapper execution requires project trust.

Project settings select profiles, offline/repository permissions, Maven settings, toolchains and executable. Non-secret choices persist per project. Trust is granted for the current session. Download dependency sources or Javadoc on demand rather than importing all attachments automatically.

POM editing provides XML highlighting/diagnostics, effective model diagnostics, declaration navigation and deliberate dependency/module edits with preview. Existing comments/property references/encoding are retained. Preparing edits does not save the POM. Normal save/external-change checks and editor undo remain authoritative.

Relevant POM/configuration/toolchain changes are coalesced and refresh metadata. Ordinary Java edits only invalidate source/compiler/semantic state. UI publication rejects stale import, compilation and test generations.
