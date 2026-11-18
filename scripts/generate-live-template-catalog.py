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
            "reformat": entry.get("toReformat") == "true",
            "shortenNames": entry.get("toShortenFQNames") == "true",
            "contexts": [c.get("name") for c in entry.findall("context/option") if c.get("value") == "true"],
            "variables": [{"name": v.get("name"), "expression": v.get("expression"),
                           "defaultValue": v.get("defaultValue"), "alwaysStop": v.get("alwaysStopAt") == "true"}
                          for v in entry.findall("variable")],
        })
# Java 25's compatible preview API uses unchecked FailedException, not the checked
# ExecutionException caught by the pinned upstream definition. Keep upstream XML unchanged.
for template in templates:
    if template["id"] == "Java/sts":
        template["body"] = template["body"].replace("java.util.concurrent.ExecutionException", "java.util.concurrent.StructuredTaskScope.FailedException")
        template["contexts"] = ["JAVA_STATEMENT_WITH_STRUCTURED_CONCURRENCY"]
        template["compatibility"] = "Java 25 StructuredTaskScope preview API; actual project capability is checked"
(base / "catalog.json").write_text(json.dumps({"schemaVersion": 1, "templates": templates}, indent=2) + "\n")
print(f"Generated {len(templates)} Java template definitions")
