#!/usr/bin/env python3
"""macOS process CPU-time deltas for an already-idle native TurboJ parent and workers."""
import argparse
import json
import subprocess
import time
from pathlib import Path

parser = argparse.ArgumentParser()
parser.add_argument('pid', type=int)
parser.add_argument('--seconds', type=float, default=5)
parser.add_argument('--samples', type=int, default=3)
parser.add_argument('--output', default='benchmarks/results/idle-macos-arm64.json')
args = parser.parse_args()
if args.seconds <= 0 or args.samples < 1:
    parser.error('seconds and samples must be positive')

def snapshot():
    result = subprocess.run(['pgrep', '-P', str(args.pid)], capture_output=True, text=True)
    pids = [args.pid] + [int(value) for value in result.stdout.split()]
    rows = []
    for pid in pids:
        line = subprocess.check_output(['ps', '-p', str(pid), '-o', 'time=,rss='], text=True).split()
        duration, rss = line
        seconds = 0.0
        for part in duration.split(':'):
            seconds = seconds * 60 + float(part)
        rows.append({'pid': pid, 'cpu_seconds': seconds, 'rss_kib': int(rss)})
    return time.monotonic(), rows

records = []
for i in range(args.samples):
    before, first = snapshot()
    time.sleep(args.seconds)
    after, last = snapshot()
    if {row['pid'] for row in first} != {row['pid'] for row in last}:
        raise SystemExit('Process set changed; repeat under stable idle conditions')
    elapsed = after - before
    cpu = sum(row['cpu_seconds'] for row in last) - sum(row['cpu_seconds'] for row in first)
    record = {'wall_seconds': elapsed, 'cpu_percent_one_core': 100 * cpu / elapsed, 'processes': last}
    records.append(record)
    print(json.dumps(record), flush=True)
Path(args.output).write_text(json.dumps({'description': 'Native app and immediate worker children, visible idle window after initial compilation; CPU-time counters rounded by ps; sum RSS is not private memory', 'samples': records}, indent=2) + '\n')
