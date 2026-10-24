#!/usr/bin/env python3
"""Runs real IDEA with a temporary profile and test-only starter; leaves user settings alone."""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile

root = Path(__file__).resolve().parents[1]
subprocess.run(['python3', str(root / 'scripts/build.py')], check=True)
idea = Path(os.environ.get('IDEA_HOME', '/Applications/IntelliJ IDEA.app/Contents'))
profile = Path(tempfile.mkdtemp(prefix='idea-host-', dir=root / 'target'))
plugin = profile / 'plugins/refactorj'
shutil.copytree(root / 'target/refactorj', plugin)
classes = profile / 'test-classes'
classes.mkdir()
classpath = (root / 'target/sdk-classpath.txt').read_text() + os.pathsep + str(root / 'target/classes')
subprocess.run(['javac', '--release', '21', '-cp', classpath, '-d', str(classes),
                *map(str, (root / 'src/test/java').rglob('*.java'))], check=True)
mainjar = plugin / 'lib/refactorj-intellij.jar'
with zipfile.ZipFile(mainjar) as archive:
    entries = {n: archive.read(n) for n in archive.namelist()}
xml = entries['META-INF/plugin.xml'].decode()
xml = xml.replace('</extensions>', '<appStarter id="refactorj-host-test" implementation="dev.turboj.refactorj.intellij.HostSmokeStarter"/></extensions>')
entries['META-INF/plugin.xml'] = xml.encode()
with zipfile.ZipFile(mainjar, 'w', zipfile.ZIP_DEFLATED) as archive:
    for name, data in entries.items(): archive.writestr(name, data)
    for path in classes.rglob('*.class'): archive.write(path, path.relative_to(classes))
java = os.environ.get('REFACTORJ_JAVA', shutil.which('java'))
properties = profile / 'idea.properties'
properties.write_text('\n'.join([
    f'idea.config.path={profile / "config"}', f'idea.system.path={profile / "system"}',
    f'idea.plugins.path={profile / "plugins"}', f'idea.log.path={profile / "log"}',
    'idea.headless.enable.statistics=false', 'idea.is.internal=false',
    'idea.initially.ask.config=false', 'idea.trust.all.projects=true',
    'intellij.startup.wizard=false',
    f'refactorj.test.result={profile / "result.txt"}', f'refactorj.test.project={profile / "project"}',
    f'refactorj.test.java={java}', 'java.awt.headless=false',
]) + '\n')
vm_options = profile / 'idea.vmoptions'
vm_options.write_text((idea / 'bin/idea.vmoptions').read_text() + '\n-Djava.awt.headless=false\n')
env = dict(os.environ, IDEA_PROPERTIES=str(properties), IDEA_VM_OPTIONS=str(vm_options))
log = profile / 'launcher.log'
print('IDEA test profile:', profile, flush=True)
with log.open('w') as output:
    run = subprocess.run([str(idea / 'MacOS/idea'), 'refactorj-host-test'], env=env,
                         stdout=output, stderr=subprocess.STDOUT, timeout=120)
result = profile / 'result.txt'
print(result.read_text() if result.exists() else 'No test result: ' + str(log))
if run.returncode or not result.exists():
    print(log.read_text()[-12000:])
    raise SystemExit(run.returncode or 1)
shutil.copyfile(result, root / 'target/host-test-result.txt')
