ALTER TABLE assistants
    ADD COLUMN prompt_text VARCHAR(2000) NULL AFTER name,
    ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE AFTER prompt_text,
    ADD COLUMN created_at DATETIME(6) NULL AFTER enabled,
    ADD COLUMN updated_at DATETIME(6) NULL AFTER created_at;

UPDATE assistants
SET created_at = UTC_TIMESTAMP(6), updated_at = UTC_TIMESTAMP(6)
WHERE created_at IS NULL OR updated_at IS NULL;

ALTER TABLE assistants
    MODIFY COLUMN created_at DATETIME(6) NOT NULL,
    MODIFY COLUMN updated_at DATETIME(6) NOT NULL,
    ADD CONSTRAINT uk_assistants_class_name UNIQUE (class_id, name);

ALTER TABLE skills
    ADD COLUMN trigger_text VARCHAR(120) NULL AFTER name,
    ADD COLUMN material_scope VARCHAR(32) NULL AFTER trigger_text,
    ADD COLUMN tool_name VARCHAR(64) NULL AFTER material_scope,
    ADD COLUMN enabled BOOLEAN NOT NULL DEFAULT TRUE AFTER tool_name,
    ADD COLUMN instructions VARCHAR(2000) NULL AFTER enabled,
    ADD COLUMN source_text TEXT NULL AFTER instructions,
    ADD COLUMN created_at DATETIME(6) NULL AFTER source_text,
    ADD COLUMN updated_at DATETIME(6) NULL AFTER created_at;

UPDATE skills
SET trigger_text = LEFT(name, 120),
    material_scope = '',
    tool_name = 'knowledge_search',
    instructions = '根据当前问题整理并引用已绑定的教学材料。',
    source_text = CONCAT(
        '名称：', name, '\n',
        '适用条件：', name, '\n',
        '资料：\n',
        '工具：knowledge_search\n',
        '启用：是\n\n',
        '根据当前问题整理并引用已绑定的教学材料。'),
    created_at = UTC_TIMESTAMP(6),
    updated_at = UTC_TIMESTAMP(6)
WHERE trigger_text IS NULL OR instructions IS NULL OR source_text IS NULL
   OR created_at IS NULL OR updated_at IS NULL;

ALTER TABLE skills
    MODIFY COLUMN trigger_text VARCHAR(120) NOT NULL,
    MODIFY COLUMN material_scope VARCHAR(32) NOT NULL,
    MODIFY COLUMN tool_name VARCHAR(64) NOT NULL,
    MODIFY COLUMN instructions VARCHAR(2000) NOT NULL,
    MODIFY COLUMN source_text TEXT NOT NULL,
    MODIFY COLUMN created_at DATETIME(6) NOT NULL,
    MODIFY COLUMN updated_at DATETIME(6) NOT NULL,
    ADD CONSTRAINT uk_skills_class_name UNIQUE (class_id, name);

CREATE TABLE assistant_skills (
    assistant_id BIGINT NOT NULL,
    skill_id BIGINT NOT NULL,
    PRIMARY KEY (assistant_id, skill_id),
    CONSTRAINT fk_assistant_skills_assistant FOREIGN KEY (assistant_id)
        REFERENCES assistants (id) ON DELETE CASCADE,
    CONSTRAINT fk_assistant_skills_skill FOREIGN KEY (skill_id)
        REFERENCES skills (id) ON DELETE CASCADE,
    INDEX idx_assistant_skills_skill (skill_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

INSERT INTO assistant_skills (assistant_id, skill_id)
SELECT assistant_id, id FROM skills;

ALTER TABLE skills
    DROP FOREIGN KEY fk_skills_assistant,
    DROP INDEX idx_skills_class_assistant,
    DROP COLUMN assistant_id;

CREATE TABLE assistant_materials (
    assistant_id BIGINT NOT NULL,
    material_id BIGINT NOT NULL,
    PRIMARY KEY (assistant_id, material_id),
    CONSTRAINT fk_assistant_materials_assistant FOREIGN KEY (assistant_id)
        REFERENCES assistants (id) ON DELETE CASCADE,
    CONSTRAINT fk_assistant_materials_material FOREIGN KEY (material_id)
        REFERENCES materials (id) ON DELETE CASCADE,
    INDEX idx_assistant_materials_material (material_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE conversations (
    id BIGINT NOT NULL AUTO_INCREMENT,
    class_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    assistant_id BIGINT NOT NULL,
    title VARCHAR(100) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_conversations_class FOREIGN KEY (class_id) REFERENCES classes (id),
    CONSTRAINT fk_conversations_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT fk_conversations_assistant FOREIGN KEY (assistant_id) REFERENCES assistants (id),
    INDEX idx_conversations_user_class_updated (user_id, class_id, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE messages (
    id BIGINT NOT NULL AUTO_INCREMENT,
    conversation_id BIGINT NOT NULL,
    role VARCHAR(20) NOT NULL,
    content TEXT NOT NULL,
    skill_name VARCHAR(255) NULL,
    citations_json JSON NOT NULL,
    status VARCHAR(20) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_messages_conversation FOREIGN KEY (conversation_id)
        REFERENCES conversations (id) ON DELETE CASCADE,
    CONSTRAINT chk_messages_role CHECK (role IN ('USER', 'ASSISTANT')),
    CONSTRAINT chk_messages_status CHECK (status IN ('COMPLETED', 'FAILED')),
    INDEX idx_messages_conversation_id (conversation_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
