# Neton Saga compensation authority (unpublished)

`CompensationStore` uses the PostgreSQL migration under `sql/postgresql`. Register a
scope-qualified operation ID in the transaction that writes its immutable outbox
command. A top-level claim consumes one persisted attempt and returns a generation.
Send outside the database transaction with the original wire identity. Only the
current, unexpired generation may record a matching receipt or an uncertain delivery.

An expired generation is never resurrected. Exhausting the attempt limit atomically
records `NEEDS_ATTENTION` and an audit row; the application can update the workflow
checkpoint in that transaction. Keep the pending command and its identity for
reconciliation. A handoff receipt is not the asynchronous business result, and
local fencing does not stop HTTP already sent by the old worker. The receiving
service still needs durable idempotency.

This is a reusable persistence primitive, not the complete 1.2.0 Saga module.
It does not provide a workflow definition engine, retry scheduling, lease renewal,
reconciliation or an authorized operator resume API. See the three-service example
for HTTP and database fault acceptance.
