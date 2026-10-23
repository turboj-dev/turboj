// Generated from native app/src/main/resources/themes/*.json. DO NOT EDIT. Source SHA-256: 35fc8b259eeed435c4c67504bbe81a783731b22a35b5979a068bbd5128aa62d9
export const themes = [
  {
    "id": "classic",
    "name": "Turbo Classic",
    "label": "Classic",
    "themeColor": "#0000AA"
  },
  {
    "id": "dark",
    "name": "Modern Dark",
    "label": "Dark",
    "themeColor": "#171A21"
  },
  {
    "id": "light",
    "name": "Modern Light",
    "label": "Light",
    "themeColor": "#FAFAF7"
  }
] as const;
export type ThemeId = typeof themes[number]['id'];
export const themeTokenPaths = [
  "editor.background",
  "editor.foreground",
  "editor.cursor",
  "editor.selection",
  "editor.selectionForeground",
  "editor.error",
  "syntax.keyword",
  "syntax.string",
  "syntax.comment",
  "syntax.number",
  "syntax.identifier",
  "syntax.operator",
  "syntax.annotation",
  "menu.background",
  "menu.foreground",
  "menu.activeBackground",
  "menu.activeForeground",
  "messages.background",
  "messages.foreground",
  "messages.errorForeground",
  "messages.inputForeground",
  "messages.cursor",
  "diagnostics.error",
  "diagnostics.warning",
  "diagnostics.success",
  "chrome.border",
  "chrome.statusBackground",
  "chrome.statusForeground",
  "ui.background",
  "ui.foreground",
  "ui.surface",
  "ui.muted",
  "ui.heading",
  "ui.border",
  "ui.accent",
  "ui.accentForeground",
  "ui.link",
  "ui.focus",
  "documentation.background",
  "documentation.foreground",
  "documentation.border",
  "documentation.code",
  "documentation.link",
  "intelligence.ghost",
  "intelligence.aiGhost",
  "intelligence.selectionBackground",
  "intelligence.selectionForeground",
  "intelligence.definition",
  "intelligence.activeParameter"
] as const;
