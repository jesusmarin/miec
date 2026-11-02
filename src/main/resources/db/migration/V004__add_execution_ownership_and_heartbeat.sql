-- IR-009-C08.2: additive migration. Historical rows remain unowned.
-- H2 wall clock: CURRENT_TIMESTAMP is fixed at transaction start in H2.
CREATE ALIAS miec_wall_clock FOR 'java.time.Instant.now()';
CREATE SEQUENCE execution_fencing_sequence START WITH 1 INCREMENT BY 1;

ALTER TABLE execution_attempt ADD COLUMN executor_id VARCHAR(255);
ALTER TABLE execution_attempt ADD COLUMN heartbeat_at TIMESTAMP;
ALTER TABLE execution_attempt ADD COLUMN lease_expires_at TIMESTAMP;
ALTER TABLE execution_attempt ADD COLUMN fencing_token BIGINT;

ALTER TABLE execution_attempt ADD CONSTRAINT ck_attempt_ownership CHECK (
    (executor_id IS NULL AND heartbeat_at IS NULL
        AND lease_expires_at IS NULL AND fencing_token IS NULL)
    OR
    (executor_id IS NOT NULL AND LENGTH(TRIM(executor_id)) > 0
        AND heartbeat_at IS NOT NULL AND lease_expires_at IS NOT NULL
        AND fencing_token IS NOT NULL AND fencing_token > 0
        AND execution_status <> 'PENDING'
        AND started_at IS NOT NULL AND heartbeat_at >= started_at
        AND lease_expires_at > heartbeat_at)
);

CREATE INDEX idx_attempt_lease ON execution_attempt(execution_status, lease_expires_at);
