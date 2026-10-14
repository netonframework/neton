import os
from pathlib import Path
import subprocess

base = Path('/tmp/neton-lan-20260918')
env = os.environ.copy()
env.update(SKIP_TUNE='true', THREADS='16', GCANNON_CPUS='0-15', RUNS='3', DURATION='5s',
           GCANNON=str(base/'neton-lan-remote-gcannon.py'), GCANNON_MODE='native')
for framework, profile in [('neton', 'baseline'), ('actix', 'baseline'),
                           ('actix', 'limited-conn'), ('neton', 'limited-conn')]:
    path = base/f'arena-{framework}-{profile}.log'
    print(f'Starting {framework}/{profile}', flush=True)
    with path.open('w') as log:
        result = subprocess.run(['bash', 'scripts/benchmark.sh', framework, profile],
                                cwd=base, env=env, stdout=log, stderr=subprocess.STDOUT,
                                timeout=240)
    print(f'Finished {framework}/{profile}, exit={result.returncode}', flush=True)
    if result.returncode:
        raise RuntimeError(f'Failed: {path}')
