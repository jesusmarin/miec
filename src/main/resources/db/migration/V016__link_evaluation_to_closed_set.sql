-- Extend existing evaluations; historical rows keep all extension fields NULL.
ALTER TABLE measurement_set ADD CONSTRAINT uq_set_full_context UNIQUE(set_id,run_id,plan_id,cycle_id);
ALTER TABLE evaluation ADD COLUMN measurement_set_id UUID;
ALTER TABLE evaluation ADD COLUMN run_id UUID;
ALTER TABLE evaluation ADD COLUMN plan_id UUID;
ALTER TABLE evaluation ADD COLUMN evaluation_policy_version VARCHAR(100);
ALTER TABLE evaluation ADD COLUMN evidence_policy_version VARCHAR(100);
ALTER TABLE evaluation ADD COLUMN input_fingerprint VARCHAR(64);
ALTER TABLE evaluation ADD COLUMN evaluation_snapshot JSON;
ALTER TABLE evaluation ADD COLUMN recorded_by VARCHAR(255);
ALTER TABLE evaluation ADD CONSTRAINT fk_evaluation_set_context FOREIGN KEY(measurement_set_id,run_id,plan_id,cycle_id)
    REFERENCES measurement_set(set_id,run_id,plan_id,cycle_id);
ALTER TABLE evaluation ADD CONSTRAINT uq_evaluation_set_policy UNIQUE(measurement_set_id,evaluation_policy_version,evidence_policy_version);
ALTER TABLE evaluation ADD CONSTRAINT ck_evaluation_extension CHECK (
    (measurement_set_id IS NULL AND run_id IS NULL AND plan_id IS NULL AND evaluation_policy_version IS NULL
        AND evidence_policy_version IS NULL AND input_fingerprint IS NULL AND evaluation_snapshot IS NULL AND recorded_by IS NULL)
    OR (measurement_set_id IS NOT NULL AND run_id IS NOT NULL AND plan_id IS NOT NULL AND evaluation_policy_version IS NOT NULL
        AND evidence_policy_version IS NOT NULL AND input_fingerprint IS NOT NULL AND LENGTH(input_fingerprint)=64
        AND evaluation_snapshot IS NOT NULL AND recorded_by IS NOT NULL AND LENGTH(TRIM(recorded_by))>0)
);
