import json
from pathlib import Path
import subprocess
import sys

backup = Path('/tmp/neton-lan-20260918/socket-settings-before.json')
if sys.argv[1] == 'restore':
    settings = json.loads(backup.read_text())
else:
    settings = {
        'net.core.somaxconn': '65535',
        'net.ipv4.tcp_max_syn_backlog': '65535',
        'net.core.netdev_max_backlog': '65535',
    }
    if sys.argv[1] == 'client':
        settings.update({
            'net.ipv4.ip_local_port_range': '1024 65535',
            'net.ipv4.tcp_max_tw_buckets': '131072',
            'net.ipv4.tcp_tw_reuse': '1',
        })
    if backup.exists():
        raise RuntimeError('Original settings already saved; refusing to overwrite')
    backup.write_text(json.dumps({k: subprocess.check_output(['sysctl', '-n', k], text=True).strip()
                                 for k in settings}, indent=2))
for key, value in settings.items():
    subprocess.run(['sysctl', '-w', f'{key}={value}'], check=True)
