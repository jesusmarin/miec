-- PostgreSQL 14: equivalente de V008__persist_immutable_execution_request.sql; historial independiente del perfil H2.
ALTER TABLE execution_operation ADD COLUMN request_format VARCHAR(32);
ALTER TABLE execution_operation ADD COLUMN request_media_type VARCHAR(255);
ALTER TABLE execution_operation ADD COLUMN request_body TEXT;
ALTER TABLE execution_operation ADD CONSTRAINT ck_operation_request_snapshot CHECK (
    (request_format IS NULL AND request_media_type IS NULL AND request_body IS NULL)
    OR (request_format IS NOT NULL AND request_format='UTF8-V1'
        AND request_media_type IS NOT NULL AND LENGTH(TRIM(request_media_type))>0 AND request_body IS NOT NULL)
);
