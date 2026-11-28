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
Migration V003 adds a shared PostgreSQL admission limit (default 2) and a persisted
next-attempt time. Claims and renewals serialize through the admission row; release,
completion and exhaustion do not acquire it. This limits **valid leases**, not
old HTTP requests that a paused process or network may still deliver. Use bounded
transport deadlines and retain receiver-side deduplication. Equal jitter is
computed once per failed attempt and scheduled against PostgreSQL time.

The three-service example uses a writer-backed outcome endpoint. After an
unconfirmed delivery, the scheduled path queries the original operation rather
than POSTing compensation again. A missing or invalid outcome stays unknown and
consumes the persisted reconciliation budget. A matched receipt and the authoritative
business result are committed with the lease-fenced finish. The same result Inbox
deduplicates any later callback.

This does not provide a workflow definition engine, a multi-tenant admission
policy, or a generic external evidence backend. The HTTP acceptance covers the
PostgreSQL-backed checkout profile, not arbitrary participant systems.
