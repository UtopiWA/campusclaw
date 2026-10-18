package com.campusclaw.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class McpSchemaMigrationTest {
    @Container
    private static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("campusclaw")
            .withUsername("campusclaw")
            .withPassword("campusclaw-test");

    @Test
    void upgradesV5DataAndEnforcesMcpDeletionContracts() throws Exception {
        migrateTo("5");
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            // 先构造一组真实 V5 记录，确认 V6 不是只对空库可用。
            statement.executeUpdate("INSERT INTO classes(id, name) VALUES (1, 'A')");
            statement.executeUpdate("""
                    INSERT INTO users(id, username, password_hash, role, class_id, enabled)
                    VALUES (1, 'teacher', 'hash', 'TEACHER', 1, TRUE)
                    """);
            statement.executeUpdate("""
                    INSERT INTO assistants(id, class_id, name, prompt_text, enabled, created_at, updated_at)
                    VALUES (1, 1, 'assistant', 'prompt', TRUE, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                    """);
            statement.executeUpdate("""
                    INSERT INTO skills(id, class_id, name, trigger_text, material_scope, tool_name, enabled,
                                       instructions, source_text, created_at, updated_at)
                    VALUES (1, 1, 'skill', 'trigger', '', 'knowledge_search', TRUE,
                            'guide', 'source', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                    """);
            statement.executeUpdate("""
                    INSERT INTO conversations(id, class_id, user_id, assistant_id, title, created_at, updated_at)
                    VALUES (1, 1, 1, 1, 'title', UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                    """);
            statement.executeUpdate("""
                    INSERT INTO messages(id, conversation_id, role, content, skill_name, citations_json, status, created_at)
                    VALUES (1, 1, 'ASSISTANT', 'answer', 'skill', JSON_ARRAY(), 'COMPLETED', UTC_TIMESTAMP(6))
                    """);
        }

        migrateTo(null);
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            assertThat(stringValue(statement, "SELECT tool_refs FROM skills WHERE id = 1"))
                    .isEqualTo("knowledge_search");
            assertThat(longValue(statement, """
                    SELECT COUNT(*) FROM information_schema.columns
                    WHERE table_schema = DATABASE() AND table_name = 'skills' AND column_name = 'tool_name'
                    """)).isZero();
            assertThat(longValue(statement, """
                    SELECT COUNT(*) FROM information_schema.columns
                    WHERE table_schema = DATABASE() AND table_name = 'messages' AND column_name = 'tool_calls_json'
                    """)).isEqualTo(1);
            assertThat(stringValue(statement, "SELECT tool_calls_json FROM messages WHERE id = 1")).isNull();

            statement.executeUpdate("""
                    INSERT INTO mcp_servers(id, class_id, name, base_url, auth_token_ciphertext, connected,
                                            connected_at, config_version, created_at, updated_at)
                    VALUES (1, 1, 'demo', 'http://mcp-demo:8090/mcp', 'v1:ciphertext', TRUE,
                            UTC_TIMESTAMP(6), 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))
                    """);
            statement.executeUpdate("""
                    INSERT INTO mcp_tools(id, server_id, name, description, input_schema, created_at)
                    VALUES (1, 1, 'formula_hint', 'hint', JSON_OBJECT('type', 'object'), UTC_TIMESTAMP(6))
                    """);
            statement.executeUpdate("""
                    INSERT INTO mcp_tool_calls(id, class_id, server_id, user_id, conversation_id,
                                               tool_ref, status, detail, created_at)
                    VALUES (1, 1, 1, 1, NULL, 'formula_hint@demo', 'OK', 'safe', UTC_TIMESTAMP(6))
                    """);

            assertThatThrownBy(() -> statement.executeUpdate("DELETE FROM mcp_servers WHERE id = 1"))
                    .isInstanceOf(SQLException.class);
            assertThat(longValue(statement, "SELECT COUNT(*) FROM mcp_servers WHERE id = 1")).isEqualTo(1);
            assertThat(longValue(statement, "SELECT COUNT(*) FROM mcp_tool_calls WHERE server_id = 1")).isEqualTo(1);

            statement.executeUpdate("DELETE FROM mcp_tool_calls WHERE server_id = 1");
            statement.executeUpdate("DELETE FROM mcp_servers WHERE id = 1");
            assertThat(longValue(statement, "SELECT COUNT(*) FROM mcp_tools WHERE server_id = 1")).isZero();
        }
    }

    private void migrateTo(String target) {
        var configuration = Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
        if (target != null) configuration.target(target);
        configuration.load().migrate();
    }

    private Connection connection() throws SQLException {
        return DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
    }

    private long longValue(Statement statement, String sql) throws SQLException {
        try (ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getLong(1);
        }
    }

    private String stringValue(Statement statement, String sql) throws SQLException {
        try (ResultSet rows = statement.executeQuery(sql)) {
            rows.next();
            return rows.getString(1);
        }
    }
}
