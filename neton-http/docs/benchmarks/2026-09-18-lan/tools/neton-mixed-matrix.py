import json
import subprocess
from pathlib import Path

base = Path('/tmp/neton-lan-20260918')
variants = {name: ['-v', f'{base}/{name}.kexe:/server:ro']
            for name in ('headers', 'admission', 'shards', 'body', 'pmcs')}
for name, library in [('backlog', 'backlog'), ('nodelay', 'nodelay')]:
    variants[name] = variants['headers'] + ['-v', f'{base}/{library}.so:/{library}.so:ro',
                                          '-e', f'LD_PRELOAD=/{library}.so']
for count in (8, 32):
    variants[f'w{count}'] = variants['headers'] + ['-e', f'TOKIO_WORKER_THREADS={count}']
order = list(variants)
subprocess.run(['python3', str(base/'neton-baseline-ab.py'), 'mixed', json.dumps(variants),
                *order, *reversed(order)], check=True)
