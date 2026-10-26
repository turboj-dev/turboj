# Inline templates

Generated code and some refactorings open as an inline template in the editor, like IntelliJ live templates or
Eclipse linked editing. The fields are ranges of the real document, not text fields over it: typing, completion,
input methods, Undo and the formatter see ordinary Java.

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
3. each parameter name (`i`, `j`);
4. the returned expression (`0`).

The method name and the parameter names are semantic links. Leaving the name field, or confirming with it changed,
applies RefactorJ's linked rename: the call in `CalculatorTest.java` becomes `calculator.product(0, 0)`, while a
comment or a string that says `multiply` stays as it was. Parameter names update their references in the body.

The returned expression follows the return type while the user has not edited it: `0` for numeric types and
`char`, `false` for `boolean`, `null` for a reference type, nothing for `void`. Once the user types into it, it is
the user's.

Enter checks every field first:

- the return type must be Java type syntax (primitives, qualified names, type arguments, arrays) or `void`;
- the name and each parameter must be a Java identifier and not a keyword;
- two parameters cannot have the same name;
- a `void` method returns no value, any other method returns one;
- collisions with existing members come from the linked rename, which refuses names that conflict.

An invalid field keeps the session open and receives the caret. An empty active field is not an error while it is
being typed.

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
| `JdtQuickActions.methodTemplate` | Placeholders of a generated method in Tab order, with the exit after the returned expression. |
| `JdtQuickActions.renameLinked` | Semantic rename of a generated name across files, as a workspace transaction. |
| `JdtQuickActions.localOccurrences` | Occurrences of a local variable or parameter by binding. |
| `StatusBar.hold` / `release` | The key hint while a mode lasts. |

## Limits

- Undo after a confirmed session is one step per workspace transaction, not one step for the whole session.
- The returned expression is checked for presence, not for type compatibility with the return type; the compiler
  reports a mismatch after confirmation.
- Parameter types are ordinary text, not fields.
- Extract Variable, Extract Method and Create Field still use their existing flow; local and parameter Rename is
  the refactoring converted to the inline template so far.
