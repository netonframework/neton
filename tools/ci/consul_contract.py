"""Exercise the registry side of N12-09 on a disposable loopback Consul agent."""
import json
import socket
import subprocess
import tempfile
import time
import uuid
from contextlib import ExitStack
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import ProxyHandler, Request, build_opener


def local_ports():
    with ExitStack() as stack:
        sockets = [stack.enter_context(socket.socket()) for _ in range(4)]
        for sock in sockets:
            sock.bind(("127.0.0.1", 0))
        return dict(zip(("http", "server", "serf_lan", "serf_wan"),
                        (sock.getsockname()[1] for sock in sockets)))


def main():
    ports = local_ports()
    node = "n12-" + uuid.uuid4().hex
    token = str(uuid.uuid4())
    client = build_opener(ProxyHandler({}))

    def call(path, method="GET", body=None, authenticated=True):
        req = Request(f"http://127.0.0.1:{ports['http']}/v1/" + path, method=method,
                      headers={"Content-Type": "application/json",
                               **({"X-Consul-Token": token} if authenticated else {})},
                      data=json.dumps(body).encode() if body is not None else None)
        with client.open(req, timeout=2) as response:
            return response.read()

    def until(check, name, timeout=20):
        deadline = time.monotonic() + timeout
        while agent.poll() is None and time.monotonic() < deadline:
            try:
                if check():
                    return
            except (URLError, TimeoutError):
                pass
            time.sleep(0.1)
        raise RuntimeError("Consul contract failed: " + name)

    def healthy():
        return {row["Service"]["ID"] for row in json.loads(call("health/service/orders?passing=true"))}

    with tempfile.TemporaryDirectory() as work:
        config = Path(work, "agent.json")
        config.write_text(json.dumps({"ports": {**ports, "dns": -1, "grpc": -1, "grpc_tls": -1},
                                      "acl": {"enabled": True, "default_policy": "deny",
                                              "tokens": {"initial_management": token, "agent": token}}}))
        config.chmod(0o600)
        agent = subprocess.Popen(["consul", "agent", "-dev", "-bind=127.0.0.1", "-client=127.0.0.1",
                                  "-node=" + node, "-config-file=" + str(config), "-data-dir=" + work],
                                 stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        try:
            until(lambda: json.loads(call("agent/self"))["Config"]["NodeName"] == node, "agent identity")
            until(lambda: bool(json.loads(call("status/leader"))), "leader election")
            try:
                call("agent/service/register", "PUT", {"Name": "forbidden"}, authenticated=False)
            except HTTPError as err:
                if err.code != 403:
                    raise
            else:
                raise AssertionError("anonymous registration accepted")
            for name, ttl in (("long", "5m"), ("short", "2s")):
                call("agent/service/register", "PUT", {"Name": "orders", "ID": name, "Address": "127.0.0.1",
                                                       "Port": 8080, "Check": {"TTL": ttl}})
                call("agent/check/pass/service:" + name, "PUT")
            until(lambda: healthy() == {"long", "short"}, "both registered instances")
            until(lambda: healthy() == {"long"}, "only expired instance excluded")
            call("agent/check/pass/service:short", "PUT")
            until(lambda: healthy() == {"long", "short"}, "renewal restores health")
            call("agent/service/deregister/short", "PUT")
            until(lambda: healthy() == {"long"}, "deregister one live instance")
        finally:
            agent.terminate()
            try:
                agent.wait(timeout=5)
            except subprocess.TimeoutExpired:
                agent.kill()
                agent.wait()
    print("Consul registry contract passed: denied write, registration, TTL expiry, recovery, removal")


if __name__ == "__main__":
    main()
