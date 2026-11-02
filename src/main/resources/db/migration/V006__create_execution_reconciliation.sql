ALTER TABLE execution_evidence ADD CONSTRAINT uq_evidence_attempt UNIQUE(evidence_id,execution_id);
CREATE TABLE execution_reconciliation (
    decision_id UUID PRIMARY KEY,
    execution_id UUID NOT NULL UNIQUE REFERENCES execution_attempt(execution_id),
    evidence_id UUID NOT NULL,
    resolution VARCHAR(20) NOT NULL CHECK(resolution IN ('APPLIED','NO_EFFECT')),
    reviewed_by VARCHAR(255) NOT NULL CHECK(LENGTH(TRIM(reviewed_by))>0),
    authorization_id UUID NOT NULL REFERENCES authorizations(authorization_id),
    rationale VARCHAR(1000) NOT NULL CHECK(LENGTH(TRIM(rationale))>0),
    original_status VARCHAR(50) NOT NULL CHECK(original_status IN ('UNKNOWN','IN_PROGRESS')),
    decided_at TIMESTAMP NOT NULL,
    CONSTRAINT fk_reconciliation_evidence FOREIGN KEY(evidence_id,execution_id)
        REFERENCES execution_evidence(evidence_id,execution_id)
);
