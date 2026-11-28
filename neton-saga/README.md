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

External participants can supply an `ExternalOutcomeQuery` and an operation/evidence
binder. A missing or mismatched result cannot authorize execution. Authority
authentication and evidence validation are part of the adapter contract.

V004/V005 support an explicitly operated, one-way handoff to a uniquely named target.
The persisted drain intent disallows new claims and renewal. A current worker can
finish within its original lease. An expired worker is fenced and its attempt remains
consumed. Retrying retirement uses the same plan; there is no reopen operation.
Retirement stores the frozen snapshot and the audit/recovery history together.

The target verifies proof fetched from its configured source, then commits the
snapshot, application checkpoint and activation receipt in a single local
transaction. A retry of the same plan can read that original receipt even if
the source process is unavailable. Deployments must use unique, non-cloned
resource identities and an authenticated control channel.

This is not a workflow definition engine, full workflow migration, a multi-tenant
admission policy, cross-database consensus or a generic evidence backend. The HTTP
acceptance covers the checkout checkpoint, scoped Inbox/Outbox, compensation,
and audit records. It does not move live leases or cover arbitrary application
schemas, batched migration, external objects or all r26 migration faults.
