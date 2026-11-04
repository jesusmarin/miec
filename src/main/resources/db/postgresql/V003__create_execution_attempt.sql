-- PostgreSQL 14: equivalente de V003__create_execution_attempt.sql; historial independiente del perfil H2.
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

                                   started_at TIMESTAMP(6) WITH TIME ZONE,

                                   completed_at TIMESTAMP(6) WITH TIME ZONE,

                                   result_reference VARCHAR(500),

                                   error_reference VARCHAR(500),

                                   created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL
                                                           DEFAULT clock_timestamp(),

                                   version BIGINT NOT NULL DEFAULT 0,

                                   CONSTRAINT fk_attempt_plan_cycle
                                       FOREIGN KEY (plan_id, cycle_id)
                                           REFERENCES intervention_plan (plan_id, cycle_id),

                                   CONSTRAINT fk_attempt_authorization
                                       FOREIGN KEY (authorization_id)
                                           REFERENCES authorizations (authorization_id),

                                   CONSTRAINT uq_attempt_action_number
                                       UNIQUE (
                                               plan_id,
                                               planned_action_id,
                                               attempt_number
                                           ),

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

CREATE INDEX idx_attempt_planned_action
    ON execution_attempt (
                          planned_action_id
        );

CREATE INDEX idx_attempt_cycle
    ON execution_attempt (
                          cycle_id
        );

CREATE INDEX idx_attempt_status
    ON execution_attempt (
                          execution_status
        );

CREATE INDEX idx_attempt_authorization
    ON execution_attempt (
                          authorization_id
        );
