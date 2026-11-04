-- PostgreSQL 14: equivalente de V020__round_continuation_requests.sql; historial independiente del perfil H2.
ALTER TABLE cycle_round ADD CONSTRAINT uq_round_single_successor UNIQUE(previous_round_id);
ALTER TABLE round_context ADD CONSTRAINT uq_continuation_context UNIQUE(round_id,decision_id,learning_id,cycle_id);
CREATE TABLE round_continuation (
    request_id UUID PRIMARY KEY,
    cycle_id UUID NOT NULL,
    round_id UUID NOT NULL,
    decision_id UUID NOT NULL,
    learning_id UUID NOT NULL,
    mode VARCHAR(30) NOT NULL CHECK(mode IN ('COLLECT_EVIDENCE','ADJUST_PLAN')),
    request_status VARCHAR(20) NOT NULL CHECK(request_status IN ('PENDING','APPROVED')),
    version BIGINT NOT NULL CHECK(version>=0),
    cycle_version BIGINT NOT NULL CHECK(cycle_version>=0),
    round_version BIGINT NOT NULL CHECK(round_version>=0),
    requested_by VARCHAR(255) NOT NULL,
    reason VARCHAR(500) NOT NULL,
    policy_version VARCHAR(100) NOT NULL,
    input_snapshot JSON NOT NULL,
    requested_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    event_id UUID NOT NULL UNIQUE,
    approved_by VARCHAR(255),
    approval_reason VARCHAR(500),
    approved_at TIMESTAMP(6) WITH TIME ZONE,
    approval_event_id UUID UNIQUE,
    new_round_id UUID UNIQUE,
    FOREIGN KEY(round_id,decision_id,learning_id,cycle_id) REFERENCES round_context(round_id,decision_id,learning_id,cycle_id),
    FOREIGN KEY(event_id,cycle_id) REFERENCES cycle_event(event_id,cycle_id),
    FOREIGN KEY(approval_event_id,cycle_id) REFERENCES cycle_event(event_id,cycle_id),
    FOREIGN KEY(new_round_id,cycle_id) REFERENCES cycle_round(round_id,cycle_id),
    CHECK((request_status='PENDING' AND approved_by IS NULL AND approval_reason IS NULL AND approved_at IS NULL AND approval_event_id IS NULL AND new_round_id IS NULL)
       OR (request_status='APPROVED' AND approved_by IS NOT NULL AND approval_reason IS NOT NULL AND approved_at IS NOT NULL AND approval_event_id IS NOT NULL AND new_round_id IS NOT NULL))
);
