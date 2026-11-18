# Java live templates

TurboJ expands declarative Java templates using the existing editor's linked fields. Type `sout`, then **Tab**:

```java
System.out.println(|);
```

`|` illustrates the caret; it is not inserted. No AI request is involved.

Typing at least two abbreviation characters (for example `so` or `sou`) opens a small, theme-aware list of applicable
live templates, with the selected template's source. Typing keeps filtering without moving focus out of the editor.
Up/Down selects a match; Tab or Enter expands it; Escape closes the list without changing the source. Exact
abbreviations rank first. User definitions participate in the same list. Comments, literals and unsupported contexts
are excluded. Moving focus to another panel or opening an unrelated command dismisses the list.

An applicable exact abbreviation such as `sout` takes precedence over an AI ghost on Tab, even if the context worker
has not yet opened the list. Existing AI requests are cancelled when template choices are displayed/expanded. If
there is no applicable template (including `sout` inside a comment), ordinary AI acceptance is preserved. An
**explicitly opened Java completion list** retains its existing acceptance behavior. No keymap is overwritten;
this uses CommandJ's existing completion and indentation routes.

Inside a live-template field, Tab/Shift+Tab move between fields; Tab after the last field
finishes at `$END$`. Enter finishes from any field. Escape dismisses completion first, then rolls back the expansion
and its field edits. Confirmed expansion, field edits and automatic import/format edits have one Undo transaction. Save, Save All and Format wait
for template finalization first. Preparation uses the current source snapshot off the EDT; a newer edit or document
switch rejects the late result and cancels the deferred command rather than overwriting new text. Changing files ends linked editing and retains the text. Editing outside fields also ends it;
that outside edit is not folded into the template's Undo transaction.

Generated-method/refactoring templates retain their established **wrapping Tab** behavior. They are semantic
workspace transactions, not abbreviation snippets. Their Enter/Save/Format validation now rejects an empty active
return type, qualified names such as `a.b`, invalid generic types such as `List<int>`, and duplicate parameter names.

## Commands and configuration

Code → Live Templates offers Expand and Insert / Surround. The application menu also has **Live Templates…**.
All three commands are in CommandJ and the palette. Supplemental shortcuts, in every profile unless customized:

| Action | Command ID | Supplemental sequence |
| --- | --- | --- |
| Expand abbreviation | `editor.expandLiveTemplate` | Ctrl+Cmd+K, L, N |
| Insert / surround | `editor.insertLiveTemplate` | Ctrl+Cmd+K, L, O |
| Edit definitions | `settings.liveTemplates` | Ctrl+Cmd+K, L, P |

Release the prefix before typing the two letters. These are TurboJ additions, not IntelliJ defaults.

The settings form opens with **Search** focused. It filters immediately by name, abbreviation, description,
stable ID and code. Up/Down chooses a result; Enter moves into its fields. **New** creates a starter;
**Duplicate** copies the selected definition under a new stable user ID. Edit **Name**, **Abbreviation**,
**Description**, **Code** and **Enabled** without writing JSON.

**Code & preview** uses the actual native source editor, with matching syntax colors, theme, zoom and tab width.
The preview is read-only and uses the real expansion engine: dotted boxes mark linked fields and a solid caret
marks `$END$`. The caption and accessible description provide field names and the final line/column. This is an
illustrative expansion in `Example.example(int[] values)`, with `selected();` as the sample selection; it is not
project-specific semantic validation and does not execute code. Unresolved macros keep their defaults or empty
editable fields, exactly as in expansion. Select/copy and scrolling remain available in the preview.

**Configuration** contains applicable contexts, **Format expanded code**, **Shorten qualified names / add imports**,
and the variable table: name, expression, default value and Stop. Project-dependent contexts are labeled explicitly.
The two transformation flags are optional schema-v1 booleans `reformat` and `shortenNames`; old user definitions
keep both disabled unless opted in. Built-ins retain the pinned upstream flags. Formatting uses the selected engine
and project language level; unsupported/invalid formatting leaves the user text intact. Qualified type names are
shortened only when their bindings resolve and the short name has no conflicting import/declaration/use. Focus the tabs and use Left/Right to change
pages. In the table, arrows select a cell, F2 edits, Enter commits and Tab leaves the table. Tab/Shift+Tab also
traverse code and preview; Escape cancels the dialog. Multi-line code uses Enter, not a form confirmation.

