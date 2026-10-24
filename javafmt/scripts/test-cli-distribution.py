#!/usr/bin/env python3
"""Smoke-test an already packaged javafmt runtime without an installed JDK."""
import json
import pathlib
import subprocess
import sys
import tempfile

if len(sys.argv) != 2:
    raise SystemExit("Usage: python3 scripts/test-cli-distribution.py DISTRIBUTION_DIRECTORY")

root = pathlib.Path(sys.argv[1]).resolve()
command = root / "javafmt"


def invoke(*arguments, source=None):
    return subprocess.run([str(command), *map(str, arguments)], input=source,
                          text=True, capture_output=True, timeout=30)


result = invoke("--version")
assert result.returncode == 0, result.stderr
assert "javafmt 0.1.0" in result.stdout
result = invoke("--stdin", source="class Main{int n=1;}")
assert result.returncode == 0, result.stderr
assert result.stdout == "class Main {\n    int n = 1;\n}\n", result.stdout
with tempfile.TemporaryDirectory(prefix="javafmt-runtime-") as directory:
    file = pathlib.Path(directory) / "Main.java"
    file.write_text("class Main{int n=1;}", encoding="utf-8")
    file.chmod(0o640)
    assert invoke("--check", file).returncode == 1
    assert invoke("--diff", file).stdout.startswith("--- ")
    assert invoke("-w", file).returncode == 0
    assert invoke("--check", file).returncode == 0
    assert file.stat().st_mode & 0o777 == 0o640
    assert invoke("--diff", file).stdout == ""
assert invoke("--stdin", source="class {").returncode == 2
request = {"protocolVersion": 1, "id": "runtime-smoke", "sourceVersion": 3,
           "source": "class C{int n=1;}"}
result = invoke("--server", source=json.dumps(request) + "\n")
reply = json.loads(result.stdout)
assert result.returncode == 0 and reply["success"] and reply["sourceVersion"] == 3
assert reply["edits"]
assert (root / "docs/runtime-dependencies.json").is_file()
print("PASS bundled runtime: version, stdin, check mismatch/clean, diff, atomic write, "
      "POSIX permissions, invalid source, versioned resident JSONL, dependency manifest")
