CREATE TABLE classes (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_classes_name UNIQUE (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE users (
    id BIGINT NOT NULL AUTO_INCREMENT,
    username VARCHAR(100) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    role VARCHAR(20) NOT NULL,
    class_id BIGINT NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (id),
    CONSTRAINT uk_users_username UNIQUE (username),
    CONSTRAINT fk_users_class FOREIGN KEY (class_id) REFERENCES classes (id),
    INDEX idx_users_class_id (class_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE materials (
    id BIGINT NOT NULL AUTO_INCREMENT,
    class_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    original_filename VARCHAR(255) NULL,
    stored_path VARCHAR(500) NULL,
    uploaded_by BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_materials_class FOREIGN KEY (class_id) REFERENCES classes (id),
    CONSTRAINT fk_materials_uploader FOREIGN KEY (uploaded_by) REFERENCES users (id),
    INDEX idx_materials_class_created (class_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE knowledge_entries (
    id BIGINT NOT NULL AUTO_INCREMENT,
    material_id BIGINT NOT NULL,
    class_id BIGINT NOT NULL,
    chunk_index INT NOT NULL,
    body_text TEXT NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_knowledge_material_chunk UNIQUE (material_id, chunk_index),
    CONSTRAINT fk_knowledge_material FOREIGN KEY (material_id) REFERENCES materials (id) ON DELETE CASCADE,
    CONSTRAINT fk_knowledge_class FOREIGN KEY (class_id) REFERENCES classes (id),
    INDEX idx_knowledge_class_material (class_id, material_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE assignments (
    id BIGINT NOT NULL AUTO_INCREMENT,
    class_id BIGINT NOT NULL,
    title VARCHAR(255) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_assignments_class FOREIGN KEY (class_id) REFERENCES classes (id),
    INDEX idx_assignments_class_id (class_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE assistants (
    id BIGINT NOT NULL AUTO_INCREMENT,
    class_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_assistants_class FOREIGN KEY (class_id) REFERENCES classes (id),
    INDEX idx_assistants_class_id (class_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE skills (
    id BIGINT NOT NULL AUTO_INCREMENT,
    assistant_id BIGINT NOT NULL,
    class_id BIGINT NOT NULL,
    name VARCHAR(255) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_skills_assistant FOREIGN KEY (assistant_id) REFERENCES assistants (id) ON DELETE CASCADE,
    CONSTRAINT fk_skills_class FOREIGN KEY (class_id) REFERENCES classes (id),
    INDEX idx_skills_class_assistant (class_id, assistant_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