Edits are dialog-local drafts, retained when filtering or changing selection. Editing a built-in creates an
override; **Restore default** removes it. **Remove** deletes a user entry from the draft. Nothing is written until
**Save** validates the complete catalog and writes atomically off the EDT. Cancel discards all drafts. While that
write is in progress, the form cannot be edited or cancelled; a failure leaves the draft available for correction.

**Edit JSON…** remains available for advanced configuration and repairing an invalid file. Apply validates and
updates the parent dialog's draft; it does not save to disk. Invalid JSON cannot replace the draft or file. Existing
schema-v1 files remain compatible: `name` and `description` are optional metadata, and legacy names default to the
abbreviation. Untouched definitions and unknown JSON properties are preserved during structured edits. The user file is
`Settings.directory()/live-templates.json` (normally the TurboJ configuration directory), not a project source file.
Startup reads it asynchronously. An invalid startup file is preserved for repair; built-ins remain usable.

```json
{
  "schemaVersion": 1,
  "templates": [
    {
      "id": "user/twice",
      "name": "Print a local value",
      "description": "Declare a variable and print it",
      "abbreviation": "twice",
      "body": "int $NAME$ = 1; System.out.println($NAME$);$END$",
      "contexts": ["JAVA_STATEMENT"],
      "variables": [{"name": "NAME", "defaultValue": "total"}]
    }
  ]
}
```

Repeated `$NAME$` occurrences edit together. `$END$` marks the final caret, `$SELECTION$` inserts selected source,
and `$$` inserts one dollar sign. Undeclared variables become empty editable fields. Variables are visited in their
declared order, followed by undeclared fields in source order. A literal `defaultValue` supplies initial text;
IntelliJ-style quoted string expressions are also accepted. `alwaysStop` defaults to true. A successfully computed
variable with `alwaysStop: false` is skipped; unresolved expressions stay editable and are identified in the status
message. Simple aliases refer to the same linked field. Derived values are recomputed on leaving a field, unless
manually edited.

Use a distinct `user/...` ID for a new template, or copy a built-in with its exact ID to override it. Set
`"disabled": true` on that copied definition to disable it. Removing the override restores the shipped definition.
Duplicate IDs in the user file are rejected. Overlapping abbreviations open a chooser instead of silently selecting
one. The chooser supports filtering, Up/Down, Enter and Escape. There is no executable script facility.

## Upstream catalog and compatibility boundary

All **51 definitions in IntelliJ's three core Java live-template XML groups** are bundled, with original XML,
license, notice and blob identities under `app/src/main/resources/live-templates/`. This is the core Java catalog,
not every language/framework plugin's templates. Reference revision:
`409f9800a1b0d1dd2056630181266fd97cf1532e`, retrieved 2026-11-17.
Run `python3 scripts/generate-live-template-catalog.py` to regenerate JSON from the pinned XML without network access.

The Java group contains:

`fori`, `else-if`, `itar`, `itco`, `iten`, `itit`, `itli`, `itm`, `ittok`, `ritar`, `iter`, `inst`, `lst`, `mn`, `mx`,
`psvm`, `main`, `toar`, `lazy`, `inn`, `ifn`, `geti`, `St`, `psf`, `prsf`, `psfi`, `psfs`, `thr`, `serr`, `serrc`,
`sout`, `iop`, `ior`, `soutc`, `souf`, `soutm`, `soutp`, `soutv`, `C`, `RL`, `WL`, `I`, `sts`.

Two further groups provide `psvm`, `main`, `psvma`, `maina` for implicit classes and for ordinary classes with
instance main methods. The chooser filters by current context; catalog presence alone does not mean applicability.

