# CommandJ

Independent Java 25 command, shortcut, keymap and palette libraries. Version 0.1.0.
No TurboJ, Eclipse, network, AI or project-index dependency. Swing is an optional adapter.

```sh
./mvnw verify
./mvnw install
```

Artifacts: `dev.turboj.commandj:commandj-core`, `commandj-keymap`, `commandj-palette`,
`commandj-swing`, all version `0.1.0`. Consume `commandj-swing` in Swing hosts;
headless applications need only the relevant smaller artifacts.

```java
try (var registry = new CommandRegistry()) {
    registry.register(Command.action("file.save", "Save", "File", () -> saveBuffer())
            .requiring("editable").withAliases("write", "store"));
    registry.execute("file.save", CommandContext.of("editable"));
}
```

Callbacks are caller-thread by default. Select `.inBackground(true)` for blocking work;
its cancellation token must be checked/cooperatively connected to an upstream operation.
Never access Swing state from a background callback. Registry contexts are immutable snapshots.

[API and integration](docs/API.md) · [Architecture](docs/ARCHITECTURE.md) ·
[Keymaps and sources](docs/KEYMAPS.md) · [Measured performance](docs/PERFORMANCE.md) ·
[Testing](docs/TESTING.md) · [Releases](docs/RELEASE.md)

The native TurboJ integration invokes these APIs directly. All existing TurboFrame actions,
menus, configurable shortcuts and palette results use one registry. New installations use
IntelliJ IDEA macOS. Existing settings without a profile retain TurboJ Classic; legacy
`keymap.json` overrides are migrated. Themes never change keyboard profiles.

MIT-licensed original implementation. See [third-party notices](THIRD_PARTY.md).
