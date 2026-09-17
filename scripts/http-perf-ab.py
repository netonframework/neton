#!/usr/bin/env python3
"""Local full-HTTP A/B or resource sweep; not an HttpArena result.

Uses examples/bench's /sum endpoint and GC CLI. Never kills unrelated processes.
Run on an otherwise idle host after builds/tests have finished. wrk shares this
host with the server; repeat with Arena's workload on dedicated Linux before release.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import platform
import re
import signal
import socket
import subprocess
import time
import urllib.request


def cpu_seconds(pid):
    if platform.system() == 'Linux':
        # comm can contain spaces: fields after its closing ')' start at field 3.
        fields = Path(f'/proc/{pid}/stat').read_text().rsplit(')', 1)[1].split()
        return (int(fields[11]) + int(fields[12])) / os.sysconf('SC_CLK_TCK')
    value = subprocess.check_output(['ps', '-o', 'time=', '-p', str(pid)], text=True).strip()
    seconds = 0.0
    for field in value.split(':'):
        seconds = seconds * 60 + float(field)
    return seconds


def measure(binary, label, workers, heap, round_id, args):
    prefix = args.out / f'{round_id:02}-{label}-w{workers}-g{heap}'
    env = os.environ.copy()
    env.update(TOKIO_WORKER_THREADS=str(workers), HYPER4K_STATS='0')
    env.update(args.a_env if label == 'A' else args.b_env)
    # Expose accidental environment overrides rather than silently benchmarking them.
    with prefix.with_suffix('.server.log').open('w') as log:
        process = subprocess.Popen([str(binary), f'gcmin={heap}', f'gctarget={heap * 2}'],
                                   cwd=args.cwd, env=env, stdout=log, stderr=subprocess.STDOUT,
                                   start_new_session=True)
        try:
            deadline = time.monotonic() + 30
            while True:
                if process.poll() is not None:
                    raise RuntimeError(f'{label} exited during startup; see {prefix}.server.log')
                try:
                    with urllib.request.urlopen(args.url, timeout=1) as response:
                        if response.status != 200 or response.read() != b'7':
                            raise RuntimeError('Expected /sum?a=3&b=4 to return 200 and body 7')
                    break
                except OSError:
                    if time.monotonic() >= deadline:
                        raise RuntimeError('Server readiness timed out')
                    time.sleep(0.1)
            base = ['wrk', f'-t{args.threads}', f'-c{args.connections}', '--latency']
            subprocess.run(base + [f'-d{args.warmup}s', args.url], check=True,
                           stdout=subprocess.DEVNULL, timeout=args.warmup + 15)
            prefix.with_suffix('.host-before.log').write_text(subprocess.check_output(
                ['ps', '-Ao', 'pid,pcpu,comm'], text=True))
            before = cpu_seconds(process.pid)
            result = subprocess.run(base + [f'-d{args.duration}s', args.url], check=True,
                                    capture_output=True, text=True, timeout=args.duration + 15)
            after = cpu_seconds(process.pid)
            prefix.with_suffix('.host-after.log').write_text(subprocess.check_output(
                ['ps', '-Ao', 'pid,pcpu,comm'], text=True))
            prefix.with_suffix('.wrk.log').write_text(result.stdout + result.stderr)
            if re.search(r'Socket errors:|Non-2xx or 3xx responses:', result.stdout):
                raise RuntimeError(f'Load errors; see {prefix}.wrk.log')
            count = int(re.search(r'(\d+) requests in', result.stdout)[1])
            rps = float(re.search(r'Requests/sec:\s+([\d.]+)', result.stdout)[1])
            p99 = re.search(r'99%\s+(\S+)', result.stdout)[1]
            rss = int(subprocess.check_output(['ps', '-o', 'rss=', '-p', str(process.pid)], text=True))
            row = dict(label=label, workers=workers, gc_min_mib=heap, gc_target_mib=heap * 2,
                       round=round_id, rps=rps, p99=p99, requests=count, rss_end_kib=rss,
                       cpu_us_per_request=(after-before)*1e6/count)
            prefix.with_suffix('.json').write_text(json.dumps(row, indent=2) + '\n')
            print(json.dumps(row), flush=True)
        finally:
            if process.poll() is None:
                os.killpg(process.pid, signal.SIGTERM)
                try:
                    process.wait(timeout=15)
                except subprocess.TimeoutExpired:
                    os.killpg(process.pid, signal.SIGKILL)
                    process.wait()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--a', type=Path, required=True)
    parser.add_argument('--b', type=Path, required=True)
    parser.add_argument('--cwd', type=Path, required=True, help='bench directory with WARN config')
    parser.add_argument('--out', type=Path, required=True)
    parser.add_argument('--workers', type=int, nargs='+', default=[4])
    parser.add_argument('--heaps', type=int, nargs='+', default=[256], help='GC minimum MiB; target is 2x')
    parser.add_argument('--rounds', type=int, default=4)
    parser.add_argument('--threads', type=int, default=2)
    parser.add_argument('--connections', type=int, default=128)
    parser.add_argument('--warmup', type=int, default=3)
    parser.add_argument('--duration', type=int, default=8)
    parser.add_argument('--url', default='http://127.0.0.1:8090/sum?a=3&b=4')
    parser.add_argument('--a-env', action='append', default=[], metavar='KEY=VALUE')
    parser.add_argument('--b-env', action='append', default=[], metavar='KEY=VALUE')
    args = parser.parse_args()
    for name in ('a_env', 'b_env'):
        values = {}
        for assignment in getattr(args, name):
            key, separator, value = assignment.partition('=')
            if not separator or key != 'HYPER4K_REQUEST_STRING_SCRATCH' or value not in ('0', '1'):
                parser.error('Variant overrides support only HYPER4K_REQUEST_STRING_SCRATCH=0 or 1')
            values[key] = value
        setattr(args, name, values)
    for value in [*args.workers, *args.heaps, args.rounds, args.threads,
                  args.connections, args.warmup, args.duration]:
        if value <= 0:
            parser.error('All numeric parameters must be positive')
    args.a, args.b, args.cwd = args.a.resolve(), args.b.resolve(), args.cwd.resolve()
    from urllib.parse import urlparse
    parsed = urlparse(args.url)
    if parsed.hostname not in ('127.0.0.1', 'localhost'):
        parser.error('Only local loopback targets are supported')
    with socket.socket() as probe:
        if probe.connect_ex((parsed.hostname, parsed.port or 80)) == 0:
            parser.error('Port already occupied; refusing to measure an unrelated server')
    args.out.mkdir(parents=True, exist_ok=False)
    identity = {
        'platform': platform.platform(), 'logical_cpus': os.cpu_count(),
        'args': {k: str(v) if isinstance(v, Path) else v for k, v in vars(args).items()},
        'sha256': {label: hashlib.sha256(path.read_bytes()).hexdigest()
                   for label, path in [('A', args.a), ('B', args.b)]},
        'config_sha256': hashlib.sha256((args.cwd/'config/application.conf').read_bytes()).hexdigest(),
        'environment': {k: v for k, v in os.environ.items()
                        if k.startswith(('NETON_SERVER', 'NETON_LOGGING', 'HYPER4K_'))},
    }
    (args.out/'environment.json').write_text(json.dumps(identity, indent=2) + '\n')
    for round_id in range(args.rounds):
        variants = [('A', args.a), ('B', args.b)]
        settings = [(w, h) for w in args.workers for h in args.heaps]
        if round_id % 2:
            variants.reverse()
            settings.reverse()
        for workers, heap in settings:
            for label, binary in variants:
                measure(binary, label, workers, heap, round_id, args)


if __name__ == '__main__':
    main()
