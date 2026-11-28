ALTER TABLE ns_compensation ADD COLUMN retired boolean NOT NULL DEFAULT false;
CREATE TABLE ns_compensation_handoff (
    id text PRIMARY KEY REFERENCES ns_compensation(id),
    plan text NOT NULL UNIQUE,
    source text NOT NULL,
    target text NOT NULL,
    payload text NOT NULL,
    generation integer NOT NULL,
    budget integer NOT NULL,
    due_at timestamptz NOT NULL,
    CHECK (source <> target)
);
