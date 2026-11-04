-- PostgreSQL 14: equivalente de V019__isolate_round_inputs.sql; historial independiente del perfil H2.
CREATE TABLE round_baseline (
    baseline_id UUID PRIMARY KEY,
    round_id UUID NOT NULL,
    cycle_id UUID NOT NULL,
    indicator_id UUID NOT NULL,
    FOREIGN KEY(round_id,cycle_id) REFERENCES cycle_round(round_id,cycle_id),
    FOREIGN KEY(baseline_id,cycle_id,indicator_id) REFERENCES baseline(baseline_id,cycle_id,indicator_id)
);
CREATE TABLE round_observation (
    evidence_id UUID PRIMARY KEY,
    round_id UUID NOT NULL,
    cycle_id UUID NOT NULL,
    FOREIGN KEY(round_id,cycle_id) REFERENCES cycle_round(round_id,cycle_id),
    FOREIGN KEY(evidence_id,cycle_id) REFERENCES evidence(evidence_id,cycle_id)
);
CREATE TABLE round_diagnosis (
    diagnosis_id UUID PRIMARY KEY,
    round_id UUID NOT NULL,
    cycle_id UUID NOT NULL,
    FOREIGN KEY(round_id,cycle_id) REFERENCES cycle_round(round_id,cycle_id),
    FOREIGN KEY(diagnosis_id,cycle_id) REFERENCES cycle_diagnosis(diagnosis_id,cycle_id)
);
CREATE TABLE round_plan_binding (
    round_id UUID PRIMARY KEY,
    cycle_id UUID NOT NULL,
    plan_id UUID NOT NULL UNIQUE,
    UNIQUE(round_id,plan_id,cycle_id),
    FOREIGN KEY(round_id,cycle_id) REFERENCES cycle_round(round_id,cycle_id),
    FOREIGN KEY(plan_id,cycle_id) REFERENCES intervention_plan(plan_id,cycle_id)
);
INSERT INTO round_plan_binding SELECT round_id,cycle_id,plan_id FROM round_context;
INSERT INTO round_baseline
    SELECT p.baseline_id,c.round_id,c.cycle_id,p.indicator_id FROM round_context c
    JOIN measurement_pair p ON p.set_id=c.set_id;
INSERT INTO round_diagnosis
    SELECT p.diagnosis_id,c.round_id,c.cycle_id FROM round_context c
    JOIN intervention_plan p ON p.plan_id=c.plan_id WHERE p.diagnosis_id IS NOT NULL;
INSERT INTO round_observation
    SELECT d.evidence_id,r.round_id,r.cycle_id FROM round_diagnosis r
    JOIN cycle_diagnosis d ON d.diagnosis_id=r.diagnosis_id;
