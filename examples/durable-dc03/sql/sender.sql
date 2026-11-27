CREATE TABLE orders (message_id text PRIMARY KEY, amount integer NOT NULL);
CREATE TABLE outbox (
    message_id text PRIMARY KEY,
    wire text NOT NULL,
    state text NOT NULL DEFAULT 'READY' CHECK (state IN ('READY','UNKNOWN','HANDED_OFF')),
    attempts integer NOT NULL DEFAULT 0,
    receipt text,
    CHECK ((state='HANDED_OFF') = (receipt IS NOT NULL))
);
