# Semantic and generative completion: coordination

TurboJ has two completion engines and one owner of what the editor shows (`CompletionCoordinator`).

| | JDT (semantic) | TurboAI (generative) |
|---|---|---|
| Answers | Symbols, members, overloads, types, imports | The rest of a line, a statement, a small block |
| Works without AI | Yes, always | — |
| Trigger | Typing pause, ⌥Space, `.` | Typing pause in a Java buffer (Local automatic by default), ⌥⇧Space |
| Shown as | Inline proposal or popup | Gray ghost text, possibly several lines |

## Rules, in order

1. **JDT wins.** While a JDT proposal or the alternatives popup is visible (`hasSemanticChoice`), no AI
   request is prepared and a late AI answer is not shown; a JDT proposal that arrives replaces an AI
   ghost. A dropped AI answer gives no notice while a Java choice is on screen.
2. **Inline templates win.** While a template session is active no AI request is made, and Tab, Enter and
   Esc belong to the template.
3. **One AI suggestion, the latest one.** Each request carries a ticket (document, version, caret,
   configuration, generation); a result is shown only if all still match and the editor has focus. One
   inference lane, one queued request, older ones are cancelled at the HTTP level.
4. **Only safe text.** A suggestion is shown only if it passes, in order:
   - `CompletionValidation`: no Markdown fences or chat preambles, text after a model control token
     (`<|endoftext|>`, `<|file_sep|>`, `<|fim_…|>`) cut, no repeat of the prefix or of the line above, no
     duplicated suffix or closing braces, at most 16 lines (32 for "longer"), a trailing `;;` reduced, and
     either a statement/block boundary or, for a finished middle inside a line (`.map(User::email)`,
     `isEmpty`, a `for` header), unchanged parenthesis balance in the file;
   - `JavaSuggestionFilter` (JDT parser, no bindings): no syntax error inside the inserted text and no new
     one elsewhere; no lower-case name used as a value that the file never mentions and the suggestion
     does not declare. Calls, members after a dot, package-qualified names, lambda parameters and
     names declared by the suggestion are not judged.
5. **Typed-through text stays.** Typing the next characters of the ghost keeps the rest of it without a
   new request; any other character discards it. A suggestion still valid at the same caret and version
   survives the general reset that each edit and caret notification performs.

TurboAI does not rank AI candidates against each other: it asks for one middle and shows it or not. There
is no learned or historical ranking, and acceptance statistics stay local (opt-in diagnostics: requests,
cache hits, cancellations, acceptances, latency percentiles) and are never uploaded.

## Keys

| Key | Action | Context |
|---|---|---|
| Tab | Accept the whole suggestion (one Undo step) | ghost visible, no template |
| ⌥→, ⌃⌥↩ | Accept the next word | AI ghost visible |
| ⌥⇧→ | Accept the next line | AI ghost visible |
| Esc | Dismiss | always |
| ⌥⇧Space | Request a suggestion now | Java buffer |

⌥→ and ⌥⇧→ keep their word-motion meaning whenever no AI suggestion is shown. ⌘→ stays "line end":
CommandJ refuses two bindings whose contexts overlap, and TurboJ's keymap already gives ⌘→ to
`editor.smartEnd`.

## Debounce

The configured delay (default 200 ms) grows by 120 ms while the user types faster than one key per
160 ms, and by 150 ms when the provider's recent first-text latency is above 600 ms, up to 1 s. A pause is
the moment to suggest; requests that would be stale before they return are not sent.
