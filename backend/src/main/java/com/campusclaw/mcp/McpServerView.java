package com.campusclaw.mcp;

import com.campusclaw.persistence.McpServer;
import java.time.Instant;

public record McpServerView(Long id, String name, String baseUrl, boolean connected,
                            Instant connectedAt, boolean hasAuth, long toolCount,
                            Instant createdAt, Instant updatedAt) {
    static McpServerView from(McpServer server, long toolCount) {
        return new McpServerView(server.getId(), server.getName(), server.getBaseUrl(), server.isConnected(),
                server.getConnectedAt(), true, toolCount, server.getCreatedAt(), server.getUpdatedAt());
    }
}
