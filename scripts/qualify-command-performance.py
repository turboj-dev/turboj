#!/usr/bin/env python3
"""Compare CommandJ dispatch, shortcut matching and palette search in alternating JMH forks."""
import argparse
import hashlib
import json
from pathlib import Path
import platform
import subprocess
from datetime import datetime, timezone

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('baseline', type=Path)
parser.add_argument('candidate', type=Path)
parser.add_argument('--output', type=Path, required=True)
parser.add_argument('--java', default='java')
parser.add_argument('--rounds', type=int, default=3)
args = parser.parse_args()
if args.rounds < 1:
    parser.error('rounds must be positive')
out = args.output.resolve()
out.mkdir(parents=True, exist_ok=True)
artifacts = {'baseline': args.baseline.resolve(), 'candidate': args.candidate.resolve()}
def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()
manifest = {'capturedAt': datetime.now(timezone.utc).isoformat(), 'platform': platform.platform(),
            'artifacts': {k: {'path': str(v), 'sha256': sha(v)} for k, v in artifacts.items()},
            'method': 'Alternating artifact order, three fresh JMH forks per benchmark/size by default. Three 1-second warmups, five 1-second measurements. Sample-time microseconds; 100 and 1000 commands. No editor or physical-input latency claim.',
            'runs': []}
try:
    for round_index in range(args.rounds):
        for label in (('baseline', 'candidate') if round_index % 2 == 0 else ('candidate', 'baseline')):
            if sha(artifacts[label]) != manifest['artifacts'][label]['sha256']:
                raise RuntimeError('Artifact changed: ' + label)
            prefix = out / f'{label}-{round_index + 1}'
            command = [args.java, '-Xmx768m', '-jar', str(artifacts[label]),
                       '.*CommandBenchmarks.(dispatch|shortcut|palette)', '-f', '1',
                       '-wi', '3', '-i', '5', '-w', '1s', '-r', '1s', '-p', 'count=100,1000',
                       '-rf', 'json', '-rff', str(prefix.with_suffix('.json'))]
            print(f'{label}, round {round_index + 1}', flush=True)
            with prefix.with_suffix('.txt').open('w') as log:
                result = subprocess.run(command, stdout=log, stderr=subprocess.STDOUT, timeout=180)
            manifest['runs'].append({'artifact': label, 'round': round_index + 1,
                                     'command': command, 'exitCode': result.returncode})
            if result.returncode:
                raise RuntimeError('JMH failed: ' + str(prefix))
            measurements = json.loads(prefix.with_suffix('.json').read_text())
            if len(measurements) != 6:
                raise RuntimeError('Incomplete JMH result: ' + str(prefix))
finally:
    (out / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
