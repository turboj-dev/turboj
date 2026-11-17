#!/usr/bin/env python3
"""Generate the shipped catalog from the three pinned, licensed upstream Java XML files. No network."""
import json
from pathlib import Path
import xml.etree.ElementTree as ET

base = Path(__file__).resolve().parents[1] / "app/src/main/resources/live-templates"
templates = []
for group in ("Java", "JavaImplicitClass", "JavaNormalClassesWithInstanceMain"):
    for entry in ET.parse(base / f"{group}.xml").getroot():
        templates.append({
            "id": f"{group}/{entry.get('name')}",
            "abbreviation": entry.get("name"),
            "body": entry.get("value"),
            "contexts": [c.get("name") for c in entry.findall("context/option") if c.get("value") == "true"],
            "variables": [{"name": v.get("name"), "expression": v.get("expression"),
                           "defaultValue": v.get("defaultValue"), "alwaysStop": v.get("alwaysStopAt") == "true"}
                          for v in entry.findall("variable")],
        })
(base / "catalog.json").write_text(json.dumps({"schemaVersion": 1, "templates": templates}, indent=2) + "\n")
print(f"Generated {len(templates)} Java template definitions")
