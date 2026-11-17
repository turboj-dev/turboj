# Inline templates

Generated code and some refactorings open as an inline template in the editor, like IntelliJ live templates or
Eclipse linked editing. The fields are ranges of the real document, not text fields over it: typing, completion,
input methods, Undo and the formatter see ordinary Java.

Abbreviation templates such as `sout` and user-defined snippets use the same linked-field editor;
see [Java live templates](live-templates.md) for their catalog and configuration. The wrapping Tab behavior below
belongs to generated/refactoring templates; snippets finish after their last field.

## Keys

| Key | Effect |
|---|---|
| Typing | Replaces the active field. The first field opens fully selected. Linked occurrences change together. |
| Tab | Next field. After the last field it goes back to the first; it never confirms and never inserts a tab. |
| Shift+Tab | Previous field; from the first field it goes to the last. |
| Enter | Confirms the whole template from any field, with the values shown, and inserts no newline. With completion open, Enter accepts the proposal first. |
| Esc | Takes back the edits of the session and returns to where the action was invoked. With completion open, Esc closes it first. |
| Click | A click in another field makes it active. A click outside all fields confirms and leaves the caret there. |

The status line shows the keys while the template lasts. Routine messages go to its history; warnings and errors
show for five seconds, then the keys return.

The active field has a solid outline in the cursor color, the other fields a dotted outline, so the state does not
depend on color alone. An invalid field has the error color and a `⚠` message after the line.

## Create Method

Cmd+1 on `calculator.multiply(0, 0)`, with no `multiply` in `Calculator`, offers
"Create method 'multiply(int, int)' in Calculator". It generates

```java
public int multiply(int i, int j) {
    return 0;
}
```

in `Calculator.java`, opens it, and starts the template with the fields in this order:

1. return type (`int`, selected);
2. method name (`multiply`);
3. each parameter type and name (`int`, `i`, `int`, `j`);
4. the returned expression (`0`).

The method name and the parameter names are semantic links. Leaving the name field, or confirming with it changed,
applies RefactorJ's linked rename: the call in `CalculatorTest.java` becomes `calculator.product(0, 0)`, while a
comment or a string that says `multiply` stays as it was. Parameter names update their references in the body.

Parameter types use RefactorJ Change Signature when leaving the field or confirming. Related source declarations
are updated together; incompatible arguments, invalid types and changes that redirect an existing call or method
reference to another overload are rejected. TurboJ does not invent casts or replacement arguments to force a change.
A compatible widening can leave call text unchanged. Parameter types exclude `void`; varargs and array dimensions
are retained by the signature operation.

The returned expression follows the return type while the user has not edited it: `0` for numeric types and
`char`, `false` for `boolean`, `null` for a reference type, nothing for `void`. Once the user types into it, it is
the user's.

Enter checks every field first:

- the return type must be Java type syntax (primitives, qualified names, type arguments, arrays) or `void`;
- the name and each parameter must be a Java identifier and not a keyword;
- two parameters cannot have the same name;
- a `void` method returns no value, any other method returns one;
- collisions with existing members come from the linked rename, which refuses names that conflict;
- a worker-side JDT validation checks the complete edited workspace against its post-generation baseline, including
  return-expression assignment compatibility, parameter/body use and callers. Java's conversion rules apply (for
  example, a constant `127` can be returned from a `byte` method, while `128` needs an explicit cast).

Confirmation stays pending during semantic validation. Save, Save All and Format resume only after successful
validation. An error leaves the fields editable; no candidate snapshot is published by validation. Edits, cancellation,
file switches and changed project identities invalidate pending responses. Unrelated errors that already existed at
session creation do not block the edit, but new compiler errors do. This is not a claim that the entire project is error-free.

