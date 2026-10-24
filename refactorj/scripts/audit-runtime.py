#!/usr/bin/env python3
"""Audit exact shipped third-party JAR bytes and preserve Maven provenance offline."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import shutil
import xml.etree.ElementTree as ET
import zipfile

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument('--repository', type=Path, default=root.parent / '.cache/m2')
args = parser.parse_args()
repository = args.repository.resolve()
namespace = {'m': 'http://maven.apache.org/POM/4.0.0'}
docs = root / 'docs'
provenance = docs / 'third-party'
provenance.mkdir(parents=True, exist_ok=True)
digest = lambda data: hashlib.sha256(data).hexdigest()

def licenses(pom, seen=None):
    seen = set() if seen is None else seen
    if pom in seen:
        raise SystemExit('Cyclic Maven license inheritance: ' + str(pom))
    seen.add(pom)
    model = ET.parse(pom).getroot()
    result = [{k: node.findtext('m:' + k, '', namespace) for k in ('name', 'url')}
              for node in model.findall('m:licenses/m:license', namespace)]
    if result:
        return result, pom
    parent = model.find('m:parent', namespace)
    if parent is not None:
        group, artifact, version = [parent.findtext('m:' + k, '', namespace)
                                    for k in ('groupId', 'artifactId', 'version')]
        path = repository / group.replace('.', '/') / artifact / version / (artifact + '-' + version + '.pom')
        if path.is_file():
            return licenses(path, seen)
    raise SystemExit('No declared license; review required: ' + str(pom))

runtime = root / 'refactorj-runtime/target/refactorj-runtime-0.1.0.jar'
jars = {}
with zipfile.ZipFile(runtime) as archive:
    for name in sorted(archive.namelist()):
        if name.startswith('refactorj/bundles/') and name.endswith('.jar') and not Path(name).name.startswith('refactorj-'):
            jars[Path(name).name] = (archive.read(name), 'refactorj-runtime-0.1.0.jar!/' + name)
for relative in ('com/google/code/gson/gson/2.13.2/gson-2.13.2.jar',
                 'org/eclipse/platform/org.eclipse.osgi/3.24.0/org.eclipse.osgi-3.24.0.jar'):
    path = repository / relative
    data = path.read_bytes()
    if path.name in jars and jars[path.name][0] != data:
        raise SystemExit('Different embedded and standalone bytes: ' + path.name)
    jars.setdefault(path.name, (data, 'lib/' + path.name))

records = []
expected_directories = set()
for filename, (data, location) in sorted(jars.items()):
    matches = [p for p in repository.rglob(filename) if p.is_file() and digest(p.read_bytes()) == digest(data)]
    if len(matches) != 1:
        raise SystemExit('Expected one exact Maven artifact for ' + filename + ', found ' + str(len(matches)))
    jar = matches[0]
    relative = jar.relative_to(repository)
    group, artifact, version = '.'.join(relative.parts[:-3]), relative.parts[-3], relative.parts[-2]
    pom = jar.with_suffix('.pom')
    declared, license_pom = licenses(pom)
    identity = group + '.' + artifact + '-' + version
    output = provenance / identity
    expected_directories.add(identity)
    output.mkdir(exist_ok=True)
    shutil.copyfile(pom, output / 'artifact.pom')
    if license_pom != pom:
        shutil.copyfile(license_pom, output / 'license-parent.pom')
    legal = {}
    with zipfile.ZipFile(io.BytesIO(data)) as original:
        for name in sorted(original.namelist()):
            base = Path(name).name.lower()
            if not name.endswith('/') and (base.startswith(('license', 'notice', 'copying', 'copyright', 'about')) or '/about_files/' in name):
                legal[name] = digest(original.read(name))
    # Animal Sniffer includes its entire MIT license and original copyright in the POM.
    supplemental = None
    if not legal:
        if artifact == 'gson' and version == '2.13.2':
            supplemental = 'licenses/gson-2.13.2-LICENSE'
        elif artifact == 'animal-sniffer-annotations' and version == '1.9':
            if b'The MIT License' not in pom.read_bytes():
                raise SystemExit('Animal Sniffer POM no longer contains its MIT notice')
            supplemental = 'third-party/' + identity + '/artifact.pom'
        else:
            raise SystemExit('No legal resource; review required: ' + filename)
    records.append(dict(groupId=group, artifactId=artifact, version=version,
                        artifactSha256=digest(data), pomSha256=digest(pom.read_bytes()),
                        location=location, declaredLicenses=declared,
                        sourceArchive='https://repo.maven.apache.org/maven2/' + str(relative.with_name(artifact + '-' + version + '-sources.jar')),
                        preservedLegalFiles=legal, supplementalLicense=supplemental))

stale = {p.name for p in provenance.iterdir() if p.is_dir()} - expected_directories
if stale:
    raise SystemExit('Review removed dependency provenance before deleting: ' + ', '.join(sorted(stale)))
for record in records:
    if record['supplementalLicense'] and not (docs / record['supplementalLicense']).is_file():
        raise SystemExit('Missing supplemental license: ' + record['supplementalLicense'])
(docs / 'runtime-dependencies.json').write_text(json.dumps(records, indent=2) + '\n')
print('Audited', len(records), 'exact third-party runtime artifacts; original JAR legal resources remain intact.')
