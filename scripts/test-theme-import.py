#!/usr/bin/env python3
"""Offline regression checks for the curated upstream-to-TurboJ conversion."""
import contextlib
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("theme_import", ROOT / "scripts/import-editor-themes.py")
importer = importlib.util.module_from_spec(spec)
spec.loader.exec_module(importer)


class ThemeImportTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.manifest = json.loads(importer.MANIFEST.read_text())
        cls.themes = {entry["id"]: json.loads((importer.DESTINATION / (entry["id"] + ".json")).read_text())
                      for entry in cls.manifest["themes"]}

    def test_offline_reproduction_and_all_source_hashes(self):
        with contextlib.redirect_stdout(io.StringIO()):
            importer.generate(check=True)

    def test_exact_count_unique_palettes_and_original_presets(self):
        self.assertEqual(100, len(self.themes))
        fingerprints = {json.dumps({key: value for key, value in theme.items()
                                   if isinstance(value, dict) and key != "font"}, sort_keys=True)
                        for theme in self.themes.values()}
        self.assertEqual(100, len(fingerprints))
        catalog = json.loads((importer.DESTINATION / "catalog.json").read_text())
        self.assertEqual(103, len(catalog["themes"]))
        self.assertEqual(["classic", "dark", "light"], [entry["id"] for entry in catalog["themes"][:3]])
        names = [self.themes[entry["id"]]["name"] for entry in catalog["themes"][3:]]
        self.assertEqual(sorted(names, key=str.lower), names)
        for original in self.manifest["primary"]:
            self.assertEqual(original["sha256"], hashlib.sha256((importer.DESTINATION / original["file"]).read_bytes()).hexdigest())

    def test_original_editor_backgrounds_are_preserved_across_all_formats(self):
        for entry in self.manifest["themes"]:
            with self.subTest(theme=entry["id"], format=entry["format"]):
                self.assertEqual(importer.extract(entry)["background"], self.themes[entry["id"]]["editor"]["background"])
        expected = {"dracula": "#282A36", "nord": "#2E3440", "catppuccin-mocha": "#1E1E2E",
                    "tokyo-night": "#1A1B26", "rainglow-peacock": "#2B2A27", "rainglow-earthsong": "#36312C",
                    "vscode-high-contrast-light": "#FFFFFF"}
        for name, background in expected.items():
            self.assertEqual(background, self.themes[name]["editor"]["background"])

    def test_adapted_text_and_controls_have_calculated_contrast(self):
        for name, theme in self.themes.items():
            pairs = [("editor.foreground", theme["editor"]["foreground"], theme["editor"]["background"], 4.5)]
            pairs += [("syntax." + role, value, theme["editor"]["background"], 4.5) for role, value in theme["syntax"].items()]
            for role in ("foreground", "heading", "muted", "link", "accent"):
                pairs += [("ui." + role, theme["ui"][role], theme["ui"][background], 4.5) for background in ("background", "surface")]
            for role in ("focus", "border"):
                pairs += [("ui." + role, theme["ui"][role], theme["ui"][background], 3) for background in ("background", "surface")]
            pairs += [("menu", theme["menu"]["foreground"], theme["menu"]["background"], 4.5),
                      ("selection", theme["editor"]["selectionForeground"], theme["editor"]["selection"], 4.5),
                      ("button", theme["ui"]["accentForeground"], theme["ui"]["accent"], 4.5),
                      ("console", theme["messages"]["foreground"], theme["messages"]["background"], 4.5)]
            for role, text, background, minimum in pairs:
                with self.subTest(theme=name, role=role):
                    self.assertGreaterEqual(importer.contrast(text, background), minimum)

    def test_source_notices_are_fully_retained_in_bundled_application_resource(self):
        notices = (importer.DESTINATION / "THIRD_PARTY_NOTICES.txt").read_text()
        for source in self.manifest["sources"]:
            self.assertIn((importer.SOURCES / source["licensePath"]).read_text().strip(), notices)
            self.assertIn(source["commit"], notices)

    def test_jsonc_preserves_strings_and_removes_comments_and_trailing_commas(self):
        value = importer.jsonc('''{
          // A line comment
          "url": "https://example.test/a//b", /* comment */
          "literal": "comma, } // /* unchanged */",
          "escaped": "a\\\"b", "list": [1, 2,],
        }''')
        self.assertEqual("https://example.test/a//b", value["url"])
        self.assertEqual("comma, } // /* unchanged */", value["literal"])
        self.assertEqual('a"b', value["escaped"])
        self.assertEqual([1, 2], value["list"])

    def test_alpha_colors_are_composited_and_invalid_colors_rejected(self):
        self.assertEqual("#800000", importer.opaque("#ff000080", "#000000"))
        self.assertEqual("#AABBCC", importer.opaque("#abc", "#000000"))
        self.assertIsNone(importer.opaque("url(https://invalid.test)", "#000000"))
        self.assertIsNone(importer.opaque("#12345", "#000000"))

    def test_modified_originals_fail_before_any_output_is_written(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "source.json").write_text("modified upstream contents")
            manifest = root / "manifest.json"
            manifest.write_text(json.dumps({"files": [{"localPath": "source.json", "sha256": "0" * 64}]}))
            with patch.object(importer, "SOURCES", root), patch.object(importer, "MANIFEST", manifest):
                with self.assertRaisesRegex(ValueError, "Source hash mismatch"):
                    importer.generate(check=True)


if __name__ == "__main__":
    unittest.main(verbosity=2)
