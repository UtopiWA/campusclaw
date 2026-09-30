ALTER TABLE materials
    ADD COLUMN index_status VARCHAR(20) NOT NULL DEFAULT 'PENDING' AFTER file_size_bytes,
    ADD COLUMN index_strategy VARCHAR(20) NOT NULL DEFAULT 'AUTO' AFTER index_status,
    ADD COLUMN index_error VARCHAR(500) NULL AFTER index_strategy,
    ADD COLUMN indexed_at DATETIME(6) NULL AFTER index_error,
    ADD INDEX idx_materials_class_index_status (class_id, index_status);

CREATE TABLE knowledge_chunks (
    id BIGINT NOT NULL AUTO_INCREMENT,
    material_id BIGINT NOT NULL,
    knowledge_entry_id BIGINT NOT NULL,
    class_id BIGINT NOT NULL,
    chunk_index INT NOT NULL,
    chunk_text TEXT NOT NULL,
    start_offset INT NOT NULL,
    end_offset INT NOT NULL,
    index_status VARCHAR(20) NOT NULL,
    index_error VARCHAR(500) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_chunks_material_index UNIQUE (material_id, chunk_index),
    CONSTRAINT fk_chunks_material FOREIGN KEY (material_id) REFERENCES materials (id) ON DELETE CASCADE,
    CONSTRAINT fk_chunks_entry FOREIGN KEY (knowledge_entry_id) REFERENCES knowledge_entries (id) ON DELETE CASCADE,
    CONSTRAINT fk_chunks_class FOREIGN KEY (class_id) REFERENCES classes (id),
    INDEX idx_chunks_class_status_material (class_id, index_status, material_id),
    FULLTEXT INDEX ft_chunks_text (chunk_text) WITH PARSER ngram
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE vector_cleanup_jobs (
    id BIGINT NOT NULL AUTO_INCREMENT,
    material_id BIGINT NOT NULL,
    point_ids JSON NOT NULL,
    status VARCHAR(20) NOT NULL,
    retry_count INT NOT NULL DEFAULT 0,
    last_error VARCHAR(500) NULL,
    next_retry_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_cleanup_status_retry (status, next_retry_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
