-- PostgreSQL 14: equivalente de V009__create_pending_execution_review.sql; historial independiente del perfil H2.
CREATE TABLE execution_pending_review (
    review_id UUID PRIMARY KEY,
    execution_id UUID NOT NULL UNIQUE REFERENCES execution_attempt(execution_id),
    authorization_id UUID NOT NULL REFERENCES authorizations(authorization_id),
    reviewed_by VARCHAR(255) NOT NULL CHECK(LENGTH(TRIM(reviewed_by))>0),
    rationale VARCHAR(1000) NOT NULL CHECK(LENGTH(TRIM(rationale))>0),
    original_created_at TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    reviewed_at TIMESTAMP(6) WITH TIME ZONE NOT NULL CHECK(reviewed_at>=original_created_at)
);
CREATE INDEX idx_attempt_created_cursor ON execution_attempt(created_at,execution_id);