An invalid field keeps the session open and receives the caret. An empty active field is not an error while it is
being typed, but cannot be confirmed or saved empty. Save All uses the same validation as Save and Format.
Validation rejects qualified method/parameter names, duplicate parameters and primitive generic arguments.
Explicit JDT completion keeps the fields linked, including when a proposal inserts imports; Enter accepts the
proposal before it can confirm the template.

## Extract Variable, Extract Method and Create Field

These actions retain their parameter forms and preview. Applying a valid preview starts fields in the generated
declaration. Closing the preview without applying leaves source untouched.

- Extract Method exposes the return type, method name, parameter types/names and a returned expression when the
  body is a single return. Existing extracted expressions are never replaced by generated default values.
- Extract Variable and Create Field expose the declaration name, its explicit type (when it is not `var`) and its
  initializer when present. Renaming the declaration updates bound references, including other files for a field.
- Quick Fix's direct Create Field/Assign to Field or Local actions use the same fields.

Names use semantic Rename, and confirmation uses the same workspace validation as Create Method. Ambiguous
multiple generated declarations keep the preview-only flow; the editor does not guess which symbol to select.
Escape rolls back the generated/extracted declaration and its linked transactions. Confirmed edits retain the
one-Undo-step-per-workspace-transaction contract below.

## Rename of a local variable or parameter

⌥⌘R (Refactor ▸ Rename) on a local variable or a parameter edits all its occurrences in place, as one field.
Occurrences come from JDT bindings: a field, another local with the same name, a comment or a string are not part
of it. Enter compares the inline result with RefactorJ's semantic Rename on the original source; if the rename
would change other code, reach other files or is not valid, the field shows why and the session stays. Other
symbols keep the Rename dialog.

## Undo and the session

- Esc removes exactly what the session did, newest first: the field edits, then each workspace transaction it
  applied (the linked renames, then the generation), never an older or an unrelated change.
- After Enter, field edits since the last transaction become one Undo step. A Create Method session with a changed
  name is two steps: the rename (declaration and calls together), then the generation. Redo restores both.
- Undo inside a session, editing outside the fields or switching buffer ends the template; the edits stay.
- Only one session exists at a time. Starting another action ends the current one.
- Automatic tests wait for the session to end. Save and Format first confirm the template; if a field is invalid
  the session stays and nothing is saved or formatted.

## Components

| Component | Role |
|---|---|
| `EditorComponent` | Template state: fields as groups of document ranges, Tab order, exit position, remapping on every edit, decorations, Enter/Tab/click handling. Esc belongs to the host. |
| `TurboFrame` (`InlineSession`) | The session: origin, the workspace transactions applied, validation, default return value, confirm, cancel, status hint. |
| `JdtQuickActions.methodTemplate` / `templateFor` | Placeholders of generated methods and variables after a validated preview. |
| `JdtQuickActions.changeParameterTypeLinked` | Change Signature with hierarchy/caller validation and overload-target preservation. |
| `JdtQuickActions.validateTemplate` | Asynchronous baseline-to-current compiler validation; temporary workspace state restored in all paths. |
| `JdtQuickActions.renameLinked` | Semantic rename of a generated name across files, as a workspace transaction. |
| `JdtQuickActions.localOccurrences` | Occurrences of a local variable or parameter by binding. |
| `StatusBar.hold` / `release` | The key hint while a mode lasts. |

## Limits

- Undo after a confirmed session is one step per workspace transaction, not one step for the whole session.
- Semantic validation requires the project's resolvable JDT workspace/classpath. It detects new compiler errors;
  it does not prove runtime behavior or repair pre-existing compilation failures.
- Unsupported or ambiguous generation shapes keep the validated preview flow without inline fields.
- Switching file or editing outside the fields ends the session and preserves the edits, as before. This is not
  semantic confirmation; ordinary compilation still reports any unfinished code when returning to it.
- Parameter edits preserve arity/order. Adding/removing/reordering parameters remains the Change Signature dialog.
- Physical keyboard/IME and VoiceOver checks remain the separately tracked KEY-05 work.
