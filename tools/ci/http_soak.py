#!/usr/bin/env python3
"""Low-rate Linux HTTP soak for the existing bench fixture; no external traffic."""
import argparse
import hashlib
import http.client
import json
import os
from pathlib import Path
import platform
import signal
import socket
import statistics
import struct
import subprocess
import time

from reference_gate import require, port_open, observe_shutdown

MIB = 1024 * 1024


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def status_values(text):
    return {key: value.strip() for key, value in
            (line.split(":", 1) for line in text.splitlines() if ":" in line)}


def memory_available():
    values = status_values(Path("/proc/meminfo").read_text())
    return int(values["MemAvailable"].split()[0]) * 1024


def sample_process(pid):
    proc = Path(f"/proc/{pid}")
    values = status_values((proc / "status").read_text())
    links = []
    for fd in (proc / "fd").iterdir():
        try:
            links.append(fd.readlink().as_posix())
        except FileNotFoundError:
            pass  # A descriptor may close while the process is sampled.
    return {"rssBytes": int(values["VmRSS"].split()[0]) * 1024,
            "fds": len(links), "sockets": sum(x.startswith("socket:") for x in links),
            "threads": int(values["Threads"]), "availableBytes": memory_available()}


def check_idle(sample, baseline, rss_growth=32 * MIB):
    require(sample["fds"] <= baseline["fds"], "Idle file descriptors did not return to baseline")
    require(sample["sockets"] <= baseline["sockets"], "Idle sockets did not return to baseline")
    require(sample["threads"] <= baseline["threads"], "Idle threads grew above baseline")
    require(sample["rssBytes"] <= baseline["rssBytes"] + rss_growth, "Idle RSS exceeded growth budget")


def verify_listener(pid, port):
    inodes = set()
    for fd in Path(f"/proc/{pid}/fd").iterdir():
        try:
            target = fd.readlink().as_posix()
        except FileNotFoundError:
            continue
        if target.startswith("socket:["):
            inodes.add(target[8:-1])
    listeners = []
    for table in ("tcp", "tcp6"):
        for line in Path(f"/proc/{pid}/net/{table}").read_text().splitlines()[1:]:
            fields = line.split()
            if fields[3] == "0A" and fields[9] in inodes:
                listeners.append((table, fields[1]))
    require(listeners == [("tcp", f"0100007F:{port:04X}")],
            f"Expected only the owned loopback listener, observed {listeners}")


