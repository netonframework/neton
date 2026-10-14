from pathlib import Path
import subprocess
import time

base = Path('/tmp/neton-lan-20260918')
for fw in ('neton', 'actix'):
    name = 'httparena-profile-probe'
    subprocess.run(['docker', 'run', '-d', '--name', name, '--network', 'host',
        '--security-opt', 'seccomp=unconfined', '--ulimit', 'nofile=1048576:1048576',
        '--cpuset-cpus', '0-15', '-v', f'{base}/data/dataset.json:/data/dataset.json:ro',
        '-v', f'{base}/certs:/certs:ro', f'httparena-{fw}'], check=True)
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
        with (base/f'profile-mixed-{fw}-load.log').open('w') as log:
            load = subprocess.Popen([str(base/'neton-lan-remote-gcannon.py'),
                'http://localhost:8080', '--raw', ','.join(str(base/'requests'/raw)
                    for raw in ('get.raw', 'post_cl.raw', 'post_chunked.raw')),
                '-c', '4096', '-t', '16', '-d', '20s', '-p', '1'], stdout=log, stderr=subprocess.STDOUT)
            time.sleep(3)
            with (base/f'profile-mixed-{fw}-threads.txt').open('w') as out:
                subprocess.run(['ps', '-L', '-p', pid, '-o', 'pid,tid,psr,pcpu,stat,comm'], stdout=out, check=True)
            result = subprocess.run(['perf', 'record', '-e', 'cpu-clock', '-F', '199',
                '-p', pid, '-o', str(base/f'profile-mixed-{fw}.data'), '--', 'sleep', '10'], capture_output=True, text=True)
            (base/f'profile-mixed-{fw}-record.log').write_text(result.stdout+result.stderr)
            if result.returncode == 0:
                with (base/f'profile-mixed-{fw}-report.txt').open('w') as out:
                    subprocess.run(['perf', 'report', '--stdio', '--no-children', '-g', 'none',
                        '-i', str(base/f'profile-mixed-{fw}.data')], stdout=out, stderr=subprocess.STDOUT, check=True)
            if load.wait(timeout=40):
                raise RuntimeError('load failed')
        if 'Templates: 3' not in (base/f'profile-mixed-{fw}-load.log').read_text():
            raise RuntimeError('Invalid baseline: expected three templates')
        print(f'{fw} profile complete; perf exit {result.returncode}', flush=True)
    finally:
        subprocess.run(['docker', 'rm', '-f', name], check=True)
