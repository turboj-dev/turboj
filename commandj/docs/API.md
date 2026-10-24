# API and integration

All public values copy collections. Key offsets are not part of this generic command engine.
`CommandRegistry` is thread safe. UI command handlers and Swing adapters run on the EDT;
background handlers use the registry's bounded worker pool. Command availability predicates must
be quick and must respect the calling thread. Supply immutable context values for worker use.

A Command describes ID/title/description/category/aliases, required context tags, availability
with disabled reason, caller/background execution, cancellation support and optional undo label.
`execute` returns `CommandExecution` with a CompletionStage and cooperative cancel method.
Exceptions become failed results. Disabled commands do not invoke their handlers.

```java
var maps = new KeymapManager(); // IntelliJ macOS default
maps.duplicate("my-map", "My keymap");
var change = maps.replaceBindings("file.save", List.of(KeyBinding.of("META+K, META+S")));
if (!change.applied()) showConflicts(change.conflicts());
String json = KeymapJson.write(maps);
KeymapJson.read(maps, json); // validates schema, inheritance and conflicts atomically
maps.resetCommand("file.save"); // may refuse a newly introduced conflict
maps.resetProfile();
```

`replaceBindingsResolving` requires an explicit set of conflicting command IDs to unbind.
`KeymapProfile` supports inheritance; cycles and depth above 32 are rejected.
Bindings have required and forbidden context tags. `KeymapJson` caps input at 1 MiB and bounds
profile/command/binding counts. It contains data only, with no callbacks or executable content.
File writes use a temporary file and atomic replacement; unsupported atomic moves fail safely.

```java
var action = new CommandAction(registry, "file.save", this::snapshotContext, this::showResult);
menu.add(new JMenuItem(action));
var dispatcher = new CommandKeyDispatcher(frame, registry, maps, this::snapshotContext, this::notice);
new CommandPaletteDialog(frame, registry, maps, snapshotContext(), this::showResult).setVisible(true);
new KeymapEditorDialog(frame, registry, maps, this::persist).setVisible(true);
```

Close the dispatcher when its window closes. UI handlers must be fast; refactoring/formatting
commands should capture a source snapshot and queue their existing asynchronous controller.
The palette snapshots contextual tags at opening; availability predicates can revalidate host
state at execution. CommandJ never performs project I/O on behalf of an action.

TurboJ public extension points: `registerCommand(id, title, category, Runnable, availability)`,
`commandRegistry()`, `commandKeymaps()`, `showKeymapEditor()`. New actions automatically appear
in the palette and searchable keymap editor; add their returned Action to the relevant menu.
