#!/usr/bin/env python3
"""Bind the built JAR to its exact production inputs; refuse stale skip-build packages."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import shutil
import stat
import subprocess
from datetime import datetime, timezone

MODULES = ('app', 'core', 'worker', 'cli', 'javafmt', 'refactorj', 'commandj', 'mavenj', 'turbotest', 'turbo-ai')


def digest(path):
    value = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            value.update(chunk)
    return value.hexdigest()


def inputs(root):
    files = [root / 'pom.xml', root / 'mvnw', root / 'scripts/package-macos.sh',
             root / 'scripts/build-receipt.py']
    for module in MODULES:
        directory = root / module
        if not directory.exists():
            continue
        for path in directory.rglob('*'):
            relative = path.relative_to(root)
            parts = relative.parts
            if path.is_file() and 'target' not in parts and 'node_modules' not in parts and (
                path.name == 'pom.xml' or ('src' in parts and 'main' in parts) or 'packaging' in parts
            ):
                files.append(path)
    if (root / '.mvn').exists():
        files.extend(p for p in (root / '.mvn').rglob('*') if p.is_file())
    return {p.relative_to(root).as_posix(): digest(p) for p in sorted(set(files)) if p.is_file()}


def effective_java(environment=None):
    environment = os.environ if environment is None else environment
    home = environment.get('JAVA_HOME')
    return str(Path(home) / 'bin/java') if home else (shutil.which('java') or 'java')


def capture(root, artifact):
    def command(*args):
        return subprocess.check_output(args, cwd=root, text=True, stderr=subprocess.STDOUT).strip()
    return {'schemaVersion': 1, 'capturedAt': datetime.now(timezone.utc).isoformat(),
            'commit': command('git', 'rev-parse', 'HEAD'),
            'worktree': command('git', 'status', '--porcelain'),
            'javaExecutable': effective_java(), 'java': command(effective_java(), '-version'),
            'maven': command('./mvnw', '--version'),
            'artifact': artifact.relative_to(root).as_posix(), 'sha256': digest(artifact),
            'sources': inputs(root), 'scope': 'Production JAR inputs; local unsigned build, not a deployment receipt'}


def package_files(bundle, receipt):
    jar = bundle / 'Contents/app/turboj-app.jar'
    if not jar.is_file() or digest(jar) != receipt['sha256']:
        raise ValueError('Packaged JAR does not match the qualified build receipt')
    required = ('Contents/app/turboj-app.jar', 'Contents/app/TurboJ.cfg', 'Contents/Info.plist',
             'Contents/MacOS/TurboJ', 'Contents/runtime/Contents/Home/bin/java',
             'Contents/runtime/Contents/Home/lib/modules', 'Contents/runtime/Contents/Home/release',
             'Contents/Resources/build-receipt.json', 'Contents/_CodeSignature/CodeResources')
    for name in required:
        if not (bundle / name).is_file():
            raise ValueError(f'Incomplete sealed application: {name}')
    return package_inventory(bundle)['files']


def package_inventory(bundle):
    bundle = bundle.resolve()
    files, links, modes = {}, {}, {}
    for path in sorted(bundle.rglob('*')):
        name = path.relative_to(bundle).as_posix()
        if path.is_symlink():
            if not path.resolve().is_relative_to(bundle):
                raise ValueError(f'Application symlink escapes the bundle: {name}')
            links[name] = os.readlink(path)
        elif path.is_file():
            files[name] = digest(path)
            modes[name] = stat.S_IMODE(path.stat().st_mode)
        elif not path.is_dir():
            raise ValueError(f'Unsupported application entry: {name}')
    return {'files': files, 'links': links, 'modes': modes}


def package_receipt_path(bundle):
    # A receipt that hashes the signed launcher cannot itself be sealed inside that launcher.
    return bundle.with_suffix('.package-receipt.json')


def verify_package(bundle, receipt):
    if receipt.get('schemaVersion') != 2 or not receipt.get('files'):
        raise ValueError('Unsupported or empty package receipt')
    inventory = package_inventory(bundle)
    for name in ('files', 'links', 'modes'):
        if inventory[name] != receipt.get(name):
            raise ValueError(f'Application {name} differ from the sealed package receipt')
    embedded = bundle / 'Contents/Resources/build-receipt.json'
    if digest(embedded) != receipt['buildReceiptSha256']:
        raise ValueError('Embedded build receipt differs from the package receipt')


def capture_package(bundle, receipt_path, jdk):
    receipt = json.loads(receipt_path.read_text())
    package_files(bundle, receipt)
    if digest(bundle / 'Contents/Resources/build-receipt.json') != digest(receipt_path):
        raise ValueError('Embedded build receipt does not match the qualified build receipt')
    runtime = bundle / 'Contents/runtime/Contents/Home/bin/java'
    def version(executable):
        return subprocess.check_output([str(executable), '-version'], text=True, stderr=subprocess.STDOUT).strip()
    return {'schemaVersion': 2, 'capturedAt': datetime.now(timezone.utc).isoformat(),
            'buildReceiptSha256': digest(receipt_path), **package_inventory(bundle),
            'packagingJdk': str(jdk), 'packagingJava': version(jdk / 'bin/java'),
            'bundledJava': version(runtime), 'architecture': platform.machine(),
            'scope': 'Final ad-hoc signed local app identity; external receipt, no Developer ID signing, notarization or deployment'}


def verify(root, receipt):
    if receipt.get('schemaVersion') != 1:
        raise ValueError('Unsupported build receipt version')
    artifact = (root / receipt['artifact']).resolve()
    if not artifact.is_relative_to(root.resolve()) or not artifact.is_file() or digest(artifact) != receipt['sha256']:
        raise ValueError('JAR differs from the build receipt; rebuild and capture a new receipt')
    if inputs(root) != receipt['sources']:
        raise ValueError('Production inputs changed after the build receipt; rebuild before packaging')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('begin', 'create', 'verify', 'package', 'verify-package'))
    parser.add_argument('--artifact', default='app/target/turboj-app.jar')
    parser.add_argument('--output', default='app/target/build-receipt.json')
    parser.add_argument('--package', type=Path)
    parser.add_argument('--jdk', type=Path)
    parser.add_argument('--package-receipt', type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    output = root / args.output
    marker = root / '.cache/build-inputs.json'
    if args.action == 'begin':
        marker.parent.mkdir(parents=True, exist_ok=True)
        marker.write_text(json.dumps(inputs(root), sort_keys=True))
    elif args.action == 'create':
        value = capture(root, root / args.artifact)
        if not marker.is_file() or json.loads(marker.read_text()) != value['sources']:
            raise ValueError('Build inputs changed or were not captured before building. Run begin, build, then create.')
        output.parent.mkdir(parents=True, exist_ok=True)
        temporary = output.with_suffix('.tmp')
        temporary.write_text(json.dumps(value, indent=2, sort_keys=True) + '\n')
        temporary.replace(output)
    elif args.action == 'verify':
        verify(root, json.loads(output.read_text()))
    elif args.action == 'package':
        if args.package is None or args.jdk is None:
            parser.error('package requires --package and --jdk')
        verify(root, json.loads(output.read_text()))
        value = capture_package(args.package.resolve(), output, args.jdk.resolve())
        output = args.package_receipt or package_receipt_path(args.package)
        if output.resolve().is_relative_to(args.package.resolve()):
            raise ValueError('The package receipt must remain outside the signed application')
        output.write_text(json.dumps(value, indent=2, sort_keys=True) + '\n')
    else:
        if args.package is None:
            parser.error('verify-package requires --package')
        output = args.package_receipt or package_receipt_path(args.package)
        verify_package(args.package, json.loads(output.read_text()))
    print(f'Build receipt {args.action}: {output}')


if __name__ == '__main__':
    main()
