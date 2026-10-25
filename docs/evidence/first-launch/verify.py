#!/usr/bin/env python3
"""Verify committed capture data; --originals also requires the local raw captures."""
import argparse
import hashlib
import json
from pathlib import Path


def digest(data):
    return hashlib.sha256(data).hexdigest()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--originals", action="store_true")
    args = parser.parse_args()
    root = Path(__file__).resolve().parent
    repository = root.parents[2]
    manifest = json.loads((root / "manifest.json").read_text())
    for entry in manifest["files"]:
        path = entry["path"]
        if "#" not in path:
            data = (root / path).read_bytes()
        else:
            filename, fragment = path.split("#", 1)
            bundle = json.loads((root / filename).read_text())
            checkpoint, key = fragment.strip("/").split("/", 1)
            if filename == "controls.json":
                value = bundle[checkpoint][key]
            elif key.startswith("buffers/"):
                value = bundle[checkpoint]["buffers"][key.removeprefix("buffers/")]
            else:
                value = bundle[checkpoint][key]
            data = value.encode("utf-8")
        assert digest(data) == entry["curatedSha256"], path
        if args.originals:
            original = repository / entry["originalPath"]
            assert digest(original.read_bytes()) == entry["originalSha256"], str(original)
    for path, expected in manifest["wholeFileSha256"].items():
        assert digest((root / path).read_bytes()) == expected, path

    run = "run" + manifest["run"]
    checkpoints = json.loads((root / run / "checkpoints.json").read_text())
    source_files = [
        "src/main/java/tutorial/HelloWorld.java",
        "src/main/java/tutorial/Calculator.java",
        "src/test/java/tutorial/CalculatorTest.java",
    ]
    for kind in ("java", "maven"):
        green = checkpoints[kind + "-green"]
        undo = checkpoints[kind + "-linked-rename-undo"]
        for source in source_files:
            assert (root / "fixtures" / kind / source).read_text() == green["buffers"][source]
        assert "addition(int left, int right)" in green["buffers"][source_files[1]]
        assert ".addition(20, 22)" in green["buffers"][source_files[2]]
        assert "add(int left, int right)" in undo["buffers"][source_files[1]]
        assert ".add(20, 22)" in undo["buffers"][source_files[2]]
        assert "status=PASSED" in green["state"] and "engineFailures=[]" in green["state"]
        if args.originals:
            raw = repository / ("app/target/first-launch-keyboard-smoke-" + manifest["run"]) / (kind + "-green/project")
            for path in (root / "fixtures" / kind).rglob("*"):
                if path.is_file():
                    assert path.read_bytes() == (raw / path.relative_to(root / "fixtures" / kind)).read_bytes()
    assert (root / "fixtures/java/.turboj/project.properties").is_file()
    assert (root / "fixtures/java/.turboj/junit.properties").is_file()
    assert "Maven resolved: true" in checkpoints["maven-green"]["state"]
    assert "Maven offline: true" in checkpoints["maven-green"]["state"]
    for path in root.rglob("*"):
        if path.is_file() and path.suffix not in (".png", ".py"):
            text = path.read_text()
            assert "/Users/" not in text and "/private/var/folders/" not in text and "/var/folders/" not in text, str(path)
    if args.originals:
        assert digest((repository / "app/target/turboj-app.jar").read_bytes()) == manifest["artifactSha256"]
        assert digest((repository / "app/target/test-classes/dev/turboj/app/FirstLaunchKeyboardSmoke.class").read_bytes()) == manifest["harnessClassSha256"]
        assert digest((repository / "app/src/test/java/dev/turboj/app/FirstLaunchKeyboardSmoke.java").read_bytes()) == manifest["harnessSourceSha256"]
    print(f"PASS {len(manifest['files'])} logical artifacts; {len(manifest['wholeFileSha256'])} files; source/buffer/Undo/JUnit/Maven assertions; path redaction.")


if __name__ == "__main__":
    main()
