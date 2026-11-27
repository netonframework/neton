"""DC-03: real Neton sender/receiver; a loopback hop drops a committed receipt."""
import argparse
import getpass
import hashlib
import http.client
import json
import os
import secrets
import socket
import subprocess
import tempfile
import threading
import time
import uuid
from concurrent.futures import ThreadPoolExecutor
from contextlib import ExitStack, contextmanager
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import quote

ROOT = Path(__file__).resolve().parents[2]


def expect(actual, wanted):
    if actual != wanted:
        raise AssertionError(f"expected {wanted!r}, got {actual!r}")


def request(port, path, body=None, token=None):
    conn = http.client.HTTPConnection("127.0.0.1", port, timeout=10)
    try:
        conn.request("GET" if body is None else "POST", path, body, {
            "Content-Type": "application/json", "Connection": "close",
            **({"Authorization": "Bearer " + token} if token else {}),
        })
        response = conn.getresponse()
        return response.status, response.read()
    finally:
        conn.close()


@contextmanager
def database(role):
    name = "dc03_" + role + "_" + uuid.uuid4().hex
    user = os.environ.get("PGUSER", getpass.getuser())
    port = int(os.environ.get("PGPORT", "5432"))
    env = dict(os.environ, PGHOST="127.0.0.1", PGUSER=user, PGPORT=str(port))
    subprocess.run(["createdb", name], env=env, check=True)
    try:
        def sql(statement, **values):
            args = ["psql", "-XqAtw", "-v", "ON_ERROR_STOP=1", "-d", name]
            for key, value in values.items():
                args.extend(["-v", f"{key}={value}"])
            return subprocess.check_output(args, env=env, input=statement, text=True).strip()
        if role == "receiver":
            sql((ROOT / "neton-durable/sql/postgresql/V001__dc03_inbox.sql").read_text())
        sql((ROOT / f"examples/durable-dc03/sql/{role}.sql").read_text())
        password = quote(env.get("PGPASSWORD") or "local-fixture", safe="")
        yield f"postgresql://{quote(user, safe='')}:{password}@127.0.0.1:{port}/{name}", sql
    finally:
        subprocess.run(["dropdb", "--force", name], env=env, check=True)


def stop(proc):
    if proc.poll() is not None:
        return
    proc.terminate()
    try:
        proc.wait(timeout=3)
    except subprocess.TimeoutExpired:
        proc.kill()
        proc.wait()


def start(stack, binary, role, uri, token, relay_port=None, target="ledger"):
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        port = sock.getsockname()[1]
    work = Path(stack.enter_context(tempfile.TemporaryDirectory()))
    (work / "config").mkdir()
    (work / "config/application.conf").write_text(
        f'[application]\nname = "dc03-{role}"\n[server]\nhost = "127.0.0.1"\nport = {port}\n')
    env = dict(os.environ, DC03_ROLE=role, DC03_TOKEN=token, DC03_DB=uri, DC03_PORT=str(port), DC03_TARGET=target,
               DC03_URL=f"http://127.0.0.1:{relay_port}/receive")
    log = stack.enter_context((work / "process.log").open("wb"))
    proc = subprocess.Popen([binary], cwd=work, env=env, stdout=log, stderr=log)
    stack.callback(stop, proc)
    deadline = time.monotonic() + 20
    while proc.poll() is None and time.monotonic() < deadline:
        try:
            status, body = request(port, "/ready")
            if status == 200 and body.decode().strip('"') == role:
                return proc, port
        except OSError:
            pass
        time.sleep(.05)
    raise RuntimeError(f"{role} failed readiness (exit={proc.poll()})")


