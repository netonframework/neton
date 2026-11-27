CREATE TABLE nd_dc03_inbox (
    namespace text NOT NULL, tenant text NOT NULL, source_id text NOT NULL, target_id text NOT NULL,
    message_id text NOT NULL, invocation_id text NOT NULL, digest text NOT NULL,
    expires_at bigint NOT NULL, receipt_id uuid NOT NULL UNIQUE DEFAULT gen_random_uuid(),
    PRIMARY KEY(namespace, tenant, source_id, target_id, message_id),
    UNIQUE(namespace, tenant, source_id, target_id, invocation_id)
);
