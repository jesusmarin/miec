-- PostgreSQL 14: equivalente de V018__round_context_and_plan_completion.sql; historial independiente del perfil H2.
ALTER TABLE evaluation ADD CONSTRAINT uq_round_evaluation_context
    UNIQUE(evaluation_id,measurement_set_id,run_id,plan_id,cycle_id);
ALTER TABLE human_decision ADD CONSTRAINT uq_round_human_context UNIQUE(decision_id,evaluation_id,cycle_id);
CREATE TABLE cycle_round (
    round_id UUID PRIMARY KEY,
    cycle_id UUID NOT NULL REFERENCES cycle(cycle_id),
    round_number INTEGER NOT NULL CHECK(round_number>0),
    previous_round_id UUID,
    round_state VARCHAR(20) NOT NULL CHECK(round_state IN ('ACTIVE','CONCLUDED')),
    active_cycle_id UUID UNIQUE,
    version BIGINT NOT NULL CHECK(version>=0),
    registered_by VARCHAR(255) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    registered_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    concluded_at TIMESTAMP(6) WITH TIME ZONE,
    event_id UUID NOT NULL UNIQUE,
    UNIQUE(round_id,cycle_id), UNIQUE(cycle_id,round_number),
    FOREIGN KEY(previous_round_id,cycle_id) REFERENCES cycle_round(round_id,cycle_id),
    FOREIGN KEY(event_id,cycle_id) REFERENCES cycle_event(event_id,cycle_id),
    CHECK((round_number=1 AND previous_round_id IS NULL) OR (round_number>1 AND previous_round_id IS NOT NULL)),
    CHECK((round_state='ACTIVE' AND active_cycle_id IS NOT NULL AND active_cycle_id=cycle_id AND concluded_at IS NULL)
       OR (round_state='CONCLUDED' AND active_cycle_id IS NULL AND concluded_at IS NOT NULL))
);
CREATE TABLE round_context (
    round_id UUID PRIMARY KEY,
    cycle_id UUID NOT NULL,
    plan_id UUID NOT NULL UNIQUE,
    run_id UUID NOT NULL UNIQUE,
    set_id UUID NOT NULL UNIQUE,
    evaluation_id UUID NOT NULL UNIQUE,
    decision_id UUID NOT NULL UNIQUE,
    learning_id UUID NOT NULL UNIQUE,
    plan_fingerprint VARCHAR(64) NOT NULL,
    context_snapshot JSON NOT NULL,
    UNIQUE(round_id,plan_id,cycle_id),
    FOREIGN KEY(round_id,cycle_id) REFERENCES cycle_round(round_id,cycle_id),
    FOREIGN KEY(evaluation_id,set_id,run_id,plan_id,cycle_id)
        REFERENCES evaluation(evaluation_id,measurement_set_id,run_id,plan_id,cycle_id),
    FOREIGN KEY(decision_id,evaluation_id,cycle_id) REFERENCES human_decision(decision_id,evaluation_id,cycle_id),
    FOREIGN KEY(learning_id,decision_id,cycle_id) REFERENCES learning(learning_id,decision_id,cycle_id)
);
CREATE TABLE plan_completion (
    plan_id UUID PRIMARY KEY,
    round_id UUID NOT NULL UNIQUE,
    cycle_id UUID NOT NULL,
    expected_cycle_version BIGINT NOT NULL CHECK(expected_cycle_version>=0),
    expected_round_version BIGINT NOT NULL CHECK(expected_round_version>=0),
    recorded_by VARCHAR(255) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    policy_version VARCHAR(100) NOT NULL,
    event_id UUID NOT NULL UNIQUE,
    completed_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    FOREIGN KEY(round_id,plan_id,cycle_id) REFERENCES round_context(round_id,plan_id,cycle_id),
    FOREIGN KEY(event_id,cycle_id) REFERENCES cycle_event(event_id,cycle_id)
);
