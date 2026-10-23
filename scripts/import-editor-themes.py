#!/usr/bin/env python3
"""Reproduce TurboJ's curated editor themes from hash-checked, vendored originals.

No network access or non-standard Python dependency is used. See docs/theme-catalog.md.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import plistlib
import re
import sys
from urllib.parse import quote
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
SOURCES = ROOT / "third-party/theme-sources"
DESTINATION = ROOT / "app/src/main/resources/themes"
MANIFEST = SOURCES / "manifest.json"
COLOR = re.compile(r"^#[0-9a-fA-F]{3,8}$")
VERIFIED_PATHS: set[Path] = set()


def jsonc(text: str):
    """Remove JSONC comments and trailing commas, leaving quoted strings intact."""
    result = []
    i = 0
    while i < len(text):
        if text[i] == '"':
            start = i
            i += 1
            while i < len(text):
                if text[i] == "\\":
                    i += 2
                elif text[i] == '"':
                    i += 1
                    break
                else:
                    i += 1
            result.append(text[start:i])
        elif text[i:i + 2] == "//":
            end = text.find("\n", i)
            i = len(text) if end < 0 else end
        elif text[i:i + 2] == "/*":
            end = text.find("*/", i + 2)
            if end < 0:
                raise ValueError("Unclosed JSONC comment")
            i = end + 2
        else:
            result.append(text[i])
            i += 1
    clean = "".join(result)
    # A second string-aware pass handles trailing commas after removed comments.
    result = []
    quoted = escaped = False
    for i, char in enumerate(clean):
        if quoted:
            result.append(char)
            if escaped:
                escaped = False
            elif char == "\\":
                escaped = True
            elif char == '"':
                quoted = False
        elif char == '"':
            quoted = True
            result.append(char)
        elif char == "," and clean[i + 1:].lstrip().startswith(("}", "]")):
            continue
        else:
            result.append(char)
    return json.loads("".join(result))


def rgb(color):
    return tuple(int(color[i:i + 2], 16) for i in (1, 3, 5))


def mix(first, second, weight):
    return "#" + "".join(f"{round(a + (b - a) * weight):02X}" for a, b in zip(rgb(first), rgb(second)))


def opaque(value, background):
    if not isinstance(value, str) or not COLOR.fullmatch(value):
        return None
    value = value.upper()
    if len(value) in (4, 5):
        value = "#" + "".join(c * 2 for c in value[1:])
    if len(value) == 9:
        return mix(background, value[:7], int(value[7:9], 16) / 255)
    return value if len(value) == 7 else None


def luminance(color):
    channels = [c / 255 for c in rgb(color)]
    return sum(weight * (c / 12.92 if c <= .04045 else ((c + .055) / 1.055) ** 2.4)
               for weight, c in zip((.2126, .7152, .0722), channels))


def contrast(a, b):
    light, dark = sorted((luminance(a), luminance(b)), reverse=True)
    return (light + .05) / (dark + .05)


def readable(color, background, minimum=4.5):
    backgrounds = (background,) if isinstance(background, str) else background
    score = lambda candidate: min(contrast(candidate, value) for value in backgrounds)
    if score(color) >= minimum:
        return color
    target = max(("#000000", "#FFFFFF"), key=score)
    if score(target) < minimum:
        raise ValueError("Upstream UI surfaces require incompatible foreground contrast")
    low, high = 0.0, 1.0
    for _ in range(24):
        middle = (low + high) / 2
        if score(mix(color, target, middle)) >= minimum:
            high = middle
        else:
            low = middle
    return mix(color, target, high)


def foreground(background):
    return max(("#000000", "#FFFFFF"), key=lambda candidate: contrast(candidate, background))


def load_vscode(path, seen=None):
    seen = set() if seen is None else seen
    path = path.resolve()
    if path in seen or not path.is_relative_to(SOURCES.resolve()):
        raise ValueError(f"Invalid or cyclic theme include: {path}")
    if VERIFIED_PATHS and path not in VERIFIED_PATHS:
        raise ValueError(f"Theme include is not hash-pinned: {path}")
    seen.add(path)
    theme = jsonc(path.read_text())
    if include := theme.get("include"):
        parent = load_vscode(path.parent / include, seen)
        theme["colors"] = {**parent.get("colors", {}), **theme.get("colors", {})}
        theme["tokenColors"] = parent.get("tokenColors", []) + theme.get("tokenColors", [])
    return theme


def extract_vscode(theme):
    colors = theme.get("colors", {})
    tokens = theme.get("tokenColors", [])
    defaults = next((rule.get("settings", {}) for rule in tokens if not rule.get("scope")), {})
    bg = opaque(colors.get("editor.background", defaults.get("background")), "#FFFFFF")
    if not bg:
        raise ValueError("Upstream theme has no editor background")
    fg = opaque(colors.get("editor.foreground", defaults.get("foreground")), bg)
    if not fg:
        raise ValueError("Upstream theme has no editor foreground")

    def color(*names, fallback=fg):
        return next((value for name in names if (value := opaque(colors.get(name), bg))), fallback)

    def token(target, fallback=fg):
        matches = []
        for order, rule in enumerate(tokens):
            scopes = rule.get("scope", [])
            if isinstance(scopes, str):
                scopes = scopes.split(",")
            value = opaque(rule.get("settings", {}).get("foreground"), bg)
            if not value:
                continue
            for scope in scopes:
                scope = scope.strip()
                if scope and (target == scope or target.startswith(scope + ".")):
                    matches.append((len(scope), order, value))
        return max(matches)[2] if matches else fallback

    surface = color("sideBar.background", "panel.background", fallback=mix(bg, fg, .07))
    keyword = token("keyword.control.java", token("storage.type.java"))
    return {
        "background": bg, "foreground": fg, "surface": surface,
        "menu": color("menu.background", "titleBar.activeBackground", fallback=surface),
        "menuForeground": color("menu.foreground", "titleBar.activeForeground"),
        "console": color("terminal.background", "panel.background", fallback=surface),
        "consoleForeground": color("terminal.foreground"),
        "selection": color("editor.selectionBackground", fallback=mix(bg, fg, .22)),
        "cursor": color("editorCursor.foreground", fallback=fg),
        "keyword": keyword, "string": token("string.quoted.double.java"),
        "comment": token("comment.line.double-slash.java", mix(bg, fg, .6)),
        "number": token("constant.numeric.java"), "operator": token("keyword.operator.java"),
        "annotation": token("storage.type.annotation.java", keyword),
        "error": color("editorError.foreground", "terminal.ansiRed", fallback="#D94F4F"),
        "warning": color("editorWarning.foreground", "terminal.ansiYellow", fallback="#D9A441"),
        "success": color("terminal.ansiGreen", fallback=token("string.quoted.double.java")),
        "accent": color("button.background", "focusBorder", fallback=keyword),
    }


def extract(entry):
    path = SOURCES / entry["localPath"]
    if entry["format"] == "vscode":
        theme = load_vscode(path)
        if entry.get("implicitColors"):
            # A few built-ins rely on registered defaults, pinned alongside the theme.
            theme["colors"] = {**entry["implicitColors"], **theme.get("colors", {})}
        return extract_vscode(theme)
    if entry["format"] == "sublime-tmtheme":
        theme = plistlib.loads(path.read_bytes())
        defaults = theme["settings"][0]["settings"]
        colors = {"editor." + target: defaults[source] for target, source in
                  (("background", "background"), ("foreground", "foreground"),
                   ("selectionBackground", "selection")) if source in defaults}
        if "caret" in defaults:
            colors["editorCursor.foreground"] = defaults["caret"]
        return extract_vscode({"colors": colors, "tokenColors": theme["settings"]})
    if entry["format"] == "jetbrains-icls":
        # ElementTree does not fetch the external resources or execute XML content.
        tree = ET.fromstring(path.read_bytes())
        colors = {node.attrib["name"]: node.attrib.get("value", "") for node in tree.findall("colors/option")}
        attributes = {node.attrib["name"]: {child.attrib["name"]: child.attrib.get("value", "")
                      for child in node.findall("value/option")} for node in tree.findall("attributes/option")}
        base = attributes["TEXT"]
        bg, fg = "#" + base["BACKGROUND"].upper(), "#" + base["FOREGROUND"].upper()
        def color(name, fallback):
            return opaque("#" + colors.get(name, ""), bg) or fallback
        def attribute(name, fallback=fg, role="FOREGROUND"):
            return opaque("#" + attributes.get(name, {}).get(role, ""), bg) or fallback
        return {"background": bg, "foreground": fg, "surface": color("GUTTER_BACKGROUND", bg),
                "menu": color("GUTTER_BACKGROUND", bg), "menuForeground": fg,
                "console": color("CONSOLE_BACKGROUND_KEY", bg), "consoleForeground": fg,
                "selection": color("SELECTION_BACKGROUND", mix(bg, fg, .22)), "cursor": color("CARET_COLOR", fg),
                "keyword": attribute("DEFAULT_KEYWORD"), "string": attribute("DEFAULT_STRING"),
                "comment": attribute("DEFAULT_LINE_COMMENT"), "number": attribute("DEFAULT_NUMBER"),
                "operator": attribute("DEFAULT_OPERATION_SIGN"), "annotation": attribute("ANNOTATION_NAME_ATTRIBUTES"),
                "error": attribute("ERRORS_ATTRIBUTES", "#D94F4F", "EFFECT_COLOR"),
                "warning": attribute("WARNING_ATTRIBUTES", "#D9A441", "EFFECT_COLOR"),
                "success": color("FILESTATUS_ADDED", fg), "accent": attribute("DEFAULT_KEYWORD")}
    if entry["format"] == "dracula-yaml":
        # Only named six-digit anchors are read; no YAML execution or general parser is needed.
        colors = dict(re.findall(r"&([A-Za-z0-9_]+)\s+['\"](#[0-9A-Fa-f]{6})['\"]", path.read_text()))
        mapping = {"background": "BG", "foreground": "FG", "surface": "BGDark", "menu": "BGLight",
                   "menuForeground": "FG", "console": "BG", "consoleForeground": "FG", "selection": "SELECTION",
                   "cursor": "FG", "keyword": "PINK", "string": "YELLOW", "comment": "COMMENT",
                   "number": "PURPLE", "operator": "PINK", "annotation": "GREEN", "error": "RED",
                   "warning": "ORANGE", "success": "GREEN", "accent": "PURPLE"}
        return {key: colors[value].upper() for key, value in mapping.items()}
    if entry["format"] == "catppuccin-palette":
        palette = json.loads(path.read_text())[entry["variant"]]["colors"]
        # Matches the pinned VS Code token/java mappings; TurboJ UI roles are adapted below.
        mapping = {"background": "base", "foreground": "text", "surface": "mantle", "menu": "mantle",
                   "menuForeground": "text", "console": "base", "consoleForeground": "text", "selection": "surface2",
                   "cursor": "rosewater", "keyword": "mauve", "string": "green", "comment": "overlay2",
                   "number": "peach", "operator": "teal", "annotation": "peach", "error": "red",
                   "warning": "yellow", "success": "green", "accent": "mauve"}
        return {key: palette[value]["hex"].upper() for key, value in mapping.items()}
    raise ValueError(f"Unsupported upstream format: {entry['format']}")


def adapt(entry, palette):
    bg, fg, surface, menu, console = (palette[k] for k in ("background", "foreground", "surface", "menu", "console"))
    changes = []

    def text(role, value, background=bg, minimum=4.5):
        result = readable(value, background, minimum)
        if result != value:
            changes.append({"role": role, "original": value, "adapted": result, "background": background,
                            "minimumContrast": minimum})
        return result

    syntax = {key: text("syntax." + key, palette[key]) for key in ("keyword", "string", "comment", "number", "operator", "annotation")}
    syntax["identifier"] = text("syntax.identifier", fg)
    ui_backgrounds = (bg, surface)
    accent = text("ui.accent", palette["accent"], ui_backgrounds)
    result = {
        "schemaVersion": 2, "id": entry["id"], "name": entry["name"],
        "editor": {"background": bg, "foreground": text("editor.foreground", fg),
                   "cursor": text("editor.cursor", palette["cursor"], minimum=3), "selection": palette["selection"],
                   "selectionForeground": foreground(palette["selection"]), "error": text("editor.error", palette["error"])},
        "syntax": syntax,
        "menu": {"background": menu, "foreground": text("menu.foreground", palette["menuForeground"], menu),
                 "activeBackground": bg, "activeForeground": text("menu.activeForeground", fg)},
        "messages": {"background": console, "foreground": text("messages.foreground", palette["consoleForeground"], console),
                     "errorForeground": text("messages.errorForeground", palette["error"], console),
                     "inputForeground": text("messages.inputForeground", fg, console),
                     "cursor": text("messages.cursor", palette["cursor"], console, 3)},
        "diagnostics": {key: text("diagnostics." + key, palette[key]) for key in ("error", "warning", "success")},
        "chrome": {"border": text("chrome.border", mix(bg, fg, .45), minimum=3), "statusBackground": menu,
                   "statusForeground": text("chrome.statusForeground", palette["menuForeground"], menu)},
        "ui": {"background": bg, "foreground": text("ui.foreground", fg, ui_backgrounds), "surface": surface,
               "muted": text("ui.muted", palette["comment"], ui_backgrounds), "heading": text("ui.heading", fg, ui_backgrounds),
               "border": text("ui.border", mix(bg, fg, .45), ui_backgrounds, 3), "accent": accent,
               "accentForeground": foreground(accent), "link": text("ui.link", palette["keyword"], ui_backgrounds),
               "focus": text("ui.focus", palette["cursor"], ui_backgrounds, 3)},
        "documentation": {"background": surface, "foreground": text("documentation.foreground", fg, surface),
                          "border": text("documentation.border", mix(bg, fg, .45), surface, 3),
                          "code": text("documentation.code", palette["string"], surface),
                          "link": text("documentation.link", palette["keyword"], surface)},
        "intelligence": {"ghost": text("intelligence.ghost", palette["comment"]),
                         "aiGhost": text("intelligence.aiGhost", palette["comment"]),
                         "selectionBackground": palette["selection"], "selectionForeground": foreground(palette["selection"]),
                         "definition": syntax["keyword"], "activeParameter": syntax["keyword"]},
        "font": {"family": "Monospaced", "size": 18, "antialias": True},
    }
    return result, changes


def encoded(value):
    return (json.dumps(value, ensure_ascii=False, indent=2) + "\n").encode()


def generate(check=False):
    manifest = json.loads(MANIFEST.read_text())
    for source in manifest["files"]:
        path = (SOURCES / source["localPath"]).resolve()
        if not path.is_relative_to(SOURCES.resolve()):
            raise ValueError("Source path escapes vendored directory")
        if hashlib.sha256(path.read_bytes()).hexdigest() != source["sha256"]:
            raise ValueError(f"Source hash mismatch: {source['localPath']}")
        VERIFIED_PATHS.add(path)
    for primary in manifest["primary"]:
        if hashlib.sha256((DESTINATION / primary["file"]).read_bytes()).hexdigest() != primary["sha256"]:
            raise ValueError(f"Primary preset was changed: {primary['file']}")
    # Match the native catalog: primary presets first, then case-insensitive names.
    entries = sorted(manifest["themes"], key=lambda entry: (entry["name"].lower(), entry["id"]))
    if len(entries) != 100 or len({entry["id"] for entry in entries}) != 100:
        raise ValueError("The curated collection must contain exactly 100 distinct theme IDs")
    reference = json.loads((DESTINATION / "classic.json").read_text())
    paths = [(section, field) for section, values in reference.items() if isinstance(values, dict) and section != "font" for field in values]
    fingerprints, outputs, report = set(), {}, []
    for entry in entries:
        if not re.fullmatch(r"[a-z][a-z0-9_-]{0,63}", entry["id"]):
            raise ValueError(f"Invalid theme ID: {entry['id']}")
        source_path = (SOURCES / entry["localPath"]).resolve()
        if source_path not in VERIFIED_PATHS or hashlib.sha256(source_path.read_bytes()).hexdigest() != entry["sha256"]:
            raise ValueError(f"Theme source is not hash-pinned: {entry['id']}")
        try:
            palette = extract(entry)
        except (OSError, ValueError, KeyError, TypeError) as error:
            raise ValueError(f"{entry['id']}: {error}") from error
        theme, adaptations = adapt(entry, palette)
        values = tuple(theme[section][field] for section, field in paths)
        if len(values) != 49 or any(not re.fullmatch(r"#[A-F0-9]{6}", color) for color in values):
            raise ValueError(f"Invalid complete palette: {entry['id']}")
        if values in fingerprints:
            raise ValueError(f"Duplicate imported palette: {entry['id']}")
        fingerprints.add(values)
        data = encoded(theme)
        if len(data) > 65_536:
            raise ValueError(f"Oversized native palette: {entry['id']}")
        outputs[DESTINATION / (entry["id"] + ".json")] = data
        report.append({"id": entry["id"], "sourceBackground": palette["background"], "adaptations": adaptations})
    outputs[SOURCES / "adaptations.json"] = encoded({"schemaVersion": 1, "mappingVersion": 1, "themes": report})
    notices = ["TurboJ third-party editor themes\n\nThese palettes are adaptations, not endorsements by their authors.\n"
               "Original editor colors and all contrast adaptations are documented in docs/theme-catalog.md.\n"
               "Pinned originals, SHA-256 hashes and import mapping: third-party/theme-sources/manifest.json.\n"]
    for source in manifest["sources"]:
        notices.append("\n" + "=" * 72 + "\n" + source["name"] + "\n" + source["repository"] +
                       "\nCommit: " + source["commit"] + "\nLicense: " + source["license"] + "\n\n" +
                       (SOURCES / source["licensePath"]).read_text().rstrip() + "\n")
    outputs[DESTINATION / "THIRD_PARTY_NOTICES.txt"] = "".join(notices).encode()
    sources_by_id = {source["id"]: source for source in manifest["sources"]}
    inventory = ["# Imported editor theme inventory\n\nGenerated by `python3 scripts/import-editor-themes.py`. "
                 "See [the curation and adaptation notes](theme-catalog.md). These are adaptations, not an objective ranking.\n\n"
                 "The three unchanged TurboJ presets are additional to these 100 imported palettes.\n\n"
                 "| # | Theme | Original format | Pinned source |\n|---:|---|---|---|\n"]
    for index, entry in enumerate(entries, 1):
        source = sources_by_id[entry["source"]]
        url = source.get("artifact") or source["repository"] + "/blob/" + source["commit"] + "/" + quote(entry["path"])
        inventory.append(f"| {index} | {entry['name']} | {entry['format']} | [{entry['source']}]({url}) |\n")
    inventory.append("\nAll upstream notices are MIT. Exact commit hashes, artifact versions, file SHA-256 values, "
                     "license paths and the primary preset hashes are in [manifest.json](../third-party/theme-sources/manifest.json).\n")
    outputs[ROOT / "docs/theme-catalog-inventory.md"] = "".join(inventory).encode()
    outputs[DESTINATION / "catalog.json"] = encoded({"schemaVersion": 1, "primary": ["classic", "dark", "light"],
        "themes": [{"id": p["id"], "file": p["file"]} for p in manifest["primary"]] +
                  [{"id": entry["id"], "file": entry["id"] + ".json"} for entry in entries]})
    stale = [str(path.relative_to(ROOT)) for path, data in outputs.items() if not path.exists() or path.read_bytes() != data]
    if check:
        if stale:
            raise ValueError("Generated themes differ: " + ", ".join(stale))
    else:
        for path, data in outputs.items():
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
    print(f"{'Verified' if check else 'Generated'} 100 distinct imported palettes + 3 unchanged TurboJ presets; {len(manifest['files'])} upstream hashes verified.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--check", action="store_true", help="Verify reproducibility without writing files")
    args = parser.parse_args()
    try:
        generate(args.check)
    except (OSError, ValueError, KeyError, TypeError) as error:
        print(f"Theme import failed: {error}", file=sys.stderr)
        sys.exit(1)
