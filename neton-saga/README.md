# Neton Saga compensation authority (unpublished)

`CompensationStore` uses the PostgreSQL migration under `sql/postgresql`. Register a
scope-qualified operation ID in the transaction that writes its immutable outbox
command. A top-level claim consumes one persisted attempt and returns a generation.
Send outside the database transaction with the original wire identity. Only the
current, unexpired generation may record a matching receipt or an uncertain delivery.

An expired generation is never resurrected. Exhausting the attempt limit atomically
records `NEEDS_ATTENTION` and an audit row; the application can update the workflow
checkpoint in that transaction. Keep the pending command and its identity for
reconciliation. `recover` checks application-provided authorization/evidence,
the expected lease generation and the workflow version (via a checkpoint CAS).
It grants one additional attempt for the existing identity; the audit also serves
as the idempotency record for an identical management request. `renew` and takeover
serialize on the same row, using database time after acquiring its lock.

A handoff receipt is not the asynchronous business result, and
local fencing does not stop HTTP already sent by the old worker. The receiving
service still needs durable idempotency.

This is a reusable persistence primitive, not the complete 1.2.0 Saga module.
It does not provide a workflow definition engine, a production dispatcher,
or a reconciliation/evidence backend. See the three-service example for a bounded
single-dispatch loop and HTTP/database fault acceptance.
