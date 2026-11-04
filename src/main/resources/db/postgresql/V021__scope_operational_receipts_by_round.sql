-- PostgreSQL 14. Sustituye las unicidades con nombres estables de V017.
-- Conserva recibos legados y asigna únicamente rondas ya acreditadas.
ALTER TABLE evaluation ADD CONSTRAINT uq_evaluation_round_plan UNIQUE(evaluation_id,plan_id,cycle_id);
CREATE TABLE round_evaluation_binding (
    round_id UUID PRIMARY KEY,
    cycle_id UUID NOT NULL,
    plan_id UUID NOT NULL,
    evaluation_id UUID NOT NULL UNIQUE,
    UNIQUE(round_id,evaluation_id,cycle_id),
    FOREIGN KEY(round_id,plan_id,cycle_id) REFERENCES round_plan_binding(round_id,plan_id,cycle_id),
    FOREIGN KEY(evaluation_id,plan_id,cycle_id) REFERENCES evaluation(evaluation_id,plan_id,cycle_id)
);
INSERT INTO round_evaluation_binding SELECT round_id,cycle_id,plan_id,evaluation_id FROM round_context;

ALTER TABLE evaluation_admission ADD COLUMN round_id UUID;
ALTER TABLE evaluation_admission ADD COLUMN legacy_cycle_id UUID;
UPDATE evaluation_admission a SET round_id=(SELECT r.round_id FROM round_context r WHERE r.evaluation_id=a.evaluation_id);
UPDATE evaluation_admission SET legacy_cycle_id=cycle_id WHERE round_id IS NULL;
ALTER TABLE evaluation_admission ADD CONSTRAINT uq_evaluation_admission_round UNIQUE(round_id);
ALTER TABLE evaluation_admission ADD CONSTRAINT uq_evaluation_admission_legacy UNIQUE(legacy_cycle_id);
ALTER TABLE evaluation_admission ADD CONSTRAINT ck_evaluation_admission_scope CHECK(
    (round_id IS NULL AND legacy_cycle_id IS NOT NULL AND legacy_cycle_id=cycle_id)
    OR (round_id IS NOT NULL AND legacy_cycle_id IS NULL));
ALTER TABLE evaluation_admission ADD CONSTRAINT fk_evaluation_admission_round
    FOREIGN KEY(round_id,evaluation_id,cycle_id) REFERENCES round_evaluation_binding(round_id,evaluation_id,cycle_id);
ALTER TABLE evaluation_admission DROP CONSTRAINT uq_admission_cycle_only;

ALTER TABLE human_decision ADD COLUMN round_id UUID;
ALTER TABLE human_decision ADD COLUMN legacy_cycle_id UUID;
UPDATE human_decision h SET round_id=(SELECT r.round_id FROM round_context r WHERE r.decision_id=h.decision_id);
UPDATE human_decision SET legacy_cycle_id=cycle_id WHERE round_id IS NULL;
ALTER TABLE human_decision ADD CONSTRAINT uq_human_decision_round UNIQUE(round_id);
ALTER TABLE human_decision ADD CONSTRAINT uq_human_decision_legacy UNIQUE(legacy_cycle_id);
ALTER TABLE human_decision ADD CONSTRAINT ck_human_decision_scope CHECK(
    (round_id IS NULL AND legacy_cycle_id IS NOT NULL AND legacy_cycle_id=cycle_id)
    OR (round_id IS NOT NULL AND legacy_cycle_id IS NULL));
ALTER TABLE human_decision ADD CONSTRAINT fk_human_decision_round
    FOREIGN KEY(round_id,evaluation_id,cycle_id) REFERENCES round_evaluation_binding(round_id,evaluation_id,cycle_id);
ALTER TABLE human_decision DROP CONSTRAINT uq_human_cycle_only;
ALTER TABLE evaluation_admission ADD CONSTRAINT uq_admission_round_context UNIQUE(evaluation_id,round_id,cycle_id);
ALTER TABLE human_decision ADD CONSTRAINT fk_human_same_round_admission
    FOREIGN KEY(evaluation_id,round_id,cycle_id) REFERENCES evaluation_admission(evaluation_id,round_id,cycle_id);
