#!/usr/bin/env python3
"""Sequential paired probes of two immutable app JARs; run on an unlocked, otherwise quiet desktop."""
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
parser.add_argument('--rounds', type=int, default=3)
parser.add_argument('--java', default='java')
args = parser.parse_args()
if args.rounds < 1:
    parser.error('rounds must be positive')
root = Path(__file__).resolve().parent.parent
out = args.output.resolve()
out.mkdir(parents=True, exist_ok=True)
classes = out / 'classes'
classes.mkdir(exist_ok=True)
sources = [root / 'app/src/test/java/dev/turboj/app/ProductPerformanceProbe.java',
           root / 'app/src/test/java/dev/turboj/testing/intelligence/LineIntelligenceBenchmark.java',
           root / 'core/src/test/java/dev/turboj/intelligence/SemanticBenchmark.java']
artifacts = {'baseline': args.baseline.resolve(), 'candidate': args.candidate.resolve()}
def sha(path):
    value = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            value.update(chunk)
    return value.hexdigest()
manifest = {'createdAt': datetime.now(timezone.utc).isoformat(), 'platform': platform.platform(),
            'java': subprocess.run([args.java, '-version'], capture_output=True, text=True, check=True).stderr,
            'artifacts': {k: {'path': str(v), 'sha256': sha(v)} for k, v in artifacts.items()},
            'sources': {str(p.relative_to(root)): sha(p) for p in sources},
            'method': 'Alternating order, serial fresh JVMs, warm OS/dependency caches. Synthetic handler-to-paint excludes physical keyboard/compositor. Baseline is pre-batch, not pre-Cherry. All samples retained; no automatic performance verdict.',
            'runs': []}
subprocess.run(['javac', '-cp', str(artifacts['candidate']), '-d', str(classes), *map(str, sources)], cwd=root, check=True)
options = ['-Xmx768m', '--enable-native-access=ALL-UNNAMED']
for package in ('api', 'code', 'file', 'parser', 'tree', 'util'):
    options.append('--add-exports=jdk.compiler/com.sun.tools.javac.' + package + '=ALL-UNNAMED')
try:
    for round_index in range(args.rounds):
        order = ('baseline', 'candidate') if round_index % 2 == 0 else ('candidate', 'baseline')
        for label in order:
            if sha(artifacts[label]) != manifest['artifacts'][label]['sha256']:
                raise RuntimeError('Artifact changed during comparison: ' + label)
            for name, main in [('native', 'dev.turboj.app.ProductPerformanceProbe'),
                               ('gutter-git', 'dev.turboj.testing.intelligence.LineIntelligenceBenchmark'),
                               ('semantic', 'dev.turboj.intelligence.SemanticBenchmark')]:
                prefix = out / f'{label}-{round_index + 1}-{name}'
                command = [args.java, *options, '-cp', str(classes) + ':' + str(artifacts[label]), main]
                if name == 'native':
                    command.append(str(prefix.with_suffix('.json')))
                else:
                    command.insert(1, '-Djava.awt.headless=true')
                print(f'{label}, round {round_index + 1}: {name}', flush=True)
                with prefix.with_suffix('.txt').open('w') as log:
                    run = subprocess.run(command, cwd=root, stdout=log, stderr=subprocess.STDOUT, timeout=180)
                manifest['runs'].append({'label': label, 'round': round_index + 1, 'probe': name, 'command': command, 'exitCode': run.returncode})
                if run.returncode:
                    raise RuntimeError('Probe failed: ' + str(prefix))
                if name == 'semantic':
                    prefix.with_suffix('.json').write_bytes((root / 'core/target/java-intelligence-performance.json').read_bytes())
                elif name == 'gutter-git':
                    text = prefix.with_suffix('.txt').read_text()
                    start = text.find('{')
                    value, _ = json.JSONDecoder().raw_decode(text[start:])
                    prefix.with_suffix('.json').write_text(json.dumps(value, indent=2) + '\n')
finally:
    (out / 'manifest.json').write_text(json.dumps(manifest, indent=2) + '\n')
