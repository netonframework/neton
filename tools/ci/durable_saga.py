"""Three real Neton processes and databases; relay only injects transport faults."""
import argparse
import json
import secrets
import socket
import threading
import time
import uuid
from contextlib import ExitStack
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from durable_dc03 import database, expect, request, start


def run(binary):
    with ExitStack() as stack:
        roles = ("coordinator", "stock", "payment")
        token = secrets.token_hex(32)
        saga = uuid.uuid4().hex
        stores = {role: stack.enter_context(database(role)) for role in roles}
        services, urls, seen = {}, {}, {}
        losses = {"release:command", "release:result"}

        def state():
            return stores["coordinator"][1]("SELECT state,version FROM saga_state;")

        def stock():
            return stores["stock"][1]("SELECT reserved,releases FROM reservation;")

        def stored_receipt(role, suffix):
            return stores[role][1]("SELECT receipt FROM saga_queue WHERE message_id=:'id';", id=f"{saga}:{suffix}")

        def relay_for(role):
            class Relay(BaseHTTPRequestHandler):
                def log_message(self, *args):
                    pass

                def do_POST(self):
                    if self.headers.get("Authorization") != "Bearer " + token or self.path != "/saga/receive":
                        self.send_error(401)
                        return
                    raw = self.rfile.read(int(self.headers["Content-Length"]))
                    identity = json.loads(raw)["identity"]
                    mid = identity["messageId"]
                    status, ack = request(services[role][1], self.path, raw, token)
                    if status == 200:
                        rid = stores[role][1](
                            "SELECT receipt_id::text FROM nd_dc03_inbox WHERE message_id=:'id';", id=mid)
                        expect(json.loads(ack)["receiptId"], rid)
                        seen.setdefault(mid, []).append((raw, ack))
                        fault = mid.removeprefix(saga + ":")
                        if fault in losses:
                            # These reads use independent connections after the receiver returned.
                            expect(stock(), "f|1")
                            expect(state(), "COMPENSATING|2" if fault.endswith(":command") else "COMPENSATED|3")
                            losses.remove(fault)
                            self.close_connection = True
                            self.connection.shutdown(socket.SHUT_RDWR)
                            return
                    self.send_response(status)
                    self.send_header("Content-Length", str(len(ack)))
                    self.end_headers()
                    self.wfile.write(ack)

            relay = stack.enter_context(ThreadingHTTPServer(("127.0.0.1", 0), Relay))
            thread = threading.Thread(target=relay.serve_forever, daemon=True)
            thread.start()
            stack.callback(thread.join)
            stack.callback(relay.shutdown)
            return f"http://127.0.0.1:{relay.server_port}"

        def boot():
            for role in roles:
                services[role] = start(stack, binary, role, stores[role][0], token, saga_urls=urls)

        def crash_and_restart():
            for proc, _ in services.values():
                proc.kill()
                proc.wait(timeout=5)
            boot()

        def call(role, route, body, answer):
            expect(request(services[role][1], "/saga/" + route, body, token), (200, answer.encode()))

        def dispatch(role, suffix="", answer="HANDED_OFF"):
            call(role, "dispatch", f"{saga}:{suffix}" if suffix else "", answer)

        urls.update({role: relay_for(role) for role in roles})
        boot()
        call("coordinator", "begin", f"{saga}:{int(time.time()) + 600}", "READY")
        dispatch("coordinator", "reserve:command")
        expect(state(), "RESERVING|0")
        expect(stock(), "t|0")
        crash_and_restart()
        dispatch("stock", "reserve:result")
        expect(state(), "CHARGING|1")
        dispatch("coordinator", "charge:command")
        expect(state(), "CHARGING|1")
        dispatch("payment", "charge:result")
        expect(state(), "COMPENSATING|2")

        # Transient local compensation failure must roll back the business and its receipt.
        stock_sql = stores["stock"][1]
        stock_sql("ALTER TABLE reservation ADD CONSTRAINT unavailable CHECK(reserved);")
        dispatch("coordinator", "release:command", "UNKNOWN")
        expect(stock(), "t|0")
        expect(stock_sql("SELECT count(*) FROM nd_dc03_inbox;"), "1")
        stock_sql("ALTER TABLE reservation DROP CONSTRAINT unavailable;")
        dispatch("coordinator", "release:command", "UNKNOWN")
        expect(state(), "COMPENSATING|2")
        expect(stock(), "f|1")
        expect(stored_receipt("coordinator", "release:command"), "")
        crash_and_restart()

        # Callback may finish before the original compensation command is acknowledged.
        dispatch("stock", "release:result", "UNKNOWN")
        expect(state(), "COMPENSATED|3")
        expect(stored_receipt("stock", "release:result"), "")
        crash_and_restart()
        dispatch("stock")
        dispatch("coordinator")
        for suffix in ("release:command", "release:result"):
            first, second = seen[f"{saga}:{suffix}"]
            expect(first, second)
            sender = "coordinator" if suffix.endswith(":command") else "stock"
            expect(stored_receipt(sender, suffix), json.loads(first[1])["receiptId"])
        expect(stock(), "f|1")
        expect(state(), "COMPENSATED|3")
        expect(stores["payment"][1]("SELECT count(*) FROM payment_decision;"), "1")
        expect(stores["coordinator"][1]("SELECT count(*) FROM saga_queue;"), "3")
        for role in roles:
            dispatch(role, answer="IDLE")
        expect(losses, set())
        print("Saga HTTP PASS: async results; compensation rollback; command and result ACK lost after commit; "
              "three-process restart; same command/result replayed; one release and one terminal transition")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("binary", type=Path)
    run(parser.parse_args().binary.resolve(strict=True))
