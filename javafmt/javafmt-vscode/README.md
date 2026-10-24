# javafmt for VS Code

A native formatting provider backed by the independent JavaFMT Style 1 engine.
The extension does not require TurboJ. It starts one local Java 25 process on the
first formatting request and reuses it. No source is sent over a network.

Install the built `.vsix` with **Extensions → Install from VSIX…**. Select
**javafmt** using **Format Document With… → Configure Default Formatter**.
Optional VS Code settings:

```json
{
  "[java]": {
    "editor.defaultFormatter": "javafmt-local.javafmt",
    "editor.formatOnSave": true
  },
  "javafmt.javaExecutable": "/path/to/jdk-25/bin/java"
}
```

Only the Java executable is configurable. Style preferences are not supported.
Other Java tooling can remain enabled. The extension does not change its settings
or make itself the default formatter automatically. Workspace Trust is required
before starting the local backend.

Document formatting, selection formatting and VS Code's format-on-save consume
the current unsaved buffer. Ordered, nonoverlapping UTF-16 edits preserve normal
editor markers and native Undo behavior. Cancellation, buffer edits and version
changes prevent obsolete results being applied. Parsing cannot be interrupted
arbitrarily; the resident backend suppresses canceled replies and bounds its queue.
The source limit is 4 Mi UTF-16 units. Parser failures leave source unchanged and
report details in the **javafmt** output channel.

## Build and test

From the independent javafmt root, build `javafmt-cli` first. Then:

```sh
cd javafmt-vscode
npm ci --ignore-scripts
npm run package
npm run test:host
```

Packaging copies the built Java formatter JAR into `server/`; the JDK is not
bundled in the VSIX. `@vscode/vsce` creates an installable local package. The
`javafmt-local` publisher ID identifies this unpublished development artifact;
it is not a registered Marketplace publisher claim. No marketplace publication
is performed by these scripts.

The formatter JAR includes dependency notices and upstream license files under
`third-party/`; `THIRD_PARTY.md` describes that inventory. Build tools remain
outside the VSIX. Their MSAL dependency is pinned to 7.1.1 to avoid the published
vulnerability in earlier versions; packaging is tested without signing in or
publishing to Marketplace.

The host test uses the installed VS Code application, with an isolated user-data
directory and empty extensions directory. Set `CODE_EXECUTABLE` for another
installation and `JAVAFMT_TEST_JAVA` for its Java 25 executable. It never installs
into or changes the user's regular VS Code profile. No VS Code download is
performed. Test output stays in ignored `artifacts/`.

The implementation uses official
[document and range formatting APIs](https://code.visualstudio.com/api/language-extensions/programmatic-language-features#format-source-code-in-an-editor),
and the [extension development host](https://code.visualstudio.com/api/working-with-extensions/testing-extension)
for integration checks. Actual executed results are recorded separately; building
a VSIX alone does not establish working IDE integration.
