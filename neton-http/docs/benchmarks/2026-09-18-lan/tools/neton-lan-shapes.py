import os
from pathlib import Path
import subprocess
import time

base = Path('/tmp/neton-lan-20260918')
for turn, fw in enumerate(('neton', 'actix', 'actix', 'neton')):
    name = 'httparena-shape-probe'
    subprocess.run(['docker', 'run', '-d', '--name', name, '--network', 'host',
        '--security-opt', 'seccomp=unconfined', '--ulimit', 'nofile=1048576:1048576',
        '--cpuset-cpus', '0-15', '-v', f'{base}/data/dataset.json:/data/dataset.json:ro',
        '-v', f'{base}/certs:/certs:ro', '-v', f'{base}/data/static:/data/static:ro',
        f'httparena-{fw}'], check=True)
    try:
        for attempt in range(30):
            probe = subprocess.run(['curl', '--silent', '--fail', '--max-time', '1',
                'http://127.0.0.1:8080/baseline11?a=13&b=42'], capture_output=True)
            if probe.returncode == 0 and probe.stdout == b'55':
                break
            time.sleep(1)
        else:
            raise RuntimeError('Server readiness failed')
        files = ['get.raw', 'post_cl.raw', 'post_chunked.raw']
        if turn % 2:
            files.reverse()
        for raw in files:
            with (base/f'shape-{turn}-{fw}-{raw}.log').open('w') as log:
                result = subprocess.run([str(base/'neton-lan-remote-gcannon.py'),
                    'http://localhost:8080', '--raw', str(base/'requests'/raw),
                    '-c', '4096', '-t', '16', '-d', '5s', '-p', '1'],
                    stdout=log, stderr=subprocess.STDOUT, timeout=45)
            if result.returncode:
                raise RuntimeError(f'Load failed: {fw}/{raw}')
            print(f'Complete {turn} {fw} {raw}', flush=True)
            time.sleep(2)
    finally:
        subprocess.run(['docker', 'rm', '-f', name], check=True)
