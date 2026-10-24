#!/usr/bin/env python3
"""Package original MavenJ/runtime JARs and complete upstream legal provenance."""
from pathlib import Path
import hashlib
import json
import re
import shutil
import subprocess
import zipfile

root = Path(__file__).resolve().parents[1]
target = root / 'target/mavenj-0.1.0'
libraries = target / 'lib'
libraries.mkdir(parents=True, exist_ok=True)
inventory_file = root / 'mavenj-mcp/target/runtime-dependencies.txt'
if not inventory_file.exists():
    raise SystemExit('Run ./mvnw -Prelease-artifacts package first')
records = []
expected = set()
digest = lambda data: hashlib.sha256(data).hexdigest()
supplement_specs = json.loads((root / 'docs/runtime-legal-supplements.json').read_text())
used_supplements = set()


def checked_legal_resource(directory, entry, expected_hash):
    """Resolve only local, reviewed resources and reject changed upstream text."""
    resource = directory / entry
    if not resource.resolve().is_relative_to(directory.resolve()):
        raise SystemExit('Unsafe supplemental legal resource: ' + entry)
    if not resource.is_file():
        raise SystemExit('Missing checked-in upstream legal resource: ' + str(resource))
    if digest(resource.read_bytes()) != expected_hash:
        raise SystemExit('Upstream legal resource hash mismatch: ' + str(resource))
    return resource


for line in inventory_file.read_text().splitlines():
    parts = line.strip().split(':')
    if len(parts) < 6 or parts[2] != 'jar':
        continue
    scope = next((i for i in range(4, len(parts)) if parts[i] in ('compile', 'runtime')), None)
    if scope is None:
        continue
    group, artifact, version = parts[0], parts[1], parts[scope - 1]
    source = Path(':'.join(parts[scope + 1:]).split(' -- module ', 1)[0].strip())
    if group == 'dev.turboj.mavenj':
        source = root / artifact / 'target' / f'{artifact}-{version}.jar'
    if not source.is_file():
        raise SystemExit('Missing resolved dependency: ' + str(source))
    expected.add(source.name)
    shutil.copyfile(source, libraries / source.name)
    if group == 'dev.turboj.mavenj':
        continue
    pom = source.with_name(f'{artifact}-{version}.pom')
    if not pom.is_file():
        raise SystemExit('Missing original dependency POM: ' + str(pom))
    identity = f'{group}.{artifact}-{version}'
    legal = root / 'docs/third-party' / identity
    legal.mkdir(parents=True, exist_ok=True)
    (legal / 'artifact.pom').write_bytes(pom.read_bytes())
    notices = {}
    with zipfile.ZipFile(source) as archive:
        for entry in archive.namelist():
            if entry.endswith('/') or entry.endswith('.class') or not re.search(r'(^|/)(license[^/]*|notice[^/]*|about[^/]*|legal[^/]*)($|/)', entry, re.I):
                continue
            destination = legal / entry
            if not destination.resolve().is_relative_to(legal.resolve()):
                raise SystemExit('Unsafe legal resource: ' + entry)
            destination.parent.mkdir(parents=True, exist_ok=True)
            data = archive.read(entry)
            destination.write_bytes(data)
            notices[entry] = digest(data)
    key = f'{group}:{artifact}:{version}'
    supplements = supplement_specs.get(key, [])
    for supplement in supplements:
        checked_legal_resource(legal, supplement['file'], supplement['sha256'])
    if supplements:
        used_supplements.add(key)
    if not notices and not supplements:
        raise SystemExit('No preserved legal coverage for dependency: ' + key)
    supplemental = next((s['sha256'] for s in supplements if s['file'] == 'LICENSE'), None)
    records.append(dict(groupId=group, artifactId=artifact, version=version, jar=source.name,
                        artifactSha256=digest(source.read_bytes()), pomSha256=digest(pom.read_bytes()),
                        preservedLegalFiles=notices, supplementalLicenseSha256=supplemental,
                        supplementalLegalFiles=supplements,
                        sourceArchive=f'https://repo.maven.apache.org/maven2/{group.replace(".", "/")}/{artifact}/{version}/{artifact}-{version}-sources.jar'))
unused_supplements = set(supplement_specs) - used_supplements
if unused_supplements:
    raise SystemExit('Supplemental legal inventory has stale dependencies: ' + ', '.join(sorted(unused_supplements)))
own = root / 'mavenj-mcp/target/mavenj-mcp-0.1.0.jar'
shutil.copyfile(own, libraries / own.name)
expected.add(own.name)
for stale in libraries.glob('*.jar'):
    if stale.name not in expected:
        raise SystemExit('Stale library in packaging directory; review/remove before release: ' + str(stale))
(root / 'docs/runtime-dependencies.json').write_text(json.dumps(sorted(records, key=lambda x: (x['groupId'], x['artifactId'])), indent=2) + '\n')
shutil.copytree(root / 'docs', target / 'docs', dirs_exist_ok=True)
shutil.copytree(root / 'examples', target / 'examples', dirs_exist_ok=True)
for name in ('README.md', 'LICENSE', 'NOTICE', 'CHANGELOG.md'):
    shutil.copyfile(root / name, target / name)
(target / 'bin').mkdir(exist_ok=True)
for source in (root / 'bin').iterdir():
    destination = target / 'bin' / source.name
    shutil.copyfile(source, destination)
    destination.chmod(0o755)
result = subprocess.run([str(target / 'bin/mavenj'), '--version'], capture_output=True, text=True, check=True)
if result.stdout.strip() != 'mavenj 0.1.0':
    raise SystemExit('Unexpected packaged CLI identity')
archive_path = target.parent / 'mavenj-0.1.0.zip'
with zipfile.ZipFile(archive_path, 'w', zipfile.ZIP_DEFLATED) as archive:
    for path in sorted(target.rglob('*')):
        if not path.is_file():
            continue
        relative = path.relative_to(target)
        info = zipfile.ZipInfo(str(Path(target.name) / relative), (2026, 10, 24, 0, 0, 0))
        info.compress_type = zipfile.ZIP_DEFLATED
        info.external_attr = (0o755 if relative.parts[0] == 'bin' else 0o644) << 16
        archive.writestr(info, path.read_bytes())
with zipfile.ZipFile(archive_path) as archive:
    for record in records:
        if digest(archive.read(f'{target.name}/lib/{record["jar"]}')) != record['artifactSha256']:
            raise SystemExit('Packaged artifact hash mismatch')
        legal_prefix = f'{target.name}/docs/third-party/{record["groupId"]}.{record["artifactId"]}-{record["version"]}'
        expected_legal = dict(record['preservedLegalFiles'])
        expected_legal['artifact.pom'] = record['pomSha256']
        expected_legal.update((item['file'], item['sha256']) for item in record['supplementalLegalFiles'])
        for entry, expected_hash in expected_legal.items():
            if digest(archive.read(f'{legal_prefix}/{entry}')) != expected_hash:
                raise SystemExit('Packaged legal resource hash mismatch: ' + legal_prefix + '/' + entry)
print(f'PASS packaged CLI startup; {len(records)} original third-party JARs with legal provenance')
print(archive_path)
print('SHA-256', digest(archive_path.read_bytes()))
