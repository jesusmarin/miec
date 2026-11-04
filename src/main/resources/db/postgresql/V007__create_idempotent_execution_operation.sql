-- PostgreSQL 14: equivalente de V007__create_idempotent_execution_operation.sql; historial independiente del perfil H2.
CREATE TABLE execution_operation (
    operation_key UUID PRIMARY KEY,
    plan_id UUID NOT NULL REFERENCES intervention_plan(plan_id),
    planned_action_id UUID NOT NULL,
    destination_id VARCHAR(255) NOT NULL CHECK(LENGTH(TRIM(destination_id))>0),
    request_fingerprint VARCHAR(64) NOT NULL CHECK(LENGTH(request_fingerprint)=64),
    created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_operation_action UNIQUE(plan_id,planned_action_id),
    CONSTRAINT uq_operation_identity UNIQUE(operation_key,plan_id,planned_action_id)
);
ALTER TABLE execution_attempt ADD COLUMN operation_key UUID;
ALTER TABLE execution_attempt ADD CONSTRAINT fk_attempt_operation
    FOREIGN KEY(operation_key,plan_id,planned_action_id)
    REFERENCES execution_operation(operation_key,plan_id,planned_action_id);
