#!/usr/bin/env python3
"""Build and accept the public reference fixture on an isolated native host."""
import argparse
import csv
import hashlib
import json
import os
from pathlib import Path
import platform
import signal
import socket
import subprocess
import tempfile
import time
import urllib.request
import uuid


class GateFailure(RuntimeError):
    pass


def require(condition, message):
    if not condition:
        raise GateFailure(message)


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def port_open(port):
    with socket.socket() as sock:
        sock.settimeout(1)
        return sock.connect_ex(("127.0.0.1", port)) == 0


def parse_connections(result):
    require(result.returncode == 0, "Connection inspection command failed")
    value = result.stdout.strip()
    require(value.isascii() and value.isdigit(), "Malformed connection inspection result")
    return int(value)


def observe_shutdown(process, is_port_open, connections, timeout=30, shutdown_signal=signal.SIGTERM):
    # Observation only: never clean up first and mistake cleanup for graceful shutdown.
    require(process.poll() is None, "Process exited before graceful shutdown")
    started = time.monotonic()
    process.send_signal(shutdown_signal)
    try:
        code = process.wait(timeout=timeout)
    except subprocess.TimeoutExpired as error:
        raise GateFailure("Graceful shutdown timed out") from error
    require(code == 0, f"Nonzero exit after graceful shutdown: {code}")
    elapsed = time.monotonic() - started
    require(elapsed <= timeout, "Shutdown exceeded deadline")
    require(not is_port_open(), "HTTP port remains open after exit")
    count = connections()
    require(count == 0, f"Database connections leaked after exit: {count}")
    return {"exitCode": code, "shutdownSeconds": elapsed, "portClosed": True,
            "databaseConnections": count, "observedBeforeTeardown": True}


def clean_revision(directory):
    revision = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=directory, text=True).strip()
    require(not subprocess.check_output(["git", "status", "--porcelain"], cwd=directory, text=True).strip(),
            f"Dirty candidate source: {directory}")
    return revision


def run_logged(command, log, **kwargs):
    with log.open("w") as output:
        subprocess.run(command, stdout=output, stderr=subprocess.STDOUT, check=True, **kwargs)


def validate_health(body):
    require(body == {"code": 0, "message": "OK", "data": {"status": "READY", "value": "neton-reference"}},
            "Unexpected health envelope")
    return body


def health(port):
    # Ignore developer proxy settings: the gate only contacts its loopback listener.
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}))
    with opener.open(f"http://127.0.0.1:{port}/health", timeout=3) as response:
        require(response.status == 200, "Health did not return HTTP 200")
        body = json.load(response)
    return validate_health(body)


