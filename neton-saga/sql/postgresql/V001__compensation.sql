CREATE TABLE ns_compensation (
    id text PRIMARY KEY,
    max_attempts integer NOT NULL CHECK (max_attempts > 0),
    generation integer NOT NULL DEFAULT 0 CHECK (generation BETWEEN 0 AND max_attempts),
    state text NOT NULL DEFAULT 'READY' CHECK (state IN ('READY','LEASED','HANDED_OFF','NEEDS_ATTENTION')),
    lease_until timestamptz,
    CHECK ((state = 'LEASED') = (lease_until IS NOT NULL))
);

CREATE TABLE ns_compensation_audit (
    id text NOT NULL REFERENCES ns_compensation(id),
    generation integer NOT NULL,
    event text NOT NULL CHECK (event = 'NEEDS_ATTENTION'),
    reason text NOT NULL CHECK (reason IN ('DELIVERY_UNCONFIRMED','LEASE_EXPIRED')),
    occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (id, generation, event)
);
