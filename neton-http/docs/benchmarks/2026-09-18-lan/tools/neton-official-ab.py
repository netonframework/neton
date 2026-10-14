import os
from pathlib import Path
import subprocess
import sys

base = Path('/tmp/neton-lan-20260918')
candidate = sys.argv[1]
env = os.environ.copy()
env.update(SKIP_TUNE='true', THREADS='16', GCANNON_CPUS='0-15', RUNS='3', DURATION='5s',
           GCANNON=str(base/'neton-lan-remote-gcannon.py'), GCANNON_MODE='native')
subprocess.run(['docker', 'tag', 'httparena-neton', 'httparena-neton-published'], check=True)
try:
    for label in ('control', candidate):
        name = 'httparena-image-assembly'
        subprocess.run(['docker', 'create', '--name', name, 'httparena-neton-published'], check=True)
        try:
            subprocess.run(['docker', 'cp', str(base/f'{label}.kexe'), f'{name}:/server'], check=True)
            subprocess.run(['docker', 'commit', name, f'httparena-neton-{label}'], check=True)
        finally:
            subprocess.run(['docker', 'rm', name], check=True)
    for turn, label in enumerate(('control', candidate, candidate, 'control')):
        subprocess.run(['docker', 'tag', f'httparena-neton-{label}', 'httparena-neton'], check=True)
        with (base/f'official-ab-{turn}-{label}.log').open('w') as out:
            subprocess.run(['bash', 'scripts/benchmark.sh', 'neton', 'baseline'], cwd=base,
                env=env, stdout=out, stderr=subprocess.STDOUT, timeout=180, check=True)
        print(f'Completed official baseline {turn}/{label}', flush=True)
finally:
    subprocess.run(['docker', 'tag', 'httparena-neton-published', 'httparena-neton'], check=True)
