from pathlib import Path
import subprocess
import time

base = Path('/tmp/neton-lan-20260918')
for turn, enabled in enumerate((False, True, True, False)):
    name = 'httparena-nodelay-probe'
    command = ['docker', 'run', '-d', '--name', name, '--network', 'host',
        '--security-opt', 'seccomp=unconfined', '--ulimit', 'nofile=1048576:1048576',
        '--cpuset-cpus', '0-15', '-v', f'{base}/data/dataset.json:/data/dataset.json:ro',
        '-v', f'{base}/certs:/certs:ro']
    if enabled:
        command += ['-v', f'{base}/nodelay.so:/nodelay.so:ro', '-e', 'LD_PRELOAD=/nodelay.so']
    subprocess.run(command+['httparena-neton'], check=True)
    try:
        for attempt in range(30):
            ready = subprocess.run(['curl', '-fsS', '--max-time', '1',
                'http://127.0.0.1:8080/baseline11?a=13&b=42'], capture_output=True)
            if ready.returncode == 0 and ready.stdout == b'55':
                break
            time.sleep(1)
        else:
            raise RuntimeError('readiness')
        with (base/f'nodelay-{turn}-{enabled}.log').open('w') as out:
            subprocess.run([str(base/'neton-lan-remote-gcannon.py'),
                'http://localhost:8080', '--raw', ','.join(str(base/'requests'/raw)
                    for raw in ('get.raw', 'post_cl.raw', 'post_chunked.raw')),
                '-c', '4096', '-t', '16', '-d', '5s', '-p', '1'], stdout=out, stderr=subprocess.STDOUT, check=True)
        print(f'Completed {turn}: nodelay={enabled}', flush=True)
    finally:
        subprocess.run(['docker', 'rm', '-f', name], check=True)
    time.sleep(2)
