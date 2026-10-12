ALTER TABLE materials
    ADD COLUMN processing_stage VARCHAR(20) NULL AFTER index_status,
    ADD COLUMN failure_code VARCHAR(40) NULL AFTER index_error,
    ADD COLUMN retryable BOOLEAN NOT NULL DEFAULT FALSE AFTER failure_code;

UPDATE materials
SET processing_stage = CASE
    WHEN index_status = 'READY' THEN 'COMPLETED'
    WHEN index_status = 'INDEXING' THEN 'INDEXING'
    ELSE 'QUEUED'
END,
failure_code = CASE WHEN index_status = 'FAILED' THEN 'PROCESSING_ERROR' ELSE NULL END,
retryable = CASE WHEN index_status = 'FAILED' THEN TRUE ELSE FALSE END;

ALTER TABLE materials
    MODIFY COLUMN processing_stage VARCHAR(20) NOT NULL;

CREATE INDEX idx_materials_recovery ON materials (index_status, updated_at);
