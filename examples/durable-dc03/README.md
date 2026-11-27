# Durable HTTP commit/receipt gate (DC-03)

Build and run from the framework root on macOS ARM64:

```sh
./gradlew :examples:durable-dc03:linkDebugExecutableMacosArm64
python3 tools/ci/durable_dc03.py examples/durable-dc03/build/bin/macosArm64/debugExecutable/durable-dc03.kexe
```

Requires local PostgreSQL, psql/createdb/dropdb and a role allowed to create disposable databases.
Set PGUSER, PGPORT and PGPASSWORD as needed. The runner uses loopback and two fresh databases.
Sender and receiver are separate native Neton processes. The Python relay is a fault injector, not an ACK authority.

1. The sender persists UNKNOWN before sending the stored envelope. The receiver commits its scoped Inbox receipt
   and ledger mutation together. An independent SQL connection verifies both before the relay drops the response.
2. Both services restart. A successful HTTP status with an unrelated invocation in the receipt cannot complete recovery.
3. The sender retries the original bytes, validates the matching receipt and persists HANDED_OFF. A subsequent scan is idle.
4. Concurrent delivery and a second subscriber check duplicate suppression and isolation of the receipt namespace.

The example handles one synchronous local credit operation. It does not implement a Saga coordinator, an async
result channel, garbage collection, or exactly-once execution of external I/O. Loopback fixture credentials
are not a production transport security configuration. No broker or release publication is required.
