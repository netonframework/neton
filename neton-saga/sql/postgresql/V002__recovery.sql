CREATE TABLE ns_compensation_recovery (
    id text NOT NULL REFERENCES ns_compensation(id),
    request_id text NOT NULL,
    generation integer NOT NULL,
    workflow_version integer NOT NULL,
    actor text NOT NULL,
    evidence text NOT NULL,
    occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (id, request_id)
);
