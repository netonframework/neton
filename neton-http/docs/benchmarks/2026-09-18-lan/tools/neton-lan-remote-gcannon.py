#!/usr/bin/python3
import json
import subprocess
import sys

args = sys.argv[1:]
if args[0] != 'http://localhost:8080':
    raise ValueError('This proxy only supports baseline and limited-conn')
args[0] = 'http://172.21.105.40:8080'
sys.exit(subprocess.call(['ssh', '-i', '/tmp/neton-lan-20260918/loadgen-key',
    '-o', 'IdentitiesOnly=yes', '-o', 'BatchMode=yes', '-o', 'StrictHostKeyChecking=accept-new',
    'root@172.21.105.41', json.dumps(args)]))
