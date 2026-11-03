-- Additive: keep baseline and historical evidence unchanged. No invented results.
ALTER TABLE plan_execution ADD CONSTRAINT uq_run_plan_cycle_v015 UNIQUE(run_id,plan_id,cycle_id);
ALTER TABLE plan_execution ADD CONSTRAINT uq_run_cycle_v015 UNIQUE(run_id,cycle_id);
ALTER TABLE evidence ADD CONSTRAINT uq_measure_evidence_cycle UNIQUE(evidence_id,cycle_id);
ALTER TABLE baseline ADD CONSTRAINT uq_baseline_indicator_cycle UNIQUE(baseline_id,cycle_id,indicator_id);

CREATE TABLE post_measurement (
    measurement_id UUID PRIMARY KEY,
    run_id UUID NOT NULL,
    plan_id UUID NOT NULL,
    cycle_id UUID NOT NULL,
    indicator_id UUID NOT NULL REFERENCES indicator(indicator_id),
    observed_value DECIMAL(20,5) NOT NULL,
    unit VARCHAR(50) NOT NULL,
    measurement_method VARCHAR(500) NOT NULL,
    source_reference VARCHAR(500) NOT NULL,
    measured_at TIMESTAMP(6) NOT NULL,
    recorded_at TIMESTAMP(6) NOT NULL,
    recorded_by VARCHAR(255) NOT NULL,
    evidence_id UUID NOT NULL UNIQUE,
    criterion_operator VARCHAR(3) NOT NULL CHECK(criterion_operator IN ('GT','GTE','LT','LTE','EQ')),
    criterion_target DECIMAL(20,5) NOT NULL,
    criterion_version VARCHAR(100) NOT NULL,
    CONSTRAINT fk_post_run_context FOREIGN KEY(run_id,plan_id,cycle_id) REFERENCES plan_execution(run_id,plan_id,cycle_id),
    CONSTRAINT fk_post_evidence_cycle FOREIGN KEY(evidence_id,cycle_id) REFERENCES evidence(evidence_id,cycle_id),
    CONSTRAINT ck_post_dates CHECK(measured_at<=recorded_at),
    CONSTRAINT uq_post_run_indicator UNIQUE(measurement_id,run_id,indicator_id)
);
CREATE INDEX ix_post_run ON post_measurement(run_id,recorded_at,measurement_id);

CREATE TABLE measurement_review (
    review_id UUID PRIMARY KEY,
    run_id UUID NOT NULL,
    cycle_id UUID NOT NULL,
    evidence_id UUID NOT NULL,
    verdict VARCHAR(30) NOT NULL CHECK(verdict IN ('ACCEPTED_WITH_LIMITATIONS','REJECTED')),
    reviewed_by VARCHAR(255) NOT NULL,
    measurement_method VARCHAR(500) NOT NULL,
    procedure_reference VARCHAR(500) NOT NULL,
    limitations VARCHAR(2000) NOT NULL,
    reviewed_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_review_run_cycle FOREIGN KEY(run_id,cycle_id) REFERENCES plan_execution(run_id,cycle_id),
    CONSTRAINT fk_review_evidence_cycle FOREIGN KEY(evidence_id,cycle_id) REFERENCES evidence(evidence_id,cycle_id),
    CONSTRAINT uq_review_run_evidence UNIQUE(run_id,evidence_id)
);

CREATE TABLE measurement_set (
    set_id UUID PRIMARY KEY,
    run_id UUID NOT NULL UNIQUE,
    plan_id UUID NOT NULL,
    cycle_id UUID NOT NULL,
    policy_version VARCHAR(30) NOT NULL CHECK(policy_version='EXPLICIT_PAIR_V1'),
    measurement_method VARCHAR(500) NOT NULL,
    baseline_from TIMESTAMP(6) NOT NULL,
    baseline_to TIMESTAMP(6) NOT NULL,
    post_from TIMESTAMP(6) NOT NULL,
    post_to TIMESTAMP(6) NOT NULL,
    sealed_by VARCHAR(255) NOT NULL,
    sealed_at TIMESTAMP(6) NOT NULL,
    CONSTRAINT fk_set_run_context FOREIGN KEY(run_id,plan_id,cycle_id) REFERENCES plan_execution(run_id,plan_id,cycle_id),
    CONSTRAINT ck_set_windows CHECK(baseline_from<=baseline_to AND baseline_to<=post_from AND post_from<=post_to AND post_to<=sealed_at),
    CONSTRAINT uq_set_run_cycle UNIQUE(set_id,run_id,cycle_id)
);
CREATE TABLE measurement_pair (
    set_id UUID NOT NULL,
    run_id UUID NOT NULL,
    cycle_id UUID NOT NULL,
    indicator_id UUID NOT NULL,
    baseline_id UUID NOT NULL,
    measurement_id UUID NOT NULL,
    baseline_review_id UUID REFERENCES measurement_review(review_id),
    post_review_id UUID REFERENCES measurement_review(review_id),
    PRIMARY KEY(set_id,indicator_id),
    CONSTRAINT fk_pair_set_context FOREIGN KEY(set_id,run_id,cycle_id) REFERENCES measurement_set(set_id,run_id,cycle_id),
    CONSTRAINT fk_pair_baseline_context FOREIGN KEY(baseline_id,cycle_id,indicator_id) REFERENCES baseline(baseline_id,cycle_id,indicator_id),
    CONSTRAINT fk_pair_post_context FOREIGN KEY(measurement_id,run_id,indicator_id) REFERENCES post_measurement(measurement_id,run_id,indicator_id)
);
