ALTER TABLE mcp_tool_calls
    ADD COLUMN duration_ms BIGINT UNSIGNED NULL AFTER detail;
