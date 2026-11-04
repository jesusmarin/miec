-- PostgreSQL 14: equivalente de V004__add_execution_ownership_and_heartbeat.sql; historial independiente del perfil H2.
CREATE FUNCTION miec_wall_clock() RETURNS TIMESTAMP WITH TIME ZONE
LANGUAGE SQL VOLATILE AS $$ SELECT clock_timestamp(); $$;
CREATE SEQUENCE execution_fencing_sequence START WITH 1 INCREMENT BY 1;

ALTER TABLE execution_attempt ADD COLUMN executor_id VARCHAR(255);
ALTER TABLE execution_attempt ADD COLUMN heartbeat_at TIMESTAMP(6) WITH TIME ZONE;
ALTER TABLE execution_attempt ADD COLUMN lease_expires_at TIMESTAMP(6) WITH TIME ZONE;
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
