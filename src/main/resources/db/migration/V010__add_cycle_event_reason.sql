-- Auditoría del inicio del MVP. Los eventos históricos conservan motivo desconocido.
ALTER TABLE cycle_event ADD COLUMN reason VARCHAR(500);

-- Cursor estable de historial por ciclo, fecha e identidad de transición.
CREATE INDEX ix_transition_cycle_history ON state_transition(cycle_id, occurred_at, transition_id);
