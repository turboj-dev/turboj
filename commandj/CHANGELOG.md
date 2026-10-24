# Changelog

## 0.1.0 — 2026-10-24

Independent command registry, bounded async dispatch and cancellation; four macOS profiles,
context-aware/chord bindings, conflict-safe customization and JSON; local fuzzy palette;
Swing actions, recorder, palette/keymap editor; direct TurboJ integration with migration;
correctness tests, native window smoke and JMH measurements.

- Swing dialogs now use internal frame overlays with nested focus/keyboard handling and an
  asynchronous internal file browser; no secondary native dialog windows are required.
