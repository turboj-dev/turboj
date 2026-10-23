# Themes and settings

The bundled themes are **Turbo C Classic**, **Turbo Amber**, and **Turbo Modern Dark**. Switch immediately through Options. Source, selection and caret are preserved. Classic uses exact DOS blue `#0000AA` and disables text antialiasing. Swing's logical Monospaced font is supplied by the operating system; no proprietary historical font is distributed. This is a DOS-inspired design, not a pixel-exact reproduction of an original font.

Copy [classic.json](../app/src/main/resources/themes/classic.json) into `~/.config/turboj/themes/my-theme.json`, edit it and choose **Options → Load theme JSON**. The current schema is version 1. Required objects:

- `editor`: background, foreground, cursor, selection, error.
- `menu`: background, foreground.
- `messages`: background, foreground.
- `syntax`: keyword, string, comment.
- `font`: size (10–48), antialias (boolean). The family field documents Monospaced; custom font family loading is not yet implemented.

Every color is a six-digit `#RRGGBB` string. Unknown fields are ignored. Invalid user themes produce an error while the existing theme remains active; invalid bundled resources fall back to safe Classic colors. User theme files are limited to 64 KiB. No theme scripts or network resources are loaded.

`config.json` stores bundled theme ID, font size, tab width, auto-close brackets, window size and up to ten recent paths. Recent-file UI and restoring a workspace are not yet implemented. A custom loaded theme is session-only. Configuration location can be overridden with `-Dturboj.config=/path` for tests or portable use. Recoverable source goes in `sessions/buffer.java` after a 2-second quiet interval; recovery is best effort, one window at a time.
