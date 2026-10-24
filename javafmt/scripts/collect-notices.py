#!/usr/bin/env python3
"""Preserve exact resolved CLI runtime notices before shading; no network requests.

Adapted from TurboJ's collect-notices.py. Read dependency:list's actual runtime
report, rather than a hand-maintained list that might omit transitive artifacts.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import xml.etree.ElementTree as ET
import zipfile

ROOT = Path(__file__).resolve().parents[1]
DEST = ROOT / 'javafmt-cli/src/main/resources/third-party'
NS = {'m': 'http://maven.apache.org/POM/4.0.0'}
SUPPLEMENTAL = {
    ('com.google.code.gson', 'gson', '2.13.2'): (
        'gson-2.13.2-LICENSE',
        'https://raw.githubusercontent.com/google/gson/gson-parent-2.13.2/LICENSE'),
    ('com.google.errorprone', 'error_prone_annotations', '2.41.0'): (
        'error_prone_annotations-2.41.0-LICENSE',
        'https://raw.githubusercontent.com/google/error-prone/v2.41.0/COPYING'),
}
EPL_SOURCE = 'https://www.eclipse.org/org/documents/epl-2.0/EPL-2.0.txt'
LEGAL = re.compile(r'(^|/)(license[^/]*|notice[^/]*|about[^/]*|legal[^/]*|epl[^/]*)($|/)', re.I)
ANSI = re.compile(r'\x1b\[[0-9;]*m')
DEPENDENCY = re.compile(r'^\s+([\w.-]+):([\w.-]+):jar:([^:\s]+):(compile|runtime):(.+?)(?: -- module.*)?$')
FORBIDDEN_GROUPS = ('com.turboj', 'org.junit', 'org.openjdk.jmh', 'com.google.googlejavaformat')


def digest(data):
    return hashlib.sha256(data).hexdigest()


def pom_licenses(path, repo, depth=0):
    if depth > 8 or not path.exists():
        return []
    tree = ET.parse(path).getroot()
    licenses = [{'name': x.findtext('m:name', '', NS), 'url': x.findtext('m:url', '', NS)}
                for x in tree.findall('m:licenses/m:license', NS)]
    if licenses:
        return licenses
    parent = tree.find('m:parent', NS)
    if parent is None:
        return []
    group, artifact, version = [parent.findtext('m:' + key, '', NS)
                                for key in ('groupId', 'artifactId', 'version')]
    return pom_licenses(repo / group.replace('.', '/') / artifact / version / f'{artifact}-{version}.pom', repo, depth + 1)


def dependencies(report):
    artifacts = []
    first_party = set()
    for line in ANSI.sub('', report.read_text()).splitlines():
        if ':' not in line or line.strip() == 'The following files have been resolved:':
            continue
        match = DEPENDENCY.fullmatch(line)
        if not match:
            raise SystemExit(f'Unexpected runtime dependency report row: {line.strip()}')
        group, artifact, version, scope, location = match.groups()
        if group == 'dev.javafmt':
            if artifact not in ('javafmt-core', 'javafmt-parser'):
                raise SystemExit(f'Unexpected first-party runtime dependency: {artifact}')
            first_party.add(artifact)
            continue
        if group.startswith(FORBIDDEN_GROUPS):
            raise SystemExit(f'Non-production dependency in CLI runtime: {group}:{artifact}')
        jar = Path(location)
        relative = f'{group.replace(".", "/")}/{artifact}/{version}/{artifact}-{version}'
        if not str(jar).endswith(relative + '.jar'):
            raise SystemExit(f'Unexpected artifact location: {jar}')
        repo = Path(str(jar)[:-len(relative + '.jar')])
        artifacts.append((group, artifact, version, scope, jar, jar.with_suffix('.pom'), repo, relative))
    if first_party != {'javafmt-core', 'javafmt-parser'} or not artifacts:
        raise SystemExit('Expected the javafmt-cli runtime report, including core/parser dependencies')
    identities = [(x[0], x[1], x[2]) for x in artifacts]
    if len(identities) != len(set(identities)):
        raise SystemExit('Duplicate runtime coordinates in report')
    return sorted(artifacts)


def collect(report):
    artifacts = dependencies(report)
    expected_dirs = {f'{artifact}-{version}' for _, artifact, version, *_ in artifacts}
    if DEST.exists():
        stale = {p.name for p in DEST.iterdir() if p.is_dir()} - expected_dirs - {'licenses'}
        if stale:
            raise SystemExit(f'Review and remove obsolete generated notices first: {sorted(stale)}')
    manifest = []
    for group, artifact, version, scope, jar, pom, repo, relative in artifacts:
        if not jar.is_file() or not pom.is_file():
            raise SystemExit(f'Resolve pinned dependencies first: missing {jar} or {pom}')
        destination = DEST / f'{artifact}-{version}'
        destination.mkdir(parents=True, exist_ok=True)
        (destination / 'artifact.pom').write_bytes(pom.read_bytes())
        files, checksums = [], {}
        with zipfile.ZipFile(jar) as archive:
            for name in sorted(archive.namelist()):
                if name.endswith(('/', '.class')) or not LEGAL.search(name):
                    continue
                target = destination / name
                if not target.resolve().is_relative_to(destination.resolve()):
                    raise SystemExit(f'Unsafe archive path in {jar}: {name}')
                data = archive.read(name)
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(data)
                files.append(name)
                checksums[name] = digest(data)
        licenses = pom_licenses(pom, repo)
        if not licenses:
            raise SystemExit(f'Missing declared licensing metadata: {group}:{artifact}:{version}')
        entry = {
            'groupId': group, 'artifactId': artifact, 'version': version, 'scope': scope,
            'sha256': digest(jar.read_bytes()), 'pomSha256': digest(pom.read_bytes()),
            'declaredLicenses': licenses,
            'sourceArchive': 'https://repo.maven.apache.org/maven2/' + relative + '-sources.jar',
            'preservedLegalFiles': files, 'legalFileSha256': checksums,
        }
        supplemental = SUPPLEMENTAL.get((group, artifact, version))
        if supplemental:
            filename, url = supplemental
            data = (ROOT / 'scripts/legal' / filename).read_bytes()
            (destination / 'LICENSE').write_bytes(data)
            entry['supplementalLicenseFile'] = 'LICENSE'
            entry['supplementalLicenseSource'] = url
            entry['supplementalLicenseSha256'] = digest(data)
        if not files and not supplemental:
            raise SystemExit(f'No original notices found; manually review {group}:{artifact}:{version}')
        manifest.append(entry)
    license_dir = DEST / 'licenses'
    license_dir.mkdir(parents=True, exist_ok=True)
    epl = (ROOT / 'scripts/legal/EPL-2.0.txt').read_bytes()
    (license_dir / 'EPL-2.0.txt').write_bytes(epl)
    (license_dir / 'SOURCES.json').write_text(json.dumps({
        'EPL-2.0.txt': {'source': EPL_SOURCE, 'sha256': digest(epl)}}, indent=2) + '\n')
    content = json.dumps(manifest, indent=2) + '\n'
    (DEST / 'runtime-dependencies.json').write_text(content)
    (ROOT / 'docs/runtime-dependencies.json').write_text(content)
    (ROOT / 'docs/runtime-dependencies.tsv').write_text(
        '# Actual javafmt-cli runtime artifacts; generated from Maven dependency:list\n' +
        ''.join(f'{x[0]}\t{x[1]}\t{x[2]}\n' for x in artifacts))
    (DEST / 'README.txt').write_text(
        'javafmt third-party runtime components\n'
        'Each artifact directory preserves its unmodified upstream POM and legal notices.\n'
        'runtime-dependencies.json records exact versions, original JAR/POM and notice SHA-256\n'
        'checksums, declared licenses, and version-specific source archive URLs.\n'
        'The Eclipse components are available as source under EPL-2.0 at those URLs.\n'
        'These artifacts retain their upstream licenses; javafmt MIT licensing does not relicense them.\n'
        'JNA offers Apache-2.0 or LGPL-2.1-or-later; javafmt uses the Apache-2.0 option.\n'
        'Full Eclipse license: licenses/EPL-2.0.txt. Other full licenses remain in artifact directories.\n'
        'No TurboJ classes, test/benchmark dependencies or OpenJDK corpus are part of this CLI.\n'
        'Generated offline by scripts/collect-notices.py from the actual resolved runtime report.\n')
    (DEST.parent / 'javafmt-LICENSE.txt').write_bytes((ROOT / 'LICENSE').read_bytes())
    print(f'Preserved {sum(len(x["preservedLegalFiles"]) for x in manifest)} original legal files '
          f'from {len(manifest)} runtime artifacts, plus 3 supplemental full license texts.')


def verify_jar(path):
    """Verify retained legal bytes and reject accidentally shipped dev/editor classes."""
    manifest_path = DEST / 'runtime-dependencies.json'
    expected = {str(p.relative_to(DEST.parent)): p.read_bytes()
                for p in DEST.rglob('*') if p.is_file()}
    expected['javafmt-LICENSE.txt'] = (DEST.parent / 'javafmt-LICENSE.txt').read_bytes()
    with zipfile.ZipFile(path) as archive:
        names = archive.namelist()
        for name, data in expected.items():
            if name not in names or archive.read(name) != data:
                raise SystemExit(f'Missing or changed notice in shaded JAR: {name}')
        forbidden = ('com/turboj/', 'org/junit/', 'org/openjdk/jmh/',
                     'com/google/googlejavaformat/', 'javafmt-test-fixtures/', 'dev/javafmt/benchmarks/')
        bad = [name for name in names if name.startswith(forbidden)]
        if bad:
            raise SystemExit(f'Non-production contents in shaded JAR: {bad[:5]}')
        bundled = {(x['groupId'], x['artifactId'], x['version']) for x in json.loads(manifest_path.read_text())}
        actual = set()
        for name in names:
            if name.startswith('META-INF/maven/') and name.endswith('/pom.properties'):
                props = dict(line.split('=', 1) for line in archive.read(name).decode().splitlines()
                             if '=' in line and not line.startswith('#'))
                coordinate = tuple(props.get(k, '') for k in ('groupId', 'artifactId', 'version'))
                if coordinate[0] != 'dev.javafmt':
                    actual.add(coordinate)
        # Some OSGi artifacts omit Maven metadata. Every discovered coordinate must be covered.
        if actual - bundled:
            raise SystemExit(f'Unrecorded shaded dependency: {sorted(actual - bundled)}')
    print(f'Verified {len(expected)} exact legal-resource files in {path.name}; no TurboJ/test/benchmark contents.')


def main():
    args = argparse.ArgumentParser(description=__doc__)
    args.add_argument('--dependency-report', type=Path,
                      default=ROOT / 'javafmt-cli/target/runtime-dependencies.txt')
    args.add_argument('--verify-jar', type=Path,
                      help='verify existing collected resources in a shaded CLI instead of regenerating')
    options = args.parse_args()
    if options.verify_jar:
        verify_jar(options.verify_jar)
    else:
        collect(options.dependency_report)


if __name__ == '__main__':
    main()
