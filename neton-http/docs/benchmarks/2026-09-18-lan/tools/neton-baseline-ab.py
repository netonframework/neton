import json
import os
import subprocess
import sys
import time
from pathlib import Path

base = Path('/tmp/neton-lan-20260918')
experiment = sys.argv[1]
duration = os.environ.get('PROBE_DURATION', '5s')
image = os.environ.get('PROBE_IMAGE', 'httparena-neton')
variants = json.loads(sys.argv[2])
for turn, label in enumerate(sys.argv[3:]):
    name = 'httparena-baseline-ab'
    command = ['docker', 'run', '-d', '--name', name, '--network', 'host',
        '--security-opt', 'seccomp=unconfined', '--ulimit', 'nofile=1048576:1048576',
        '--cpuset-cpus', '0-15', '-v', f'{base}/data/dataset.json:/data/dataset.json:ro',
        '-v', f'{base}/certs:/certs:ro']
    command += variants[label]
    subprocess.run(command+[image], check=True)
    try:
        for attempt in range(30):
            ready = subprocess.run(['curl', '-fsS', '--max-time', '1',
                'http://127.0.0.1:8080/baseline11?a=13&b=42'], capture_output=True)
            if ready.returncode == 0 and ready.stdout == b'55':
                break
            time.sleep(1)
        else:
            raise RuntimeError('readiness')
        pid = subprocess.check_output(['docker', 'inspect', '-f', '{{.State.Pid}}', name], text=True).strip()
        group = Path(f'/proc/{pid}/cgroup').read_text().strip().split('::', 1)[1]
        cpu_stat = Path('/sys/fs/cgroup')/group.lstrip('/')/'cpu.stat'
        def snapshot():
            return {'time_ns': time.monotonic_ns(), 'host_stat': Path('/proc/stat').read_text(),
                    'cpu_stat': cpu_stat.read_text(), 'process_status': Path(f'/proc/{pid}/status').read_text(),
                    'netstat': Path('/proc/net/netstat').read_text(),
                    'listeners': subprocess.check_output(['ss', '-lnt', 'sport = :8080'], text=True)}
        before = snapshot()
        with (base/f'{experiment}-{turn}-{label}.log').open('w') as out:
            subprocess.run([str(base/'neton-lan-remote-gcannon.py'),
                'http://localhost:8080', '--raw', ','.join(str(base/'requests'/raw)
                    for raw in ('get.raw', 'post_cl.raw', 'post_chunked.raw')),
                '-c', '4096', '-t', '16', '-d', duration, '-p', '1'], stdout=out, stderr=subprocess.STDOUT, check=True)
        output = (base/f'{experiment}-{turn}-{label}.log').read_text()
        if 'Templates: 3' not in output or 'Per-template-ok:' not in output:
            raise RuntimeError('Invalid baseline: all three templates must be active')
        (base/f'{experiment}-{turn}-{label}.json').write_text(json.dumps(
            {'before': before, 'after': snapshot(), 'variant': variants[label], 'image': image}, indent=2))
        print(f'Completed {experiment}/{turn}/{label}', flush=True)
    finally:
        subprocess.run(['docker', 'rm', '-f', name], check=True)
    time.sleep(2)
