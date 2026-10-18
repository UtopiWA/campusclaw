CREATE TABLE mcp_servers (
    id BIGINT NOT NULL AUTO_INCREMENT,
    class_id BIGINT NOT NULL,
    name VARCHAR(32) NOT NULL,
    base_url VARCHAR(512) NOT NULL,
    auth_token_ciphertext TEXT NOT NULL,
    connected BOOLEAN NOT NULL DEFAULT FALSE,
    connected_at DATETIME(6) NULL,
    config_version BIGINT NOT NULL DEFAULT 0,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_mcp_servers_class_name UNIQUE (class_id, name),
    CONSTRAINT fk_mcp_servers_class FOREIGN KEY (class_id) REFERENCES classes (id),
    INDEX idx_mcp_servers_class_created (class_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE mcp_tools (
    id BIGINT NOT NULL AUTO_INCREMENT,
    server_id BIGINT NOT NULL,
    name VARCHAR(64) NOT NULL,
    description VARCHAR(1000) NOT NULL,
    input_schema JSON NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_mcp_tools_server_name UNIQUE (server_id, name),
    CONSTRAINT fk_mcp_tools_server FOREIGN KEY (server_id)
        REFERENCES mcp_servers (id) ON DELETE CASCADE,
    INDEX idx_mcp_tools_server (server_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE mcp_tool_calls (
    id BIGINT NOT NULL AUTO_INCREMENT,
    class_id BIGINT NOT NULL,
    server_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    conversation_id BIGINT NULL,
    tool_ref VARCHAR(128) NOT NULL,
    status VARCHAR(20) NOT NULL,
    detail VARCHAR(512) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_mcp_tool_calls_class FOREIGN KEY (class_id) REFERENCES classes (id),
    CONSTRAINT fk_mcp_tool_calls_server FOREIGN KEY (server_id) REFERENCES mcp_servers (id),
    CONSTRAINT fk_mcp_tool_calls_user FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT chk_mcp_tool_calls_status CHECK (status IN ('OK', 'DENIED', 'ERROR')),
    INDEX idx_mcp_tool_calls_class_created (class_id, created_at),
    INDEX idx_mcp_tool_calls_server (server_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

ALTER TABLE skills ADD COLUMN tool_refs TEXT NULL AFTER material_scope;
UPDATE skills SET tool_refs = tool_name WHERE tool_refs IS NULL;
ALTER TABLE skills MODIFY COLUMN tool_refs TEXT NOT NULL;
ALTER TABLE skills DROP COLUMN tool_name;

ALTER TABLE messages ADD COLUMN tool_calls_json JSON NULL AFTER citations_json;