def smoke(binary, pg_bin, output, evidence):
    # Every run owns a PostgreSQL cluster, not just a schema on a developer server.
    # Keep Unix socket paths below macOS's sockaddr_un length limit.
    windows = os.name == "nt"
    def pg_exe(tool):
        return str(pg_bin / (tool + ".exe" if windows else tool))

    with tempfile.TemporaryDirectory(prefix="neton-reference-", dir=None if windows else "/tmp") as temporary:
        work = Path(temporary)
        if windows:
            # initdb drops administrator privileges. Python's private temporary
            # directory ACL may name the elevated token owner (Administrators).
            # Grant only this process's user SID on our owned disposable folder;
            # do not grant Everyone/Users or modify any parent directory ACL.
            identity = subprocess.check_output(['whoami', '/user', '/fo', 'csv', '/nh'], text=True)
            row = next(csv.reader(identity.strip().splitlines()))
            sid = row[-1]
            require(sid.startswith('S-1-') and all(part.isdigit() for part in sid[2:].split('-')),
                    'Could not identify Windows gate user SID')
            run_logged(['icacls', str(work), '/grant', f'*{sid}:(OI)(CI)M'],
                       output / 'postgres-work-acl.log', timeout=30)
        data = work / "pgdata"
        sockets = work / "sockets"
        sockets.mkdir()
        db_port, http_port = free_port(), free_port()
        while db_port == http_port:
            http_port = free_port()
        database = "neton_gate_" + uuid.uuid4().hex
        env = {key: value for key, value in os.environ.items()
               if not key.startswith(("PG", "NETON_")) and key not in ("ENV", "NODE_ENV")}
        env.update(PGHOST="127.0.0.1", PGPORT=str(db_port), PGUSER="neton_gate", PGDATABASE="postgres")
        env["PATH"] = str(pg_bin) + os.pathsep + env.get("PATH", "")
        started = False
        process = None

        def pg(tool, *args, **kwargs):
            return subprocess.run([pg_exe(tool), *args], env=env, text=True,
                                  capture_output=True, timeout=30, **kwargs)

        def connections():
            return parse_connections(pg("psql", "-X", "-A", "-t", "-v", "ON_ERROR_STOP=1", "-c",
                f"SELECT count(*) FROM pg_stat_activity WHERE datname = '{database}'"))

        try:
            run_logged([pg_exe("initdb"), "-D", str(data), "-U", "neton_gate",
                        "--auth=trust", "--no-locale", "-E", "UTF8"], output / "postgres-init.log", env=env, timeout=60)
            # PG is bound only to loopback; its trust credentials exist only in this disposable cluster.
            started = True  # also attempt cleanup if pg_ctl starts but its readiness wait fails
            options = f"-h 127.0.0.1 -p {db_port}" + ("" if windows else f" -k {sockets}")
            run_logged([pg_exe("pg_ctl"), "-D", str(data), "-l", str(output / "postgres.log"),
                        "-o", options, "-w", "start"],
                       output / "postgres-start.log", env=env, timeout=60)
            pg("createdb", database, check=True)
            config = work / "config"
            config.mkdir()
            (config / "application.conf").write_text(f'[server]\nhost = "127.0.0.1"\nport = {http_port}\n')
            (config / "database.conf").write_text(
                '[default]\ndriver = "POSTGRESQL"\n'
                f'uri = "postgresql://neton_gate:disposable@127.0.0.1:{db_port}/{database}"\n'
                '[migration]\nhistory_table = "neton_reference_history"\n')
            evidence["migrations"] = []
            for index, command in enumerate(("up", "up", "status", "verify")):
                log = output / f"migration-{index}-{command}.log"
                run_logged([str(binary), "migrate", command], log, cwd=work, env=env, timeout=60)
                require(f"NETON_REFERENCE_MIGRATION_{command.upper()}_OK" in log.read_text(encoding="utf-8"),
                        "Migration success marker missing")
                require(connections() == 0, "Migration process leaked a database connection")
                evidence["migrations"].append({"command": command, "exitCode": 0, "connectionsAfterExit": 0})
            evidence["cycles"] = []
            for cycle in (1, 2):
                require(not port_open(http_port), "HTTP port already occupied before launch")
                with (output / f"cycle-{cycle}.log").open("w") as log:
                    process = subprocess.Popen([str(binary)], cwd=work, env=env, stdout=log,
                                               stderr=subprocess.STDOUT,
                                               start_new_session=not windows,
                                               creationflags=subprocess.CREATE_NEW_PROCESS_GROUP if windows else 0)
                    deadline = time.monotonic() + 60
                    while True:
                        require(process.poll() is None, "Reference process exited before readiness")
                        try:
                            response = health(http_port)
                            break
                        except (OSError, ValueError, GateFailure):
                            require(time.monotonic() < deadline, "Reference readiness timed out")
                            time.sleep(0.25)
                    # Prove the pool was used before later asserting that it was closed.
                    active = connections()
                    require(active > 0, "No application database connection while serving")
                    healthy_since = time.monotonic()
                    while time.monotonic() - healthy_since < 60:
                        require(process.poll() is None, "Reference process exited during hold")
                        health(http_port)
                        time.sleep(1)
                    held = time.monotonic() - healthy_since
                    result = observe_shutdown(process, lambda: port_open(http_port), connections,
                                              shutdown_signal=signal.CTRL_BREAK_EVENT if windows else signal.SIGTERM)
                    result["shutdownSignal"] = "CTRL_BREAK_EVENT" if windows else "SIGTERM"
                    result.update(cycle=cycle, healthySeconds=held, health=response, connectionsWhileServing=active)
                    evidence["cycles"].append(result)
                    process = None
                    print(f"Reference cycle {cycle}: healthy >=60s, exit 0, port closed, connections 0", flush=True)
        finally:
            # These actions are cleanup, never acceptance evidence.
            if process is not None and process.poll() is None:
                if windows:
                    process.kill()
                else:
                    os.killpg(process.pid, signal.SIGKILL)
                process.wait(timeout=10)
            if started:
                result = pg("pg_ctl", "-D", str(data), "-m", "immediate", "-w", "stop")
                require(result.returncode == 0, "Owned PostgreSQL cluster cleanup failed")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--target", choices=("macosArm64", "linuxX64", "linuxArm64", "mingwX64"), required=True)
    parser.add_argument("--dependencies", choices=("sources", "published"), default="sources")
    parser.add_argument("--pg-bin", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=False)
    evidence = {"passed": False, "fixture": "examples/release-gate", "target": args.target,
                "repository": "https://github.com/netonframework/neton", "host": platform.platform()}
    owned_console = False
    try:
        if os.name == "nt":
            # Hosted Windows runners may start Python without a console. Allocate
            # one for this harness and its owned process groups only; never send
            # an event to process-group zero (which would affect unrelated jobs).
            import ctypes
            kernel = ctypes.windll.kernel32
            if kernel.GetConsoleCP() == 0:
                require(kernel.AllocConsole() != 0, "Cannot allocate the gate's Windows control console")
                owned_console = True
        for tool in ("initdb", "pg_ctl", "psql", "createdb"):
            require((args.pg_bin / (tool + ".exe" if os.name == "nt" else tool)).is_file(), f"Missing PostgreSQL tool: {tool}")
        require((platform.system(), platform.machine()) in {
            "macosArm64": ("Darwin", "arm64"), "linuxX64": ("Linux", "x86_64"),
            "linuxArm64": ("Linux", "aarch64"),
            "mingwX64": ("Windows", "AMD64"),
        }.values(), "Unsupported host")
        expected_host = {"macosArm64": ("Darwin", "arm64"), "linuxX64": ("Linux", "x86_64"),
                         "linuxArm64": ("Linux", "aarch64"), "mingwX64": ("Windows", "AMD64")}[args.target]
        require((platform.system(), platform.machine()) == expected_host, "Target must match the native runner")
        evidence["revision"] = clean_revision(root)
        require(not (root / "examples/release-gate/build").exists(),
                "Reference gate requires a fresh checkout/output directory")
        evidence["postgresVersion"] = subprocess.check_output(
            [str(args.pg_bin / ("postgres.exe" if os.name == "nt" else "postgres")), "--version"], text=True).strip()
        sources = Path(os.environ.get("NETON_PROTOCOL_SOURCES_DIR", str(root.parent))).resolve()
        evidence["dependencies"] = {}
        evidence["dependencyMode"] = args.dependencies
        evidence["versionCatalogSha256"] = hashlib.sha256((root / "gradle/libs.versions.toml").read_bytes()).hexdigest()
        if args.dependencies == "sources":
            for line in (root / "tools/ci/protocol-sources.lock").read_text().splitlines():
                name, revision = line.split()
                require(clean_revision(sources / name) == revision, f"Protocol pin mismatch: {name}")
                evidence["dependencies"][name] = revision
        task = f":examples:release-gate:linkReleaseExecutable{args.target[0].upper() + args.target[1:]}"
        wrapper = str(root / "gradlew.bat") if os.name == "nt" else "./gradlew"
        mode = (["-Pnetonstream.local=true", "-Pnetonstream.local.openssl=true",
                 f"-Pnetonstream.sourcesDir={sources}", "-PnativeTargets=all"]
                if args.dependencies == "sources" else ["-Pnetonstream.local=false"])
        command = [wrapper, "--no-daemon", "--warning-mode=fail", *mode, "-Pneton.database.driver=postgres", task]
        evidence["buildCommand"] = command
        evidence["smokeCommand"] = ["python3", "tools/ci/reference_gate.py", "--target", args.target,
                                    "--pg-bin", str(args.pg_bin), "--output", str(output), "--dependencies", args.dependencies]
        # A fresh CI checkout supplies clean output; the second build must succeed without clean.
        for attempt in (1, 2):
            print(f"Building reference release binary (pass {attempt})", flush=True)
            run_logged(command, output / f"build-{attempt}.log", cwd=root, timeout=2400)
        binary = root / f"examples/release-gate/build/bin/{args.target}/releaseExecutable" / (
            "release-gate.exe" if os.name == "nt" else "release-gate.kexe")
        require(binary.is_file(), "Release binary missing")
        evidence["binarySha256"] = hashlib.sha256(binary.read_bytes()).hexdigest()
        smoke(binary, args.pg_bin.resolve(), output, evidence)
        require(clean_revision(root) == evidence["revision"], "Candidate source changed during acceptance")
        for name, revision in evidence["dependencies"].items():
            require(clean_revision(sources / name) == revision, f"Protocol source changed: {name}")
        require(hashlib.sha256(binary.read_bytes()).hexdigest() == evidence["binarySha256"], "Binary changed during acceptance")
        evidence["passed"] = True
    except Exception as error:
        evidence["failure"] = f"{type(error).__name__}: {error}"
        raise
    finally:
        (output / "evidence.json").write_text(json.dumps(evidence, indent=2) + "\n")
        if owned_console:
            kernel.FreeConsole()


if __name__ == "__main__":
    main()
