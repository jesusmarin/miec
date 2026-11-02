
-- ============================================================
-- MIEC-F03 / IR-009
-- V003 - Persistencia de intentos de ejecución
-- ============================================================
--
-- Objetivo:
-- Registrar cada intento de ejecución de una acción
-- planificada, conservando su identidad, estado,
-- resultado, autorización y trazabilidad.
--
-- Dependencias:
-- V001__create_initial_schema.sql
-- V002__strengthen_referential_integrity.sql
--
-- No modifica registros de executed_action.
-- No modifica migraciones anteriores.
-- ============================================================


-- ------------------------------------------------------------
-- 1. Tabla de intentos de ejecución
-- ------------------------------------------------------------

CREATE TABLE execution_attempt (

                                   execution_id UUID PRIMARY KEY,

                                   planned_action_id UUID NOT NULL,

                                   plan_id UUID NOT NULL,

                                   cycle_id UUID NOT NULL,

                                   authorization_id UUID NOT NULL,

                                   attempt_number INTEGER NOT NULL,

                                   execution_status VARCHAR(50) NOT NULL,

                                   effect_outcome VARCHAR(50) NOT NULL,

                                   actor_id VARCHAR(255) NOT NULL,

                                   started_at TIMESTAMP,

                                   completed_at TIMESTAMP,

                                   result_reference VARCHAR(500),

                                   error_reference VARCHAR(500),

                                   created_at TIMESTAMP NOT NULL
                                                           DEFAULT CURRENT_TIMESTAMP,

                                   version BIGINT NOT NULL DEFAULT 0,


    -- --------------------------------------------------------
    -- 2. Integridad referencial
    -- --------------------------------------------------------

                                   CONSTRAINT fk_attempt_plan_cycle
                                       FOREIGN KEY (plan_id, cycle_id)
                                           REFERENCES intervention_plan (plan_id, cycle_id),

                                   CONSTRAINT fk_attempt_authorization
                                       FOREIGN KEY (authorization_id)
                                           REFERENCES authorizations (authorization_id),


    -- --------------------------------------------------------
    -- 3. Unicidad de los intentos
    -- --------------------------------------------------------

                                   CONSTRAINT uq_attempt_action_number
                                       UNIQUE (
                                               plan_id,
                                               planned_action_id,
                                               attempt_number
                                           ),


    -- --------------------------------------------------------
    -- 4. Validaciones de dominio
    -- --------------------------------------------------------

                                   CONSTRAINT ck_attempt_number_positive
                                       CHECK (attempt_number >= 1),

                                   CONSTRAINT ck_attempt_version
                                       CHECK (version >= 0),

                                   CONSTRAINT ck_attempt_status
                                       CHECK (
                                           execution_status IN (
                                                                'PENDING',
                                                                'IN_PROGRESS',
                                                                'COMPLETED',
                                                                'FAILED',
                                                                'UNKNOWN'
                                               )
                                           ),

                                   CONSTRAINT ck_attempt_effect_outcome
                                       CHECK (
                                           effect_outcome IN (
                                                              'NO_EFFECT_CONFIRMED',
                                                              'EFFECT_APPLIED',
                                                              'EFFECT_UNKNOWN'
                                               )
                                           ),


    -- --------------------------------------------------------
    -- 5. Coherencia de estados y resultados
    -- --------------------------------------------------------

                                   CONSTRAINT ck_attempt_status_effect
                                       CHECK (
                                           (
                                               execution_status IN (
                                                                    'PENDING',
                                                                    'IN_PROGRESS',
                                                                    'UNKNOWN'
                                                   )
                                                   AND effect_outcome = 'EFFECT_UNKNOWN'
                                               )
                                               OR (
                                               execution_status = 'COMPLETED'
                                                   AND effect_outcome = 'EFFECT_APPLIED'
                                               )
                                               OR (
                                               execution_status = 'FAILED'
                                                   AND effect_outcome IN (
                                                                          'NO_EFFECT_CONFIRMED',
                                                                          'EFFECT_APPLIED'
                                                   )
                                               )
                                           ),


    -- --------------------------------------------------------
    -- 6. Coherencia temporal
    -- --------------------------------------------------------

                                   CONSTRAINT ck_attempt_timestamps
                                       CHECK (
                                           (
                                               execution_status = 'PENDING'
                                                   AND started_at IS NULL
                                                   AND completed_at IS NULL
                                               )
                                               OR (
                                               execution_status = 'IN_PROGRESS'
                                                   AND started_at IS NOT NULL
                                                   AND completed_at IS NULL
                                               )
                                               OR (
                                               execution_status IN (
                                                                    'COMPLETED',
                                                                    'FAILED',
                                                                    'UNKNOWN'
                                                   )
                                                   AND started_at IS NOT NULL
                                                   AND completed_at IS NOT NULL
                                                   AND completed_at >= started_at
                                               )
                                           )
);


-- ------------------------------------------------------------
-- 7. Índices para trazabilidad y consulta
-- ------------------------------------------------------------

-- Recuperar el historial de una acción planificada.
CREATE INDEX idx_attempt_planned_action
    ON execution_attempt (
                          planned_action_id
        );

-- Consultar intentos por ciclo.
CREATE INDEX idx_attempt_cycle
    ON execution_attempt (
                          cycle_id
        );

-- Localizar intentos por estado.
CREATE INDEX idx_attempt_status
    ON execution_attempt (
                          execution_status
        );

-- Consultar intentos asociados a una autorización.
CREATE INDEX idx_attempt_authorization
    ON execution_attempt (
                          authorization_id
        );


-- ============================================================
-- Consideraciones arquitectónicas
-- ============================================================
--
-- planned_action_id:
-- No tiene FK directa porque las acciones se encuentran
-- actualmente en intervention_plan.actions (JSON).
-- PlannedActionPolicy valida su pertenencia al plan.
--
-- authorization_id:
-- Su FK garantiza existencia, no que la autorización
-- corresponda a esa acción o esté vigente.
-- IR-010 continúa validando esas condiciones.
--
-- attempt_number:
-- La unicidad evita duplicar el número de intento
-- dentro del mismo plan y acción.
-- La asignación concurrente del siguiente número
-- corresponde al futuro ExecutionAttemptService.
--
-- Estados finales:
-- COMPLETED, FAILED y UNKNOWN no deben reabrirse.
-- Esta regla de transición se mantiene en el dominio.
--
-- Los efectos externos no se revierten mediante
-- transacciones SQL.
-- ============================================================
