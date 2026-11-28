"""Run CFG acceptance against a disposable real Consul and native Neton fixture."""
import hashlib
import json
import os
import socket
import subprocess
import sys
import tempfile
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from contextlib import ExitStack
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import ProxyHandler, Request, build_opener

from consul_contract import local_ports


PREFIX = "/v1/kv/neton/config/fixture/test/"
OPENER = build_opener(ProxyHandler({}))


def call(origin, path, method="GET", data=None, token=None, timeout=4):
    request = Request(origin + path, data=data, method=method,
                      headers={"X-Consul-Token": token} if token else {})
    with OPENER.open(request, timeout=timeout) as response:
        return response.read().decode()


def until(check, why, timeout=15):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            if result := check():
                return result
        except (OSError, URLError):
            pass
        time.sleep(.03)
    raise AssertionError(why)


def stop(process):
    if process.poll() is None:
        process.terminate()
        try:
            process.wait(5)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait()


def denied(fn):
    try:
        fn()
    except HTTPError as error:
        assert error.code == 403, error
    else:
        raise AssertionError("Consul ACL accepted unauthorized request")


class FaultProxy:
    def __init__(self, origin):
        self.down = False
        self.rule = None
        self.paths = []
        proxy = self

        class Handler(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def log_message(self, *_):
                pass

            def do_GET(self):
                proxy.paths.append(self.path)
                rule = proxy.rule
                try:
                    if proxy.down:
                        raise ConnectionError("partition")
                    if rule:
                        rule(self.path, "before")
                    token = self.headers.get("X-Consul-Token")
                    data = call(origin, self.path, token=token).encode()
                    if rule:
                        rule(self.path, "after")
                    status = 200
                except HTTPError as error:
                    status, data = error.code, b""
                except (OSError, URLError):
                    self.close_connection = True
                    return
                try:
                    self.send_response(status)
                    self.send_header("Content-Length", str(len(data)))
                    self.end_headers()
                    self.wfile.write(data)
                except OSError:
                    pass

        class Server(ThreadingHTTPServer):
            def handle_error(self, request, address):
                if not isinstance(sys.exc_info()[1], (BrokenPipeError, ConnectionResetError)):
                    super().handle_error(request, address)

        self.server = Server(("127.0.0.1", 0), Handler)
        self.url = f"http://127.0.0.1:{self.server.server_port}"
        threading.Thread(target=self.server.serve_forever, daemon=True).start()

    def close(self):
        self.server.shutdown()
        self.server.server_close()


def run(binary, stack, work):
    ports = local_ports()
    admin = uuid.uuid4().hex
    config = work / "agent.json"
    config.write_text(json.dumps({
        "ports": {**ports, "dns": -1, "grpc": -1, "grpc_tls": -1},
        "acl": {"enabled": True, "default_policy": "deny",
                "tokens": {"initial_management": admin, "agent": admin}},
    }))
    config.chmod(0o600)
    consul = subprocess.Popen(
        ["consul", "agent", "-dev", "-bind=127.0.0.1", "-client=127.0.0.1",
         "-config-file=" + str(config)], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    stack.callback(stop, consul)
    origin = f"http://127.0.0.1:{ports['http']}"
    until(lambda: json.loads(call(origin, "/v1/status/leader", token=admin)), "Consul leader")

    def admin_call(path, method="GET", data=None):
        return call(origin, path, method, data, admin).strip()

    policy = json.loads(admin_call("/v1/acl/policy", "PUT", json.dumps({
        "Name": "fixture-kv-read", "Rules": 'key_prefix "neton/config/fixture/test/" { policy = "read" }',
    }).encode()))
    token = json.loads(admin_call("/v1/acl/token", "PUT", json.dumps({
        "Policies": [{"ID": policy["ID"]}],
    }).encode()))["SecretID"]
    other_key = "/v1/kv/neton/config/another/test/current"
    admin_call(other_key, "PUT", b"A")
    denied(lambda: call(origin, other_key, token=token))
    denied(lambda: call(origin, PREFIX + "current", "PUT", b"rogue", token))

    def publish(release, pair, cold="cold0", defect=None, environment='x = "env"\n', extra=""):
        documents = {
            "left.conf": f'x = "base"\npair = "{pair}"\n' + extra,
            "left.test.conf": environment,
            "right.conf": f'pair = "{pair if defect != "validate" else "different"}"\n',
            "cold.conf": f'value = "{cold}"\n',
        }
        digests = {key: hashlib.sha256(value.encode()).hexdigest() for key, value in documents.items()}
        if defect == "digest":
            digests["right.conf"] = "0" * 64
        root = PREFIX + f"releases/{release}/"
        for key, value in documents.items():
            if key != "right.conf" or defect != "missing":
                assert admin_call(root + "files/" + key, "PUT", value.encode()) == "true"
        assert admin_call(root + "manifest", "PUT", json.dumps(digests).encode()) == "true"
        try:
            index = json.loads(admin_call(PREFIX + "current?consistent"))[0]["ModifyIndex"]
        except HTTPError as error:
            if error.code != 404:
                raise
            index = 0
        assert admin_call(PREFIX + f"current?cas={index}", "PUT", release.encode()) == "true"

    def instance(label, proxy, override=None, cli=None, enabled=True, ready=True):
        directory = work / label
        (directory / "config").mkdir(parents=True, exist_ok=True)
        (directory / "config" / "left.conf").write_text('x = "local-base"\n')
        (directory / "config" / "left.test.conf").write_text('x = "local-env"\n')
        (directory / "config" / "application.conf").write_text('[logging]\nformat = "JSON"\nlevel = "DEBUG"\nasync = false\n')
        with socket.socket() as sock:
            sock.bind(("127.0.0.1", 0))
            port = sock.getsockname()[1]
        env = {**os.environ, "FIXTURE_PORT": str(port), "FIXTURE_TOKEN": token, "NETON_ENV": "test"}
        env["FIXTURE_CONSUL"] = proxy.url
        env["FIXTURE_DISABLE"] = "0" if enabled else "1"
        if override:
            env["NETON_LEFT__X"] = override
        log = directory / "output"
        with log.open("wb") as output:
            process = subprocess.Popen([binary, *([f"--left.x={cli}"] if cli else [])],
                                       env=env, cwd=directory, stdout=output, stderr=output)
        stack.callback(stop, process)
        url = f"http://127.0.0.1:{port}"
        if ready:
            try:
                until(lambda: call(url, "/state"), f"{label} ready")
            except AssertionError as error:
                raise AssertionError(log.read_text(errors="replace")) from error
        return process, url, log

    def state(node):
        return call(node[1], "/state").split("|")

    def applied(node, release):
        return until(lambda: state(node)[0] == release, f"release {release} on {node[1]}")

    def lines(node, kind):
        records = []
        for line in node[2].read_text(errors="replace").splitlines():
            if line.startswith("{") and f"neton.config.external.{kind}" in line:
                records.append(json.loads(line))
        return records

    p1, p2 = FaultProxy(origin), FaultProxy(origin)
    stack.callback(p1.close)
    stack.callback(p2.close)
    publish("Base", "base", environment="", extra='optional = "present"\n')
    base = instance("base", p2)
    assert state(base)[4:] == ["base", "base", "present"]
    publish("A", "a")
    applied(base, "A")
    assert state(base)[-1] == "default"
    stop(base[0])
    a, b = instance("one", p1, "variable"), instance("two", p2, "variable", "cli")
    assert state(a) == ["A", "a", "a", "cold0", "variable", "variable", "default"]
    assert state(b) == ["A", "a", "a", "cold0", "cli", "cli", "default"]
    plain = instance("plain", p2)
    assert state(plain)[4:6] == ["env", "env"]
    stop(plain[0])
    disabled_proxy = FaultProxy(origin)
    stack.callback(disabled_proxy.close)
    local = instance("local", disabled_proxy, enabled=False)
    assert state(local)[0] == "local" and state(local)[4:6] == ["local-env", "local-env"]
    stop(local[0])
    assert not disabled_proxy.paths
    print("CFG-01 precedence, removed keys, startup overlay and ACL passed")

    for index, defect in enumerate(("missing", "digest", "validate")):
        counts = [len(lines(node, "rejected")) for node in (a, b)]
        publish(f"bad{index}", "bad", defect=defect)
        for node, count in zip((a, b), counts):
            until(lambda: len(lines(node, "rejected")) > count, f"{defect} rejected")
            assert state(node)[0:3] == ["A", "a", "a"]
    dead = instance("bad-cold", p2, ready=False)
    assert dead[0].wait(10) != 0
    assert state(a)[0] == state(b)[0] == "A"
    publish("B", "b")
    for node in (a, b):
        applied(node, "B")
    print("CFG-02 invalid groups and cold readiness passed")

    p1.down = True
    publish("C", "c")
    applied(b, "C")
    until(lambda: lines(a, "retry.scheduled"), "partition retry")
    assert state(a)[0:3] == ["B", "b", "b"]
    p1.down = False
    applied(a, "C")

    # Intercept only final pointer reads, after the matching release documents were read.
    def recheck_fault(candidate, after, action, next_candidate=None, second_action=None):
        seen = set()
        hooks = {candidate: action, **({next_candidate: second_action} if next_candidate else {})}

        def hook(path, phase):
            if phase != "before":
                return
            for key in tuple(hooks):
                if f"/releases/{key}/files/" in path:
                    seen.add(key)
            if path.startswith(PREFIX + "current?"):
                for key in tuple(hooks):
                    if key in seen:
                        hooks.pop(key)()
                        return

        p1.rule = hook
        try:
            after()
            assert not hooks, hooks
        finally:
            p1.rule = None

    def partition_recheck():
        p1.down = True
        publish("AfterCut", "cut")
        raise ConnectionError("recheck disconnected before reaching Consul")

    def expect_cut():
        before = len(lines(a, "rejected"))
        publish("Cut", "should-not-apply")
        applied(b, "AfterCut")
        until(lambda: len(lines(a, "rejected")) > before, "recheck failed")
        assert state(a)[0] == "C"
        p1.down = False
        applied(a, "AfterCut")

    recheck_fault("Cut", expect_cut, partition_recheck)

    def expect_invalid_new():
        before = len(lines(a, "rejected"))
        publish("Superseded1", "before-invalid")
        until(lambda: len(lines(a, "rejected")) > before, "replacement validated")
        assert state(a)[0] == "AfterCut"

    recheck_fault("Superseded1", expect_invalid_new, lambda: publish("InvalidNew", "bad", defect="validate"))

    def expect_twice_superseded():
        publish("Superseded2", "first")
        applied(a, "Final")
        applied(b, "Final")

    recheck_fault("Superseded2", expect_twice_superseded, lambda: publish("Intermediate", "middle"),
                  "Intermediate", lambda: publish("Final", "c"))
    assert not {"Cut", "Superseded1", "InvalidNew", "Superseded2", "Intermediate"} & {
        row["release"] for row in lines(a, "applied")
    }
    print("CFG-03 partition, invalid successor and repeated recheck passed")

    with ThreadPoolExecutor(max_workers=2) as executor:
        held = [executor.submit(call, node[1], "/state?hold=1", timeout=25) for node in (a, b)]
        for node in (a, b):
            until(lambda: call(node[1], "/entered") == "true", "old request pinned")
        entered, release_old, done = threading.Event(), threading.Event(), threading.Event()
        retry_entered, release_retry = threading.Event(), threading.Event()
        seen_d = False

        def rule(path, phase):
            nonlocal seen_d
            if "/releases/D/files/" in path:
                seen_d = True
            if not seen_d or not path.startswith(PREFIX + "current?"):
                return
            if phase == "before" and entered.is_set() and not retry_entered.is_set():
                retry_entered.set()
                if not release_retry.wait(3):
                    raise ConnectionError("retry barrier expired")
            if phase == "after" and not entered.is_set():
                entered.set()
                release_old.wait(18)
                done.set()

        p1.rule = rule
        scheduled = len(lines(a, "retry.scheduled"))
        publish("D", "d")
        assert entered.wait(8), "D final recheck captured"
        old_attempt = next(row["attempt"] for row in lines(a, "recheck") if row["release"] == "D")
        applied(b, "D")
        p1.down = True
        publish("R", "a")
        applied(b, "R")
        p1.down = False
        until(lambda: len(lines(a, "retry.scheduled")) > scheduled, "old read ended; recovery retry scheduled")
        assert state(a)[0] == "Final"
        scheduled_before_restore = lines(a, "retry.scheduled")[-1]["attempt"]
        assert retry_entered.wait(3), "scheduled retry did not start"
        assert old_attempt + 1 == scheduled_before_restore
        assert old_attempt in {row["attempt"] for row in lines(a, "attempt.done")}
        assert lines(a, "read.retry")[-1]["attempt"] == scheduled_before_restore
        release_retry.set()
        applied(a, "R")
        # Now test late transport completion while a *new* failure's retry is pending.
        # The serial worker already exited the old operation before publishing R.
        new_failure, new_retry, let_retry_run = threading.Event(), threading.Event(), threading.Event()

        def recover_current(path, phase):
            if phase != "before" or not path.startswith(PREFIX + "current?"):
                return
            if not new_failure.is_set():
                new_failure.set()
                raise ConnectionError("post-rollback read failed")
            if not new_retry.is_set():
                new_retry.set()
                if not let_retry_run.wait(3):
                    raise ConnectionError("post-rollback retry barrier expired")

        until(lambda: any(row["attempt"] == scheduled_before_restore for row in lines(a, "retry.recovered")),
              "rollback retry recovered")
        p1.rule = recover_current
        assert new_failure.wait(4), "post-rollback failure not exercised"
        assert new_retry.wait(4), "post-rollback retry did not start"
        current_attempt = lines(a, "retry.scheduled")[-1]["attempt"]
        assert lines(a, "read.retry")[-1]["attempt"] == current_attempt
        rejected = lines(a, "rejected")
        retry_plan = lines(a, "retry.scheduled")
        window = len(p1.paths)
        release_old.set()
        assert done.wait(3), "late response released"
        assert len(p1.paths) == window, "old or background read overtook the original retry"
        let_retry_run.set()
        until(lambda: any(row["attempt"] == current_attempt for row in lines(a, "retry.recovered")),
              "same post-rollback retry recovered")
        assert lines(a, "rejected") == rejected, "late response changed rejection diagnostics"
        assert lines(a, "retry.scheduled") == retry_plan, "late response changed the retry plan"
        assert current_attempt not in {row["attempt"] for row in lines(a, "read.poll")}
        p1.rule = None
        for node in (a, b):
            call(node[1], "/resume")
        for response in held:
            assert response.result().split("|")[:3] == ["Final", "c", "c"]
        assert all(state(node)[:3] == ["R", "a", "a"] for node in (a, b))
        for node in (a, b):
            trace = [line["release"] for line in lines(node, "applied")]
            assert trace[trace.index("R"):] == ["R"], trace
        assert "D" not in {line["release"] for line in lines(a, "applied")}
        rollback_attempt = next(row["attempt"] for row in lines(a, "applied") if row["release"] == "R")
        assert rollback_attempt == scheduled_before_restore
        assert rollback_attempt in {row["attempt"] for row in lines(a, "read.retry")}
        assert rollback_attempt in {row["attempt"] for row in lines(a, "retry.recovered")}
        assert rollback_attempt not in {row["attempt"] for row in lines(a, "read.poll")}
    print("CFG-04 late response, pinned requests, retry provenance and commit traces passed")

    publish("Restart", "restarted", "cold1")
    for node in (a, b):
        until(lambda: lines(node, "pending_restart"), "pending restart")
        assert state(node)[:4] == ["R", "a", "a", "cold0"]
    stop(a[0])
    new_a = instance("one-restarted", p1)
    assert state(new_a)[:4] == ["Restart", "restarted", "restarted", "cold1"]
    assert state(b)[0] == "R"
    stop(b[0])
    new_b = instance("two-restarted", p2)
    assert state(new_b)[:4] == ["Restart", "restarted", "restarted", "cold1"]
    print("CFG-05 whole-group rolling restart passed")


if __name__ == "__main__":
    with tempfile.TemporaryDirectory(prefix="neton-cfg-") as path, ExitStack() as stack:
        run(str(Path(sys.argv[1]).resolve()), stack, Path(path))
