CREATE TABLE ns_compensation_drain (
    id text PRIMARY KEY REFERENCES ns_compensation(id),
    plan text NOT NULL UNIQUE, source text NOT NULL, target text NOT NULL,
    CHECK (source <> target)
);

ALTER TABLE ns_compensation_handoff
    ADD COLUMN state text NOT NULL DEFAULT 'READY' CHECK (state IN ('READY','HANDED_OFF','NEEDS_ATTENTION')),
    ADD COLUMN audit text NOT NULL DEFAULT '[]',
    ADD COLUMN recovery text NOT NULL DEFAULT '[]',
    ADD COLUMN activation_id uuid NOT NULL DEFAULT gen_random_uuid();
