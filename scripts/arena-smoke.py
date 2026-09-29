#!/usr/bin/env python3
"""Run the actual Arena binary and check mixed baseline requests; not a throughput benchmark."""
import argparse
import concurrent.futures
import http.client
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import time


def check_connection(count):
    connection = http.client.HTTPConnection("127.0.0.1", 8080, timeout=15)
    try:
        for i in range(count):
            mode = i % 3
            if mode == 0:
                connection.request("GET", "/baseline11?a=13&b=42")
            elif mode == 1:
                connection.request("POST", "/baseline11?a=13&b=42", body=b"20")
            else:
                connection.request("POST", "/baseline11?a=13&b=42", body=iter([b"2", b"0"]), encode_chunked=True)
            response = connection.getresponse()
            data = response.read()
            assert response.status == 200, (response.status, data)
            assert data == (b"55" if mode == 0 else b"75"), data
    finally:
        connection.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--entry", required=True)
    parser.add_argument("--binary", required=True)
    parser.add_argument("--dataset", required=True)
    args = parser.parse_args()
    with socket.socket() as probe:
        probe.bind(("127.0.0.1", 8080))
    env = dict(os.environ, ARENA_DATASET=str(Path(args.dataset).resolve()))
    with tempfile.TemporaryFile(mode="w+") as log:
        process = subprocess.Popen([str(Path(args.binary).resolve())], cwd=args.entry, env=env,
                                   stdout=log, stderr=subprocess.STDOUT)
        try:
            deadline = time.monotonic() + 30
            while True:
                if process.poll() is not None:
                    raise RuntimeError(f"server exited: {process.returncode}")
                try:
                    check_connection(3)
                    break
                except (ConnectionError, OSError):
                    if time.monotonic() > deadline:
                        raise
                    time.sleep(0.1)
            check_connection(300)
            with concurrent.futures.ThreadPoolExecutor(max_workers=128) as executor:
                list(executor.map(check_connection, [12] * 128))
            assert process.poll() is None
            print("PASS: 1839 mixed GET/content-length POST/chunked POST responses; 128 concurrent clients; no 503")
        except BaseException:
            log.seek(0)
            print(log.read())
            raise
        finally:
            process.terminate()
            try:
                process.wait(timeout=10)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()


if __name__ == "__main__":
    main()
