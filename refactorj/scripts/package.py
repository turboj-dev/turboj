#!/usr/bin/env python3
"""Assemble independently runnable RefactorJ CLI/LSP/MCP from verified Maven artifacts."""
import argparse
from pathlib import Path
import shutil
import zipfile
import os
import subprocess

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--repository', type=Path, default=root.parent / '.cache/m2')
args = parser.parse_args()
subprocess.run(['python3', str(root / 'scripts/audit-runtime.py'), '--repository', str(args.repository)], check=True)
target = root / 'target/refactorj-0.1.0'
libraries = target / 'lib'
libraries.mkdir(parents=True, exist_ok=True)
for module in ['api', 'transactions', 'runtime', 'cli', 'lsp', 'mcp']:
    source = root / f'refactorj-{module}/target/refactorj-{module}-0.1.0.jar'
    if not source.is_file(): raise SystemExit('Run Maven verify first; missing ' + str(source))
    shutil.copyfile(source, libraries / source.name)
for relative in ['com/google/code/gson/gson/2.13.2/gson-2.13.2.jar', 'org/eclipse/platform/org.eclipse.osgi/3.24.0/org.eclipse.osgi-3.24.0.jar']:
    source = args.repository / relative
    if not source.is_file(): raise SystemExit('Missing pinned dependency ' + str(source))
    shutil.copyfile(source, libraries / source.name)
for source in (root / 'bin').iterdir():
    (target / 'bin').mkdir(exist_ok=True)
    shutil.copyfile(source, target / 'bin' / source.name)
    (target / 'bin' / source.name).chmod(0o755)
for name in ['README.md', 'LICENSE', 'NOTICE']:
    if (root / name).is_file(): shutil.copyfile(root / name, target / name)
shutil.copytree(root / 'docs', target / 'docs', dirs_exist_ok=True)
archive = target.parent / 'refactorj-0.1.0.zip'
with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED) as output:
    for path in sorted(target.rglob('*')):
        if not path.is_file(): continue
        info = zipfile.ZipInfo(str(Path(target.name) / path.relative_to(target)), (2026, 1, 1, 0, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        info.external_attr = (0o755 if path.parent.name == 'bin' else 0o644) << 16
        output.writestr(info, path.read_bytes())
print(archive)
