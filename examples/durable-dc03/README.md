# Durable HTTP commit/receipt gate (DC-03)

Build and run from the framework root on macOS ARM64:

```sh
./gradlew :examples:durable-dc03:linkDebugExecutableMacosArm64
python3 tools/ci/durable_dc03.py examples/durable-dc03/build/bin/macosArm64/debugExecutable/durable-dc03.kexe
python3 tools/ci/durable_saga.py examples/durable-dc03/build/bin/macosArm64/debugExecutable/durable-dc03.kexe
```

Requires local PostgreSQL, psql/createdb/dropdb and a role allowed to create disposable databases.
Set PGUSER, PGPORT and PGPASSWORD as needed. The runner uses loopback and two fresh databases.
Sender and receiver are separate native Neton processes. The Python relay is a fault injector, not an ACK authority.

1. A sender is killed while its Outbox INSERT is blocked after the order INSERT (neither survives), then after
   the transaction commits (both survive). A restarted sender persists UNKNOWN before sending the stored envelope.
   The receiver commits its scoped Inbox receipt
   and ledger mutation together. An independent SQL connection verifies both before the relay drops the response.
2. Both services restart. A successful HTTP status with an unrelated invocation in the receipt cannot complete recovery.
3. The sender retries the original bytes, validates the matching receipt and persists HANDED_OFF. A subsequent scan is idle.
4. Concurrent delivery and a second subscriber check duplicate suppression and isolation of the receipt namespace.

The second script starts three real Neton services (checkout coordinator, stock and payment), each with its
own database. A fixed checkout workflow reserves stock, receives an asynchronous payment decline, then releases
stock in compensation. The stock effect and its result Outbox commit with the command Inbox; the coordinator's
state change and next command commit with the result Inbox. The driver separately dispatches persisted requests
and callbacks so it can select crash boundaries deterministically without depending on timer scheduling.

It verifies rollback of a failed release, loss of both the compensation command receipt and result receipt
after commit, and process restarts. Recovery scans persisted queues, resends identical command/result bytes
and gets the original receipts; stock is released once, the coordinator advances once per result, and delivering
a result does not enqueue another result. This is a fixed workflow, not the production Saga API: bounded retry,
worker fencing and the exhaustion audit use the reusable `neton-saga` store. The same script also checks that
an exhausted command stays pending with one audit after restart, and holds a real HTTP receipt while a second
coordinator takes over. After the lease is made expired in PostgreSQL, the second coordinator can write and
the old one cannot. If the final attempt expires, takeover instead records an audit without a new delivery.
The recovery case uses a separate operator credential, a versioned checkpoint,
and a concurrent repeated operation ID. After restart a lifecycle-owned worker
scans pending rows and drains the queues without manual dispatch. The example's
operator evidence is an attestation, not an independent reconciliation backend.
Workflow definition versioning, production retry scheduling, reconciliation and cleanup remain outside this example.
Loopback credentials are not a production transport security configuration. No broker or release publication
is required.