Supported automatic values include enclosing class/method, file name, parameter logging, unused index/variable
names, visible bound variables, array components, generic arguments (including inherited collection types), aliases,
string escaping, nested expected-type expressions, resolved iterator/list expression types and compatible casts.
The application asks the existing shared JDT semantic workspace; it does not create another project index. Static
contexts exclude instance fields, out-of-scope declarations are excluded, and a variable is not offered inside its
own initializer. Primitive variables are never used for reference/null-check templates.

The macro evaluator accepts nested calls with bounded depth. Ambiguous subtype choices and unavailable bindings
remain explicit editable fields; no arbitrary subtype or unsafe cast is invented. The illustrative settings preview
continues to use its declared sample context, so it can show unresolved fields that are inferred in a real project.

* `serrc` / `soutc` require a resolved one-argument, void-returning functional target (for example `Consumer<String>`).
* `iop` / `ior` require Java 25+ and `IO` resolving to the actual `java.lang.IO`, without a shadowing declaration.
* `sts` requires preview enabled and the actual modern `StructuredTaskScope.open()/join()/FailedException` API.
  Java 25 `join()` does not throw the checked `ExecutionException` caught by the pinned upstream definition.
  The generated catalog deliberately adapts that catch to `StructuredTaskScope.FailedException`, and records the
  compatibility decision. Pinned XML remains unchanged. The incompatible old context ID remains readable for
  existing user JSON but is not offered automatically.
* Optional shortening/imports and formatting run on confirmation. Missing classpath or a name conflict keeps the
  qualified name. Unknown formatting support leaves the expansion intact. Explicit Format is still available.
* `groovyScript`, clipboard macros and arbitrary code execution remain unsupported and are never executed.

These limits are intentionally visible rather than importing working-looking actions with invented semantic results.
Ordinary statement/declaration/expression contexts exclude strings, comments and text blocks. User templates can
declare those contexts explicitly. Lexical Java parsing runs on the template worker and semantic requests use the existing serial workspace worker
only for a matching abbreviation or explicit chooser. Applying a result requires the same document, revision, caret/selection, request and editor focus; late
results cannot overwrite subsequent edits. Catalog sizes and template lengths are bounded.

References: [IntelliJ live templates](https://www.jetbrains.com/help/idea/using-live-templates.html),
[variables](https://www.jetbrains.com/help/idea/template-variables.html),
[pinned Java definitions](https://github.com/JetBrains/intellij-community/tree/409f9800a1b0d1dd2056630181266fd97cf1532e/java/java-impl/resources/liveTemplates).

## Validation

Unit tests: `LiveTemplateSemanticTest`, `LiveTemplateFinishTest`, `LiveTemplateSettingsTest`, `LiveTemplatesTest`, `JavaTemplateTypesTest`, `EditorTemplateTest`, `KeyboardDefaultsTest`.
Native acceptance: `LiveTemplateDialogSmoke`, `LiveTemplatePrecedenceSmoke`, `LiveTemplateWorkflowSmoke` and the existing `InlineTemplateSmoke`, using isolated preferences
and projects, real Swing key events, CommandJ callbacks, actual linked fields and JDT proposals.

```sh
./mvnw -pl app -am install
bash scripts/verify-keyboard-effects.sh --only LiveTemplateDialogSmoke LiveTemplatePrecedenceSmoke LiveTemplateWorkflowSmoke InlineTemplateSmoke
```

The native suite covers all four keymaps, exact `sout` caret placement, linked fields, Undo/Redo, custom definition
editing/persistence, validation failures, chooser navigation, Save/Format, file switching, generated-field validation
and completion. It does not establish physical keyboard/IME/VoiceOver behavior or IntelliJ macro parity.

The [precedence regression evidence](evidence/live-templates-2026-11-17/precedence/README.md) uses deterministic AI ghost fixtures to test the actual native shortcut route. It is not a live model-quality or paid-provider test.

[Structured dialog qualification, 18 November](evidence/template-dialog-2026-11-18/README.md) includes native Monokai
captures, validation/persistence controls, retained failed attempts and the production JAR identity.
