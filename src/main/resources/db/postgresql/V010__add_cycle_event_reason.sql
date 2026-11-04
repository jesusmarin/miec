-- PostgreSQL 14: equivalente de V010__add_cycle_event_reason.sql; historial independiente del perfil H2.
ALTER TABLE cycle_event ADD COLUMN reason VARCHAR(500);

CREATE INDEX ix_transition_cycle_history ON state_transition(cycle_id, occurred_at, transition_id);
