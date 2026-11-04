-- PostgreSQL 14: equivalente de V012__add_measurable_baselines.sql; historial independiente del perfil H2.
ALTER TABLE indicator ADD COLUMN comparison_operator VARCHAR(3);
ALTER TABLE indicator ADD CONSTRAINT ck_indicator_comparison CHECK (comparison_operator IS NULL OR comparison_operator IN ('GT','GTE','LT','LTE','EQ'));
ALTER TABLE baseline ADD COLUMN source_reference VARCHAR(500);
ALTER TABLE baseline ADD COLUMN recorded_by VARCHAR(255);
ALTER TABLE baseline ADD COLUMN unit VARCHAR(50);
ALTER TABLE baseline ADD COLUMN evidence_id UUID REFERENCES evidence(evidence_id);
CREATE INDEX ix_baseline_cycle_indicator ON baseline(cycle_id,indicator_id);
