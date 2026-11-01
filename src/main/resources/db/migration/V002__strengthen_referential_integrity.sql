-- MIEC - V002: strengthen referential integrity
-- Flyway migration for H2. Apply after V001 on a database where V002
-- has NOT been executed manually.
-- Do not modify V001 or insert rows into flyway_schema_history manually.

-- 1. Unique keys required by composite foreign keys.
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

-- 2. IR-001: a cycle and its objective must belong to the same system.
-- Backfill from the owning cycle before making the column mandatory.
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

-- 3. IR-002: evaluations must refer to objectives linked to their cycle.
ALTER TABLE evaluation
    ADD CONSTRAINT fk_evaluation_cycle_objective
        FOREIGN KEY (cycle_id, objective_id)
            REFERENCES cycle_objective (cycle_id, objective_id);

-- 4. IR-003: an evaluation's optional baseline must belong to its cycle.
ALTER TABLE evaluation
    ADD CONSTRAINT fk_evaluation_baseline_cycle
        FOREIGN KEY (baseline_id, cycle_id)
            REFERENCES baseline (baseline_id, cycle_id);

-- 5. IR-005: executed actions must belong to the same cycle as their plan.
ALTER TABLE executed_action
    ADD CONSTRAINT fk_action_plan_cycle
        FOREIGN KEY (plan_id, cycle_id)
            REFERENCES intervention_plan (plan_id, cycle_id);

-- 6. IR-006: an optional evaluation attached to a decision shares its cycle.
ALTER TABLE decision_record
    ADD CONSTRAINT fk_decision_evaluation_cycle
        FOREIGN KEY (evaluation_id, cycle_id)
            REFERENCES evaluation (evaluation_id, cycle_id);

-- 7. IR-007: an optional triggering event belongs to the transition's cycle.
ALTER TABLE state_transition
    ADD CONSTRAINT fk_transition_event_cycle
        FOREIGN KEY (trigger_event_id, cycle_id)
            REFERENCES cycle_event (event_id, cycle_id);

-- 8. IR-008: plan-scoped authorizations reference an existing plan.
-- IR-009 is intentionally deferred: authorization must precede execution.
ALTER TABLE authorizations
    ADD CONSTRAINT fk_authorization_plan
        FOREIGN KEY (plan_id)
            REFERENCES intervention_plan (plan_id);

-- 9. IR-011: a parent cycle belongs to the same system as its child.
ALTER TABLE cycle
    ADD CONSTRAINT fk_cycle_parent_system
        FOREIGN KEY (parent_cycle_id, system_id)
            REFERENCES cycle (cycle_id, system_id);

-- 10. IR-012: a cycle cannot be its own parent.
ALTER TABLE cycle
    ADD CONSTRAINT ck_cycle_not_self_parent
        CHECK (parent_cycle_id IS NULL OR parent_cycle_id <> cycle_id);

-- Intentionally not constrained by V002:
-- IR-004: evidence may be reused across cycles, preserving provenance.
-- IR-009: planned actions require a future separate domain model.
-- IR-010: authorization scope, validity and status are domain validations.
-- IR-013: indirect parent cycles are prevented by transactional domain logic.
