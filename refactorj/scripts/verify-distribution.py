#!/usr/bin/env python3
"""Verify shipped CLI/IDE archives against their exact embedded dependency inventory."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import zipfile

parser = argparse.ArgumentParser()
parser.add_argument('archives', nargs='+', type=Path)
args = parser.parse_args()
digest = lambda data: hashlib.sha256(data).hexdigest()
for path in args.archives:
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        inventories = [name for name in names if name.endswith('/docs/runtime-dependencies.json')]
        if len(inventories) != 1:
            raise SystemExit('Expected one complete dependency inventory: ' + str(path))
        inventory = inventories[0]
        docs = inventory.removesuffix('runtime-dependencies.json')
        records = json.loads(archive.read(inventory))
        expected_external = {record['artifactId'] + '-' + record['version'] + '.jar': record['artifactSha256'] for record in records}
        for entry in names:
            if '/lib/' in entry and entry.endswith('.jar') and not Path(entry).name.startswith('refactorj-'):
                if expected_external.get(Path(entry).name) != digest(archive.read(entry)):
                    raise SystemExit('Unknown or changed external dependency: ' + entry)
        def library(filename):
            entries = [name for name in names if name.endswith('/lib/' + filename)]
            if len(entries) != 1:
                raise SystemExit('Missing/duplicate library ' + filename + ' in ' + str(path))
            return archive.read(entries[0])
        with zipfile.ZipFile(io.BytesIO(library('refactorj-runtime-0.1.0.jar'))) as runtime:
            for record in records:
                location = record['location']
                data = runtime.read(location.split('!/', 1)[1]) if '!/' in location else library(Path(location).name)
                if digest(data) != record['artifactSha256']:
                    raise SystemExit('Artifact mismatch: ' + record['artifactId'])
                with zipfile.ZipFile(io.BytesIO(data)) as dependency:
                    for name, checksum in record['preservedLegalFiles'].items():
                        if digest(dependency.read(name)) != checksum:
                            raise SystemExit('License resource mismatch: ' + record['artifactId'] + '/' + name)
                identity = record['groupId'] + '.' + record['artifactId'] + '-' + record['version']
                if digest(archive.read(docs + 'third-party/' + identity + '/artifact.pom')) != record['pomSha256']:
                    raise SystemExit('Maven provenance mismatch: ' + identity)
                if record['supplementalLicense']:
                    archive.read(docs + record['supplementalLicense'])
        if not any(name.endswith('/NOTICE') for name in names):
            raise SystemExit('Missing distribution NOTICE')
        if path.suffix == '.vsix':
            if any('/out/test/' in name or '/node_modules/' in name for name in names):
                raise SystemExit('Development-only files in VSIX')
        plugin_jars = [name for name in names if name.endswith('/lib/refactorj-intellij.jar')]
        for plugin in plugin_jars:
            with zipfile.ZipFile(io.BytesIO(archive.read(plugin))) as jar:
                if any('HostSmokeStarter' in name for name in jar.namelist()) or b'refactorj-host-test' in jar.read('META-INF/plugin.xml'):
                    raise SystemExit('Test-only starter in IntelliJ release')
    print('PASS', path, str(len(records)) + ' exact third-party JARs, notices, Maven provenance and release contents;', 'SHA-256', digest(path.read_bytes()))
