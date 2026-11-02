-- Append-only through the application. No change to historical attempts.
CREATE TABLE execution_evidence (
    evidence_id UUID PRIMARY KEY,
    execution_id UUID NOT NULL REFERENCES execution_attempt(execution_id),
    source_type VARCHAR(40) NOT NULL,
    reported_by VARCHAR(255) NOT NULL,
    executor_id VARCHAR(255),
    fencing_token BIGINT,
    effect_outcome VARCHAR(50) NOT NULL,
    external_reference VARCHAR(500) NOT NULL,
    occurred_at TIMESTAMP NOT NULL,
    received_at TIMESTAMP NOT NULL,
    verification_authorization_id UUID REFERENCES authorizations(authorization_id),
    CONSTRAINT ck_evidence_source CHECK (
        (source_type='EXECUTOR_REPORT' AND executor_id IS NOT NULL
            AND fencing_token IS NOT NULL AND fencing_token>0 AND verification_authorization_id IS NULL)
        OR (source_type='DESTINATION_VERIFIED' AND executor_id IS NULL AND fencing_token IS NULL
            AND verification_authorization_id IS NOT NULL)
    ),
    CONSTRAINT ck_evidence_outcome CHECK (
        effect_outcome IN ('NO_EFFECT_CONFIRMED','EFFECT_APPLIED','EFFECT_UNKNOWN')),
    CONSTRAINT ck_evidence_text CHECK (LENGTH(TRIM(reported_by))>0 AND LENGTH(TRIM(external_reference))>0),
    CONSTRAINT ck_evidence_dates CHECK (occurred_at<=received_at)
);
CREATE INDEX idx_evidence_attempt ON execution_evidence(execution_id,received_at,evidence_id);
