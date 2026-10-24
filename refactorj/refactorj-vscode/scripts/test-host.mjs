import { mkdir, writeFile } from 'node:fs/promises';
import { existsSync } from 'node:fs';
import { spawn } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import path from 'node:path';

const root = fileURLToPath(new URL('../', import.meta.url));
const executable = process.env.CODE_EXECUTABLE ?? (process.platform === 'darwin'
  ? (existsSync('/Applications/Visual Studio Code.app/Contents/MacOS/Code')
    ? '/Applications/Visual Studio Code.app/Contents/MacOS/Code'
    : '/Applications/Visual Studio Code.app/Contents/MacOS/Electron') : 'code');
const artifacts = path.join(root, 'artifacts');
const userData = path.join(artifacts, `profile-${Date.now()}`);
await mkdir(path.join(userData, 'User'), { recursive: true });
await mkdir(path.join(artifacts, 'empty-extensions'), { recursive: true });
await writeFile(path.join(userData, 'User/settings.json'), JSON.stringify({
  'telemetry.telemetryLevel': 'off', 'update.mode': 'none', 'extensions.autoUpdate': false,
  'extensions.autoCheckUpdates': false, 'workbench.startupEditor': 'none',
  'files.hotExit': 'off', 'window.confirmBeforeClose': 'never',
  'refactorj.javaExecutable': process.env.REFACTORJ_TEST_JAVA ?? (process.env.JAVA_HOME ? path.join(process.env.JAVA_HOME, 'bin/java') : 'java'),
}));
await mkdir(path.join(artifacts, 'workspace'), { recursive: true });
const env = { ...process.env, REFACTORJ_TEST_ARTIFACTS: artifacts };
delete env.ELECTRON_RUN_AS_NODE;
const child = spawn(executable, [
  '--extensionDevelopmentPath=' + root,
  '--extensionTestsPath=' + path.join(root, 'out/test/run.js'),
  '--user-data-dir=' + userData,
  '--extensions-dir=' + path.join(artifacts, 'empty-extensions'),
  '--disable-extensions', '--disable-workspace-trust', '--skip-welcome', '--skip-release-notes',
  '--disable-updates', '--disable-telemetry', '--new-window', path.join(artifacts, 'workspace'),
], { env, stdio: 'inherit' });
const timeout = setTimeout(() => child.kill(), 120_000);
child.on('error', error => { clearTimeout(timeout); console.error(error.message); process.exitCode = 2; });
child.on('exit', code => { clearTimeout(timeout); process.exitCode = code ?? 2; });
