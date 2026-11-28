"""Three real Neton processes and databases; relay only injects transport faults."""
import argparse
import json
import secrets
import socket
import threading
import time
import uuid
from contextlib import ExitStack
from concurrent.futures import ThreadPoolExecutor
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

from durable_dc03 import database, expect, hold_table, request, start


def run(binary, scenario):
    with ExitStack() as stack:
        roles = ("coordinator", "stock", "payment")
        token = secrets.token_hex(32)
        operator = secrets.token_hex(32)
        saga = uuid.uuid4().hex
        stores = {role: stack.enter_context(database(role)) for role in roles}
        services, urls, seen, queried, deliveries = {}, {}, {}, {}, {}
        losses = ({"release:command", "release:result"} if scenario == "replay" else
                  {"release:command"} if scenario in ("recovery", "schedule") else set())
        held_ack, resume_ack = threading.Event(), threading.Event()
        hold_release = scenario in ("takeover", "expired", "schedule")
        attempts = {"exhaustion": 2, "expired": 1, "recovery": 1}.get(scenario, 8)

        def state():
            return stores["coordinator"][1]("SELECT state,version FROM saga_state WHERE id=:'id';", id=saga)

        def stock():
            return stores["stock"][1]("SELECT reserved,releases FROM reservation WHERE saga_id=:'id';", id=saga)

        def stored_receipt(role, suffix):
            return stores[role][1]("SELECT receipt FROM saga_queue WHERE message_id=:'id';", id=f"{saga}:{suffix}")

        def relay_for(role):
            class Relay(BaseHTTPRequestHandler):
                def log_message(self, *args):
                    pass

                def do_POST(self):
                    if self.headers.get("Authorization") != "Bearer " + token or self.path not in ("/saga/receive", "/saga/outcome"):
                        self.send_error(401)
                        return
                    raw = self.rfile.read(int(self.headers["Content-Length"]))
                    identity = json.loads(raw)["identity"]
                    mid = identity["messageId"]
                    if self.path == "/saga/receive":
                        deliveries[mid] = deliveries.get(mid, 0) + 1
                    try:
                        status, ack = request(services[role][1], self.path, raw, token)
                    except ConnectionRefusedError:
                        if scenario != "recovery" or role != "coordinator":
                            raise
                        # A participant can drain its outbox before the restarted coordinator binds.
                        self.send_error(503)
                        return
                    if self.path == "/saga/outcome":
                        queried.setdefault(mid, []).append((status, ack))
                    elif status == 200:
                        rid = stores[role][1](
                            "SELECT receipt_id::text FROM nd_dc03_inbox WHERE message_id=:'id';", id=mid)
                        expect(json.loads(ack)["receiptId"], rid)
                        seen.setdefault(mid, []).append((raw, ack))
                        fault = mid.removeprefix(saga + ":")
                        if hold_release and fault == "release:command" and len(seen[mid]) == 1:
                            held_ack.set()
                            if not resume_ack.wait(8):
                                raise AssertionError("test did not release the old worker")
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

        def boot(auto=False):
            # Start the compensation target before enabling the coordinator's one restored attempt.
            for role in (("stock", "payment", "coordinator") if auto else roles):
                services[role] = start(stack, binary, role, stores[role][0], token, saga_urls=urls,
                                       attempts=attempts, operator=operator if role == "coordinator" else None,
                                       auto=auto, renew=hold_release, scheduled=scenario == "schedule")

        def crash_and_restart(auto=False):
            for proc, _ in services.values():
                proc.kill()
                proc.wait(timeout=5)
            boot(auto)

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

        coordinator_sql = stores["coordinator"][1]
        lease = "SELECT state,generation FROM ns_compensation;"
        audit = "SELECT generation,event,reason FROM ns_compensation_audit;"
        if scenario == "schedule":
            other = uuid.uuid4().hex
            coordinator_sql("UPDATE ns_compensation_admission SET capacity=1;")
            call("coordinator", "begin", f"{other}:{int(time.time()) + 600}", "READY")
            for role, suffix in (("coordinator", "reserve:command"), ("stock", "reserve:result"),
                                 ("coordinator", "charge:command"), ("payment", "charge:result")):
                call(role, "dispatch", f"{other}:{suffix}", "HANDED_OFF")
            cid = f"{saga}:release:command"
            other_id = f"{other}:release:command"
            _, replacement = start(stack, binary, "coordinator", stores["coordinator"][0], token,
                                   saga_urls=urls, scheduled=True)
            # Ensure the single slot is held across a real HTTP request, not a synthetic SQL claim.
            with ThreadPoolExecutor(1) as pool:
                first = pool.submit(request, services["coordinator"][1], "/saga/dispatch", cid, token)
                try:
                    expect(held_ack.wait(7), True)
                    expect(request(replacement, "/saga/dispatch", other_id, token), (200, b"AT_CAPACITY"))
                    expect(coordinator_sql("SELECT generation FROM ns_compensation WHERE id=:'id';", id=other_id), "0")
                    before = coordinator_sql("SELECT clock_timestamp();")
                finally:
                    resume_ack.set()
                expect(first.result(), (200, b"UNKNOWN"))
            after = coordinator_sql("SELECT clock_timestamp();")
            expect(coordinator_sql("""
                SELECT next_attempt_at BETWEEN CAST(:'before' AS timestamptz)+interval '1 second'
                AND CAST(:'after' AS timestamptz)+interval '2 seconds' FROM ns_compensation WHERE id=:'id';
            """, before=before, after=after, id=cid), "t")
            coordinator_sql("UPDATE ns_compensation SET next_attempt_at=clock_timestamp()+interval '1 hour' WHERE id=:'id';", id=cid)
            crash_and_restart()
            dispatch("coordinator", "release:command", "BACKOFF")
            expect(len(seen[cid]), 1)
            expect(queried.get(cid), None)
            # Force the persisted due date; no dependency on a sleep or scheduler speed.
            coordinator_sql("UPDATE ns_compensation SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE id=:'id';", id=cid)
            dispatch("coordinator", "release:command")
            expect(len(seen[cid]), 1)
            expect(queried[cid][0][0], 200)
            expect(coordinator_sql("SELECT state,version FROM saga_state WHERE id=:'id';", id=saga), "COMPENSATED|3")
            expect(stores["stock"][1]("SELECT reserved,releases FROM reservation WHERE saga_id=:'id';", id=saga), "f|1")
            # The original asynchronous callback can arrive after reconciliation without progressing twice.
            dispatch("stock", "release:result")
            expect(coordinator_sql("SELECT state,version FROM saga_state WHERE id=:'id';", id=saga), "COMPENSATED|3")
            # Without a committed outcome, a read miss cannot authorize another execution.
            stores["stock"][1]("ALTER TABLE reservation ADD CONSTRAINT deny_other_release CHECK (saga_id <> :'id' OR reserved);", id=other)
            call("coordinator", "dispatch", other_id, "UNKNOWN")
            coordinator_sql("UPDATE ns_compensation SET next_attempt_at=clock_timestamp()-interval '1 second' WHERE id=:'id';", id=other_id)
            call("coordinator", "dispatch", other_id, "UNKNOWN")
            expect(queried[other_id][0][0], 404)
            expect(deliveries[other_id], 1)
            expect(coordinator_sql("SELECT state,version FROM saga_state WHERE id=:'id';", id=other), "COMPENSATING|2")
            print("Saga scheduling PASS: shared concurrency cap, persisted jittered backoff, restart reconciles committed result without resend")
            return
        if scenario == "recovery":
            dispatch("coordinator", "release:command", "NEEDS_ATTENTION")
            expect(stock(), "f|1")
            expect(state(), "NEEDS_ATTENTION|3")
            port = services["coordinator"][1]
            base = f"{saga}|recovery-01|1|3|receiver-idempotency-verified:incident-17"
            restore = "/saga/recover"
            expect(request(port, restore, base, token), (403, b"FORBIDDEN"))
            expect(request(port, restore, base.replace("|3|", "|2|"), operator), (409, b"CONFLICT"))
            expect(request(port, restore, base.replace("|1|", "|0|"), operator), (409, b"CONFLICT"))
            expect(request(port, restore, base.replace("receiver-idempotency-verified:", "invalid:"), operator),
                   (403, b"FORBIDDEN"))
            expect(state(), "NEEDS_ATTENTION|3")
            expect(coordinator_sql("SELECT count(*) FROM ns_compensation_recovery;"), "0")
            with ThreadPoolExecutor(2) as pool:
                with hold_table(coordinator_sql, "ns_compensation") as wait_for:
                    jobs = [pool.submit(request, port, restore, base, operator) for _ in range(2)]
                    wait_for(2)
                expect(sorted(job.result() for job in jobs), [(200, b"REPLAYED"), (200, b"RESUMED")])
            expect(request(port, restore, base + "-mismatch", operator), (409, b"CONFLICT"))
            expect(state(), "COMPENSATING|4")
            expect(coordinator_sql("SELECT state,generation,max_attempts FROM ns_compensation;"), "READY|1|2")
            crash_and_restart(auto=True)
            count_pending = "SELECT count(*) FROM saga_queue WHERE receipt IS NULL;"
            deadline = time.monotonic() + 15
            while state() != "COMPENSATED|5" or any(stores[r][1](count_pending) != "0" for r in roles):
                if time.monotonic() >= deadline:
                    raise AssertionError("automatic restart did not complete queues")
                time.sleep(.05)
            expect(stock(), "f|1")
            command_id = f"{saga}:release:command"
            expect(len(seen[command_id]), 1)
            expect(queried[command_id][0][0], 200)
            expect(coordinator_sql("""
                SELECT request_id,actor,generation,workflow_version,evidence FROM ns_compensation_recovery;
            """), "recovery-01|checkout-operator|1|3|receiver-idempotency-verified:incident-17")
            expect(request(services["coordinator"][1], restore, base, operator), (200, b"REPLAYED"))
            expect(state(), "COMPENSATED|5")
            print("Saga recovery PASS: authorization and CAS enforced; concurrent replay audited once; automatic restart reconciled original command")
            return
        if scenario == "exhaustion":
            stock_sql = stores["stock"][1]
            stock_sql("ALTER TABLE reservation ADD CONSTRAINT unavailable CHECK(reserved);")
            dispatch("coordinator", "release:command", "UNKNOWN")
            expect(coordinator_sql(lease), "READY|1")
            expect(coordinator_sql(audit), "")
            crash_and_restart()
            dispatch("coordinator", "release:command", "NEEDS_ATTENTION")
            expect(state(), "NEEDS_ATTENTION|3")
            expect(coordinator_sql(lease), "NEEDS_ATTENTION|2")
            expect(coordinator_sql(audit), "2|NEEDS_ATTENTION|DELIVERY_UNCONFIRMED")
            expect(coordinator_sql("SELECT count(*) FROM ns_compensation_audit WHERE occurred_at IS NOT NULL;"), "1")
            expect(stored_receipt("coordinator", "release:command"), "")
            expect(stock(), "t|0")
            # Operator work stays visible and does not get retried automatically after recovery.
            stock_sql("ALTER TABLE reservation DROP CONSTRAINT unavailable;")
            crash_and_restart()
            dispatch("coordinator", answer="IDLE")
            dispatch("coordinator", "release:command", "NEEDS_ATTENTION")
            expect(coordinator_sql(audit), "2|NEEDS_ATTENTION|DELIVERY_UNCONFIRMED")
            expect(stock(), "t|0")
            print("Saga exhaustion PASS: bounded by persisted attempts; pending responsibility and single audit survive restart")
            return
        if hold_release:
            _, replacement = start(stack, binary, "coordinator", stores["coordinator"][0], token, saga_urls=urls)
            old_port = services["coordinator"][1]
            command_id = f"{saga}:release:command"
            with ThreadPoolExecutor(1) as pool:
                old = pool.submit(request, old_port, "/saga/dispatch", command_id, token)
                try:
                    expect(held_ack.wait(7), True)
                    expect(stock(), "f|1")
                    expect(coordinator_sql(lease), "LEASED|1")
                    coordinator_sql("UPDATE ns_compensation SET lease_until=clock_timestamp()+interval '5 seconds';")
                    expect(request(old_port, "/saga/renew", command_id, token), (200, b"RENEWED"))
                    expect(coordinator_sql("SELECT lease_until > clock_timestamp()+interval '20 seconds' FROM ns_compensation;"), "t")
                    expect(request(replacement, "/saga/dispatch", command_id, token), (200, b"BUSY"))
                    coordinator_sql("UPDATE ns_compensation SET lease_until=clock_timestamp()-interval '1 second';")
                    with ThreadPoolExecutor(2) as competitors:
                        with hold_table(coordinator_sql, "ns_compensation") as wait_for:
                            new = competitors.submit(request, replacement, "/saga/dispatch", command_id, token)
                            renewed = competitors.submit(request, old_port, "/saga/renew", command_id, token)
                            wait_for(2)
                        takeover = new.result()
                        expect(renewed.result(), (200, b"FENCED"))
                    if scenario == "expired":
                        expect(takeover, (200, b"NEEDS_ATTENTION"))
                    else:
                        expect(takeover, (200, b"HANDED_OFF"))
                        expect(coordinator_sql(lease), "HANDED_OFF|2")
                    expect(request(old_port, "/saga/renew", command_id, token), (200, b"FENCED"))
                finally:
                    resume_ack.set()
                expect(old.result(timeout=10), (200, b"FENCED"))
            if scenario == "expired":
                expect(coordinator_sql(lease), "NEEDS_ATTENTION|1")
                expect(coordinator_sql(audit), "1|NEEDS_ATTENTION|LEASE_EXPIRED")
                expect(state(), "NEEDS_ATTENTION|3")
                expect(stored_receipt("coordinator", "release:command"), "")
                expect(len(seen[command_id]), 1)
                expect(stock(), "f|1")
                print("Saga lease expiry PASS: last claim expires after remote commit; late receipt cannot clear audit or pending state")
                return
            first, second = seen[command_id]
            expect(first, second)
            expect(stored_receipt("coordinator", "release:command"), json.loads(second[1])["receiptId"])
            expect(coordinator_sql(lease), "HANDED_OFF|2")
            expect(coordinator_sql(audit), "")
            dispatch("stock", "release:result")
            expect(stock(), "f|1")
            expect(state(), "COMPENSATED|3")
            print("Saga takeover PASS: live claim excluded; generation 2 commits; generation 1 receives its ACK but is fenced")
            return

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
    binary = parser.parse_args().binary.resolve(strict=True)
    for scenario in ("replay", "exhaustion", "takeover", "expired", "recovery", "schedule"):
        run(binary, scenario)
