-- PostgreSQL 14: equivalente de V002__strengthen_referential_integrity.sql; historial independiente del perfil H2.
ALTER TABLE cycle
    ADD CONSTRAINT uq_cycle_system UNIQUE (cycle_id, system_id);

ALTER TABLE objective
    ADD CONSTRAINT uq_objective_system UNIQUE (objective_id, system_id);

ALTER TABLE baseline
    ADD CONSTRAINT uq_baseline_cycle UNIQUE (baseline_id, cycle_id);

ALTER TABLE evaluation
    ADD CONSTRAINT uq_evaluation_cycle UNIQUE (evaluation_id, cycle_id);

ALTER TABLE intervention_plan
    ADD CONSTRAINT uq_plan_cycle UNIQUE (plan_id, cycle_id);

ALTER TABLE cycle_event
    ADD CONSTRAINT uq_event_cycle UNIQUE (event_id, cycle_id);

ALTER TABLE cycle_objective
    ADD COLUMN system_id UUID;

UPDATE cycle_objective co
SET system_id = (
    SELECT c.system_id
    FROM cycle c
    WHERE c.cycle_id = co.cycle_id
);

ALTER TABLE cycle_objective
    ALTER COLUMN system_id SET NOT NULL;

ALTER TABLE cycle_objective
    ADD CONSTRAINT fk_co_cycle_system
        FOREIGN KEY (cycle_id, system_id)
            REFERENCES cycle (cycle_id, system_id);

ALTER TABLE cycle_objective
    ADD CONSTRAINT fk_co_objective_system
        FOREIGN KEY (objective_id, system_id)
            REFERENCES objective (objective_id, system_id);

ALTER TABLE evaluation
    ADD CONSTRAINT fk_evaluation_cycle_objective
        FOREIGN KEY (cycle_id, objective_id)
            REFERENCES cycle_objective (cycle_id, objective_id);

ALTER TABLE evaluation
    ADD CONSTRAINT fk_evaluation_baseline_cycle
        FOREIGN KEY (baseline_id, cycle_id)
            REFERENCES baseline (baseline_id, cycle_id);

ALTER TABLE executed_action
    ADD CONSTRAINT fk_action_plan_cycle
        FOREIGN KEY (plan_id, cycle_id)
            REFERENCES intervention_plan (plan_id, cycle_id);

ALTER TABLE decision_record
    ADD CONSTRAINT fk_decision_evaluation_cycle
        FOREIGN KEY (evaluation_id, cycle_id)
            REFERENCES evaluation (evaluation_id, cycle_id);

ALTER TABLE state_transition
    ADD CONSTRAINT fk_transition_event_cycle
        FOREIGN KEY (trigger_event_id, cycle_id)
            REFERENCES cycle_event (event_id, cycle_id);

ALTER TABLE authorizations
    ADD CONSTRAINT fk_authorization_plan
        FOREIGN KEY (plan_id)
            REFERENCES intervention_plan (plan_id);

ALTER TABLE cycle
    ADD CONSTRAINT fk_cycle_parent_system
        FOREIGN KEY (parent_cycle_id, system_id)
            REFERENCES cycle (cycle_id, system_id);

ALTER TABLE cycle
    ADD CONSTRAINT ck_cycle_not_self_parent
        CHECK (parent_cycle_id IS NULL OR parent_cycle_id <> cycle_id);
