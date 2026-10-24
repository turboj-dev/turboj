#!/usr/bin/env python3
"""Build against an installed, real IntelliJ SDK. No mocked platform classes."""
import os
from pathlib import Path
import shutil
import subprocess
import zipfile
import json

root = Path(__file__).resolve().parents[1]
idea = Path(os.environ.get('IDEA_HOME', '/Applications/IntelliJ IDEA.app/Contents'))
if not (idea / 'lib/app.jar').is_file():
    raise SystemExit('Set IDEA_HOME to an IntelliJ IDEA 2026.1 installation (Contents on macOS).')
java_home = os.environ.get('JAVA_HOME')
javac = str(Path(java_home) / 'bin/javac') if java_home else 'javac'
jar = root.parent / 'javafmt-cli/target/javafmt.jar'
if not jar.is_file():
    raise SystemExit('Build the engine first: ../mvnw -pl javafmt-cli -am package')
target = root / 'target'
classes = target / 'classes'
classes.mkdir(parents=True, exist_ok=True)
classpath = os.pathsep.join(str(p) for p in sorted((idea / 'lib').glob('*.jar')))
classpath += os.pathsep + os.pathsep.join(str(p) for p in sorted((idea / 'plugins/java/lib').glob('*.jar')))
sources = sorted((root / 'src/main/java').rglob('*.java'))
subprocess.run([javac, '--release', '21', '-encoding', 'UTF-8', '-cp', classpath, '-d', str(classes), *map(str, sources)], check=True)
package = target / 'javafmt'
(package / 'lib').mkdir(parents=True, exist_ok=True)
(package / 'engine').mkdir(parents=True, exist_ok=True)
with zipfile.ZipFile(package / 'lib/javafmt-intellij.jar', 'w', zipfile.ZIP_DEFLATED) as out:
    for p in sorted(classes.rglob('*.class')):
        out.write(p, p.relative_to(classes))
    for p in sorted((root / 'src/main/resources').rglob('*')):
        if p.is_file(): out.write(p, p.relative_to(root / 'src/main/resources'))
shutil.copyfile(jar, package / 'engine/javafmt.jar')
for filename in ('LICENSE', 'NOTICE'):
    if (root.parent / filename).is_file(): shutil.copyfile(root.parent / filename, package / filename)
for filename in ('LICENSES.md', 'runtime-dependencies.json'):
    source = root.parent / 'docs' / filename
    if source.is_file(): shutil.copyfile(source, package / filename)
archive = target / 'javafmt-intellij-0.1.0.zip'
with zipfile.ZipFile(archive, 'w', zipfile.ZIP_DEFLATED) as out:
    for p in sorted(package.rglob('*')):
        if p.is_file(): out.write(p, Path('javafmt') / p.relative_to(package))
(target / 'sdk-classpath.txt').write_text(classpath)
print(archive)
