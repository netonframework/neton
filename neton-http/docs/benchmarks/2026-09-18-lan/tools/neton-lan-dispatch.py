#!/usr/bin/python3
import json
import os
import subprocess
import sys
import resource
import time
from pathlib import Path

base = Path('/tmp/neton-lan-20260918')
args = json.loads(os.environ['SSH_ORIGINAL_COMMAND'])
if not isinstance(args, list) or not all(isinstance(a, str) for a in args):
    raise ValueError('Expected string arguments')
if not args or args[0] != 'http://172.21.105.40:8080':
    raise ValueError('Only the private benchmark target is allowed')
allowed = {'--raw', '-c', '-t', '-d', '-p', '-r'}
if len(args) % 2 != 1:
    raise ValueError('Expected flag/value pairs')
for flag, value in zip(args[1::2], args[2::2]):
    if flag not in allowed:
        raise ValueError(flag)
    if flag == '--raw':
        paths = [str(base/'requests'/Path(p).name) for p in value.split(',')]
        if any(Path(p).name not in ('get.raw', 'post_cl.raw', 'post_chunked.raw') for p in paths):
            raise ValueError('Unexpected request template')
        args[args.index(flag)+1] = ','.join(paths)
os.chdir(base)
def snapshot():
    return {'cpu': Path('/proc/stat').read_text().splitlines()[0],
            'net': Path('/proc/net/dev').read_text()}

started = time.monotonic()
before = snapshot()
result = subprocess.run(['timeout', '40', 'taskset', '-c', '0-15',
    str(base/'neton-lan-gcannon-src/gcannon'), *args])
usage = resource.getrusage(resource.RUSAGE_CHILDREN)
(base/f'load-telemetry-{time.time_ns()}.json').write_text(json.dumps(dict(
    args=args, wall_seconds=time.monotonic()-started, exit_code=result.returncode,
    client_cpu_seconds=usage.ru_utime+usage.ru_stime, max_rss_kib=usage.ru_maxrss,
    before=before, after=snapshot()), indent=2))
sys.exit(result.returncode)
