-- PostgreSQL 14: equivalente de V017__create_cycle_completion_workflow.sql; historial independiente del perfil H2.
CREATE TABLE evaluation_admission (
    evaluation_id UUID PRIMARY KEY,
    cycle_id UUID NOT NULL CONSTRAINT uq_admission_cycle_only UNIQUE,
    event_id UUID NOT NULL UNIQUE,
    expected_version BIGINT NOT NULL CHECK(expected_version>=0),
    recorded_by VARCHAR(255) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    recorded_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_admission_cycle UNIQUE(evaluation_id,cycle_id),
    CONSTRAINT fk_admission_evaluation FOREIGN KEY(evaluation_id,cycle_id) REFERENCES evaluation(evaluation_id,cycle_id),
    CONSTRAINT fk_admission_event FOREIGN KEY(event_id,cycle_id) REFERENCES cycle_event(event_id,cycle_id)
);
ALTER TABLE decision_record ADD COLUMN outcome_snapshot JSON;
ALTER TABLE decision_record ADD CONSTRAINT uq_decision_full_context UNIQUE(decision_id,evaluation_id,cycle_id);
CREATE TABLE human_decision (
    decision_id UUID PRIMARY KEY,
    evaluation_id UUID NOT NULL UNIQUE,
    cycle_id UUID NOT NULL CONSTRAINT uq_human_cycle_only UNIQUE,
    selected_action VARCHAR(100) NOT NULL CHECK(selected_action IN ('CLOSE_CYCLE','COLLECT_EVIDENCE','ADJUST_PLAN','REQUEST_HUMAN_REVIEW')),
    decided_by VARCHAR(255) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    acknowledged_limitations BOOLEAN NOT NULL CHECK(acknowledged_limitations=TRUE),
    policy_version VARCHAR(100) NOT NULL,
    event_id UUID NOT NULL UNIQUE,
    decided_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT uq_human_decision_cycle UNIQUE(decision_id,cycle_id),
    CONSTRAINT fk_human_record FOREIGN KEY(decision_id,evaluation_id,cycle_id) REFERENCES decision_record(decision_id,evaluation_id,cycle_id),
    CONSTRAINT fk_human_admission FOREIGN KEY(evaluation_id,cycle_id) REFERENCES evaluation_admission(evaluation_id,cycle_id),
    CONSTRAINT fk_human_event FOREIGN KEY(event_id,cycle_id) REFERENCES cycle_event(event_id,cycle_id)
);
ALTER TABLE learning ADD COLUMN decision_id UUID;
ALTER TABLE learning ADD COLUMN recorded_by VARCHAR(255);
ALTER TABLE learning ADD COLUMN limitations VARCHAR(2000);
ALTER TABLE learning ADD COLUMN event_id UUID;
ALTER TABLE learning ADD CONSTRAINT uq_learning_decision UNIQUE(decision_id);
ALTER TABLE learning ADD CONSTRAINT uq_learning_full_context UNIQUE(learning_id,decision_id,cycle_id);
ALTER TABLE learning ADD CONSTRAINT fk_learning_decision FOREIGN KEY(decision_id,cycle_id) REFERENCES human_decision(decision_id,cycle_id);
ALTER TABLE learning ADD CONSTRAINT fk_learning_event FOREIGN KEY(event_id,cycle_id) REFERENCES cycle_event(event_id,cycle_id);
ALTER TABLE learning ADD CONSTRAINT ck_learning_extension CHECK (
    (decision_id IS NULL AND recorded_by IS NULL AND limitations IS NULL AND event_id IS NULL)
    OR (decision_id IS NOT NULL AND recorded_by IS NOT NULL AND limitations IS NOT NULL AND event_id IS NOT NULL)
);
CREATE TABLE cycle_closure (
    cycle_id UUID PRIMARY KEY REFERENCES cycle(cycle_id),
    decision_id UUID NOT NULL,
    learning_id UUID NOT NULL UNIQUE,
    closed_by VARCHAR(255) NOT NULL,
    reason VARCHAR(255) NOT NULL,
    summary VARCHAR(4000) NOT NULL,
    acknowledged_limitations BOOLEAN NOT NULL CHECK(acknowledged_limitations=TRUE),
    event_id UUID NOT NULL UNIQUE,
    closed_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    CONSTRAINT fk_closure_learning FOREIGN KEY(learning_id,decision_id,cycle_id) REFERENCES learning(learning_id,decision_id,cycle_id),
    CONSTRAINT fk_closure_event FOREIGN KEY(event_id,cycle_id) REFERENCES cycle_event(event_id,cycle_id)
);
