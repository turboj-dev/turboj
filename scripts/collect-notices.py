#!/usr/bin/env python3
"""Preserve each pinned runtime artifact's original legal resources before jar shading."""
import hashlib
import json
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
REPO = Path(sys.argv[1]) if len(sys.argv) > 1 else ROOT / '.cache/m2'
DEST = ROOT / 'app/src/main/resources/third-party'
NS = {'m': 'http://maven.apache.org/POM/4.0.0'}
SUPPLEMENTAL = {
    'gson': 'https://raw.githubusercontent.com/google/gson/gson-parent-2.13.2/LICENSE',
    'error_prone_annotations': 'https://raw.githubusercontent.com/google/error-prone/v2.41.0/COPYING',
}


def pom_licenses(path, depth=0):
    if depth > 8 or not path.exists():
        return []
    tree = ET.parse(path).getroot()
    namespace = NS if tree.tag.startswith('{') else {}
    prefix = 'm:' if namespace else ''
    names = [x.text for x in tree.findall(f'{prefix}licenses/{prefix}license/{prefix}name', namespace)]
    if names:
        return names
    parent = tree.find(prefix + 'parent', namespace)
    if parent is None:
        return []
    group, artifact, version = [parent.findtext(prefix + key, '', namespace) for key in ['groupId', 'artifactId', 'version']]
    return pom_licenses(REPO / group.replace('.', '/') / artifact / version / f'{artifact}-{version}.pom', depth + 1)


def main():
    manifest = []
    supplement_path = ROOT / 'docs/runtime-legal-supplements.json'
    supplements = json.loads(supplement_path.read_text()) if supplement_path.exists() else {}
    for row in (ROOT / 'docs/runtime-dependencies.tsv').read_text().splitlines():
        if not row or row.startswith('#'):
            continue
        columns = row.split('\t')
        group, artifact, version = columns[:3]
        classifier = columns[3] if len(columns) > 3 else ''
        suffix = '-' + classifier if classifier else ''
        relative = f'{group.replace(".", "/")}/{artifact}/{version}/{artifact}-{version}'
        jar, pom = REPO / (relative + suffix + '.jar'), REPO / (relative + '.pom')
        if not jar.exists() or not pom.exists():
            raise SystemExit(f'Resolve the pinned dependencies first: missing {jar}')
        destination = DEST / f'{artifact}-{version}{suffix}'
        destination.mkdir(parents=True, exist_ok=True)
        (destination / 'artifact.pom').write_bytes(pom.read_bytes())
        files = []
        with zipfile.ZipFile(jar) as archive:
            for entry in archive.namelist():
                if entry.endswith('/') or entry.endswith('.class'):
                    continue
                if not re.search(r'(^|/)(license[^/]*|notice[^/]*|about[^/]*|legal[^/]*|epl[^/]*)($|/)', entry, re.I):
                    continue
                target = destination / entry
                if not target.resolve().is_relative_to(destination.resolve()):
                    raise SystemExit(f'Unsafe archive path in {jar}: {entry}')
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(archive.read(entry))
                files.append(entry)
        entry = {'groupId': group, 'artifactId': artifact, 'version': version,
                         'sha256': hashlib.sha256(jar.read_bytes()).hexdigest(),
                         'jar': jar.name,
                         **({'classifier': classifier} if classifier else {}),
                         'declaredLicenses': pom_licenses(pom),
                         'sourceArchive': 'https://repo.maven.apache.org/maven2/' + relative + '-sources.jar',
                         'preservedLegalFiles': files}
        key = ':'.join([group, artifact, version] + ([classifier] if classifier else []))
        if key in supplements:
            for resource in supplements[key]:
                local = destination / resource['file']
                if not local.is_file() or hashlib.sha256(local.read_bytes()).hexdigest() != resource['sha256']:
                    raise SystemExit(f'Preserve the audited upstream legal supplement: {local}')
            entry['supplementalLegalFiles'] = supplements[key]
        if not entry['declaredLicenses']:
            raise SystemExit(f'No verified upstream license declaration for {key}')
        if artifact in SUPPLEMENTAL:
            if not (destination / 'LICENSE').is_file():
                raise SystemExit(f'Keep the checked-in upstream LICENSE: {destination}')
            entry['supplementalLicenseFile'] = 'LICENSE'
            entry['supplementalLicenseSource'] = SUPPLEMENTAL[artifact]
        manifest.append(entry)
    DEST.mkdir(parents=True, exist_ok=True)
    content = json.dumps(manifest, indent=2) + '\n'
    (DEST / 'runtime-dependencies.json').write_text(content)
    (ROOT / 'docs/runtime-dependencies.json').write_text(content)
    (DEST / 'README.txt').write_text(
        'TurboJ third-party runtime components\n'
        'Each artifact directory retains its unmodified upstream POM and legal notices.\n'
        'runtime-dependencies.json identifies versions, original jar checksums, and exact source archive URLs.\n'
        'These artifacts retain their upstream licenses; TurboJ MIT licensing does not relicense them.\n'
        'Generated by scripts/collect-notices.py from the actual resolved artifacts.\n')
    print(f'Preserved {sum(len(x["preservedLegalFiles"]) for x in manifest)} original JAR legal files and {sum(len(x.get("supplementalLegalFiles", [])) for x in manifest)} audited supplements from {len(manifest)} runtime artifacts.')


if __name__ == '__main__':
    main()
