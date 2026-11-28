ALTER TABLE ns_compensation ADD COLUMN next_attempt_at timestamptz NOT NULL DEFAULT clock_timestamp();
CREATE INDEX ns_compensation_schedule ON ns_compensation(state,next_attempt_at,id);
CREATE INDEX ns_compensation_leased ON ns_compensation(lease_until) WHERE state='LEASED';

CREATE TABLE ns_compensation_admission (
    singleton boolean PRIMARY KEY CHECK (singleton),
    capacity integer NOT NULL CHECK (capacity > 0)
);
INSERT INTO ns_compensation_admission VALUES (true,2);
