# Testing

`./mvnw test` runs 28 independent tests: registry 8, keymap 10, palette 3, Swing bridge 3, internal overlays 4.
They exercise bounded work/cancellation races, inherited/reset bindings, explicit conflict
resolution, JSON rejection and round trips, chords/timeouts/contexts, locale independence,
logical key translation and Action dispatch. Swing AWT initialization requires GUI-capable
execution on macOS. `-Djava.awt.headless=true` can be used for compatible unit execution.

Native integration adds `CommandSettingsTest` and `EditorCommandTest` (3 tests) for profile
migration, legacy binding removal, LF/CRLF line operations and undo. Existing formatting and
AI coordinator tests were also run as regression checks.

Native host smoke:

```sh
./mvnw -pl app -am -DskipTests package
java --enable-native-access=ALL-UNNAMED -cp app/target/test-classes:app/target/turboj-app.jar dev.turboj.app.CommandJSmoke
```

The smoke opens a real macOS window with isolated temporary configuration. It exercises chord
routing, exactly-once dispatch, removal of an old Undo binding, replacement Undo, palette
search/Enter, live-registry keymap search, theme independence and persisted preference loading.
It writes `app/target/commandj-smoke/native-commandj.png` and `report.txt`.
The registry additionally hosts native RefactorJ and TurboTest actions. Their separate real-engine
checks are `dev.turboj.app.RefactoringSmoke` and `dev.turboj.app.TurboTestSmoke`; see the main
application test reports for their current outcomes.

Events are dispatched through AWT's real focus manager; this is not a physical keyboard-layout
test. No IntelliJ/Eclipse/VS Code host plugin is required by CommandJ.

The headless `OverlayHostTest` exercises shared nesting, viewport bounds, modal event-loop
liveness, result delivery and theme selection colors. TurboJ's `OverlaySmoke` additionally
runs a real native frame and checks one visible native window, nested Escape behavior,
focus restoration, command suppression, input results, actual file selection, resizing,
and all three bundled visual themes. See `app/target/overlay-smoke/` after running that smoke.

Verified on the final `dev.turboj` package: the full reactor verification, all 28 CommandJ
unit tests, and actual `CommandJSmoke`, `OverlaySmoke`, `RefactoringSmoke`, and `TurboTestSmoke`
runs passed on 2026-10-24. Overlay screenshots include native menu accelerators/disabled states
and live theme refresh of open internal panels. No additional native window is created.

`dev.turboj.app.CommandRolloverSmoke` is a real AWT regression for overlapping keys. It
reproduced a lost character before the fix: hold a shortcut key, then press/type/release a
printable key before releasing the shortcut. The final JAR passes that sequence, pending
chord cancellation, exactly-once dispatch, and suppression of the handled command’s own
typed event. The dispatcher resets typed suppression for each new key press. This test uses
complete press/typed/release sequences, not an artificial stream of typed-only events.
