#!/usr/bin/env python3
"""Compare installed app LaunchServices startup, with isolated profiles and warm OS caches."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import signal
import subprocess
import time

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('baseline', type=Path)
parser.add_argument('candidate', type=Path)
parser.add_argument('--probe', type=Path, required=True,
                    help='Compile scripts/launch-package-probe.swift with swiftc first')
parser.add_argument('--output', type=Path, required=True)
parser.add_argument('--rounds', type=int, default=3)
args = parser.parse_args()
if args.rounds < 1:
    parser.error('rounds must be positive')
out = args.output.resolve()
if any(c.isspace() or c in '"\'' for c in str(out)):
    parser.error('use a controlled output path without whitespace or quotes; the native probe sets JAVA_TOOL_OPTIONS')
out.mkdir(parents=True, exist_ok=False)
workspace = out / 'workspace'
workspace.mkdir(exist_ok=True)
fixture = workspace / 'Startup.java'
fixture.write_text('class Startup { public static void main(String[] args) { System.out.println("TurboJ qualification"); } }\n')
apps = {'baseline': args.baseline.resolve(), 'candidate': args.candidate.resolve()}
manifest = {'method': 'Alternating order, new JVM and fresh isolated profile, same source, warm OS caches. LaunchServices dispatch to CGWindowList observation at 20 ms polling; not physical compositor presentation or completed semantic initialization.',
            'probeSha256': hashlib.sha256(args.probe.read_bytes()).hexdigest(),
            'driverSha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
            'fixtureSha256': hashlib.sha256(fixture.read_bytes()).hexdigest(),
            'artifacts': {k: {'app': str(v), 'jarSha256': hashlib.sha256((v / 'Contents/app/turboj-app.jar').read_bytes()).hexdigest()} for k, v in apps.items()},
            'runs': []}
try:
    for index in range(args.rounds):
        for label in (('baseline', 'candidate') if index % 2 == 0 else ('candidate', 'baseline')):
            result_path = out / f'{label}-{index + 1}.json'
            # Recovery/preference files must never become project source inputs.
            profile = out / 'profiles' / f'{label}-{index + 1}'
            command = [str(args.probe.resolve()), str(apps[label]), str(profile), str(fixture), str(result_path)]
            print(f'{label}, round {index + 1}', flush=True)
            subprocess.run(command, check=True, timeout=60)
            result = json.loads(result_path.read_text())
            manifest['runs'].append({'label': label, 'round': index + 1, **result})
            pid = result['pid']
            name = subprocess.check_output(['ps', '-p', str(pid), '-o', 'comm='], text=True).strip()
            if name != str(apps[label] / 'Contents/MacOS/TurboJ'):
                raise RuntimeError('Refusing to terminate an unexpected process: ' + name)
            os.kill(pid, signal.SIGTERM)
            for _ in range(100):
                try:
                    os.kill(pid, 0)
                except ProcessLookupError:
                    break
                time.sleep(0.1)
            else:
                raise RuntimeError('Owned app did not terminate promptly')
            time.sleep(1)
finally:
    (out / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
