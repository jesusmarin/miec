-- Append-only application audit; does not reinterpret attempts, effects, leases or retries.
ALTER TABLE execution_attempt ADD CONSTRAINT uq_incident_attempt_context
    UNIQUE (execution_id,plan_id,cycle_id,planned_action_id);
ALTER TABLE plan_execution ADD CONSTRAINT uq_incident_run_context UNIQUE (run_id,plan_id,cycle_id);
CREATE TABLE execution_incident (
    incident_id UUID PRIMARY KEY,
    related_incident_id UUID REFERENCES execution_incident(incident_id),
    run_id UUID,
    execution_id UUID,
    plan_id UUID NOT NULL,
    cycle_id UUID NOT NULL,
    action_id UUID NOT NULL,
    origin VARCHAR(20) NOT NULL CHECK (origin IN ('CORE','COORDINATOR')),
    phase VARCHAR(40) NOT NULL CHECK (phase IN ('REJECTED_BEFORE_SEND','OUTCOME_UNCERTAIN','APPLIED_CONFIRMATION_FAILED','COORDINATION_INTERRUPTED')),
    code VARCHAR(80) NOT NULL,
    recorded_by VARCHAR(255) NOT NULL,
    secondary_failure_count INTEGER NOT NULL CHECK (secondary_failure_count>=0),
    occurred_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_incident_plan_cycle FOREIGN KEY (plan_id,cycle_id) REFERENCES intervention_plan(plan_id,cycle_id),
    CONSTRAINT fk_incident_run_context FOREIGN KEY (run_id,plan_id,cycle_id) REFERENCES plan_execution(run_id,plan_id,cycle_id),
    CONSTRAINT fk_incident_attempt_context FOREIGN KEY (execution_id,plan_id,cycle_id,action_id)
        REFERENCES execution_attempt(execution_id,plan_id,cycle_id,planned_action_id),
    CONSTRAINT ck_incident_relation CHECK (related_incident_id IS NULL OR related_incident_id<>incident_id)
);
CREATE INDEX ix_incident_run ON execution_incident(run_id,occurred_at,incident_id);
CREATE INDEX ix_incident_attempt ON execution_incident(execution_id,occurred_at,incident_id);
