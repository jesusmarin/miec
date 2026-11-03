-- Plan coordination only. Attempts, external effects and idempotency remain in IR-009.
CREATE TABLE plan_execution (
    run_id UUID PRIMARY KEY,
    plan_id UUID NOT NULL UNIQUE,
    cycle_id UUID NOT NULL,
    plan_fingerprint VARCHAR(64) NOT NULL CHECK (LENGTH(plan_fingerprint)=64),
    run_state VARCHAR(30) NOT NULL CHECK (run_state IN ('RUNNING','APPLIED','RECOVERY_REQUIRED')),
    version BIGINT NOT NULL DEFAULT 0 CHECK (version>=0),
    generation BIGINT NOT NULL CHECK (generation>0),
    owner_id VARCHAR(100),
    lease_expires_at TIMESTAMP(6),
    created_by VARCHAR(255) NOT NULL,
    updated_by VARCHAR(255) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_run_plan_cycle FOREIGN KEY (plan_id,cycle_id) REFERENCES intervention_plan(plan_id,cycle_id),
    CONSTRAINT ck_run_ownership CHECK (
        (run_state='RUNNING' AND owner_id IS NOT NULL AND lease_expires_at IS NOT NULL)
        OR (run_state<>'RUNNING' AND owner_id IS NULL AND lease_expires_at IS NULL))
);
CREATE TABLE plan_execution_step (
    run_id UUID NOT NULL REFERENCES plan_execution(run_id),
    ordinal INTEGER NOT NULL CHECK (ordinal BETWEEN 0 AND 19),
    action_id UUID NOT NULL,
    authorization_id UUID NOT NULL REFERENCES authorizations(authorization_id),
    expected_operation_key UUID NOT NULL,
    step_state VARCHAR(30) NOT NULL CHECK (step_state IN ('READY','DISPATCHING','APPLIED','RECOVERY_REQUIRED')),
    execution_id UUID REFERENCES execution_attempt(execution_id),
    result_reference VARCHAR(500),
    PRIMARY KEY (run_id,ordinal),
    CONSTRAINT uq_run_action UNIQUE (run_id,action_id),
    CONSTRAINT ck_step_result CHECK (step_state<>'APPLIED' OR (execution_id IS NOT NULL AND result_reference IS NOT NULL))
);
CREATE INDEX ix_run_cycle ON plan_execution(cycle_id,created_at);
