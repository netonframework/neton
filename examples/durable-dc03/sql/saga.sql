CREATE TABLE saga_state (
    id text PRIMARY KEY, state text NOT NULL, version integer NOT NULL DEFAULT 0,
    CHECK (state IN ('RESERVING','CHARGING','COMPENSATING','COMPENSATED','SUCCEEDED'))
);
CREATE TABLE saga_queue (
    message_id text PRIMARY KEY, wire text NOT NULL,
    receipt text
);
CREATE TABLE reservation (
    saga_id text PRIMARY KEY, reserved boolean NOT NULL, releases integer NOT NULL DEFAULT 0
);
CREATE TABLE payment_decision (saga_id text PRIMARY KEY);
