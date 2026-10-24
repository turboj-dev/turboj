#!/usr/bin/env python3
"""Fresh-process startup and a 100-file batch, including actual CLI I/O."""
import json
from pathlib import Path
import statistics
import subprocess
import sys
import tempfile
import time

launcher = Path(sys.argv[1]).resolve()
destination = Path(sys.argv[2]) if len(sys.argv) > 2 else Path('target/cli-measurements.json')
source = 'class Workload{' + ''.join(
    f'int method{i}(int value){{int doubled=value*2;if(doubled>100){{return doubled-1;}}return doubled+1;}}'
    for i in range(12)) + '}'

def invoke(args, data=None):
    start = time.perf_counter_ns()
    result = subprocess.run([str(launcher), *args], input=data, text=True,
                            capture_output=True, timeout=60, check=True)
    return (time.perf_counter_ns() - start) / 1e6, result

times = []
for _ in range(12):
    elapsed, result = invoke(['--stdin'], source)
    if 'int doubled = value * 2;' not in result.stdout:
        raise RuntimeError('Formatter output was not validated')
    times.append(elapsed)
with tempfile.TemporaryDirectory(prefix='javafmt-batch-') as directory:
    for i in range(100):
        Path(directory, f'Workload{i}.java').write_text(source)
    batch, _ = invoke(['-w', directory])
    check, _ = invoke(['--check', directory])
report = {
    'launcher': launcher.name, 'input_utf16': len(source),
    'fresh_process_ms': times, 'first_process_ms': times[0],
    'median_ms': statistics.median(times), 'maximum_ms': max(times),
    'batch_files': 100, 'batch_write_ms': batch,
    'batch_noop_check_ms': check,
    'notes': 'Each sample starts a fresh JVM. OS filesystem caches are not flushed. Includes process and pipe/file I/O. No samples discarded; 12 samples are too few for reliable tail percentiles.'
}
destination.parent.mkdir(parents=True, exist_ok=True)
destination.write_text(json.dumps(report, indent=2) + '\n')
print(json.dumps(report, indent=2))
