-- PostgreSQL 14: equivalente de V011__create_intervention_workflow.sql; historial independiente del perfil H2.
CREATE TABLE cycle_diagnosis (
    diagnosis_id UUID PRIMARY KEY,
    cycle_id UUID NOT NULL REFERENCES cycle(cycle_id),
    evidence_id UUID NOT NULL REFERENCES evidence(evidence_id),
    summary VARCHAR(4000) NOT NULL,
    created_by VARCHAR(255) NOT NULL,
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_diagnosis_cycle UNIQUE(diagnosis_id,cycle_id)
);
CREATE INDEX ix_diagnosis_cycle ON cycle_diagnosis(cycle_id,created_at,diagnosis_id);

ALTER TABLE intervention_plan ADD COLUMN created_by VARCHAR(255);
ALTER TABLE intervention_plan ADD COLUMN revision BIGINT NOT NULL DEFAULT 0;
ALTER TABLE intervention_plan ADD COLUMN diagnosis_id UUID;
ALTER TABLE intervention_plan ADD CONSTRAINT fk_plan_diagnosis_cycle
    FOREIGN KEY(diagnosis_id,cycle_id) REFERENCES cycle_diagnosis(diagnosis_id,cycle_id);

ALTER TABLE authorizations ADD COLUMN request_fingerprint VARCHAR(64);
ALTER TABLE authorizations ADD COLUMN version BIGINT NOT NULL DEFAULT 0;

CREATE TABLE authorization_decision (
    decision_id UUID PRIMARY KEY,
    authorization_id UUID NOT NULL UNIQUE REFERENCES authorizations(authorization_id),
    decision VARCHAR(20) NOT NULL CHECK(decision IN ('APPROVED','REJECTED')),
    decided_by VARCHAR(255) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    decided_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    plan_fingerprint VARCHAR(64) NOT NULL,
    event_id UUID NOT NULL REFERENCES cycle_event(event_id)
);
CREATE INDEX ix_authorization_plan_action ON authorizations(plan_id,action_id,status);
