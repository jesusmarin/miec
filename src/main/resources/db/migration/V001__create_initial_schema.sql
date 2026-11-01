-- ============================================================================
-- MIEC-F03: Esquema inicial del Modelo Integral de Evolución Continua
-- Versión: 0.1
-- Descripción: Tablas principales para ciclos, objetivos, evidencias, decisiones
-- ============================================================================

-- ----------------------------------------------------------------------------
-- ENT-01: Sistema objetivo
-- ----------------------------------------------------------------------------
CREATE TABLE system_objective (
                                  system_id UUID PRIMARY KEY,
                                  name VARCHAR(255) NOT NULL,
                                  domain VARCHAR(100),
                                  description TEXT,
                                  owner_reference VARCHAR(255),
                                  constraints JSON,
                                  status VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
                                  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                  updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ----------------------------------------------------------------------------
-- ENT-02: Ciclo de evolución
-- ----------------------------------------------------------------------------
CREATE TABLE cycle (
                       cycle_id UUID PRIMARY KEY,
                       system_id UUID NOT NULL,
                       cycle_number INTEGER,
                       parent_cycle_id UUID,
                       state VARCHAR(50) NOT NULL DEFAULT 'E00_INACTIVO',
                       autonomy_level VARCHAR(50) NOT NULL DEFAULT 'A0',
                       configuration_version_id VARCHAR(100),
                       started_at TIMESTAMP,
                       closed_at TIMESTAMP,
                       closure_reason VARCHAR(255),
                       created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                       updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                       version BIGINT NOT NULL DEFAULT 0,

                       CONSTRAINT fk_cycle_system FOREIGN KEY (system_id) REFERENCES system_objective(system_id),
                       CONSTRAINT fk_cycle_parent FOREIGN KEY (parent_cycle_id) REFERENCES cycle(cycle_id)
);

-- ----------------------------------------------------------------------------
-- ENT-03: Objetivo
-- ----------------------------------------------------------------------------
CREATE TABLE objective (
                           objective_id UUID PRIMARY KEY,
                           system_id UUID NOT NULL,
                           name VARCHAR(255) NOT NULL,
                           description TEXT,
                           success_criteria TEXT,
                           priority INTEGER DEFAULT 0,
                           status VARCHAR(50) NOT NULL DEFAULT 'ACTIVE',
                           valid_from TIMESTAMP,
                           valid_until TIMESTAMP,
                           version_id VARCHAR(100),
                           created_by VARCHAR(255),
                           created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

                           CONSTRAINT fk_objective_system FOREIGN KEY (system_id) REFERENCES system_objective(system_id)
);

-- ----------------------------------------------------------------------------
-- Relación entre ciclo y objetivo
-- ----------------------------------------------------------------------------
CREATE TABLE cycle_objective (
                                 cycle_id UUID NOT NULL,
                                 objective_id UUID NOT NULL,
                                 PRIMARY KEY (cycle_id, objective_id),

                                 CONSTRAINT fk_cycle_objective_cycle FOREIGN KEY (cycle_id) REFERENCES cycle(cycle_id) ON DELETE CASCADE,
                                 CONSTRAINT fk_cycle_objective_objective FOREIGN KEY (objective_id) REFERENCES objective(objective_id)
);

-- ----------------------------------------------------------------------------
-- ENT-04: Indicador
-- ----------------------------------------------------------------------------
CREATE TABLE indicator (
                           indicator_id UUID PRIMARY KEY,
                           objective_id UUID NOT NULL,
                           name VARCHAR(255) NOT NULL,
                           description TEXT,
                           unit VARCHAR(50),
                           measurement_method TEXT,
                           preferred_direction VARCHAR(50),
                           acceptance_threshold DECIMAL(20, 5),
                           tolerance DECIMAL(20, 5),
                           measurement_period VARCHAR(100),
                           source_requirements TEXT,
                           version_id VARCHAR(100),
                           created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

                           CONSTRAINT fk_indicator_objective FOREIGN KEY (objective_id) REFERENCES objective(objective_id)
);

-- ----------------------------------------------------------------------------
-- ENT-05: Línea base
-- ----------------------------------------------------------------------------
CREATE TABLE baseline (
                          baseline_id UUID PRIMARY KEY,
                          cycle_id UUID NOT NULL,
                          indicator_id UUID NOT NULL,
                          observed_value DECIMAL(20, 5),
                          measurement_context TEXT,
                          quality_status VARCHAR(50),
                          measured_at TIMESTAMP NOT NULL,
                          version_id VARCHAR(100),
                          created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

                          CONSTRAINT fk_baseline_cycle FOREIGN KEY (cycle_id) REFERENCES cycle(cycle_id),
                          CONSTRAINT fk_baseline_indicator FOREIGN KEY (indicator_id) REFERENCES indicator(indicator_id)
);

-- ----------------------------------------------------------------------------
-- ENT-09: Evidencia
-- ----------------------------------------------------------------------------
CREATE TABLE evidence (
                          evidence_id UUID PRIMARY KEY,
                          cycle_id UUID NOT NULL,
                          source_type VARCHAR(50) NOT NULL,
                          source_reference VARCHAR(500),
                          description TEXT,
                          captured_at TIMESTAMP NOT NULL,
                          observed_period VARCHAR(100),
                          provenance TEXT,
                          integrity_reference VARCHAR(500),
                          quality_status VARCHAR(50) NOT NULL,
                          limitations TEXT,
                          access_classification VARCHAR(50),
                          created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

                          CONSTRAINT fk_evidence_cycle FOREIGN KEY (cycle_id) REFERENCES cycle(cycle_id)
);

-- ----------------------------------------------------------------------------
-- ENT-10: Evaluación
-- ----------------------------------------------------------------------------
CREATE TABLE evaluation (
                            evaluation_id UUID PRIMARY KEY,
                            cycle_id UUID NOT NULL,
                            objective_id UUID NOT NULL,
                            baseline_id UUID,
                            criteria_version_id VARCHAR(100) NOT NULL,
                            observed_results JSON,
                            result_classification VARCHAR(50) NOT NULL,
                            evidence_sufficiency VARCHAR(50) NOT NULL,
                            confidence_level VARCHAR(50) NOT NULL,
                            limitations TEXT,
                            rationale TEXT,
                            evaluated_at TIMESTAMP NOT NULL,
                            evaluator_reference VARCHAR(255),
                            created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

                            CONSTRAINT fk_evaluation_cycle FOREIGN KEY (cycle_id) REFERENCES cycle(cycle_id),
                            CONSTRAINT fk_evaluation_objective FOREIGN KEY (objective_id) REFERENCES objective(objective_id),
                            CONSTRAINT fk_evaluation_baseline FOREIGN KEY (baseline_id) REFERENCES baseline(baseline_id)
);

-- ----------------------------------------------------------------------------
-- Relación entre evaluación y evidencia
-- ----------------------------------------------------------------------------
CREATE TABLE evaluation_evidence (
                                     evaluation_id UUID NOT NULL,
                                     evidence_id UUID NOT NULL,
                                     PRIMARY KEY (evaluation_id, evidence_id),

                                     CONSTRAINT fk_eval_evidence_evaluation FOREIGN KEY (evaluation_id) REFERENCES evaluation(evaluation_id) ON DELETE CASCADE,
                                     CONSTRAINT fk_eval_evidence_evidence FOREIGN KEY (evidence_id) REFERENCES evidence(evidence_id)
);

-- ----------------------------------------------------------------------------
-- ENT-11: Decisión
-- ----------------------------------------------------------------------------
CREATE TABLE decision_record (
                                 decision_id UUID PRIMARY KEY,
                                 cycle_id UUID NOT NULL,
                                 evaluation_id UUID,
                                 decision_type VARCHAR(50) NOT NULL,
                                 recommendation VARCHAR(100),
                                 alternatives_considered JSON,
                                 selection_rationale TEXT,
                                 risk_constraints JSON,
                                 authorization_status VARCHAR(50),
                                 proposed_transition JSON,
                                 actual_transition_id UUID,
                                 decision_status VARCHAR(50) NOT NULL,
                                 decision_rule_version_id VARCHAR(100),
                                 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

                                 CONSTRAINT fk_decision_cycle FOREIGN KEY (cycle_id) REFERENCES cycle(cycle_id),
                                 CONSTRAINT fk_decision_evaluation FOREIGN KEY (evaluation_id) REFERENCES evaluation(evaluation_id)
);

-- ----------------------------------------------------------------------------
-- ENT-13: Evento
-- ----------------------------------------------------------------------------
CREATE TABLE cycle_event (
                             event_id UUID PRIMARY KEY,
                             cycle_id UUID NOT NULL,
                             event_type VARCHAR(100) NOT NULL,
                             actor_id VARCHAR(255),
                             occurred_at TIMESTAMP NOT NULL,
                             recorded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                             payload_reference VARCHAR(500),
                             correlation_id VARCHAR(255),
                             causation_id VARCHAR(255),
                             created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

                             CONSTRAINT fk_event_cycle FOREIGN KEY (cycle_id) REFERENCES cycle(cycle_id)
);

-- ----------------------------------------------------------------------------
-- ENT-14: Transición de estado
-- ----------------------------------------------------------------------------
CREATE TABLE state_transition (
                                  transition_id UUID PRIMARY KEY,
                                  cycle_id UUID NOT NULL,
                                  from_state VARCHAR(50) NOT NULL,
                                  to_state VARCHAR(50) NOT NULL,
                                  trigger_event_id UUID,
                                  transition_rule_id VARCHAR(100),
                                  authorization_reference VARCHAR(255),
                                  transition_status VARCHAR(50) NOT NULL,
                                  occurred_at TIMESTAMP NOT NULL,
                                  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

                                  CONSTRAINT fk_transition_cycle FOREIGN KEY (cycle_id) REFERENCES cycle(cycle_id),
                                  CONSTRAINT fk_transition_event FOREIGN KEY (trigger_event_id) REFERENCES cycle_event(event_id)
);

-- ----------------------------------------------------------------------------
-- ENT-12: Autorización
-- ----------------------------------------------------------------------------
CREATE TABLE authorizations (
                               authorization_id UUID PRIMARY KEY,
                               plan_id UUID,
                               action_id UUID,
                               requested_by VARCHAR(255) NOT NULL,
                               approved_by VARCHAR(255),
                               scope TEXT,
                               status VARCHAR(50) NOT NULL,
                               conditions TEXT,
                               issued_at TIMESTAMP NOT NULL,
                               expires_at TIMESTAMP,
                               revoked_at TIMESTAMP,
                               authorization_policy_version_id VARCHAR(100),
                               created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ----------------------------------------------------------------------------
-- ENT-07: Plan de intervención
-- ----------------------------------------------------------------------------
CREATE TABLE intervention_plan (
                                   plan_id UUID PRIMARY KEY,
                                   cycle_id UUID NOT NULL,
                                   objective_ids JSON,
                                   hypothesis_ids JSON,
                                   actions JSON,
                                   expected_outcomes TEXT,
                                   risk_assessment_id UUID,
                                   rollback_strategy TEXT,
                                   authorization_requirements TEXT,
                                   status VARCHAR(50) NOT NULL,
                                   version_id VARCHAR(100),
                                   created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

                                   CONSTRAINT fk_plan_cycle FOREIGN KEY (cycle_id) REFERENCES cycle(cycle_id)
);

-- ----------------------------------------------------------------------------
-- ENT-08: Acción ejecutada
-- ----------------------------------------------------------------------------
CREATE TABLE executed_action (
                                 action_id UUID PRIMARY KEY,
                                 plan_id UUID NOT NULL,
                                 cycle_id UUID NOT NULL,
                                 action_type VARCHAR(100) NOT NULL,
                                 parameters_reference VARCHAR(500),
                                 execution_status VARCHAR(50) NOT NULL,
                                 started_at TIMESTAMP,
                                 completed_at TIMESTAMP,
                                 actor_id VARCHAR(255),
                                 authorization_id UUID,
                                 result_reference VARCHAR(500),
                                 error_reference VARCHAR(500),
                                 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

                                 CONSTRAINT fk_action_plan FOREIGN KEY (plan_id) REFERENCES intervention_plan(plan_id),
                                 CONSTRAINT fk_action_cycle FOREIGN KEY (cycle_id) REFERENCES cycle(cycle_id),
                                 CONSTRAINT fk_action_authorization FOREIGN KEY (authorization_id) REFERENCES authorizations(authorization_id)
);

-- ----------------------------------------------------------------------------
-- ENT-16: Lección aprendida
-- ----------------------------------------------------------------------------
CREATE TABLE learning (
                          learning_id UUID PRIMARY KEY,
                          cycle_id UUID NOT NULL,
                          hypothesis_id UUID,
                          statement TEXT NOT NULL,
                          supporting_evidence_ids JSON,
                          confidence_level VARCHAR(50),
                          applicable_conditions TEXT,
                          status VARCHAR(50) NOT NULL,
                          created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

                          CONSTRAINT fk_learning_cycle FOREIGN KEY (cycle_id) REFERENCES cycle(cycle_id)
);

-- ----------------------------------------------------------------------------
-- ENT-15: Versión de configuración
-- ----------------------------------------------------------------------------
CREATE TABLE configuration_version (
                                       version_id VARCHAR(100) PRIMARY KEY,
                                       description TEXT,
                                       configuration JSON NOT NULL,
                                       created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                       created_by VARCHAR(255)
);

-- ----------------------------------------------------------------------------
-- Índices para mejorar el rendimiento de consultas frecuentes
-- ----------------------------------------------------------------------------

CREATE INDEX idx_cycle_system ON cycle(system_id);
CREATE INDEX idx_cycle_state ON cycle(state);
CREATE INDEX idx_cycle_started_at ON cycle(started_at);
CREATE INDEX idx_event_cycle ON cycle_event(cycle_id);
CREATE INDEX idx_event_type ON cycle_event(event_type);
CREATE INDEX idx_transition_cycle ON state_transition(cycle_id);
CREATE INDEX idx_evidence_cycle ON evidence(cycle_id);
CREATE INDEX idx_evaluation_cycle ON evaluation(cycle_id);
CREATE INDEX idx_decision_cycle ON decision_record(cycle_id);

-- ----------------------------------------------------------------------------
-- Comentarios sobre las tablas (documentación en la base de datos)
-- ----------------------------------------------------------------------------

COMMENT ON TABLE system_objective IS 'ENT-01: Representa el sistema que el MIEC observa o transforma';
COMMENT ON TABLE cycle IS 'ENT-02: Identifica una ejecución del ciclo de evolución';
COMMENT ON TABLE objective IS 'ENT-03: Define el resultado que se pretende alcanzar';
COMMENT ON TABLE indicator IS 'ENT-04: Representa una variable utilizada para medir el estado o el resultado';
COMMENT ON TABLE baseline IS 'ENT-05: Registra las condiciones iniciales de referencia';
COMMENT ON TABLE evidence IS 'ENT-09: Representa una fuente, medición o registro que respalda una evaluación';
COMMENT ON TABLE evaluation IS 'ENT-10: Registra la interpretación de resultados según criterios definidos';
COMMENT ON TABLE decision_record IS 'ENT-11: Conserva la recomendación o determinación emitida por el motor';
COMMENT ON TABLE cycle_event IS 'ENT-13: Registra un hecho relevante ocurrido durante la operación';
COMMENT ON TABLE state_transition IS 'ENT-14: Registra el cambio entre estados del modelo F01';
COMMENT ON TABLE authorizations IS 'ENT-12: Representa el permiso para ejecutar una acción o plan';
COMMENT ON TABLE intervention_plan IS 'ENT-07: Describe las acciones propuestas para alcanzar un objetivo';
COMMENT ON TABLE executed_action IS 'ENT-08: Registra una operación efectivamente realizada';
COMMENT ON TABLE learning IS 'ENT-16: Registra una conclusión derivada de uno o varios ciclos';
COMMENT ON TABLE configuration_version IS 'ENT-15: Conserva una versión identificable de reglas, criterios o parámetros';