def run(binary):
    with ExitStack() as stack:
        token = secrets.token_hex(32)
        send_uri, sender_sql = stack.enter_context(database("sender"))
        recv_uri, receiver_sql = stack.enter_context(database("receiver"))
        receiver, receiver_port = start(stack, binary, "receiver", recv_uri, token)
        observations = []

        class Relay(BaseHTTPRequestHandler):
            def log_message(self, *args):
                pass

            def do_POST(self):
                if self.headers["Authorization"] != "Bearer " + token:
                    self.send_error(401)
                    return
                raw = self.rfile.read(int(self.headers["Content-Length"]))
                status, ack = request(receiver_port, "/receive", raw, token)
                identity = json.loads(raw)["identity"]
                # Independent connection: both commits must be visible BEFORE fault injection.
                row = receiver_sql("SELECT receipt_id::text FROM nd_dc03_inbox WHERE message_id=:'id';",
                                   id=identity["messageId"])
                expect(status, 200)
                expect(json.loads(ack)["receiptId"], row)
                expect(receiver_sql("SELECT balance,applies FROM ledger;"), "7|1")
                observations.append((raw, ack))
                if len(observations) == 1:
                    self.close_connection = True
                    self.connection.shutdown(socket.SHUT_RDWR)
                    return
                if len(observations) == 2:
                    wrong = json.loads(ack)
                    wrong["identity"]["invocationId"] = "unrelated"
                    ack = json.dumps(wrong).encode()
                self.send_response(200)
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(ack)))
                self.end_headers()
                self.wfile.write(ack)

        relay = stack.enter_context(ThreadingHTTPServer(("127.0.0.1", 0), Relay))
        thread = threading.Thread(target=relay.serve_forever, daemon=True)
        thread.start()
        stack.callback(thread.join)
        stack.callback(relay.shutdown)
        sender, sender_port = start(stack, binary, "sender", send_uri, token, relay.server_port)
        mid = uuid.uuid4().hex
        msg = {"identity": {"protocolVersion": 1, "namespace": "dc03", "tenant": "local", "source": "orders",
                            "target": "ledger", "messageId": mid, "invocationId": uuid.uuid4().hex,
                            "requestDigest": hashlib.sha256(b"credit:v1:7").hexdigest()},
               "amount": 7, "expiresAt": int(time.time()) + 600}
        raw = json.dumps(msg)
        sender_sql("INSERT INTO outbox(message_id,wire) VALUES (:'id',:'wire');", id=mid, wire=raw)
        def dispatch(outcome, attempt):
            expect(request(sender_port, "/recover", "", token), (200, outcome.encode()))
            expect(sender_sql("SELECT state,attempts FROM outbox;"), f"{'HANDED_OFF' if outcome == 'IDLE' else outcome}|{attempt}")
        dispatch("UNKNOWN", 1)
        expect(len(observations), 1)
        # No service memory may be used to reconstruct the envelope or the receipt.
        stop(sender)
        stop(receiver)
        receiver, receiver_port = start(stack, binary, "receiver", recv_uri, token)
        sender, sender_port = start(stack, binary, "sender", send_uri, token, relay.server_port)
        dispatch("UNKNOWN", 2)
        dispatch("HANDED_OFF", 3)
        expect([item[0] for item in observations], [raw.encode()] * 3)
        expect(len({ack for _, ack in observations}), 1)
        expect(sender_sql("SELECT receipt FROM outbox;"), json.loads(observations[0][1])["receiptId"])
        dispatch("IDLE", 3)
        expect(len(observations), 3)

        # Independent live deliveries of one new command still have one business effect.
        msg["identity"].update(messageId=uuid.uuid4().hex, invocationId=uuid.uuid4().hex)
        raw = json.dumps(msg)
        with ThreadPoolExecutor(2) as pool:
            locker = subprocess.Popen(["psql", "-XqAtw", "-h", "127.0.0.1", "-d", receiver_sql("SELECT current_database();")], stdin=subprocess.PIPE,
                                      stdout=subprocess.PIPE, text=True)
            try:
                locker.stdin.write("BEGIN; LOCK TABLE nd_dc03_inbox IN EXCLUSIVE MODE; SELECT pg_backend_pid();\n")
                locker.stdin.flush()
                locker_pid = int(locker.stdout.readline())
                jobs = [pool.submit(request, receiver_port, "/receive", raw, token) for _ in range(2)]
                deadline = time.monotonic() + 7
                while receiver_sql(f"SELECT count(*) FROM pg_stat_activity WHERE {locker_pid}=ANY(pg_blocking_pids(pid));") != "2":
                    if time.monotonic() >= deadline:
                        raise AssertionError("two concurrent deliveries did not reach the inbox lock")
                    time.sleep(.02)
            finally:
                locker.communicate("ROLLBACK;\n", timeout=5)
            one, two = [job.result() for job in jobs]
        expect(one[0], 200)
        expect(one, two)
        expect(receiver_sql("SELECT balance,applies FROM ledger;"), "14|2")
        expect(receiver_sql("SELECT count(*) FROM nd_dc03_inbox;"), "2")
        _, second_port = start(stack, binary, "receiver", recv_uri, token, target="audit")
        msg["identity"]["target"] = "audit"
        second = request(second_port, "/receive", json.dumps(msg), token)
        expect(second[0], 200)
        expect(request(second_port, "/receive", json.dumps(msg), token), second)
        expect(json.loads(second[1])["receiptId"] != json.loads(one[1])["receiptId"], True)
        expect(receiver_sql("SELECT balance,applies FROM ledger;"), "21|3")
        print("DC-03 PASS: commit observed before lost ACK; both services restarted; wrong ACK rejected; "
              "same envelope replayed; durable receipt matched; duplicate business effect excluded")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("binary", type=Path)
    run(parser.parse_args().binary.resolve(strict=True))