def check_trend(samples, warmup):
    idle = [s for s in samples if s["phase"] == "idle" and s["elapsedSeconds"] >= warmup]
    require(len(idle) >= 2, "Insufficient post-warmup idle observations")
    window = max(1, min(60, len(idle) // 3))
    first = statistics.median(s["rssBytes"] for s in idle[:window])
    last = statistics.median(s["rssBytes"] for s in idle[-window:])
    require(last - first <= 8 * MIB, "Post-warmup median RSS grew by more than 8 MiB")
    return {"idleSamples": len(idle), "windowSamples": window,
            "initialMedianRssBytes": first, "finalMedianRssBytes": last,
            "medianGrowthBytes": last - first}


def request(connection, path="/plaintext", slow=False):
    connection.request("GET", path)
    response = connection.getresponse()
    require(response.status == 200, f"Unexpected HTTP status: {response.status}")
    if slow:
        data = response.read(1)
        time.sleep(0.2)
        data += response.read(65536)
    else:
        data = response.read(65536)
    require(len(data) < 65536, "Response exceeded fixture limit")
    body = json.loads(data)
    require(body == {"code": 0, "message": "OK", "data": "Hello, World!"},
            "HTTP response envelope changed")


def interrupted_peer(port, reset=False):
    with socket.create_connection(("127.0.0.1", port), timeout=3) as peer:
        if reset:
            peer.sendall(b"GET /jsonbig HTTP/1.1\r\nHost: localhost\r\n\r\n")
            peer.setsockopt(socket.SOL_SOCKET, socket.SO_LINGER, struct.pack("ii", 1, 0))
        else:
            peer.sendall(b"GET /plaintext HTTP/1.1\r\nHost:")
            time.sleep(0.2)


def write_report(path, report):
    temporary = path.with_suffix(".tmp")
    temporary.write_text(json.dumps(report, indent=2) + "\n")
    temporary.replace(path)


def run(args):
    require(platform.system() == "Linux", "Linux /proc is required")
    require(args.seconds >= 180 and 0 <= args.warmup <= args.seconds - 120,
            "Require at least 180 seconds and two post-warmup cycles")
    require(1024 <= args.port <= 65535, "Use an unprivileged port")
    require(digest(args.binary) == args.sha256, "Binary checksum mismatch")
    require(not port_open(args.port), "Chosen loopback port is already occupied")
    require(memory_available() >= 384 * MIB, "Insufficient host memory to start safely")
    out = args.output.resolve()
    out.mkdir(parents=True, exist_ok=False)
    report = {"passed": False, "soakCertified": False, "scope": "http1-loopback-low-load",
              "revision": args.revision, "binarySha256": args.sha256,
              "baselineSha256": args.baseline_sha256, "harnessSha256": digest(__file__),
              "host": platform.platform(), "requestedSeconds": args.seconds,
              "warmupSeconds": args.warmup, "startedUtc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()),
              "thresholds": {"maxRssBytes": 192 * MIB, "idleGrowthBytes": 32 * MIB,
                             "medianGrowthBytes": 8 * MIB, "minHostAvailableBytes": 256 * MIB},
              "counts": {"requests": 0, "reconnects": 0, "slowReads": 0, "partialCloses": 0, "resets": 0}}
    command = [str(args.binary.resolve()), f"--server.port={args.port}", "--server.host=127.0.0.1",
               "--http.timeout=3000", "--http.maxConnections=16", "--logging.level=WARN"]
    report["command"] = command
    process = None
    connection = None
    samples = []
    started = time.monotonic()
    baseline = None
    write_report(out / "evidence.json", report)
    try:
        with (out / "server.log").open("w") as log, (out / "samples.jsonl").open("w", buffering=1) as measurements:
            process = subprocess.Popen(command, cwd=out, stdout=log, stderr=subprocess.STDOUT,
                                       env={k: v for k, v in os.environ.items() if k in {"PATH", "HOME", "LANG", "TMPDIR"}})
            report["serverPid"] = process.pid
            for _ in range(100):
                require(process.poll() is None, "Server exited during startup")
                if port_open(args.port):
                    break
                time.sleep(0.1)
            require(port_open(args.port), "Server did not become ready")
            verify_listener(process.pid, args.port)
            # Startup is not counted toward the 24-hour workload interval.
            started = time.monotonic()
            deadline = started + args.seconds
            cycle = 0
            while time.monotonic() < deadline:
                active_end = min(time.monotonic() + 50, deadline)
                connection = http.client.HTTPConnection("127.0.0.1", args.port, timeout=3)
                interrupted_peer(args.port)
                report["counts"]["partialCloses"] += 1
                interrupted_peer(args.port, reset=True)
                report["counts"]["resets"] += 1
                index = 0
                while time.monotonic() < active_end:
                    tick = time.monotonic()
                    slow = index % 10 == 0
                    request(connection, slow=slow)
                    report["counts"]["requests"] += 1
                    report["counts"]["slowReads"] += int(slow)
                    if index % 10 == 9:
                        connection.close()
                        connection = http.client.HTTPConnection("127.0.0.1", args.port, timeout=3)
                        report["counts"]["reconnects"] += 1
                    require(process.poll() is None, "Server exited during workload")
                    sample = sample_process(process.pid)
                    require(sample["rssBytes"] < 192 * MIB, "Server RSS exceeded hard guard")
                    require(sample["availableBytes"] >= 256 * MIB, "Host memory pressure; stopping test")
                    if index % 10 == 0:
                        sample.update(phase="active", elapsedSeconds=time.monotonic() - started)
                        samples.append(sample)
                        measurements.write(json.dumps(sample) + "\n")
                    time.sleep(max(0, min(1 - (time.monotonic() - tick), active_end - time.monotonic())))
                    index += 1
                connection.close()
                connection = None
                time.sleep(10)
                require(process.poll() is None, "Server exited during idle recovery")
                idle = sample_process(process.pid)
                idle.update(phase="idle", elapsedSeconds=time.monotonic() - started)
                samples.append(idle)
                measurements.write(json.dumps(idle) + "\n")
                if idle["elapsedSeconds"] >= args.warmup:
                    if baseline is None:
                        baseline = idle
                        report["idleBaseline"] = baseline
                    check_idle(idle, baseline)
                cycle += 1
                report.update(cycles=cycle, elapsedSeconds=time.monotonic() - started, lastSample=idle)
                write_report(out / "evidence.json", report)
            report["trend"] = check_trend(samples, args.warmup)
            report["shutdown"] = observe_shutdown(process, lambda: port_open(args.port), lambda: 0)
            del report["shutdown"]["databaseConnections"]  # This fixture does not use a database.
            require(not Path(f"/proc/{process.pid}/fd").exists(), "Server process resources remain after shutdown")
            report["shutdown"]["processResourcesReclaimed"] = True
            report["passed"] = True
            report["soakCertified"] = args.seconds >= 86400 and time.monotonic() - started >= 86400
    except BaseException as exc:
        report["error"] = f"{type(exc).__name__}: {exc}"
        raise
    finally:
        if connection is not None:
            connection.close()
        if process is not None and process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=30)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=10)
                report["forcedCleanup"] = True
        report["elapsedSeconds"] = time.monotonic() - started
        report["finishedUtc"] = time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())
        write_report(out / "evidence.json", report)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--binary", type=Path, required=True)
    parser.add_argument("--sha256", required=True)
    parser.add_argument("--revision", required=True)
    parser.add_argument("--baseline-sha256", required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--seconds", type=int, default=86400)
    parser.add_argument("--warmup", type=int, default=600)
    parser.add_argument("--port", type=int, default=18991)
    args = parser.parse_args()
    def interrupted(signum, frame):
        raise RuntimeError(f"Interrupted by signal {signum}")
    signal.signal(signal.SIGTERM, interrupted)
    run(args)


if __name__ == "__main__":
    main()
